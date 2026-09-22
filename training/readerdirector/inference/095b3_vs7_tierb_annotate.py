# -*- coding: utf-8 -*-
"""TASK-095-VS7-TIER-B — 4B Teacher 标注（EVENT/OWNER/REFERENCE/EVIDENCE）。

复用 VS2 验证模式：AI 判语义 + Host exact-span 验证。
输出：runs/task095/vs7_tierb_annotated.jsonl
"""
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
    "你是 ReaderDirector。判断下面真实小说文本中的声音控制事件。\n"
    "\n"
    "事件：\n"
    "- VOICE_OVERRIDE_SET：声线身份被临时替换（模仿/借用/变成某人的声音、假声、捏嗓子）\n"
    "- VOICE_OVERRIDE_CLEAR：恢复原本的声音/停止变声/切换回自己的声线\n"
    "- PERFORMANCE：仍是本来的声音，只是当前怎么说（压低声音/冷冷道/颤抖/笑着/哽咽等）\n"
    "- NONE：与声音身份变化无关（说话内容、物体声音、情绪描写、固有音色描述等）\n"
    "\n"
    "绑定：\n"
    "- O = 实际发声者（谁的声音状态变化），不确定 → UNKNOWN\n"
    "- R = 被模仿/借用的声音归属，无 → NONE\n"
    "- A用B的声音 → O=A, R=B；A让B用C的声音 → O=B, R=C；A听见B变声 → O=B（听见者不选）\n"
    "\n"
    "输出 JSON：{\"event\": \"VOICE_OVERRIDE_SET|VOICE_OVERRIDE_CLEAR|PERFORMANCE|NONE\", \"owner\": \"原文名字或 UNKNOWN\", \"reference\": \"原文名字或 NONE/UNKNOWN\", \"evidence\": \"原文逐字证据（NONE 时 null）\", \"confidence\": \"H|M|L\"}\n"
    "\n"
    "文本：{text}"
)

def parse_event(raw):
    raw = raw.strip()
    try:
        return json.loads(raw)
    except Exception:
        m = re.search(r"\{.*\}", raw, re.S)
        if m:
            try:
                return json.loads(m.group(0))
            except Exception:
                return None
    return None

def host_validate(rec, obj):
    text = rec["text"]
    ev = (obj or {}).get("event")
    if ev not in ("VOICE_OVERRIDE_SET", "VOICE_OVERRIDE_CLEAR", "PERFORMANCE", "NONE"):
        return None, "invalid event"
    if ev == "NONE":
        return {"event": "NONE", "owner": None, "reference": None, "evidence": None, "confidence": None}, None
    evd = (obj or {}).get("evidence")
    if not evd or evd not in text:
        return None, "evidence not in text"
    o = (obj or {}).get("owner")
    if o and o not in ("UNKNOWN",) and o not in text:
        return None, f"owner not in text: {o}"
    r2 = (obj or {}).get("reference")
    if r2 and r2 not in ("NONE", "UNKNOWN") and r2 not in text:
        return None, f"reference not in text: {r2}"
    return {"event": ev, "owner": o or "UNKNOWN", "reference": r2 or "NONE",
            "evidence": evd, "confidence": (obj or {}).get("confidence")}, None

def main():
    import argparse
    ap = argparse.ArgumentParser()
    ap.add_argument("--resume", action="store_true")
    ap.add_argument("--limit", type=int, default=0)
    args = ap.parse_args()

    from transformers import AutoModelForCausalLM, AutoTokenizer
    tok = AutoTokenizer.from_pretrained(MODEL_DIR, trust_remote_code=True)
    model = AutoModelForCausalLM.from_pretrained(MODEL_DIR, trust_remote_code=True,
                                                 torch_dtype="auto", device_map="auto")
    print("4B loaded", flush=True)

    rows = [json.loads(l) for l in open(os.path.join(OUT, "vs7_tierb_candidates.jsonl"), encoding="utf-8")]
    if args.limit:
        rows = rows[:args.limit]
    op = os.path.join(OUT, "vs7_tierb_annotated.jsonl")
    done = set()
    if args.resume and os.path.exists(op):
        for l in open(op, encoding="utf-8"):
            try:
                done.add(json.loads(l)["idx"])

            except Exception:

                pass
        print(f"resume: {len(done)}", flush=True)


    n = 0

    for i, c in enumerate(rows):

        if args.resume and i in done:

            continue

        raw = run(model, tok, PROMPT.replace("{text}", c["text"]), max_new_tokens=120)

        obj = parse_event(raw)

        valid, err = host_validate(c, obj)

        rec = {"idx": i, **c, "teacher_raw": raw, "teacher_event": obj,

               "validated": valid, "error": err}

        with open(op, "a", encoding="utf-8") as f:

            f.write(json.dumps(rec, ensure_ascii=False) + chr(10))

        n += 1

        if n % 20 == 0:

            print(f"  {n} done (err={err or 'ok'})", flush=True)

    print(f"done {n}, saved {op}", flush=True)


if __name__ == "__main__":

    main()
