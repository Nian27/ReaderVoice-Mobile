# -*- coding: utf-8 -*-
"""VS2.2-v3.1 评估：Evidence Object Binding 验证 + v3 vs v31 对照诊断表。

关键对照：
  4 个 v3 known FP（#53/#63/#192/#303）：必须全部纠正
  7 个 v3 known TP（#79/#207/#211/#221/#224/#272/#393）：必须全部保留
  100 PERFORMANCE：仍无 OVERRIDE leakage
  80 hard_neg：仍 0 OVERRIDE
  span invalid 那 1 条（#?）：是否修复
"""
import sys, io
sys.stdout = io.TextIOWrapper(sys.stdout.buffer, encoding='utf-8', errors='replace')
import json
from collections import Counter

def load(ver):
    return [json.loads(l) for l in open(f"runs/task095/vs2_annotated_{ver}.jsonl", encoding="utf-8")]

v3 = {r['idx']: r for r in load('v3')}
v31 = {r['idx']: r for r in load('v31')}
print("v31 total:", len(v31))

# 1. host validity
errs = [(i, r.get('error')) for i, r in v31.items() if r.get('error')]
print(f"host valid: {len(v31)-len(errs)}/{len(v31)} = {(len(v31)-len(errs))/len(v31)*100:.1f}% | errors: {errs}")

# 2. kind 分布
kd = Counter()
for i, r in v31.items():
    v = r.get('validated') or {}
    k = v.get('kind', 'INVALID')
    if k == 'VOICE_OVERRIDE': kd[f"VOICE_OVERRIDE_{v.get('operation')}"] += 1
    else: kd[k] += 1
print("kind dist:", dict(kd))

# 3. 4 known FP / 7 known TP 对照
FP = [53, 63, 192, 303]
TP = [79, 207, 211, 221, 224, 272, 393]
def evof(r):
    v = r.get('validated') or {}
    k = v.get('kind', '?')
    return f"{k}_{v.get('operation')}" if k == 'VOICE_OVERRIDE' else k

print("\n=== 4 known FP（v3 误报，v31 必须纠正） ===")
fp_fixed = 0
for i in FP:
    old, new = evof(v3[i]), evof(v31[i])
    ok = new in ("NONE", "PERFORMANCE") and new != "VOICE_OVERRIDE_SET"
    if ok: fp_fixed += 1
    print(f"  #{i}: v3={old} -> v31={new} {'FIXED' if ok else 'STILL FP'}")

print("\n=== 7 known TP（v3 正确，v31 必须保留） ===")
tp_kept = 0
for i in TP:
    old, new = evof(v3[i]), evof(v31[i])
    same = old == new and new.startswith("VOICE_OVERRIDE")
    if same: tp_kept += 1
    print(f"  #{i}: v3={old} -> v31={new} {'KEPT' if same else 'LOST'}")

# 4. hard_neg / PERFORMANCE leakage
hn = [i for i, r in v31.items() if r.get('kind') == 'hard_neg']
hn_ov = [i for i in hn if evof(v31[i]).startswith("VOICE_OVERRIDE")]
print(f"\nhard_neg -> OVERRIDE: {len(hn_ov)}/{len(hn)} {hn_ov}")
# PERFORMANCE in v3 现在是否仍是 PERFORMANCE（不坍缩）
perf3 = [i for i, r in v3.items() if (r.get('validated') or {}).get('kind') == 'PERFORMANCE']
perf3_now = [i for i in perf3 if evof(v31[i]) == 'PERFORMANCE']
print(f"v3 PERFORMANCE 100 条中 v31 仍 PERFORMANCE: {len(perf3_now)}/100 | 坍缩去向: {dict(Counter(evof(v31[i]) for i in perf3))}")

# 5. OVERRIDE 全量（人工审核）
ov = [(i, r) for i, r in sorted(v31.items()) if evof(r).startswith("VOICE_OVERRIDE")]
print(f"\n=== v31 OVERRIDE 全量 ({len(ov)}) ===")
for i, r in ov:
    v = r.get('validated') or {}
    print(f"#{i} [{r.get('kind')}] {v.get('operation')} style={v.get('style')}")
    print(f"  T: {r['text'][:120]}")
    print(f"  E: {v.get('evidence_span')}")

# 6. 汇总
print("\n=== SUMMARY ===")
print(f"FP corrected: {fp_fixed}/4 | TP retained: {tp_kept}/7 | hard_neg override: {len(hn_ov)} | perf retained: {len(perf3_now)}/100")