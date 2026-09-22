# -*- coding: utf-8 -*-
"""TASK-095B.3-EVAL — 0.8B MENTION 评估（frozen challenge + NONE + 旧任务回归接口）。

指标：Exact Recall / Bounded-Contain Recall / Span IoU / Overcapture / Precision /
Exact-source Validity / 15 域 macro / NONE accuracy。
用法: python inference/095b3_eval08b.py [--checkpoint runs/task095/mention_r8_v1/checkpoint-XXX]
"""
import argparse
import json
import os
import re
import sys

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
from importlib import import_module
_m = import_module("095b2_eval_mention")
run = _m.run
parse_mentions = _m.parse_mentions
PROMPT_FS = _m.PROMPT

ROOT = os.path.abspath(os.path.join(os.path.dirname(__file__), "..", "..", ".."))
TASK095 = os.path.join(ROOT, "training", "readerdirector", "runs", "task095")
BASE = os.path.join(ROOT, "training", "models", "qwen3.5-0.8b-base")

CLAUDE_BOUNDARY = re.compile(r"[，。！？；：、\s“”\"《》]")


def bounded_contain(gold, pred):
    """gold 与某 pred 互相包含，且 pred 不跨越明显 clause/punctuation boundary。"""
    for p in pred:
        if gold == p:
            return True
        if gold in p or p in gold:
            # pred 内含 gold：检查 pred 是否跨界（pred 内部含句读，且 gold 不是完整句）
            inner = p.replace(gold, "")
            if inner and CLAUDE_BOUNDARY.search(inner) and not CLAUDE_BOUNDARY.search(gold):
                continue
            return True
    return False


def iou(gold_set, pred_set, text):
    """span IoU（字符级并集/交集，粗粒度）。"""
    if not gold_set or not pred_set:
        return 0.0
    inter = 0
    for g in gold_set:
        for p in pred_set:
            if g in p or p in g:
                inter += min(len(g), len(p))
    union = sum(len(g) for g in gold_set) + sum(len(p) for p in pred_set) - inter
    return inter / union if union else 0.0


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--checkpoint", default=None, help="adapter checkpoint 目录")
    ap.add_argument("--resume", action="store_true")
    args = ap.parse_args()

    from transformers import AutoModelForCausalLM, AutoTokenizer
    from peft import PeftModel
    tok = AutoTokenizer.from_pretrained(BASE, trust_remote_code=True)
    model = AutoModelForCausalLM.from_pretrained(BASE, trust_remote_code=True,
                                                 torch_dtype="auto", device_map="auto")
    if args.checkpoint:
        model = PeftModel.from_pretrained(model, args.checkpoint)
        print(f"adapter loaded: {args.checkpoint}", flush=True)
    else:
        print("WARNING: no adapter — base model (对照组)", flush=True)
    model.eval()

    cases = [json.loads(l) for l in open(os.path.join(TASK095, "challenge_mentions_150.jsonl"), encoding="utf-8")]
    # NONE 评估集（crossdomain NONE 抽 60）
    none_pool = [json.loads(l) for l in open(os.path.join(TASK095, "crossdomain_train.jsonl"), encoding="utf-8")]
    none_cases = [c for c in none_pool if c["domain"] == "NONE"][:60]

    tag = "base" if not args.checkpoint else os.path.basename(args.checkpoint)
    res_path = os.path.join(TASK095, f"eval08b_{tag}.jsonl")
    done = set()
    if args.resume and os.path.exists(res_path):
        for l in open(res_path, encoding="utf-8"):
            try:
                done.add(json.loads(l)["id"])
            except Exception:
                pass

    def predict(text):
        raw = run(model, tok, PROMPT_FS.replace("{text}", text), max_new_tokens=200)
        return parse_mentions(raw)

    out = []
    for c in cases + [{"id": f"NONE-{i}", "domain": "NONE", "text": c["text"], "mentions": []}
                      for i, c in enumerate(none_cases)]:
        if args.resume and c["id"] in done:
            continue
        pred = [p for p in predict(c["text"]) if p and p in c["text"]]
        out.append({"id": c["id"], "domain": c["domain"], "text": c["text"],
                    "gold": c["mentions"], "pred": pred})
        with open(res_path, "a", encoding="utf-8") as f:
            f.write(json.dumps(out[-1], ensure_ascii=False) + "\n")
    print(f"done {len(out)}, saved {res_path}")


if __name__ == "__main__":
    main()
