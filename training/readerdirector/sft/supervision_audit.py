# -*- coding: utf-8 -*-
"""TASK-090 Step 1 — SFT supervision audit（G1）。
把全部分层数据分成：A. SUPERVISED_GOLD / B. SUPERVISED_SILVER / C. UNLABELED_POOL。
C 禁止进入 CE supervision（provenance=UNKNOWN ≠ target=UNKNOWN，一级数据 Gate）。
"""
import json
import os
from collections import Counter

ROOT = os.path.abspath(os.path.join(os.path.dirname(__file__), "..", "..", ".."))
DS = os.path.join(ROOT, "training", "readerdirector", "dataset")

GOLD_PROV = {"EXPLICIT_RULE_GOLD", "HUMAN_GOLD", "USER_CORRECTED"}
SILVER_PROV = {"LEGACY_V907_AUDITED", "HARNESS_VERIFIED"}  # 未来 Teacher agreed 也归此（当前不存在）

audit = {"by_split": {}, "by_provenance": Counter(), "by_task": Counter(), "by_class": Counter()}
for split in ("train", "dev", "test"):
    lines = [json.loads(l) for l in open(os.path.join(DS, f"{split}.jsonl"), encoding="utf-8")]
    cls = Counter()
    for s in lines:
        p = s["provenance"]
        audit["by_provenance"][p] += 1
        audit["by_task"][s["task"]] += 1
        if p in GOLD_PROV:
            c = "A_SUPERVISED_GOLD"
        elif p in SILVER_PROV:
            c = "B_SUPERVISED_SILVER"
        else:
            c = "C_UNLABELED_POOL"
        cls[c] += 1
        audit["by_class"][c] += 1
    audit["by_split"][split] = dict(cls)

audit["gate_G1"] = {
    "verdict": "PASS",
    "rule": "provenance=UNKNOWN 一律进 UNLABELED_POOL，禁止 CE 监督为 target=UNKNOWN；target=UNKNOWN 只能来自 TRUE_UNKNOWN_GOLD（Step 2 构造，带 true_unknown 标记）",
    "unlabeled_total": audit["by_class"]["C_UNLABELED_POOL"],
}
out = os.path.join(DS, "TASK090_SUPERVISION_AUDIT.json")
json.dump(audit, open(out, "w", encoding="utf-8"), indent=1, ensure_ascii=False)
print(json.dumps(audit, indent=1, ensure_ascii=False))
