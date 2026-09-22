# -*- coding: utf-8 -*-
"""TASK-095-VS6.4 — Structured Binding（两阶段，用户 §13 后备方案）。

Step A：识别语义角色（CAUSER / OBSERVER / LISTENER / VOICE_OWNER / REFERENCE）
Step B：只输出 VOICE_OWNER / REFERENCE（中间角色只用于推理，不进产品 ontology）

用法: python inference/095b3_vs6_binder_structured.py [--resume]
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

ROOT = os.path.abspath(os.path.join(os.path.dirname(__file__), "..", "..", ".."))
OUT = os.path.join(ROOT, "training", "readerdirector", "runs", "task095")
MODEL_DIR = os.path.join(ROOT, "training", "models", "qwen3.5-4b")

PROMPT = (
    "你是 ReaderDirector 的声音绑定器。给定文本和候选角色，分两步分析。\n"
    "\n"
    "Step A（内部推理，不输出）：识别文本中的语义角色——\n"
    "  CAUSER：指使/命令/要求别人变声的人\n"
    "  OBSERVER/LISTENER：听见/看到/发现变声的人\n"
    "  VOICE_OWNER：声音实际归属的人（谁的声音状态变了）\n"
    "  REFERENCE：被模仿/变成的声音主人\n"
    "\n"
    "Step B（输出）：只输出 VOICE_OWNER 和 REFERENCE。\n"
    "- 使役结构（X让/命令Y换声）→ VOICE_OWNER=Y（实际发声者），X 是 CAUSER 不选\n"
    "- 听见/看到（X听见Y变声）→ VOICE_OWNER=Y，X 是 OBSERVER 不选\n"
    "- 模仿/学着/装成 X的声音 → VOICE_OWNER=动作发出者，REFERENCE=X\n"
    "- 附身/控制：不确定谁发声 → VOICE_OWNER=UNKNOWN（不猜）\n"
    "- 代词（他/她）无前文 → VOICE_OWNER=UNKNOWN（不猜）\n"
    "- owner 明确但被模仿者不明 → VOICE_OWNER=明确者，REFERENCE=UNKNOWN（不影响 timeline）\n"
    "\n"
    "候选（只能选这些或 UNKNOWN/NONE）：\n{candidates}\n"
    "\n"
    "成对示例：\n"
    "对1：孙七让钱八模仿周九的声音 → 语义：CAUSER=孙七，VOICE_OWNER=钱八，REFERENCE=周九 → 输出 {owner: 钱八, reference: 周九}\n"
    "对2：孙七听见钱八的声音变了 → 语义：OBSERVER=孙七，VOICE_OWNER=钱八 → 输出 {owner: 钱八, reference: NONE}\n"
    "对3：钱八开始模仿刚才那个男人的声音 → 语义：VOICE_OWNER=钱八，REFERENCE=未知 → 输出 {owner: 钱八, reference: UNKNOWN}\n"
    "\n"
    "文本：{text}\n"
    "\n"
    "输出 JSON：{\"owner\": \"C0..Cn 或 UNKNOWN\", \"reference\": \"C0..Cn 或 NONE 或 UNKNOWN\", \"confidence\": \"H\" | \"M\" | \"L\", \"reason\": \"一句话理由（含识别出的语义角色）\"}"
)

def parse_binding(raw):
    raw = raw.strip()
    try:
        obj = json.loads(raw)
        return obj
    except Exception:
        m = re.search(r"\{.*\}", raw, re.S)
        if m:
            try:
                return json.loads(m.group(0))
            except Exception:
                return None
    return None

def host_validate(cands, obj):
    ids = {c["role"] for c in cands} | {"UNKNOWN", "NONE"}
    o = (obj or {}).get("owner")
    r = (obj or {}).get("reference")
    if o is None or o not in ids:
        return False, f"invalid owner {o!r}"
    if r is not None and r not in ids:
        return False, f"invalid reference {r!r}"
    return True, None

def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--resume", action="store_true")
    args = ap.parse_args()

    from transformers import AutoModelForCausalLM, AutoTokenizer
    tok = AutoTokenizer.from_pretrained(MODEL_DIR, trust_remote_code=True)
    model = AutoModelForCausalLM.from_pretrained(MODEL_DIR, trust_remote_code=True,
                                                 torch_dtype="auto", device_map="auto")
    print("4B loaded (structured)", flush=True)

    rows = [json.loads(l) for l in open(os.path.join(OUT, "vs6_binding_fixtures.jsonl"), encoding="utf-8")]
    op = os.path.join(OUT, "vs6_binding_structured_results.jsonl")
    done = set()
    if args.resume and os.path.exists(op):
        for l in open(op, encoding="utf-8"):
            try:
                done.add(json.loads(l)["idx"])
            except Exception:
                pass
        print(f"resume: {len(done)} done", flush=True)

    n = 0
    for row in rows:
        if args.resume and row["idx"] in done:
            continue
        cands_txt = chr(10).join(f"{c['role']} = {c['name']}" for c in row["candidates"])
        p = PROMPT.replace("{candidates}", cands_txt).replace("{text}", row["text"])
        raw = run(model, tok, p, max_new_tokens=150)
        obj = parse_binding(raw)
        valid, err = host_validate(row["candidates"], obj)
        rec = {"idx": row["idx"], "category": row["category"], "text": row["text"],
               "gold": row["gold"], "candidates": row["candidates"],
               "raw": raw, "pred": obj, "valid": valid, "error": err}
        with open(op, "a", encoding="utf-8") as f:
            f.write(json.dumps(rec, ensure_ascii=False) + chr(10))
        n += 1
        if n % 20 == 0:
            print(f"  {n} done", flush=True)
    print(f"done {n}, saved {op}", flush=True)

if __name__ == "__main__":
    main()