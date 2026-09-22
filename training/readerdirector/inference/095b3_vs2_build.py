# -*- coding: utf-8 -*-
"""TASK-095-VS2 Step 4 — VoiceEvent feasibility set 构建。

流程（与 MENTION 同协议：AI 判语义，Host 验证据）：
  1. rules 高召回检索：books_private（Train/Dev 来源）扫描高价值 voice cue（用户清单）
  2. 采样候选段落（正例 cue 命中 + 负例/硬负例采样：emotion/prosody/scene-exit）
  3. 4B few-shot 标注 NO_EVENT/TEMP_SET/TEMP_CLEAR（带 style/scope/evidence_span）
  4. host 验证 evidence_span 逐字存在 + scope 合法性

用法: python inference/095b3_vs2_build.py --stage retrieve|annotate
"""
import argparse
import glob
import json
import os
import random
import re

ROOT = os.path.abspath(os.path.join(os.path.dirname(__file__), "..", "..", ".."))
BOOKS = os.path.join(ROOT, "books_private")
OUT = os.path.join(ROOT, "training", "readerdirector", "runs", "task095")
MODEL_DIR = os.path.join(ROOT, "training", "models", "qwen3.5-4b")

# ---- 用户指定的高价值 cue 清单（TEMP_SET 方向） ----
TEMP_SET_CUES = [
    "压低声音", "压低了声音", "压低嗓音",
    "变了声音", "变了个声音", "改变了声音", "换了个声音",
    "改变声线", "变了声线",
    "模仿", "学着", "学着.*的声音", "学着.*说话", "学着.*语气",
    "装成", "装出", "假装.*声音", "故意.*声音", "故意.*语气",
    "粗着嗓子", "粗声粗气", "尖着嗓子", "尖声尖气",
    "苍老的声音", "沙哑的声音", "嘶哑的声音", "低沉的声音", "阴冷的声音",
    "用.*声音说", "用.*嗓音", "用.*语气说",
    "换了一副.*嗓子", "换了个.*嗓子",
    "用.*腔调", "捏着嗓子", "掐着嗓子",
]
# TEMP_CLEAR 方向
TEMP_CLEAR_CUES = [
    "恢复原声", "恢复了原本的声音", "恢复原本的声音", "恢复自己的声音", "恢复正常声音",
    "恢复了本来", "恢复本来", "变回原来的声音", "回到原本的声音",
    "本来的声音", "恢复了自己", "恢复正常", "声音恢复正常",
    "不再装", "撤掉.*声音", "收起.*声音", "解除.*变声",
]
# 硬负例 cue（emotion/prosody/scene-exit——禁止视为 temp voice change）
HARD_NEG_CUES = [
    "愤怒", "怒道", "冷冷道", "沉声道", "大声道", "喊道", "厉声道",  # emotion/prosody
    "轻声说", "低声说", "小声说", "柔声道", "颤声道", "哭道", "笑道",  # prosody
    "退场", "离开了", "转身离开", "走出", "出了门", "离开房间", "走了出去",  # scene-exit
    "换场", "镜头一转", "画面一转", "次日", "第二天", "回到",  # scene change
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

def classify_cue(text):
    """规则粗分类：命中返回 (cat, cue)；cat ∈ temp_set/temp_clear/hard_neg/none"""
    for c in TEMP_SET_CUES:
        if re.search(c, text):
            return ("temp_set", c)
    for c in TEMP_CLEAR_CUES:
        if re.search(c, text):
            return ("temp_clear", c)
    for c in HARD_NEG_CUES:
        if re.search(c, text):
            return ("hard_neg", c)
    return ("none", None)

def main_retrieve(args):
    files = sorted(glob.glob(os.path.join(BOOKS, "*.txt")) + glob.glob(os.path.join(BOOKS, "**", "*.txt"), recursive=True))
    buckets = {"temp_set": [], "temp_clear": [], "hard_neg": [], "none": []}
    rng = random.Random(7)
    for f in files:
        paras = paragraphs(f)
        for p in paras:
            cat, cue = classify_cue(p)
            if cat == "temp_set":
                buckets["temp_set"].append({"text": p, "cue": cue, "book": os.path.basename(f)[:30]})
            elif cat == "temp_clear":
                buckets["temp_clear"].append({"text": p, "cue": cue, "book": os.path.basename(f)[:30]})
            elif cat == "hard_neg":
                buckets["hard_neg"].append({"text": p, "cue": cue, "book": os.path.basename(f)[:30]})
            else:
                buckets["none"].append({"text": p, "book": os.path.basename(f)[:30]})
    print("raw bucket sizes:", {k: len(v) for k, v in buckets.items()})
    # 目标配额：TEMP_SET 120 / TEMP_CLEAR 60 / NO_EVENT 150 / hard_neg 80（含在 NO_EVENT 标注中）
    def sample(lst, n):
        rng.shuffle(lst)
        return lst[:n]
    picked = []
    picked += [{"kind": "temp_set", **d} for d in sample(buckets["temp_set"], 120)]
    picked += [{"kind": "temp_clear", **d} for d in sample(buckets["temp_clear"], 60)]
    picked += [{"kind": "hard_neg", **d} for d in sample(buckets["hard_neg"], 80)]
    picked += [{"kind": "none", **d} for d in sample(buckets["none"], 150)]
    rng.shuffle(picked)
    # 若 TEMP_SET 不足 120：从 none 桶补 cue 弱命中（低优先级）
    print("picked:", {k: sum(1 for d in picked if d["kind"] == k) for k in ["temp_set", "temp_clear", "hard_neg", "none"]})
    op = os.path.join(OUT, "vs2_candidates.jsonl")
    with open(op, "w", encoding="utf-8") as f:
        for d in picked:
            f.write(json.dumps(d, ensure_ascii=False) + chr(10))
    print("saved:", op, "total", len(picked))

if __name__ == "__main__":
    ap = argparse.ArgumentParser()
    ap.add_argument("--stage", choices=["retrieve", "annotate"], required=True)
    args = ap.parse_args()
    if args.stage == "retrieve":
        main_retrieve(args)
    else:
        print("annotate stage: 见 095b3_vs2_annotate.py")