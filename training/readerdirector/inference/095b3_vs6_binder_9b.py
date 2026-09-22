# -*- coding: utf-8 -*-
"""VS6.4b — 4B vs 9B Teacher 对比（capability ceiling 判定，用户 §14）。

难例子集：causer/observer/listener_trap/pronoun/multi_person/set_ref_unknown（v5 错误集中类别）
PROMPT 直接 import 自 095b3_vs6_binder（v5 最优版，保证对照一致），只换模型。
"""
import json
import os
import re
import sys

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
from importlib import import_module
_m = import_module("095b2_eval_mention")
run = _m.run
_b = import_module("095b3_vs6_binder")
PROMPT = _b.PROMPT
parse_binding = _b.parse_binding
host_validate = _b.host_validate

ROOT = os.path.abspath(os.path.join(os.path.dirname(__file__), "..", "..", ".."))
OUT = os.path.join(ROOT, "training", "readerdirector", "runs", "task095")
MODEL_DIR = os.path.join(ROOT, "training", "models", "qwen3.5-9b")

FOCUS = {"causer", "observer", "listener_trap", "pronoun", "multi_person", "set_ref_unknown"}

def main():
    from transformers import AutoModelForCausalLM, AutoTokenizer
    tok = AutoTokenizer.from_pretrained(MODEL_DIR, trust_remote_code=True)
    model = AutoModelForCausalLM.from_pretrained(MODEL_DIR, trust_remote_code=True,
                                                 torch_dtype="auto", device_map="auto")
    print("9B loaded", flush=True)

    rows = [json.loads(l) for l in open(os.path.join(OUT, "vs6_binding_fixtures.jsonl"), encoding="utf-8")]
    focus = [r for r in rows if r["category"] in FOCUS]
    print(f"focus subset: {len(focus)}/{len(rows)}", flush=True)

    op = os.path.join(OUT, "vs6_binding_9b_results.jsonl")
    n = 0
    for row in focus:
        cands_txt = chr(10).join(f"{c['role']} = {c['name']}" for c in row["candidates"])
        p = PROMPT.replace("{candidates}", cands_txt).replace("{text}", row["text"])
        raw = run(model, tok, p, max_new_tokens=120)
        obj = parse_binding(raw)
        valid, err = host_validate(row["candidates"], obj)
        rec = {"idx": row["idx"], "category": row["category"], "text": row["text"],
               "gold": row["gold"], "candidates": row["candidates"],
               "raw": raw, "pred": obj, "valid": valid, "error": err}
        with open(op, "a", encoding="utf-8") as f:
            f.write(json.dumps(rec, ensure_ascii=False) + chr(10))
        n += 1
        if n % 10 == 0:
            print(f"  {n} done", flush=True)
    print(f"done {n}, saved {op}", flush=True)

if __name__ == "__main__":
    main()