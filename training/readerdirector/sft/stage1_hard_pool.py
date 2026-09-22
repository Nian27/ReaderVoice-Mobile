# -*- coding: utf-8 -*-
"""TASK-100 Stage 1 — Hard Pool 构建器（G2：Hard Pool 不是随机 37k）。
从真实书 UNLABELED_POOL 的 SPEAKER 样本算规则信号 hardness_score（0-9），按分数分层采样。
（student/4B/9B 信号在 Stage 6 标注时追加，形成 disagreement matrix。）
"""
import argparse
import hashlib
import json
import os
from collections import Counter

ROOT = os.path.abspath(os.path.join(os.path.dirname(__file__), "..", "..", ".."))
DS = os.path.join(ROOT, "training", "readerdirector", "dataset")

CUE_RE = __import__("re").compile(r"(说道|说|问|答|喊|叫|嚷|喝道|道|低声道|笑道|哭道|叹道|问道|答道|厉声道|沉声道|开口|回答|叫道|怒道|淡淡道|冷冷道|应道|回道|喊道)")


def signals(sample):
    """9 个规则信号，各 0/1。"""
    inp = sample["input"]
    text = inp.get("text") or ""
    cands = inp.get("rule_candidates") or []
    rc = inp.get("recent_context") or []
    sig = {}
    # 1. rule == UNKNOWN
    sig["rule_unknown"] = 1 if sample.get("provenance") != "EXPLICIT_RULE_GOLD" else 0
    # 2. target 前无 cue（近端文本）
    pre = rc[-1] if rc else ""
    sig["no_cue"] = 1 if not CUE_RE.search(pre) else 0
    # 3. postposed cue 信号：recent_context 短且 target 独立成段（近似；post_cue 字段未生成时为 0）
    sig["postposed"] = 1 if inp.get("post_cue") else 0
    # 4. 3+ candidates
    sig["k3plus"] = 1 if len(cands) >= 3 else 0
    # 5. candidate#1 ≠ explicit cue entity（cue 点名者不在第 1 位）
    cued = None
    for line in rc:
        for c in cands:
            if line.startswith(c.get("name") or ""):
                cued = c["role"]
                break
    sig["cue_neq_cand1"] = 1 if (cued is not None and cands and cands[0]["role"] != cued) else 0
    # 6. pronoun-heavy target
    sig["pronoun"] = 1 if text[:2] in ("“我", "“你", "“他", "“她", "“咱") else 0
    # 7. alias surface != canonical（opaque id 尾部与 name 不同）
    sig["alias"] = 1 if any((c.get("name") or "") not in c["role"] for c in cands) else 0
    # 8. long-distance cue（上下文 ≥4 段）
    sig["long_ctx"] = 1 if len(rc) >= 4 else 0
    # 9. 2-person 交替（恰好 2 候选）
    sig["k2_alt"] = 1 if len(cands) == 2 else 0
    return sig


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--n", type=int, default=3000, help="hard pool 规模")
    ap.add_argument("--min_score", type=int, default=1)
    args = ap.parse_args()

    lines = [json.loads(l) for l in open(os.path.join(DS, "train.jsonl"), encoding="utf-8")]
    sp = [s for s in lines if s["task"] == "SPEAKER" and s.get("provenance") != "EXPLICIT_RULE_GOLD"]
    print(f"UNLABELED speaker pool: {len(sp)}")

    scored = []
    dist = Counter()
    for s in sp:
        sig = signals(s)
        score = sum(sig.values())
        dist[score] += 1
        s["_hardness"] = score
        s["_signals"] = sig
        scored.append(s)
    print("hardness distribution (all pool):", dict(sorted(dist.items())))

    # 分层采样：优先高分数，保底覆盖各分数档（确定性 seed）
    import random
    rng = random.Random(100)
    by_score = {}
    for s in scored:
        by_score.setdefault(s["_hardness"], []).append(s)
    picked = []
    for score in sorted(by_score.keys(), reverse=True):
        bucket = by_score[score]
        rng.shuffle(bucket)
        # 高分段全拿，低分段抽样
        take = len(bucket) if score >= 3 else min(len(bucket), max(1, args.n // 9))
        picked += bucket[:take]
        if len(picked) >= args.n:
            break
    picked = picked[:args.n]
    picked.sort(key=lambda s: s["sample_id"])

    out_path = os.path.join(DS, "hard_pool_v1.jsonl")
    with open(out_path, "w", encoding="utf-8") as f:
        for s in picked:
            f.write(json.dumps(s, ensure_ascii=False) + "\n")
    h = hashlib.sha256(open(out_path, "rb").read()).hexdigest()
    picked_dist = Counter(s["_hardness"] for s in picked)
    print(f"hard_pool_v1: {len(picked)} samples, sha256={h}, score dist={dict(sorted(picked_dist.items()))}")
    json.dump({"name": "hard_pool_v1", "n": len(picked), "sha256": h,
               "score_dist": dict(sorted(picked_dist.items())),
               "rule": "规则信号 hardness_score 0-9，分层采样（非随机 37k）"},
              open(os.path.join(DS, "HARD_POOL_V1_MANIFEST.json"), "w", encoding="utf-8"), indent=1, ensure_ascii=False)


if __name__ == "__main__":
    main()
