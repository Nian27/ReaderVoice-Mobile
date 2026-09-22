# -*- coding: utf-8 -*-
"""TASK-090 Step 5/6 — checkpoint 评估（dev 选 rank / locked test 最终）。
指标：strict JSON / candidate violation / 三任务 Macro-F1 / False Merge / Hard-negative violation /
      permutation consistency / TRUE_UNKNOWN / VoiceState leakage sentinel。
用法: python eval_checkpoint.py --checkpoint <dir> --split dev|test [--perm] [--true_unknown]
"""
import argparse
import json
import os
import sys
import time

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
from prompt_builder import build_prompt, load_prompts
from parser import parse

ROOT = os.path.abspath(os.path.join(os.path.dirname(__file__), "..", "..", ".."))
DS = os.path.join(ROOT, "training", "readerdirector", "dataset")
RUNS = os.path.join(ROOT, "training", "readerdirector", "runs", "task090")
PROMPTS = load_prompts("v2")

SPEAKER_STATUS = {"CONFIRMED", "PROVISIONAL", "UNKNOWN"}


def gold_of(sample):
    t, tg = sample["task"], sample.get("target") or {}
    if sample.get("provenance") != "EXPLICIT_RULE_GOLD":
        return None
    return tg.get("speaker") if t == "SPEAKER" else (tg.get("relation") if t == "IDENTITY" else tg.get("action"))


def run_generation(model, tok, prompt, max_new_tokens=32):
    msgs = [{"role": "user", "content": prompt}]
    try:
        text = tok.apply_chat_template(msgs, tokenize=False, add_generation_prompt=True, enable_thinking=False)
    except Exception:
        text = tok.apply_chat_template(msgs, tokenize=False, add_generation_prompt=True)
    ids = tok(text, return_tensors="pt").input_ids.to(model.device)
    out = model.generate(ids, max_new_tokens=max_new_tokens, do_sample=False, temperature=None,
                         pad_token_id=tok.pad_token_id if tok.pad_token_id is not None else tok.eos_token_id)
    new = out[0][ids.shape[1]:]
    eos = (new == tok.eos_token_id).any().item() if tok.eos_token_id is not None else False
    loop = bool(len(new) >= 32 and (new[-8:] == new[-16:-8]).all().item())
    return tok.decode(new, skip_special_tokens=True), eos, loop


def evaluate(model, tok, samples, do_perm=False, max_new_tokens=32):
    recs = []
    for s in samples:
        task = s["task"]
        candidates = [c["role"] for c in (s["input"].get("rule_candidates") or [])]
        prompt, _ = build_prompt(task, s, PROMPTS)
        raw, eos, loop = run_generation(model, tok, prompt, max_new_tokens)
        obj, verdict = parse(raw, task, candidates=candidates)
        key = "speaker" if task == "SPEAKER" else ("relation" if task == "IDENTITY" else "action")
        recs.append({
            "sample_id": s["sample_id"], "task": task, "gold": gold_of(s),
            "pred": obj.get(key) if obj else None,
            "pred_raw": raw, "verdict": verdict, "candidates": candidates,
            "hard_block": s["input"].get("hard_block") if task == "IDENTITY" else None,
            "eos": eos, "loop": loop,
        })
        # permutation consistency（SPEAKER gold 样本 ×4 候选顺序）
        if do_perm and task == "SPEAKER" and gold_of(s) is not None and len(candidates) >= 2:
            cands = s["input"]["rule_candidates"]
            for k in range(1, 4):
                variant = dict(s)
                variant["input"] = dict(s["input"])
                tail = cands[1:]
                r = k % len(tail)
                variant["input"]["rule_candidates"] = [cands[0]] + tail[r:] + tail[:r]
                p2, _ = build_prompt(task, variant, PROMPTS)
                raw2, _, _ = run_generation(model, tok, p2, max_new_tokens)
                c2 = [c["role"] for c in variant["input"]["rule_candidates"]]
                o2, _ = parse(raw2, task, candidates=c2)
                recs[-1].setdefault("perm_preds", [recs[-1]["pred"]])  # 原序
                recs[-1]["perm_preds"].append(o2.get("speaker") if o2 else None)
    return recs


def summarize(recs):
    m = {}
    n = len(recs)
    m["n"] = n
    m["strict_valid"] = sum(1 for r in recs if r["verdict"] == "STRICT") / max(n, 1)
    m["candidate_violation"] = sum(1 for r in recs if r["verdict"] == "OUT_OF_CANDIDATE_PROTOCOL_ERROR") / max(n, 1)
    m["eos_rate"] = sum(1 for r in recs if r["eos"]) / max(n, 1)
    m["loop_rate"] = sum(1 for r in recs if r["loop"]) / max(n, 1)
    for task in ("SPEAKER", "IDENTITY", "VOICE_STATE"):
        rs = [r for r in recs if r["task"] == task]
        golds = [r for r in rs if r["gold"] is not None]
        m[task] = {"n": len(rs), "gold": len(golds)}
        if golds:
            acc = sum(1 for r in golds if r["pred"] == r["gold"]) / len(golds)
            m[task]["accuracy"] = acc
            # macro-F1
            from collections import Counter
            conf = Counter((r["gold"], r["pred"]) for r in golds)
            labels = sorted({r["gold"] for r in golds} | {p for r in golds if (p := r["pred"]) is not None})
            f1s = []
            for lbl in labels:
                tp = conf.get((lbl, lbl), 0)
                fp = sum(v for (g, p), v in conf.items() if p == lbl and g != lbl)
                fn = sum(v for (g, p), v in conf.items() if g == lbl and p != lbl)
                f1s.append(2 * tp / (2 * tp + fp + fn) if (2 * tp + fp + fn) else 0.0)
            m[task]["macro_f1"] = sum(f1s) / len(f1s) if f1s else 0.0
        if task == "IDENTITY":
            fm = sum(1 for r in golds if r["gold"] == "DIFFERENT" and r["pred"] == "SAME")
            fs = sum(1 for r in golds if r["gold"] == "SAME" and r["pred"] == "DIFFERENT")
            m["false_merge"] = fm
            m["false_split"] = fs
            hn = [r for r in golds if r.get("hard_block")]
            m["hard_negative_violation"] = sum(1 for r in hn if r["pred"] == "SAME") / max(len(hn), 1)
        if task == "VOICE_STATE":
            m["voice_actions"] = sorted({r["pred"] for r in rs if r["pred"]})
        # permutation consistency
        perm = [r for r in rs if r.get("perm_preds") and len(r["perm_preds"]) == 4]
        if perm:
            m["permutation_consistency"] = sum(
                1 for r in perm if len(set(r["perm_preds"])) == 1 and r["perm_preds"][0] == r["gold"]
            ) / len(perm)
            m["permutation_n"] = len(perm)
    return m


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--checkpoint", required=True)
    ap.add_argument("--split", default="dev")
    ap.add_argument("--data", default=None, help="自定义数据集文件（A5 VOICE_STATE dev 等）；覆盖 --split 映射")
    ap.add_argument("--perm", action="store_true")
    ap.add_argument("--name", default=None)
    ap.add_argument("--max_new_tokens", type=int, default=32)
    args = ap.parse_args()

    from transformers import AutoModelForCausalLM, AutoTokenizer
    from peft import LoraConfig, get_peft_model
    from safetensors.torch import load_file

    base = os.path.join(ROOT, "training", "models", "qwen3.5-0.8b-base")
    tok = AutoTokenizer.from_pretrained(base, trust_remote_code=True)
    model = AutoModelForCausalLM.from_pretrained(base, trust_remote_code=True, torch_dtype="auto", device_map="auto")
    # ms-swift adapter：键名含包装层 language_model.，重映射回 raw 命名后挂载
    cfg = json.load(open(os.path.join(args.checkpoint, "adapter_config.json"), encoding="utf-8"))
    sd = load_file(os.path.join(args.checkpoint, "adapter_model.safetensors"))
    remapped = {
        k.replace(".model.language_model.layers.", ".model.layers.")
         .replace(".lora_A.weight", ".lora_A.default.weight")
         .replace(".lora_B.weight", ".lora_B.default.weight")
        : v for k, v in sd.items()
    }
    lora_cfg = LoraConfig(
        r=cfg["r"], lora_alpha=cfg["lora_alpha"],
        target_modules="all-linear",
        lora_dropout=cfg.get("lora_dropout", 0.0),
        bias=cfg.get("bias", "none"),
    )
    model = get_peft_model(model, lora_cfg)
    missing, unexpected = model.load_state_dict(remapped, strict=False)
    assert not unexpected, f"unexpected keys: {unexpected[:3]}"
    missing = [m for m in missing if "lora" in m]
    assert not missing, f"missing lora keys: {missing[:3]}"
    model.eval()

    data_path = args.data or os.path.join(DS, f"{args.split}.jsonl")
    samples = [json.loads(l) for l in open(data_path, encoding="utf-8")]
    t0 = time.perf_counter()
    recs = evaluate(model, tok, samples, do_perm=args.perm, max_new_tokens=args.max_new_tokens)
    wall = time.perf_counter() - t0
    m = summarize(recs)
    m["checkpoint"] = args.checkpoint
    m["split"] = args.split
    m["wall_s"] = round(wall, 1)
    name = args.name or os.path.basename(os.path.normpath(args.checkpoint))
    out = os.path.join(RUNS, "eval", f"{name}__{args.split}.json")
    os.makedirs(os.path.dirname(out), exist_ok=True)
    json.dump(m, open(out, "w", encoding="utf-8"), indent=1, ensure_ascii=False)
    with open(os.path.join(RUNS, "eval", f"{name}__{args.split}.predictions.jsonl"), "w", encoding="utf-8") as f:
        for r in recs:
            f.write(json.dumps(r, ensure_ascii=False) + "\n")
    print(json.dumps(m, indent=1, ensure_ascii=False))
    print("saved:", out)


if __name__ == "__main__":
    main()
