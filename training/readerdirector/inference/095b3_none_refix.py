# -*- coding: utf-8 -*-
"""TASK-095B.3-QC — NONE 段落修正重跑（强化 prompt）。

审计发现：713 条 NONE 中 ~35% 是假 NONE（叙述提及人名/代词动作主体漏提）。
强化 prompt：叙述段人名必须提；代词（他/她/我）作动作主体在无具体名字时标注；
排除产品名/媒体名/广告（FALSE 噪声）。
只重跑 validated_mentions 为空的段落，修正后写回 mention_train.jsonl。
"""
import json
import os
import re
import sys
import time

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
from importlib import import_module
_m = import_module("095b2_eval_mention")
run = _m.run
parse_mentions = _m.parse_mentions
_md = import_module("095b3_mention_data")
align_offsets = _md.align_offsets

ROOT = os.path.abspath(os.path.join(os.path.dirname(__file__), "..", "..", ".."))
OUT = os.path.join(ROOT, "training", "readerdirector", "runs", "task095")
MODEL_DIR = os.path.join(ROOT, "training", "models", "qwen3.5-4b")

PROMPT_FIX = (
    "你是 ReaderDirector。找出下面文本中所有\u201c人物/可发声实体\u201d的提及（mention）："
    "人物、群体、职位/称谓、昵称、非人智能体等。\n"
    "规则：\n"
    "- 只输出原文中逐字存在的片段（surface），不要创造、改写、省略或翻译名字\n"
    "- 叙述中出现的任何人名都是 mention（如\u201c殷正茂有三个儿子\u201d中的\u201c殷正茂\u201d）\n"
    "- 代词（他/她/我）作动作主体且无具体名字时也标注（如\u201c她嚼碎灵药\u201d中的\u201c她\u201d）\n"
    "- 群体（将士们/众家丁）也是 mention\n"
    "- 排除：产品名/媒体名/书名/杂志名（如\u201cA 片\u201d\u201c《精灵luna》\u201d）\n"
    "- 每个 mention 输出 surface 和 type（PERSON/GROUP/ROLE/AGENT）\n"
    "- 没有则输出空列表\n"
    '输出 JSON: {"mentions": [{"surface": "...", "type": "..."}]}\n\n'
    "示例：\n文本：殷正茂有三个儿子，老大殷宗伊在前军都督府做经历。\n"
    '输出：{"mentions": [{"surface": "殷正茂", "type": "PERSON"}, {"surface": "殷宗伊", "type": "PERSON"}]}\n\n'
    "文本：\n{text}"
)


def main():
    from transformers import AutoModelForCausalLM, AutoTokenizer
    tok = AutoTokenizer.from_pretrained(MODEL_DIR, trust_remote_code=True)
    model = AutoModelForCausalLM.from_pretrained(
        MODEL_DIR, trust_remote_code=True, torch_dtype="auto", device_map="auto")
    print("4B loaded", flush=True)

    path = os.path.join(OUT, "mention_train.jsonl")
    rows = [json.loads(l) for l in open(path, encoding="utf-8")]
    none_rows = [r for r in rows if not r.get("validated_mentions")]
    print(f"total {len(rows)} | NONE to refix: {len(none_rows)}", flush=True)

    n = 0
    for r in none_rows:
        text = r["source_text"]
        raw = run(model, tok, PROMPT_FIX.replace("{text}", text))
        teacher_raw = parse_mentions(raw)
        validated = [s for s in teacher_raw if s and s in text]
        offsets = []
        for s in validated:
            off = align_offsets(s, text)
            if off:
                offsets.append(off)
        r["teacher_raw_output"] = raw
        r["teacher_mentions_raw"] = teacher_raw
        r["validated_mentions"] = validated
        r["normalized_mentions"] = validated
        r["offsets"] = offsets
        r["refixed"] = True
        n += 1
        if n % 100 == 0:
            print(f"  {n}/{len(none_rows)} refixed ({time.perf_counter():.0f}s)", flush=True)
    with open(path, "w", encoding="utf-8") as f:
        for r in rows:
            f.write(json.dumps(r, ensure_ascii=False) + "\n")
    still_none = sum(1 for r in rows if not r.get("validated_mentions"))
    print(f"refixed {n} | still NONE: {still_none} ({still_none/len(rows)*100:.1f}%)")


if __name__ == "__main__":
    main()
