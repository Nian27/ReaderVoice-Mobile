# -*- coding: utf-8 -*-
"""VS2.2-v33 评估：v32 回归点 #207 修复验证 + 全量诊断。"""
import sys, io
sys.stdout = io.TextIOWrapper(sys.stdout.buffer, encoding='utf-8', errors='replace')
import json
from collections import Counter

def load(ver):
    return {r['idx']: r for r in [json.loads(l) for l in open(f'runs/task095/vs2_annotated_{ver}.jsonl', encoding='utf-8')]}

v32 = load('v32')
v33 = load('v33')
print('v33 total:', len(v33))

errs = [(i, r.get('error')) for i, r in v33.items() if r.get('error')]
print(f'host valid: {len(v33)-len(errs)}/{len(v33)} = {(len(v33)-len(errs))/len(v33)*100:.1f}% | errors: {errs}')

kd = Counter()
for i, r in v33.items():
    v = r.get('validated') or {}
    k = v.get('kind', 'INVALID')
    if k == 'VOICE_OVERRIDE': kd[f'VOICE_OVERRIDE_{v.get("operation")}'] += 1
    else: kd[k] += 1
print('kind dist:', dict(kd))

def evof(r):
    v = r.get('validated') or {}
    k = v.get('kind', '?')
    return f'{k}_{v.get("operation")}' if k == 'VOICE_OVERRIDE' else k

# 专项：#207 回归 + 4FP/7TP
print(f'\n#207 (模仿+修饰词): v32={evof(v32[207])} -> v33={evof(v33[207])}')
FP = [53, 63, 192, 303]
TP = [79, 207, 211, 221, 224, 272, 393]
fp_fixed = sum(1 for i in FP if evof(v33[i]) in ('NONE', 'PERFORMANCE'))
tp_kept = sum(1 for i in TP if evof(v33[i]).startswith('VOICE_OVERRIDE'))
print(f'FP corrected: {fp_fixed}/4 | TP retained: {tp_kept}/7')
for i in TP:
    print(f'  TP#{i}: {evof(v33[i])}')

hn = [i for i, r in v33.items() if r.get('kind') == 'hard_neg']
hn_ov = [i for i in hn if evof(v33[i]).startswith('VOICE_OVERRIDE')]
print(f'hard_neg -> OVERRIDE: {len(hn_ov)}/80 {hn_ov}')
perf32 = [i for i, r in v32.items() if (r.get('validated') or {}).get('kind') == 'PERFORMANCE']
perf_kept = sum(1 for i in perf32 if evof(v33[i]) == 'PERFORMANCE')
print(f'v32 PERFORMANCE {len(perf32)} 条 v33 保留: {perf_kept}/{len(perf32)} | 去向: {dict(Counter(evof(v33[i]) for i in perf32))}')

ov = [(i, r) for i, r in sorted(v33.items()) if evof(r).startswith('VOICE_OVERRIDE')]
print(f'\n=== v33 OVERRIDE 全量 ({len(ov)}) ===')
for i, r in ov:
    v = r.get('validated') or {}
    print(f'#{i} [{r.get("kind")}] {v.get("operation")} style={v.get("style")}')
    print(f'  T: {r["text"][:110]}')
    print(f'  E: {v.get("evidence_span")}')

print('\n=== SUMMARY ===')
print(f'FP {fp_fixed}/4 | TP {tp_kept}/7 | hard_neg {len(hn_ov)} | perf {perf_kept}/{len(perf32)} | validity {(len(v33)-len(errs))}/{len(v33)}')