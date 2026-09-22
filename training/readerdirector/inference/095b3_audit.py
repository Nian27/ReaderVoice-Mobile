# -*- coding: utf-8 -*-
"""TASK-095B.3-QC Q2 — stratified 300 条人工审计抽样。

分层：普通中文姓名 / 称谓 / 多人场景 / 无人物段落(NONE) / 首次出场 / 长名字 /
外国/音译名 / 群体 / 非人智能体 / 复杂 narration（按 mention 数/文本特征分层）。
输出：audit_300.md（可读）+ audit_300.jsonl（含审核字段）。
用法: python inference/095b3_audit.py
"""
import json
import os
import random

ROOT = os.path.abspath(os.path.join(os.path.dirname(__file__), "..", "..", ".."))
TASK095 = os.path.join(ROOT, "training", "readerdirector", "runs", "task095")


def classify(r):
    mentions = r.get("validated_mentions") or []
    text = r.get("source_text", "")
    n = len(mentions)
    if n == 0:
        return "NONE"
    if n >= 3:
        return "multi_person"
    m0 = mentions[0]
    if any(c.isascii() for c in m0):
        return "foreign_name"
    if len(m0) >= 6:
        return "long_name"
    if any(x in m0 for x in "局长大人将军公子小姐殿下陛下师父长老掌门掌柜老板师傅道长"):
        return "role_title"
    if any(x in m0 for x in "众将士执法队家丁弟子们孩子们一群人"):
        return "group"
    if any(x in m0 for x in "系统AI精灵器灵机械管家机器人"):
        return "nonhuman"
    if "我是" in text or "我叫" in text or "在下" in text:
        return "first_appearance"
    return "chinese_name"


def main():
    rows = [json.loads(l) for l in open(os.path.join(TASK095, "mention_train_v2.jsonl"), encoding="utf-8")]  # 重标版（v1 仅证据）
    rng = random.Random(7)
    buckets = {}
    for r in rows:
        buckets.setdefault(classify(r), []).append(r)
    print("bucket sizes:", {k: len(v) for k, v in sorted(buckets.items())})
    picked = []
    # 每桶抽 30（不足全取），凑 300
    per = max(1, 300 // len(buckets))
    for k in sorted(buckets.keys()):
        pool = buckets[k]
        rng.shuffle(pool)
        picked.extend(pool[:per])
    picked = picked[:300]
    out = []
    md = ["# MENTION Teacher Silver 审计（300 条 stratified）", "",
          "每项：source_text | teacher mentions | 审核结论（OK/BOUNDARY_ERROR/TYPE_ERROR/MISSED/FALSE + 备注）", ""]
    for i, r in enumerate(picked, 1):
        rec = {
            "idx": i, "para_id": r["para_id"], "bucket": classify(r),
            "text": r.get("source_text", ""),
            "teacher_mentions": r.get("validated_mentions", []),
            "audit": {"verdict": None, "note": None},
        }
        out.append(rec)
        md.append(f"### {i} [{rec['bucket']}]")
        md.append(f"文本：{rec['text'][:150]}")
        md.append(f"Teacher mentions：{rec['teacher_mentions']}")
        md.append("审核：____")
        md.append("")
    with open(os.path.join(TASK095, "audit_300.md"), "w", encoding="utf-8") as f:
        f.write("\n".join(md))
    with open(os.path.join(TASK095, "audit_300.jsonl"), "w", encoding="utf-8") as f:
        for r in out:
            f.write(json.dumps(r, ensure_ascii=False) + "\n")
    print(f"audit set: {len(picked)} -> audit_300.md / .jsonl")
    from collections import Counter
    print("bucket dist:", dict(Counter(r["bucket"] for r in out)))


if __name__ == "__main__":
    main()