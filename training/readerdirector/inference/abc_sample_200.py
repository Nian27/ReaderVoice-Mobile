# -*- coding: utf-8 -*-
"""A/B/C 对比实验 Step 2：200 条 Hard Case 采样 + 三路输入构建。

从 real_pool_v1_samples.jsonl（2000 条，17 本真实书）按分层采样 200 条：
  - 候选含污染切片（rule_candidates 含 gate 字符）
  - 规则 UNKNOWN（rule_unknown 信号）
  - 代词开头
  - 多候选（k>=4）
输出 runs/task100/abc_cases_200.jsonl：
  {sample_id, book, text, recent_context, task,
   cands_a: [role...]（regex 候选=原 rule_candidates）,
   cands_b: [role...]（实体候选= ∩ 该书实体集）,
   char_history_c: [name...]（该书实体集 top40）,
   human_speaker: null（待标注）,
   a_answer/b_answer/c_answer: null（待推理填充）}
用法: python inference/abc_sample_200.py
"""
import json
import os

ROOT = os.path.abspath(os.path.join(os.path.dirname(__file__), "..", "..", ".."))
DS = os.path.join(ROOT, "training", "readerdirector", "dataset")
SEG_DIR = os.path.join(ROOT, "books_private", "real_pool_v1")
RUNS = os.path.join(ROOT, "training", "readerdirector", "runs", "task100")

GATE_CHARS = "的地得道说问答喊叫想看听忍住禁释追恭该当即续板街反没还就都也又再才刚正不对从在了着过被把只"


def main():
    rows = [json.loads(l) for l in open(os.path.join(DS, "real_pool_v1_samples.jsonl"), encoding="utf-8")]
    entity_sets = json.load(open(os.path.join(SEG_DIR, "entity_sets.json"), encoding="utf-8"))

    def has_pollution(s):
        return any((c["role"] or "") and any(ch in GATE_CHARS for ch in c["role"])
                   for c in (s["input"].get("rule_candidates") or []))

    def rule_unknown(s):
        return s.get("provenance") != "EXPLICIT_RULE_GOLD"

    def pronoun(s):
        t = s["input"].get("text") or ""
        return t.startswith("“我") or t.startswith("“你") or t.startswith("“他") or t.startswith("“她")

    def k4plus(s):
        return len(s["input"].get("rule_candidates") or []) >= 4

    # 分层采样（确定性顺序）：每层先按书摊匀
    layers = {
        "pollution": [s for s in rows if has_pollution(s)],
        "rule_unknown": [s for s in rows if rule_unknown(s)],
        "pronoun": [s for s in rows if pronoun(s)],
        "k4plus": [s for s in rows if k4plus(s)],
    }
    for k, v in layers.items():
        print(f"layer {k}: {len(v)}")

    picked, seen = [], set()
    # 每层 50 条，按书摊匀（每本 ~3 条/层），总 200
    per_book = {}
    for layer, pool in layers.items():
        by_book = {}
        for s in pool:
            by_book.setdefault(s["book_hash"], []).append(s)
        count = 0
        books = sorted(by_book.keys())
        while count < 50 and any(by_book.values()):
            for bk in books:
                if not by_book[bk] or count >= 50:
                    continue
                s = by_book[bk].pop(0)
                if s["sample_id"] in seen:
                    continue
                seen.add(s["sample_id"])
                picked.append(s)
                per_book[s["sample_id"]] = layer
                count += 1

    # 构建三路输入
    cases = []
    for s in picked:
        book = s["book_hash"].replace("REAL-", "", 1)
        eset = entity_sets.get(book, {})
        cands_a = [(c["role"], c["name"]) for c in (s["input"].get("rule_candidates") or [])]
        cands_b = [(r, n) for r, n in cands_a if r in eset]
        history_c = sorted(eset.items(), key=lambda x: -x[1])[:40]
        cases.append({
            "sample_id": s["sample_id"],
            "book": book,
            "layer": per_book[s["sample_id"]],
            "text": s["input"].get("text", ""),
            "recent_context": s["input"].get("recent_context", []),
            "post_cue": s["input"].get("post_cue"),
            "cands_a": [r for r, _ in cands_a],
            "cands_b": [r for r, _ in cands_b],
            "char_history_c": [r for r, _ in history_c],
            "human_speaker": None,
            "a_answer": None, "b_answer": None, "c_answer": None,
            "a_valid": None, "b_valid": None, "c_valid": None,
        })

    op = os.path.join(RUNS, "abc_cases_200.jsonl")
    with open(op, "w", encoding="utf-8") as f:
        for c in cases:
            f.write(json.dumps(c, ensure_ascii=False) + "\n")
    print(f"cases: {len(cases)} -> {op}")
    from collections import Counter
    print("layer dist:", dict(Counter(c["layer"] for c in cases)))
    print("books:", len(set(c["book"] for c in cases)))
    kb = sum(1 for c in cases if len(c["cands_b"]) < len(c["cands_a"]))
    print(f"cases where B filters candidates: {kb}")


if __name__ == "__main__":
    main()
