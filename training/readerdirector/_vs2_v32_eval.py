# -*- coding: utf-8 -*-
"""VS2.2-v32 评估：对照 v31 残余 3 问题（#88 吊嗓子/#290 恢复正常/#257 null span）+ 全量诊断。"""
import sys, io
sys.stdout = io.TextIOWrapper(sys.stdout.buffer, encoding='utf-8', errors='replace')
import json
from collections import Counter

def load(ver):
    return {r['idx']: r for r in [json.loads(l) for l in open(f'runs/task095/vs2_annotated_{ver}.jsonl', encoding='utf-8')]}

v31 = load('v31')
v32 = load('v32')
print('v32 total:', len(v32))

errs = [(i, r.get('error')) for i, r in v32.items() if r.get('error')]
print(f'host valid: {len(v32)-len(errs)}/{len(v32)} = {(len(v32)-len(errs))/len(v32)*100:.1f}% | errors: {errs}')

kd = Counter()
for i, r in v32.items():
    v = r.get('validated') or {}
    k = v.get('kind', 'INVALID')
    if k == 'VOICE_OVERRIDE': kd[f'VOICE_OVERRIDE_{v.get("operation")}'] += 1
    else: kd[k] += 1
print('kind dist:', dict(kd))

def evof(r):
    v = r.get('validated') or {}
    k = v.get('kind', '?')
    return f'{k}_{v.get("operation")}' if k == 'VOICE_OVERRIDE' else k

# 三个残余问题专项
print('\n=== v31 残余 3 问题对照 ===')
for i, label in [(88, '吊嗓子(职业惯例)'), (290, '恢复正常(未绑定声音)'), (257, 'null span')]:
    print(f'  #{i} {label}: v31={evof(v31[i])} err={v31[i].get("error")} -> v32={evof(v32[i])} err={v32[i].get("error")}')

# 4 FP / 7 TP 再确认
FP = [53, 63, 192, 303]
TP = [79, 207, 211, 221, 224, 272, 393]
fp_fixed = sum(1 for i in FP if evof(v32[i]) in ('NONE', 'PERFORMANCE'))
tp_kept = sum(1 for i in TP if evof(v32[i]).startswith('VOICE_OVERRIDE'))
print(f'\nFP corrected: {fp_fixed}/4 | TP retained: {tp_kept}/7')

# hard_neg / performance
hn = [i for i, r in v32.items() if r.get('kind') == 'hard_neg']
hn_ov = [i for i in hn if evof(v32[i]).startswith('VOICE_OVERRIDE')]
print(f'hard_neg -> OVERRIDE: {len(hn_ov)}/80 {hn_ov}')
perf3 = [i for i, r in v31.items() if (r.get('validated') or {}).get('kind') == 'PERFORMANCE']
perf_kept = sum(1 for i in perf3 if evof(v32[i]) == 'PERFORMANCE')
print(f'v31 PERFORMANCE 100 条 v32 保留: {perf_kept}/100 | 去向: {dict(Counter(evof(v32[i]) for i in perf3))}')

# OVERRIDE 全量
ov = [(i, r) for i, r in sorted(v32.items()) if evof(r).startswith('VOICE_OVERRIDE')]
print(f'\n=== v32 OVERRIDE 全量 ({len(ov)}) ===')
for i, r in ov:
    v = r.get('validated') or {}
    print(f'#{i} [{r.get("kind")}] {v.get("operation")} style={v.get("style")}')
    print(f'  T: {r["text"][:120]}')
    print(f'  E: {v.get("evidence_span")}')

print('\n=== SUMMARY ===')
print(f'FP {fp_fixed}/4 | TP {tp_kept}/7 | hard_neg {len(hn_ov)} | perf {perf_kept}/100 | validity {(len(v32)-len(errs))}/{len(v32)}')