# -*- coding: utf-8 -*-
"""VS2.1 A/B 对比：v1 vs v2（同一 410 条候选集）。
输出：TEMP_SET count / audited precision（需人工） / TEMP_CLEAR P·R proxy / NO_EVENT / hard-neg rejection / evidence validity / 四类误报数。
"""
import sys, io
sys.stdout = io.TextIOWrapper(sys.stdout.buffer, encoding='utf-8', errors='replace')
import json
from collections import Counter

def load(ver):
    try:
        return [json.loads(l) for l in open(f"runs/task095/vs2_annotated_{ver}.jsonl", encoding="utf-8")]
    except FileNotFoundError:
        return None

v1, v2 = load("v1"), load("v2")
if v1 is None or v2 is None:
    print("missing files:", "v1" if v1 is None else "", "v2" if v2 is None else "")
    sys.exit(1)

def metrics(rows, name):
    n = len(rows)
    ok = [r for r in rows if r.get("error") is None]
    ev = Counter((r.get("validated") or {}).get("event", "INVALID") for r in rows)
    # hard-neg 拒绝率
    hn = [r for r in rows if r.get("kind") == "hard_neg"]
    hn_noev = sum(1 for r in hn if (r.get("validated") or {}).get("event") == "NO_EVENT")
    # none 桶 NO_EVENT 率
    noneb = [r for r in rows if r.get("kind") == "none"]
    none_noev = sum(1 for r in noneb if (r.get("validated") or {}).get("event") == "NO_EVENT")
    # TEMP_SET 按 kind 分布（误报来源）
    set_by_kind = Counter(r.get("kind") for r in rows if (r.get("validated") or {}).get("event") == "TEMP_SET")
    clear_by_kind = Counter(r.get("kind") for r in rows if (r.get("validated") or {}).get("event") == "TEMP_CLEAR")
    # 候选 kind 召回 proxy：temp_set cue 命中且判 TEMP_SET
    ts_cue = [r for r in rows if r.get("kind") == "temp_set"]
    ts_hit = sum(1 for r in ts_cue if (r.get("validated") or {}).get("event") == "TEMP_SET")
    tc_cue = [r for r in rows if r.get("kind") == "temp_clear"]
    tc_hit = sum(1 for r in tc_cue if (r.get("validated") or {}).get("event") == "TEMP_CLEAR")
    print(f"=== {name} (n={n}) ===")
    print(f"  event dist: {dict(ev)}")
    print(f"  host valid: {len(ok)}/{n} = {len(ok)/n*100:.1f}%")
    print(f"  hard_neg -> NO_EVENT: {hn_noev}/{len(hn)} = {hn_noev/len(hn)*100:.0f}%" if hn else "  hard_neg: none")
    print(f"  none -> NO_EVENT: {none_noev}/{len(noneb)} = {none_noev/len(noneb)*100:.0f}%" if noneb else "  none: none")
    print(f"  TEMP_SET by kind: {dict(set_by_kind)}")
    print(f"  TEMP_CLEAR by kind: {dict(clear_by_kind)}")
    print(f"  recall proxy (cue hit): temp_set={ts_hit}/{len(ts_cue)} ({ts_hit/len(ts_cue)*100:.0f}%) temp_clear={tc_hit}/{len(tc_cue)} ({tc_hit/len(tc_cue)*100:.0f}%)")
    print()
    return ev

e1 = metrics(v1, "v1")
e2 = metrics(v2, "v2")

# 逐条 diff
print("=== per-item diff (event changed) ===")
d = [r for r in v2 if (r.get("validated") or {}).get("event") != [x for x in v1 if x["idx"] == r["idx"]][0].get("validated", {}).get("event")]
print(f"changed: {len(d)}/410")
from collections import Counter as C2
trans = C2()
for r in d:
    old = [x for x in v1 if x["idx"] == r["idx"]][0].get("validated", {}).get("event")
    new = (r.get("validated") or {}).get("event")
    trans[(old, new)] += 1
for k, v in trans.most_common():
    print(f"  {k[0]} -> {k[1]}: {v}")