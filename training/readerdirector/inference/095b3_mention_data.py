# -*- coding: utf-8 -*-
"""TASK-095B.3 — MENTION 训练数据构建。

17 本真实小说 → 段落抽样（每本 500，均匀覆盖）→ 4B few-shot MENTION 提取
→ 程序 span 验证（surface 逐字存在于段落）→ mention_train.jsonl。

增量落盘 + resume（机器重启/中断安全）。
用法: python inference/095b3_mention_data.py [--resume]
"""
import argparse
import glob
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
from mention_prompt_v2 import PROMPT_FS_V2, PROMPT_FS_V3
PROMPT_FS = _m.PROMPT  # v1 默认（冻结基线）；--prompt_version v2 用 PROMPT_FS_V2（ADR-039）

ROOT = os.path.abspath(os.path.join(os.path.dirname(__file__), "..", "..", ".."))
BOOKS = os.path.join(ROOT, "books_private")
OUT = os.path.join(ROOT, "training", "readerdirector", "runs", "task095")
MODEL_DIR = os.path.join(ROOT, "training", "models", "qwen3.5-4b")

PER_BOOK = 250


def read_text(path):
    for enc in ("utf-8", "gb18030", "gbk", "big5"):
        try:
            raw = open(path, encoding=enc).read()
            if "�" not in raw[:500]:
                return raw
        except (UnicodeDecodeError, UnicodeError):
            continue
    return open(path, encoding="utf-8", errors="ignore").read()


def paragraphs(path):
    """TXT → 段落（行级切分，网文每行一段；过滤极短行/章节头/空行）。"""
    raw = read_text(path)
    out = []
    for line in raw.split("\n"):
        b = line.strip()
        if len(b) < 40 or len(b) > 600:
            continue
        if re.fullmatch(r"[\d\s第章节卷回]+", b[:20]):
            continue
        out.append(b)
    return out


def align_offsets(surface, text):
    """host 计算 offsets：surface 逐字匹配 text；重复出现无法唯一定位 → AMBIGUOUS_ALIGNMENT。"""
    starts = [m.start() for m in re.finditer(re.escape(surface), text)]
    if not starts:
        return None
    if len(starts) > 1:
        return {"surface": surface, "start": starts[0], "end": starts[0] + len(surface),
                "align": "AMBIGUOUS_ALIGNMENT", "occurrences": len(starts)}
    return {"surface": surface, "start": starts[0], "end": starts[0] + len(surface), "align": "UNIQUE"}


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--resume", action="store_true")
    ap.add_argument("--prompt_version", default="v1", choices=["v1", "v2", "v3"], help="v2 = ADR-039 非实体清单+呼语/代词规则")
    args = ap.parse_args()
    global PROMPT_FS
    if args.prompt_version == "v2":
        PROMPT_FS = PROMPT_FS_V2
    elif args.prompt_version == "v3":
        PROMPT_FS = PROMPT_FS_V3

    from transformers import AutoModelForCausalLM, AutoTokenizer
    tok = AutoTokenizer.from_pretrained(MODEL_DIR, trust_remote_code=True)
    model = AutoModelForCausalLM.from_pretrained(
        MODEL_DIR, trust_remote_code=True, torch_dtype="auto", device_map="auto")
    print("4B loaded", flush=True)

    # 当前环境重标统一写 mention_train_v2.jsonl（与历史 mention_train.jsonl 环境不一致，证据保留 §39）
    out_path = os.path.join(OUT, "mention_train_v2.jsonl")
    done = set()
    if args.resume and os.path.exists(out_path):
        for l in open(out_path, encoding="utf-8"):
            try:
                done.add(json.loads(l)["para_id"])
            except Exception:
                pass
        print(f"resume: {len(done)} done", flush=True)

    t0 = time.perf_counter()
    n = 0
    files = sorted(glob.glob(os.path.join(BOOKS, "*.txt")))
    for f in files:
        book = os.path.basename(f)[:20]
        paras = paragraphs(f)
        if not paras:
            continue
        # 均匀抽样（确定性）
        step = max(1, len(paras) // PER_BOOK)
        picked = paras[::step][:PER_BOOK]
        for pi, text in enumerate(picked):
            para_id = f"{book}|{pi * step}"
            if args.resume and para_id in done:
                continue
            prompt = PROMPT_FS.replace("{text}", text)
            # 固定 400：200 截断致 MISS（A1 归因）；v1+400 是 Dev-QC 综合最优（回归最少、无碎化）
            raw = run(model, tok, prompt, max_new_tokens=400)
            teacher_raw = parse_mentions(raw)
            # span 验证（host 权威）：surface 逐字存在
            validated = [s for s in teacher_raw if s and s in text]
            offsets = []
            for s in validated:
                off = align_offsets(s, text)
                if off:
                    offsets.append(off)
            rec = {
                "para_id": para_id, "book": book,
                "source_text": text,
                "teacher_model": "qwen3.5-4b",
                "prompt_version": "mention_fs_" + args.prompt_version,
                "generation_config": {"max_new_tokens": 200, "do_sample": False},
                "teacher_raw_output": raw,
                "teacher_mentions_raw": teacher_raw,
                "validated_mentions": validated,
                "normalized_mentions": validated,
                "offsets": offsets,
            }
            with open(out_path, "a", encoding="utf-8") as fo:
                fo.write(json.dumps(rec, ensure_ascii=False) + "\n")
            n += 1
            if n % 50 == 0:
                print(f"  {n} new ({time.perf_counter()-t0:.0f}s)", flush=True)
    print(f"done {n} new, total {len(done)+n}, saved {out_path}")


if __name__ == "__main__":
    main()