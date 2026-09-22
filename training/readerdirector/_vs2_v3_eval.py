# -*- coding: utf-8 -*-
"""VS2.2 评估：v3 四类 ontology 结果 + 与 v1/v2 对比。"""
import sys, io
sys.stdout = io.TextIOWrapper(sys.stdout.buffer, encoding='utf-8', errors='replace')
import json
from collections import Counter

rows = [json.loads(l) for l in open("runs/task095/vs2_annotated_v3.jsonl", encoding="utf-8")]
print("total:", len(rows))

# host validity
ok = [r for r in rows if r.get("error") is None]
errs = Counter(r.get("error") for r in rows if r.get("error"))
print(f"host valid: {len(ok)}/{len(rows)} = {len(ok)/len(rows)*100:.1f}% | errors: {dict(errs)}")

# kind 分布（含 operation 细分）
kd = Counter()
for r in rows:
    v = r.get("validated") or {}
    k = v.get("kind", "INVALID")
    if k == "VOICE_OVERRIDE":
        kd[f"VOICE_OVERRIDE_{v.get('operation')}"] += 1
    else:
        kd[k] += 1
print("kind dist:", dict(kd))

# 按候选 kind 的分布
print()
for kk in ["temp_set", "temp_clear", "hard_neg", "none"]:
    sub = [r for r in rows if r.get("kind") == kk]
    if not sub: continue
    c = Counter()
    for r in sub:
        v = r.get("validated") or {}
        k = v.get("kind", "INVALID")
        if k == "VOICE_OVERRIDE": c[f"OVERRIDE_{v.get('operation')}"] += 1
        else: c[k] += 1
    n = len(sub)
    print(f"  {kk:<10} n={n:<4} " + " ".join(f"{k}={v}({v/n*100:.0f}%)" for k, v in c.most_common()))

# hard_neg 与 none 桶的 NONE 率（新协议：NONE=与声音无关）
hn = [r for r in rows if r.get("kind") == "hard_neg"]
hn_none = sum(1 for r in hn if (r.get("validated") or {}).get("kind") == "NONE")
nb = [r for r in rows if r.get("kind") == "none"]
nb_none = sum(1 for r in nb if (r.get("validated") or {}).get("kind") == "NONE")
print()
print(f"hard_neg -> NONE: {hn_none}/{len(hn)} = {hn_none/len(hn)*100:.0f}% (新协议下 PERFORMANCE 也算合理)")
print(f"none -> NONE: {nb_none}/{len(nb)} = {nb_none/len(nb)*100:.0f}%")

# PERFORMANCE 数量与 style 分布
perf = [r for r in rows if (r.get("validated") or {}).get("kind") == "PERFORMANCE"]
print(f"\nPERFORMANCE: {len(perf)}")
sc = Counter((r.get("validated") or {}).get("style") for r in perf)
for k, v in sc.most_common(12):
    print(f"  {k}: {v}")

# OVERRIDE 全量列表（供人工审核）
ov = [r for r in rows if (r.get("validated") or {}).get("kind") == "VOICE_OVERRIDE"]
print(f"\n=== VOICE_OVERRIDE 全量 ({len(ov)}) ===")
for r in ov:
    v = r["validated"]
    print(f"#{r['idx']} [{r.get('kind')}] {v.get('operation')} style={v.get('style')} scope={v.get('scope')}")
    print(f"  T: {r['text'][:120]}")
    print(f"  E: {v.get('evidence_span')}")
    print()