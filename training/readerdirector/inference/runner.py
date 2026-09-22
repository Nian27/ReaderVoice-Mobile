# -*- coding: utf-8 -*-
"""TASK-080 runner：零样本推理（NON_THINKING, temperature=0, max_new_tokens=32）。
用法: python runner.py --model training/models/qwen3.5-0.8b --split test --run A [--subset 100] [--probe]
产物: runs/task080/<model_name>/run_<A|B>/predictions.jsonl + metrics.json + timing.json
"""
import argparse
import hashlib
import json
import os
import sys
import time

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
from prompt_builder import build_prompt, load_prompts
from parser import parse
import metrics as M

REPO_ROOT = os.path.abspath(os.path.join(os.path.dirname(__file__), "..", "..", ".."))
DATASET_DIR = os.path.join(REPO_ROOT, "training", "readerdirector", "dataset")
RUNS_DIR = os.path.join(REPO_ROOT, "training", "readerdirector", "runs", "task080")


def gold_of(sample):
    t = sample["task"]
    tg = sample.get("target") or {}
    if t == "SPEAKER":
        if sample.get("provenance") == "EXPLICIT_RULE_GOLD" and tg.get("speaker"):
            return tg["speaker"]
        return None  # UNKNOWN 样本：规则答案为 UNKNOWN → gold=UNKNOWN（诚实标注）
    if t == "IDENTITY":
        if sample.get("provenance") == "EXPLICIT_RULE_GOLD":
            return tg.get("relation")
        return None
    if t == "VOICE_STATE":
        if sample.get("provenance") == "EXPLICIT_RULE_GOLD":
            return tg.get("action")
        return None
    return None


def build_records(samples, prompts, tokenizer, generate):
    records = []
    for s in samples:
        task = s["task"]
        inp = s["input"]
        candidates = [c["role"] for c in (inp.get("rule_candidates") or [])]
        prompt, dropped = build_prompt(task, s, prompts)
        t0 = time.perf_counter()
        raw, meta = generate(prompt)
        dt = time.perf_counter() - t0
        obj, verdict = parse(raw, task, candidates=candidates)
        gold = gold_of(s)
        rec = {
            "sample_id": s["sample_id"],
            "task": task,
            "book_hash": s["book_hash"],
            "gold": gold,
            "pred": obj.get(("speaker" if task == "SPEAKER" else ("relation" if task == "IDENTITY" else "action"))) if obj else None,
            "pred_status": obj.get("status") if obj else None,
            "pred_raw": raw,
            "verdict": verdict,
            "candidates": candidates,
            "hard_block": inp.get("hard_block") if task == "IDENTITY" else None,
            "extra_text": verdict != "STRICT",
            "loop": meta.get("loop", False),
            "eos": meta.get("eos", True),
            "latency_ms": round(dt * 1000, 1),
            "input_tokens": meta.get("input_tokens"),
            "output_tokens": meta.get("output_tokens"),
            "truncated": bool(dropped) or meta.get("truncated", False),
        }
        records.append(rec)
    return records


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--model_dir", required=True)
    ap.add_argument("--model_name", required=True)
    ap.add_argument("--split", default="test")
    ap.add_argument("--run", default="A", choices=["A", "B"])
    ap.add_argument("--subset", type=int, default=0, help=">0 时只跑前 N 条（Base probe 用）")
    ap.add_argument("--probe", action="store_true", help="probe 模式：不计算 task accuracy")
    ap.add_argument("--prompt_version", default="v1")
    args = ap.parse_args()

    # ---- load model ----
    os.environ.setdefault("HF_ENDPOINT", "https://hf-mirror.com")
    from transformers import AutoModelForCausalLM, AutoTokenizer

    t0 = time.perf_counter()
    tok = AutoTokenizer.from_pretrained(args.model_dir, trust_remote_code=True)
    model = AutoModelForCausalLM.from_pretrained(
        args.model_dir, trust_remote_code=True, torch_dtype="auto", device_map="auto"
    )
    model.eval()
    load_s = time.perf_counter() - t0
    print(f"[{args.model_name}] load {load_s:.1f}s", flush=True)

    def generate(prompt):
        msgs = [{"role": "user", "content": prompt}]
        try:
            text = tok.apply_chat_template(msgs, tokenize=False, add_generation_prompt=True,
                                           enable_thinking=False)
            template_ok = True
        except Exception:
            text = tok.apply_chat_template(msgs, tokenize=False, add_generation_prompt=True)
            template_ok = False
        ids = tok(text, return_tensors="pt").input_ids.to(model.device)
        out = model.generate(
            ids,
            max_new_tokens=32,
            do_sample=False,
            temperature=None,
            top_p=None,
            pad_token_id=tok.pad_token_id if tok.pad_token_id is not None else tok.eos_token_id,
        )
        new = out[0][ids.shape[1]:]
        raw = tok.decode(new, skip_special_tokens=True)
        # EOS 判定：输出 token 里含 eos
        eos = (new == tok.eos_token_id).any().item() if tok.eos_token_id is not None else False
        loop = len(new) >= 32 and (new[-8:] == new[-16:-8]).all().item() if len(new) >= 16 else False
        return raw, {
            "input_tokens": ids.shape[1],
            "output_tokens": len(new),
            "eos": eos,
            "loop": loop,
            "truncated": ids.shape[1] > 2048,
            "enable_thinking": False,
            "template_ok": template_ok,
        }

    # ---- samples ----
    samples = [json.loads(l) for l in open(os.path.join(DATASET_DIR, f"{args.split}.jsonl"), encoding="utf-8")]
    if args.subset > 0:
        samples = samples[:args.subset]
    prompts = load_prompts(args.prompt_version)

    # ---- run ----
    t0 = time.perf_counter()
    records = build_records(samples, prompts, tok, generate)
    wall_s = time.perf_counter() - t0

    out_dir = os.path.join(RUNS_DIR, args.model_name, f"run_{args.run}")
    os.makedirs(out_dir, exist_ok=True)
    with open(os.path.join(out_dir, "predictions.jsonl"), "w", encoding="utf-8") as f:
        for r in records:
            f.write(json.dumps(r, ensure_ascii=False) + "\n")

    # hash of raw outputs（§32 确定性检查）
    raw_hash = hashlib.sha256(
        "\n".join(json.dumps(r["pred_raw"], ensure_ascii=False) for r in records).encode()
    ).hexdigest()

    metrics = {"model": args.model_name, "split": args.split, "run": args.run,
               "prompt_version": args.prompt_version, "mode": "NON_THINKING",
               "temperature": 0, "max_new_tokens": 32, "load_s": round(load_s, 1),
               "wall_s": round(wall_s, 1), "raw_outputs_sha256": raw_hash,
               "template_ok": all(r.get("template_ok", True) for r in records) if records else None}
    if not args.probe:
        for task in ("SPEAKER", "IDENTITY", "VOICE_STATE"):
            rs = [r for r in records if r["task"] == task]
            if not rs:
                continue
            fn = {"SPEAKER": M.speaker_metrics, "IDENTITY": M.identity_metrics, "VOICE_STATE": M.voice_metrics}[task]
            metrics[task] = fn(rs)
    else:
        metrics["probe"] = True
        metrics["probe_valid"] = sum(1 for r in records if r["verdict"] == "STRICT") / len(records)
        metrics["probe_out_of_candidate"] = sum(1 for r in records if r["verdict"] == "OUT_OF_CANDIDATE_PROTOCOL_ERROR") / len(records)
        metrics["probe_eos"] = sum(1 for r in records if r.get("eos")) / len(records)
        metrics["probe_avg_output_tokens"] = round(sum(r["output_tokens"] or 0 for r in records) / len(records), 1)

    with open(os.path.join(out_dir, "metrics.json"), "w", encoding="utf-8") as f:
        json.dump(metrics, f, indent=1, ensure_ascii=False)
    print(json.dumps(metrics, indent=1, ensure_ascii=False))
    print(f"DONE -> {out_dir}")


if __name__ == "__main__":
    main()
