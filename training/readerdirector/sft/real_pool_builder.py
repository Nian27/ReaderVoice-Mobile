# -*- coding: utf-8 -*-
"""REAL_POOL_V1 样本池构建：books_private/real_pool_v1/*.segments.jsonl → 可标注样本。
输出 real_pool_v1_samples.jsonl（rule_unknown/postposed/pronoun/k3+ 加权 hard 采样）+ 统计。
"""
import hashlib
import json
import os
import re
from collections import Counter

ROOT = os.path.abspath(os.path.join(os.path.dirname(__file__), "..", "..", ".."))
RP = os.path.join(ROOT, "books_private", "real_pool_v1")
DS = os.path.join(ROOT, "training", "readerdirector", "dataset")

SCENES = ["夜已深，灯下两个人影。", "山道上，一行人沉默前行。", "屋内炉火正旺。"]


def main():
    seg_files = sorted(f for f in os.listdir(RP) if f.endswith(".segments.jsonl"))
    all_segs = []
    for f in seg_files:
        for l in open(os.path.join(RP, f), encoding="utf-8"):
            all_segs.append(json.loads(l))
    print(f"total segments: {len(all_segs)}")

    stats = Counter()
    for s in all_segs:
        sig = s["signals"]
        for k in sig:
            if sig[k]:
                stats[k] += 1
    print("signal stats:", dict(stats))

    # 加权 hard score
    def hard_score(s):
        sig = s["signals"]
        return sum(1 for k in ("rule_unknown", "postposed", "pronoun", "k3plus") if sig.get(k)) + (1 if sig.get("cross_cue") else 0)

    for s in all_segs:
        s["hard_score"] = hard_score(s)

    # 分层采样 4000（高分段优先）
    import random
    rng = random.Random(101)
    by_score = {}
    for s in all_segs:
        by_score.setdefault(s["hard_score"], []).append(s)
    picked = []
    for score in sorted(by_score.keys(), reverse=True):
        bucket = by_score[score]
        rng.shuffle(bucket)
        picked += bucket[:max(200, 4000 // max(len(by_score), 1)) if score >= 2 else 0]
        if len(picked) >= 4000:
            break
    picked = picked[:4000]

    # 组织为样本（student/teacher 可直接消费）
    out = []
    for s in picked:
        cands = [{"role": c, "name": c.split("-")[-1], "distance": i + 1} for i, c in enumerate(s["candidates"])]
        rc = s.get("recent_context") or []
        if not rc:
            rc = [SCENES[0]] if not s["signals"]["postposed"] else [SCENES[0], "（前文省略）"]
        sample = {
            "task": "SPEAKER",
            "provenance": "UNKNOWN" if s["signals"]["rule_unknown"] else "EXPLICIT_RULE_GOLD",
            "book_hash": f"REAL-{s['book']}",
            "sample_id": hashlib.sha256(f"{s['book']}|{s['para_id']}|{s['seg_idx']}".encode()).hexdigest()[:16],
            "input": {
                "text": s["text"], "segment_type": s["type"],
                "text_ref": f"real/{s['book']}/{s['para_id']}/{s['seg_idx']}",
                "recent_context": rc,
                "rule_candidates": cands,
                "identity_constraints": [], "embodiment": [], "overrides": [],
                "post_cue": s["text"] if False else None,
            },
            "target": {"speaker": s["rule_speaker"] or "UNKNOWN", "status": s["status"]},
            "meta": {"book": s["book"], "para_id": s["para_id"], "seg_idx": s["seg_idx"],
                     "hard_score": s["hard_score"], "signals": s["signals"], "n_candidates": s["n_candidates"]},
        }
        out.append(sample)
    out.sort(key=lambda s: s["sample_id"])
    op = os.path.join(DS, "real_pool_v1_samples.jsonl")
    with open(op, "w", encoding="utf-8") as f:
        for s in out:
            f.write(json.dumps(s, ensure_ascii=False) + "\n")
    h = hashlib.sha256(open(op, "rb").read()).hexdigest()
    dist = Counter(s["meta"]["hard_score"] for s in out)
    print(f"real_pool_v1_samples: {len(out)} sha256={h} hard_dist={dict(sorted(dist.items()))}")
    json.dump({"n": len(out), "sha256": h, "hard_dist": dict(sorted(dist.items()))},
              open(os.path.join(DS, "REAL_POOL_V1_SAMPLES_MANIFEST.json"), "w", encoding="utf-8"), indent=1)


if __name__ == "__main__":
    main()
