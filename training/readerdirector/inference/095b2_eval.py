# -*- coding: utf-8 -*-
"""TASK-095B.2 — Evidence-aware match + ranking + Recall@K 评估（Dev-A 200 条）。

候选 = CUE/SCENE（095A compiler 基线，高优先）
     ∪ RECENT_MENTION（lexicon evidence-aware 匹配：conf 排序）
截断永远在 merge + rank 之后（ADR-037）。
输出：Recall@K（2/4/6/8/12/All）、gold rank mean/p95、候选规模、pollution。
"""
import json
import os
import re

ROOT = os.path.abspath(os.path.join(os.path.dirname(__file__), "..", "..", ".."))
RUNS = os.path.join(ROOT, "training", "readerdirector", "runs", "task100")
OUT = os.path.join(ROOT, "training", "readerdirector", "runs", "task095")

CJK = re.compile(r"^[\u4e00-\u9fa5]{2,4}$")


def main():
    lex = json.load(open(os.path.join(OUT, "mention_lexicon_v5.json"), encoding="utf-8"))
    rows = [json.loads(l) for l in open(os.path.join(RUNS, "abc_results_v2.jsonl"), encoding="utf-8")]
    known = [r for r in rows if r.get("human_speaker") and r["human_speaker"] != "UNKNOWN"]
    unknown = [r for r in rows if not (r.get("human_speaker") and r["human_speaker"] != "UNKNOWN")]

    # 每 case：候选 = cands_a（CUE/SCENE，score 100+） + lexicon RECENT（conf 排序）
    cases = []
    for r in rows:
        book_lex = lex.get(r["book"], {})
        # RECENT_MENTION：recent_context 证据匹配
        recent = {}
        for rc in (r.get("recent_context") or []):
            for blk in re.split(r"[，。！？；：、\s“”\"《》]", rc):
                blk = blk.strip()
                if not blk:
                    continue
                hits = []
                if CJK.match(blk) and blk in book_lex:
                    hits.append(blk)
                elif len(blk) > 4:
                    for L in (4, 3, 2):
                        if CJK.match(blk[:L]) and blk[:L] in book_lex:
                            hits.append(blk[:L])
                for h in hits:
                    conf = book_lex[h]["conf"]
                    recent[h] = max(recent.get(h, 0), conf)
        # merge + rank（ADR-037：budget 在 rank 后）
        ranked = []
        seen = set()
        for c in r.get("cands_a") or []:  # CUE/SCENE 基线（095A compiler）
            if c and c not in seen:
                seen.add(c)
                ranked.append((c, 120.0))
        for name, conf in sorted(recent.items(), key=lambda x: -x[1]):  # RECENT by conf
            if name not in seen:
                seen.add(name)
                ranked.append((name, conf))
        cases.append({"sample": r, "ranked": ranked})

    # Recall@K
    def recall_at(k):
        hit = 0
        for c in cases:
            gold = c["sample"]["human_speaker"]
            if not gold or gold == "UNKNOWN":
                continue
            top = [n for n, _ in c["ranked"][:k]]
            if gold in top:
                hit += 1
        return hit / len(known)

    print("=== Recall@K (Dev-A, known=%d) ===" % len(known))
    for k in (2, 4, 6, 8, 12, 20, 10**9):
        label = "All" if k == 10**9 else str(k)
        print(f"  Recall@{label:<4}: {recall_at(k)*100:.1f}%")

    # gold rank
    ranks = []
    miss = 0
    for c in cases:
        gold = c["sample"]["human_speaker"]
        if not gold or gold == "UNKNOWN":
            continue
        names = [n for n, _ in c["ranked"]]
        if gold in names:
            ranks.append(names.index(gold) + 1)
        else:
            miss += 1
    import statistics
    if ranks:
        print(f"gold rank: mean={statistics.mean(ranks):.2f} p95={sorted(ranks)[int(len(ranks)*0.95)]} miss={miss}")

    # 候选规模
    sizes = [len(c["ranked"]) for c in cases]
    print(f"candidate size: mean={statistics.mean(sizes):.1f} median={statistics.median(sizes)} "
          f"p95={sorted(sizes)[int(len(sizes)*0.95)]} max={max(sizes)}")

    # pollution（候选含 gate 字符）
    GATE = set("的地得道说问答喊叫想看听忍住禁释追恭该当即续板街反没还就都也又再才刚不对从在了着过被把只")
    polluted = sum(1 for c in cases for n, _ in c["ranked"] if any(ch in GATE for ch in n))
    print(f"polluted candidate tokens: {polluted}")


if __name__ == "__main__":
    main()
