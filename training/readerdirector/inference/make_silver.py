# -*- coding: utf-8 -*-
"""TASK-100 Stage 3/4 — Silver 生成 + 9B 仲裁子集筛选。
输入：teacher_*.jsonl（含 rule/student/teacher answer + agreement_pattern + perm_stable）
处理决策表（Stage 4/5，9B 只仲裁 disagreement）：
  ALL_AGREE / STUDENT_TEACHER_RULE_HARD + perm_stable → 高置信 Silver（TEACHER_AUDITED）
  RULE_STUDENT_TEACHER_DIFF / NEW_CAPABILITY → 规则可能错/新能力 → Human audit 最高价值
  MIXED / RULE_TEACHER_STUDENT_HARD（rule 与 teacher 印证）/ ALL_UNKNOWN → 9B 仲裁
  perm_unstable（TEACHER_ORDER_UNSTABLE）/ protocol invalid → 隔离（不进任何下游，证据保留）
输出：
  silver_v1.jsonl（SFT v2 可消费）
  arbitration_9b.jsonl（待 9B 仲裁）
  human_audit_candidates.jsonl（最高价值，≤100）
"""
import argparse
import hashlib
import json
import os
import sys
from collections import Counter

sys.path.insert(0, os.path.join(os.path.dirname(os.path.abspath(__file__)), "..", "inference"))
from prompt_builder import build_prompt, load_prompts

ROOT = os.path.abspath(os.path.join(os.path.dirname(__file__), "..", "..", ".."))
DS = os.path.join(ROOT, "training", "readerdirector", "dataset")
RUNS = os.path.join(ROOT, "training", "readerdirector", "runs", "task100")
PROMPTS = load_prompts("v2")

SILVER_PATTERNS = {"ALL_AGREE", "STUDENT_TEACHER_RULE_HARD"}
AUDIT_PATTERNS = {"RULE_STUDENT_TEACHER_DIFF", "NEW_CAPABILITY"}


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--teacher_file", required=True)
    ap.add_argument("--pool_file", default="hard_pool_v1.jsonl")
    args = ap.parse_args()

    recs = [json.loads(l) for l in open(args.teacher_file, encoding="utf-8")]
    pool = {json.loads(l)["sample_id"]: json.loads(l) for l in open(os.path.join(DS, args.pool_file), encoding="utf-8")}

    silver, arb9, audit, quarantine = [], [], [], []
    stats = Counter()
    for r in recs:
        s = pool.get(r["sample_id"])
        if s is None:
            continue
        pattern = r["agreement_pattern"]
        if not r.get("protocol_valid"):
            quarantine.append({**s, "quarantine": "PROTOCOL_INVALID",
                               "teacher_answer": r["answer"], "rule_answer": r["rule_answer"],
                               "student_answer": r["student_answer"]})
            stats["quarantine_protocol"] += 1
            continue
        perm_unstable = r.get("teacher_order_unstable")
        if perm_unstable:
            quarantine.append({**s, "quarantine": "TEACHER_ORDER_UNSTABLE",
                               "teacher_answer": r["answer"], "rule_answer": r["rule_answer"],
                               "student_answer": r["student_answer"]})
            stats["quarantine_perm"] += 1
            continue
        if pattern in SILVER_PATTERNS and r["answer"] is not None:
            sample = dict(s)
            sample["provenance"] = "TEACHER_AUDITED"
            sample["target"] = {"speaker": r["answer"], "status": "CONFIRMED"}
            sample["teacher_meta"] = {"teacher": r["teacher_model"], "revision": r["teacher_revision"],
                                      "agreement": pattern, "student_answer": r["student_answer"],
                                      "rule_answer": r["rule_answer"]}
            silver.append(sample)
            stats["silver"] += 1
        elif pattern in AUDIT_PATTERNS:
            audit.append({"sample_id": r["sample_id"], "pattern": pattern,
                          "rule": r["rule_answer"], "student": r["student_answer"], "teacher": r["answer"],
                          "perm_stable": r.get("perm_stable"), "input": s.get("input", {}),
                          "prompt_hash": r.get("prompt_hash")})
            stats["audit_candidates"] += 1
        else:
            arb9.append({"sample_id": r["sample_id"], "task": s.get("task"), "pattern": pattern,
                         "rule": r["rule_answer"], "student": r["student_answer"], "teacher": r["answer"],
                         "input": s.get("input", {})})
            stats["arb9"] += 1

    os.makedirs(RUNS, exist_ok=True)
    with open(os.path.join(RUNS, "silver_v1.jsonl"), "w", encoding="utf-8") as f:
        for s in silver:
            f.write(json.dumps(s, ensure_ascii=False) + "\n")
    with open(os.path.join(RUNS, "arbitration_9b.jsonl"), "w", encoding="utf-8") as f:
        for s in arb9:
            f.write(json.dumps(s, ensure_ascii=False) + "\n")
    with open(os.path.join(RUNS, "quarantine.jsonl"), "w", encoding="utf-8") as f:
        for s in quarantine:
            f.write(json.dumps(s, ensure_ascii=False) + "\n")
    # Human audit ≤100
    audit.sort(key=lambda a: 0 if a["pattern"] in AUDIT_PATTERNS else 1)
    with open(os.path.join(RUNS, "human_audit_candidates.jsonl"), "w", encoding="utf-8") as f:
        for s in audit[:100]:
            f.write(json.dumps(s, ensure_ascii=False) + "\n")
    print("stats:", dict(stats))
    print(f"silver_v1: {len(silver)} | arbitration_9b: {len(arb9)} | human_audit_candidates: {min(len(audit), 100)} | quarantine: {len(quarantine)}")


if __name__ == "__main__":
    main()
