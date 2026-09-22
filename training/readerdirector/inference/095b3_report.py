# -*- coding: utf-8 -*-
"""TASK-095B.3-EVAL — 指标汇总报告（读 eval08b_*.jsonl）。

指标：Exact Recall / Bounded-Contain Recall / Span IoU / Precision /
Exact-source Validity / 15 域 macro / NONE accuracy。
"""
import json
import os
import sys
from collections import Counter

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
from importlib import import_module
_m = import_module("095b3_eval08b")
bounded_contain = _m.bounded_contain

ROOT = os.path.abspath(os.path.join(os.path.dirname(__file__), "..", "..", ".."))
TASK095 = os.path.join(ROOT, "training", "readerdirector", "runs", "task095")


def report(tag):
    path = os.path.join(TASK095, f"eval08b_{tag}.jsonl")
    if not os.path.exists(path):
        print(f"{tag}: no results")
        return
    rows = [json.loads(l) for l in open(path, encoding="utf-8")]
    known = [r for r in rows if r["domain"] != "NONE"]
    none = [r for r in rows if r["domain"] == "NONE"]
    total_gold = sum(len(r["gold"]) for r in known)
    exact_hit = 0
    contain_hit = 0
    correct_pred = 0
    total_pred = 0
    span_valid = 0
    span_total = 0
    by_domain = {}
    for r in known:
        gold = r["gold"]
        pred = [p for p in r["pred"] if p in r["text"]]
        exact_hit += sum(1 for g in gold if g in pred)
        contain_hit += sum(1 for g in gold if bounded_contain(g, pred))
        correct_pred += sum(1 for p in pred if any(g == p or g in p or p in g for g in gold))
        total_pred += len(pred)
        for p in r["pred"]:
            span_total += 1
            if p in r["text"]:
                span_valid += 1
        d = by_domain.setdefault(r["domain"], [0, 0])
        d[0] += len(gold)
        d[1] += sum(1 for g in gold if bounded_contain(g, pred))
    none_acc = sum(1 for r in none if not r["pred"]) / max(len(none), 1)
    macro = sum(h / g for g, h in by_domain.values()) / len(by_domain)
    min_domain = min(h / g for g, h in by_domain.values())
    print(f"=== {tag} ===")
    print(f"Exact Recall: {exact_hit}/{total_gold} = {exact_hit/total_gold*100:.1f}%")
    print(f"Bounded-Contain Recall: {contain_hit}/{total_gold} = {contain_hit/total_gold*100:.1f}%")
    print(f"Precision: {correct_pred}/{total_pred} = {correct_pred/max(total_pred,1)*100:.1f}%")
    print(f"Source validity: {span_valid}/{span_total} = {span_valid/max(span_total,1)*100:.1f}%")
    print(f"NONE accuracy: {none_acc*100:.1f}% ({len(none)} cases)")
    print(f"Domain macro (bounded): {macro*100:.1f}% | min domain: {min_domain*100:.1f}%")
    print("by domain (bounded):")
    for d, (g, h) in sorted(by_domain.items()):
        print(f"  {d:<22}: {h}/{g} = {h/g*100:.0f}%")


if __name__ == "__main__":
    tag = sys.argv[1] if len(sys.argv) > 1 else "latest"
    report(tag)
