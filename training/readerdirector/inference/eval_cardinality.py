# -*- coding: utf-8 -*-
"""TASK-090.1 — DEV-CARD 评估：SpeakerAcc@K / CandidatePermutationConsistency@K / ΔK / per-pattern。
用法: python eval_cardinality.py --checkpoint <dir> [--max_new_tokens 48]
"""
import argparse
import json
import os
import sys

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
from prompt_builder import build_prompt, load_prompts
from parser import parse

ROOT = os.path.abspath(os.path.join(os.path.dirname(__file__), "..", "..", ".."))
DS = os.path.join(ROOT, "training", "readerdirector", "dataset")
RUNS = os.path.join(ROOT, "training", "readerdirector", "runs", "task090")
PROMPTS = load_prompts("v2")

PATTERNS = ("preposed_pos1", "preposed_mid", "preposed_last", "postposed", "recent_neq", "true_unknown")


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
    return tok.decode(new, skip_special_tokens=True)


def load_model(checkpoint):
    from transformers import AutoModelForCausalLM, AutoTokenizer
    from peft import LoraConfig, get_peft_model
    from safetensors.torch import load_file
    base = os.path.join(ROOT, "training", "models", "qwen3.5-0.8b-base")
    tok = AutoTokenizer.from_pretrained(base, trust_remote_code=True)
    model = AutoModelForCausalLM.from_pretrained(base, trust_remote_code=True, torch_dtype="auto", device_map="auto")
    cfg = json.load(open(os.path.join(checkpoint, "adapter_config.json"), encoding="utf-8"))
    sd = load_file(os.path.join(checkpoint, "adapter_model.safetensors"))
    remapped = {
        k.replace(".model.language_model.layers.", ".model.layers.")
         .replace(".lora_A.weight", ".lora_A.default.weight")
         .replace(".lora_B.weight", ".lora_B.default.weight")
        : v for k, v in sd.items()
    }
    model = get_peft_model(model, LoraConfig(r=cfg["r"], lora_alpha=cfg["lora_alpha"], target_modules="all-linear"))
    missing, unexpected = model.load_state_dict(remapped, strict=False)
    model.eval()
    return model, tok


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--checkpoint", required=True)
    ap.add_argument("--name", default=None)
    ap.add_argument("--max_new_tokens", type=int, default=32)
    args = ap.parse_args()
    model, tok = load_model(args.checkpoint)

    samples = [json.loads(l) for l in open(os.path.join(DS, "dev_card.jsonl"), encoding="utf-8")]
    sp = [s for s in samples if s["task"] == "SPEAKER"]
    byK = {K: {"correct": 0, "total": 0, "violations": 0, "perm_ok": 0, "perm_total": 0,
               "unknown_ok": 0, "unknown_total": 0} for K in (2, 3, 4, 5, 6)}
    byPattern = {p: {"correct": 0, "total": 0} for p in PATTERNS}
    preds_out = []

    for s in sp:
        K = s["meta"]["K"]
        cands = s["input"]["rule_candidates"]
        gold = s["target"]["speaker"]
        prompt, _ = build_prompt("SPEAKER", s, PROMPTS)
        raw = run_generation(model, tok, prompt, args.max_new_tokens)
        obj, verdict = parse(raw, "SPEAKER", candidates=[c["role"] for c in cands])
        pred = obj.get("speaker") if obj else None
        r = byK[K]
        r["total"] += 1
        if verdict == "OUT_OF_CANDIDATE_PROTOCOL_ERROR":
            r["violations"] += 1
        if pred == gold:
            r["correct"] += 1
        if s["meta"]["true_unknown"]:
            r["unknown_total"] += 1
            if pred == "UNKNOWN":
                r["unknown_ok"] += 1
        bp = byPattern[s["meta"]["pattern"]]
        bp["total"] += 1
        if pred == gold:
            bp["correct"] += 1
        preds_out.append({"sample_id": s["sample_id"], "K": K, "pattern": s["meta"]["pattern"],
                          "gold": gold, "pred": pred, "verdict": verdict, "raw": raw})
        # perm@K：gold 移到每个位置，预测应不变
        if gold != "UNKNOWN":
            gold_pos = next(i for i, c in enumerate(cands) if c["role"] == gold)
            preds = [pred]
            for pos in range(K):
                if pos == gold_pos:
                    continue
                variant = dict(s)
                variant["input"] = dict(s["input"])
                c2 = list(cands)
                gi = next(i for i, c in enumerate(c2) if c["role"] == gold)
                c2[pos], c2[gi] = c2[gi], c2[pos]
                variant["input"]["rule_candidates"] = c2
                p2, _ = build_prompt("SPEAKER", variant, PROMPTS)
                raw2 = run_generation(model, tok, p2, args.max_new_tokens)
                o2, _ = parse(raw2, "SPEAKER", candidates=[c["role"] for c in c2])
                preds.append(o2.get("speaker") if o2 else None)
            r["perm_total"] += 1
            if len(set(preds)) == 1 and preds[0] == gold:
                r["perm_ok"] += 1

    out = {"checkpoint": args.checkpoint, "prompt_version": "v2", "max_new_tokens": args.max_new_tokens}
    for K in (2, 3, 4, 5, 6):
        r = byK[K]
        out[f"acc@{K}"] = r["correct"] / r["total"] if r["total"] else None
        out[f"perm@{K}"] = r["perm_ok"] / r["perm_total"] if r["perm_total"] else None
        out[f"cand_viol@{K}"] = r["violations"] / r["total"] if r["total"] else None
        out[f"true_unknown@{K}"] = r["unknown_ok"] / r["unknown_total"] if r["unknown_total"] else None
        out[f"n@{K}"] = r["total"]
    out["delta4"] = (out.get("acc@2") or 0) - (out.get("acc@4") or 0)
    out["delta5"] = (out.get("acc@2") or 0) - (out.get("acc@5") or 0)
    out["delta6"] = (out.get("acc@2") or 0) - (out.get("acc@6") or 0)
    out["by_pattern"] = {p: [byPattern[p]["correct"], byPattern[p]["total"]] for p in PATTERNS}
    name = args.name or os.path.basename(os.path.normpath(args.checkpoint))
    json.dump(out, open(os.path.join(RUNS, "eval", f"{name}__card.json"), "w", encoding="utf-8"), indent=1, ensure_ascii=False)
    with open(os.path.join(RUNS, "eval", f"{name}__card.predictions.jsonl"), "w", encoding="utf-8") as f:
        for r in preds_out:
            f.write(json.dumps(r, ensure_ascii=False) + "\n")
    print(json.dumps(out, indent=1, ensure_ascii=False))


if __name__ == "__main__":
    main()
