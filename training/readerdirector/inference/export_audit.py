# -*- coding: utf-8 -*-
"""TASK-100 Stage 14/18 — Human Audit 集导出（200-500 条最高价值样本）。
选择优先级：Teacher disagreement > Rule vs Teacher > Student 高置信错误 > Perm unstable >
Identity SAME 候选 > Embodiment > TRUE_UNKNOWN。
输出 audit_v1.jsonl（人类可读，含上下文/各方答案/待标注字段）。
"""
import argparse
import json
import os
import sys
from collections import Counter

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
from prompt_builder import build_prompt, load_prompts

ROOT = os.path.abspath(os.path.join(os.path.dirname(__file__), "..", "..", ".."))
DS = os.path.join(ROOT, "training", "readerdirector", "dataset")
RUNS = os.path.join(ROOT, "training", "readerdirector", "runs", "task100")
PROMPTS = load_prompts("v2")

PRIORITY = {
    "RULE_STUDENT_TEACHER_DIFF": 0,   # Rule+Student 一致但 Teacher 不同 → 规则可能错
    "STUDENT_TEACHER_RULE_HARD": 1,
    "RULE_TEACHER_STUDENT_HARD": 2,
    "NEW_CAPABILITY": 3,
    "MIXED": 4,
    "ALL_AGREE": 5,
}


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--teacher_file", required=True, help="teacher_*.jsonl (record 含 disagreement)")
    ap.add_argument("--n", type=int, default=300)
    args = ap.parse_args()

    records = [json.loads(l) for l in open(args.teacher_file, encoding="utf-8")]
    samples = {json.loads(l)["sample_id"]: json.loads(l)
               for l in open(os.path.join(DS, "hard_pool_v1.jsonl"), encoding="utf-8")}

    # 打分：agreement_pattern 优先级 + perm unstable + identity SAME + embodiment
    def score(r):
        s = PRIORITY.get(r["agreement_pattern"], 9)
        if r.get("teacher_order_unstable"):
            s -= 0.5
        if r["task"] == "IDENTITY" and r["answer"] == "SAME":
            s -= 0.3
        smp = samples.get(r["sample_id"], {})
        if smp.get("input", {}).get("embodiment"):
            s -= 0.2
        return s

    records.sort(key=score)
    picked = records[:args.n]
    picked.sort(key=lambda r: r["sample_id"])

    out = []
    for r in picked:
        smp = samples.get(r["sample_id"], {})
        prompt, _ = build_prompt(smp.get("task", "SPEAKER"), smp, PROMPTS) if smp else ("", [])
        out.append({
            "sample_id": r["sample_id"], "task": r["task"],
            "agreement_pattern": r["agreement_pattern"],
            "rule_answer": r["rule_answer"], "student_answer": r["student_answer"],
            "teacher_answer": r["answer"], "teacher_model": r["teacher_model"],
            "perm_stable": r.get("perm_stable"),
            "input_preview": json.dumps(smp.get("input", {}), ensure_ascii=False)[:1500],
            "prompt": prompt[:1200],
            "human_gold": None,   # 待人工标注
            "audited_by": None, "audited_at": None,
        })
    os.makedirs(RUNS, exist_ok=True)
    out_path = os.path.join(RUNS, "human_audit_v1.jsonl")
    with open(out_path, "w", encoding="utf-8") as f:
        for o in out:
            f.write(json.dumps(o, ensure_ascii=False) + "\n")
    dist = Counter(o["agreement_pattern"] for o in out)
    print(f"audit set: {len(out)} samples -> {out_path}")
    print("pattern dist:", dict(dist))


if __name__ == "__main__":
    main()
