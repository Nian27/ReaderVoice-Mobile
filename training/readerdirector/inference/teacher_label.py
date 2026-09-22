# -*- coding: utf-8 -*-
"""TASK-100 Stage 2/3/4/5 — Teacher 标注管线。
- Teacher 输入与 Student 完全一致（v2 prompt，无 gold；可选 TEACHER_EXTENDED_CONTEXT）
- 每 record 保存 teacher_model/revision/prompt_hash/input_hash/answer/protocol_valid/
  rule_answer/student_answer/agreement_pattern/provenance
- Candidate Permutation Consistency Probe（4 置换）→ TEACHER_ORDER_UNSTABLE 标记
- 输出 disagreement matrix（Rule/Student/Teacher 三列 → 处理决策）
用法: python teacher_label.py --model_dir <4B|9B> --pool hard_pool_v1 --subset N [--perm]
"""
import argparse
import hashlib
import json
import os
import sys
import time
from collections import Counter

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
from prompt_builder import build_prompt, load_prompts
from parser import parse

ROOT = os.path.abspath(os.path.join(os.path.dirname(__file__), "..", "..", ".."))
DS = os.path.join(ROOT, "training", "readerdirector", "dataset")
RUNS = os.path.join(ROOT, "training", "readerdirector", "runs", "task100")
PROMPTS = None  # 按 --prompt_version 加载


def run_generation(model, tok, prompt, max_new_tokens=48):
    msgs = [{"role": "user", "content": prompt}]
    try:
        text = tok.apply_chat_template(msgs, tokenize=False, add_generation_prompt=True, enable_thinking=False)
    except Exception:
        text = tok.apply_chat_template(msgs, tokenize=False, add_generation_prompt=True)
    ids = tok(text, return_tensors="pt").input_ids.to(model.device)
    out = model.generate(ids, max_new_tokens=max_new_tokens, do_sample=False, temperature=None,
                         pad_token_id=tok.pad_token_id if tok.pad_token_id is not None else tok.eos_token_id)
    new = out[0][ids.shape[1]:]
    return tok.decode(new, skip_special_tokens=True)


def student_answer(model, tok, sample):
    """student_answer：用同一 checkpoint 对同 prompt 的预测（无 gold）。"""
    prompt, _ = build_prompt(sample["task"], sample, PROMPTS)
    raw = run_generation(model, tok, prompt)
    cands = [c["role"] for c in (sample["input"].get("rule_candidates") or [])]
    obj, verdict = parse(raw, sample["task"], candidates=cands)
    key = "speaker" if sample["task"] == "SPEAKER" else ("relation" if sample["task"] == "IDENTITY" else "action")
    return (obj.get(key) if obj else None), verdict, raw


def rule_answer(sample):
    tg = sample.get("target") or {}
    if sample.get("provenance") == "EXPLICIT_RULE_GOLD":
        return tg.get("speaker") or tg.get("relation") or tg.get("action") or "UNKNOWN"
    return "UNKNOWN"  # UNLABELED：rule 无答案


def agreement_pattern(rule, student, teacher):
    if rule == "UNKNOWN" and student == "UNKNOWN" and teacher == "UNKNOWN":
        return "ALL_UNKNOWN"
    if rule == student == teacher:
        return "ALL_AGREE"
    if rule == teacher and student != teacher:
        return "RULE_TEACHER_STUDENT_HARD"
    if student == teacher and rule != teacher:
        return "STUDENT_TEACHER_RULE_HARD"
    if rule == student and teacher != rule:
        return "RULE_STUDENT_TEACHER_DIFF"  # 最高价值：规则可能错
    if rule == "UNKNOWN" and student == teacher and teacher != "UNKNOWN":
        return "NEW_CAPABILITY"
    return "MIXED"


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--model_dir", required=True)
    ap.add_argument("--model_name", required=True)
    ap.add_argument("--pool", default="hard_pool_v1")
    ap.add_argument("--pool_path", default=None, help="pool 文件绝对路径（覆盖 --pool 的 dataset/ 路径，用于 9B 仲裁子集）")
    ap.add_argument("--subset", type=int, default=0)
    ap.add_argument("--perm", action="store_true", help="Teacher permutation probe")
    ap.add_argument("--prompt_version", default="v2")
    ap.add_argument("--student_checkpoint", default=None, help="0.8B student checkpoint（生成 student_answer）")
    ap.add_argument("--resume", action="store_true", help="增量续跑：跳过输出文件中已存在的 sample_id")
    args = ap.parse_args()
    global PROMPTS
    PROMPTS = load_prompts(args.prompt_version)

    from transformers import AutoModelForCausalLM, AutoTokenizer
    t0 = time.perf_counter()
    tok = AutoTokenizer.from_pretrained(args.model_dir, trust_remote_code=True)
    kwargs = {}
    if "bnb" in args.model_name or "4bit" in args.model_name:
        # transformers 5.x 自定义架构须用 BitsAndBytesConfig（load_in_4bit 直接传会报错）
        from transformers import BitsAndBytesConfig
        import torch as _torch
        kwargs["quantization_config"] = BitsAndBytesConfig(
            load_in_4bit=True, bnb_4bit_compute_dtype=_torch.bfloat16, bnb_4bit_use_double_quant=True)
    model = AutoModelForCausalLM.from_pretrained(
        args.model_dir, trust_remote_code=True, torch_dtype="auto", device_map="auto", **kwargs)
    model.eval()
    load_s = time.perf_counter() - t0
    print(f"[{args.model_name}] load {load_s:.1f}s", flush=True)

    # revision
    try:
        from huggingface_hub import HfApi
        rev = HfApi().model_info(args.model_name.replace("bnb4", "") if args.model_name.startswith("bnb") else args.model_name).sha
    except Exception:
        rev = "local"

    # student model（student_answer）
    student_model = student_tok = None
    if args.student_checkpoint:
        from peft import LoraConfig, get_peft_model
        from safetensors.torch import load_file
        base = os.path.join(ROOT, "training", "models", "qwen3.5-0.8b-base")
        student_tok = AutoTokenizer.from_pretrained(base, trust_remote_code=True)
        sm = AutoModelForCausalLM.from_pretrained(base, trust_remote_code=True, torch_dtype="auto", device_map="auto")
        cfg = json.load(open(os.path.join(args.student_checkpoint, "adapter_config.json"), encoding="utf-8"))
        sd = load_file(os.path.join(args.student_checkpoint, "adapter_model.safetensors"))
        remapped = {k.replace(".model.language_model.layers.", ".model.layers.")
                     .replace(".lora_A.weight", ".lora_A.default.weight")
                     .replace(".lora_B.weight", ".lora_B.default.weight"): v for k, v in sd.items()}
        student_model = get_peft_model(sm, LoraConfig(r=cfg["r"], lora_alpha=cfg["lora_alpha"], target_modules="all-linear"))
        student_model.load_state_dict(remapped, strict=False)
        student_model.eval()
        print("student loaded", flush=True)

    pool_file = args.pool_path or os.path.join(DS, f"{args.pool}.jsonl")
    samples = [json.loads(l) for l in open(pool_file, encoding="utf-8")]
    if args.subset > 0:
        samples = samples[:args.subset]

    records = []
    os.makedirs(RUNS, exist_ok=True)
    out = os.path.join(RUNS, f"teacher_{args.model_name}_{args.pool}.jsonl")
    done_ids = set()
    if args.resume and os.path.exists(out):
        with open(out, encoding="utf-8") as f:
            for line in f:
                r = json.loads(line)
                records.append(r)
                done_ids.add(r["sample_id"])
        print(f"resume: {len(records)} already done ({out})", flush=True)
    t_gen0 = time.perf_counter()
    n_processed = len(records)
    for i, s in enumerate(samples):
        if args.resume and s["sample_id"] in done_ids:
            continue
        task = s["task"]
        cands = [c["role"] for c in (s["input"].get("rule_candidates") or [])]
        cand_names = [c.get("name") for c in (s["input"].get("rule_candidates") or [])]
        prompt, _ = build_prompt(task, s, PROMPTS)
        raw = run_generation(model, tok, prompt)
        obj, verdict = parse(raw, task, candidates=cands)
        # REPAIRABLE_NAME_MAP：名字回显且唯一匹配候选 → 映射回 role（name 在候选内，非额外信息）
        if task == "SPEAKER" and verdict == "OUT_OF_CANDIDATE_PROTOCOL_ERROR":
            import json as _json
            try:
                o2 = _json.loads(raw.strip())
                nm = o2.get("speaker")
                if nm in cand_names and cand_names.count(nm) == 1:
                    obj = {"speaker": cands[cand_names.index(nm)], "status": o2.get("status", "UNKNOWN")}
                    verdict = "REPAIRABLE_NAME_MAP"
            except Exception:
                pass
        key = "speaker" if task == "SPEAKER" else ("relation" if task == "IDENTITY" else "action")
        ans = obj.get(key) if obj else None
        stu, stu_verdict, _ = student_answer(student_model, student_tok, s) if student_model else (None, None, None)
        rec = {
            "sample_id": s["sample_id"], "task": task,
            "teacher_model": args.model_name, "teacher_revision": rev,
            "prompt_hash": hashlib.sha256(prompt.encode()).hexdigest()[:16],
            "input_hash": hashlib.sha256(json.dumps(s["input"], ensure_ascii=False, sort_keys=True).encode()).hexdigest()[:16],
            "answer": ans, "protocol_valid": verdict == "STRICT",
            "rule_answer": rule_answer(s),
            "student_answer": stu, "student_protocol_valid": stu_verdict == "STRICT",
            "agreement_pattern": agreement_pattern(rule_answer(s), stu, ans),
            "provenance": "TEACHER_4B" if "4b" in args.model_name else "TEACHER_9B",
            "latency_ms": 0,
        }
        if args.perm and task == "SPEAKER" and len(cands) >= 2:
            rc = s["input"].get("rule_candidates") or []
            roles = [c["role"] for c in rc]
            by_role = {c["role"]: c for c in rc}
            preds = [ans]
            for k in range(1, 4):
                variant = dict(s)
                variant["input"] = dict(s["input"])
                tail = roles[1:]
                r = k % len(tail)
                order = [roles[0]] + tail[r:] + tail[:r]
                variant["input"]["rule_candidates"] = [by_role[o] for o in order]
                p2, _ = build_prompt(task, variant, PROMPTS)
                raw2 = run_generation(model, tok, p2)
                o2, _ = parse(raw2, task, candidates=order)
                preds.append(o2.get("speaker") if o2 else None)
            rec["perm_stable"] = len(set(preds)) == 1
            if not rec["perm_stable"]:
                rec["teacher_order_unstable"] = True
        records.append(rec)
        n_processed += 1
        with open(out, "a", encoding="utf-8") as f:
            f.write(json.dumps(rec, ensure_ascii=False) + "\n")
        if n_processed % 100 == 0:
            print(f"  {n_processed}/{len(samples)} ({time.perf_counter()-t_gen0:.0f}s)", flush=True)

    # 循环结束后把 out 指针对齐（增量模式已逐条落盘，无需二次写）

    # disagreement matrix
    matrix = Counter(r["agreement_pattern"] for r in records)
    proto = Counter(r["protocol_valid"] for r in records)
    summary = {
        "model": args.model_name, "pool": args.pool, "n": len(records),
        "strict_valid": proto.get(True, 0) / len(records),
        "agreement_matrix": dict(matrix),
        "teacher_unknown_rate": sum(1 for r in records if r["answer"] == "UNKNOWN") / len(records),
        "student_unknown_rate": sum(1 for r in records if r["student_answer"] == "UNKNOWN") / len(records),
        "rule_unknown_rate": sum(1 for r in records if r["rule_answer"] == "UNKNOWN") / len(records),
        "perm_unstable_rate": sum(1 for r in records if r.get("teacher_order_unstable")) / len(records) if args.perm else None,
    }
    print(json.dumps(summary, indent=1, ensure_ascii=False))
    print("saved:", out)


if __name__ == "__main__":
    main()
