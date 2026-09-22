# -*- coding: utf-8 -*-
"""TASK-095-VS7-TIER-B — 真实书预筛（用户 §4）。

预筛三类（保留真实分布又控制成本）：
  1. Likely Voice Event：SET/CLEAR cue（声音身份变化）
  2. Likely Performance：prosody cue（低声/冷冷/笑着/颤声 等）
  3. Random Background NONE：无 cue 随机采样

输出：runs/task095/vs7_tierb_candidates.jsonl
"""
import glob
import json
import os
import random
import re

ROOT = os.path.abspath(os.path.join(os.path.dirname(__file__), "..", "..", ".."))
BOOKS = os.path.join(ROOT, "books_private")
OUT = os.path.join(ROOT, "training", "readerdirector", "runs", "task095")

# SET/CLEAR cue（声音身份变化，真实书语言更复杂）
SET_CUES = [
    r"(?:用|借|装成|换成|变回|换回|学着|模仿|学|发出)[^，。！？；：]{0,10}(?:声音|嗓音|声线|腔调|嗓子)",
    r"(?:声音|嗓音|声线|嗓子)[^，。！？；：]{0,8}(?:变了|换了|变成|伪装|嘶哑|苍老|完全)",
    r"(?:恢复|变回|换回)[^，。！？；：]{0,10}(?:声音|嗓音|声线|原声|嗓子)",
    r"(?:不再|别在|别用|放下)(?:捏着|装出|假装|用)[^，。！？；：]{0,6}(?:嗓子|假声|声音)",
    r"捏着嗓子|掐着嗓子|假声|变了声",
]
# PERFORMANCE cue（prosody，非身份变化）
PERF_CUES = [
    r"(?:压低|放轻|提高|加大|压低)(?:声音|嗓音|声线|嗓子|音量)",
    r"(?:冷冷|淡淡|轻声|低声|沉声|柔声|颤声|哑声|哽咽|激动|不耐烦|悲伤|笑着|哭着|咬牙切齿|叹息)(?:地)?(?:说道|说|问|答|开口|道)",
    r"(?:声音|嗓音)[^，。！？；：]{0,6}(?:颤抖|哽咽|沙哑|含糊|低沉|轻柔)",
]
# CLEAR 单独 cue（自然小说稀少，重点捞）
CLEAR_CUES = [
    r"(?:恢复|变回|换回)(?:了)?(?:原本|原来|本来|自己|正常)?(?:的)?(?:声音|嗓音|声线|原声|嗓子)",
    r"(?:声音|嗓音)(?:又)?(?:恢复正常|恢复如初|变回原样)",
    r"不再(?:捏着嗓子|装模作样|假声)",
]

def read_text(path):
    for enc in ("utf-8", "gb18030", "gbk", "big5"):
        try:
            raw = open(path, encoding=enc).read()
            if "\ufffd" not in raw[:500]:
                return raw
        except (UnicodeDecodeError, UnicodeError):
            continue
    return open(path, encoding="utf-8", errors="ignore").read()

def paragraphs(path):
    raw = read_text(path)
    out = []
    for line in raw.split("\n"):
        b = line.strip()
        if len(b) < 40 or len(b) > 600:
            continue
        if re.fullmatch(r"[\d\s\u7b2c\u7ae0\u8282\u5377\u56de]+\S{0,20}", b[:30]):
            continue
        out.append(b)
    return out

def classify(text):
    for pat in CLEAR_CUES:
        if re.search(pat, text):
            return ("clear", pat)
    for pat in SET_CUES:
        if re.search(pat, text):
            return ("set", pat)
    for pat in PERF_CUES:
        if re.search(pat, text):
            return ("perf", pat)
    return ("none", None)

def main():
    files = sorted(glob.glob(os.path.join(BOOKS, "*.txt")) + glob.glob(os.path.join(BOOKS, "**", "*.txt"), recursive=True))
    buckets = {"set": [], "clear": [], "perf": [], "none": []}
    for f in files:
        book = os.path.basename(f)[:30]
        for p in paragraphs(f):
            cat, cue = classify(p)
            buckets[cat].append({"text": p, "cue": cue, "book": book, "bucket": cat})
    print("raw buckets:", {k: len(v) for k, v in buckets.items()})

    rng = random.Random(7)
    def sample(lst, n):
        rng.shuffle(lst)
        return lst[:n]
    # 目标：SET 400 / CLEAR 300（重点捞）/ PERF 500 / NONE 800
    picked = (sample(buckets["set"], 400) + sample(buckets["clear"], 300) +
              sample(buckets["perf"], 500) + sample(buckets["none"], 800))
    rng.shuffle(picked)
    print("picked:", {k: sum(1 for d in picked if d["bucket"] == k) for k in ["set", "clear", "perf", "none"]})
    op = os.path.join(OUT, "vs7_tierb_candidates.jsonl")
    with open(op, "w", encoding="utf-8") as f:
        for d in picked:
            f.write(json.dumps(d, ensure_ascii=False) + chr(10))
    print("saved:", op, "total", len(picked))

if __name__ == "__main__":
    main()