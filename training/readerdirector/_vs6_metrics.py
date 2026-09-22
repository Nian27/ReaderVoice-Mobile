# -*- coding: utf-8 -*-
"""VS6.3 指标：Owner/Reference/Joint/UNKNOWN/observer-causer 错误 + host validity。"""
import sys, io
sys.stdout = io.TextIOWrapper(sys.stdout.buffer, encoding='utf-8', errors='replace')
import json
from collections import Counter

rows = [json.loads(l) for l in open('runs/task095/vs6_binding_results.jsonl', encoding='utf-8')]
print('total:', len(rows))

valid = [r for r in rows if r.get('valid')]
print(f'host valid: {len(valid)}/{len(rows)} = {len(valid)/len(rows)*100:.1f}%')
inv = [r for r in rows if not r.get('valid')]
print('invalid errors:', Counter(r.get('error') for r in inv).most_common(5))

def owner_hit(r):
    g = r['gold'].get('owner')
    p = (r.get('pred') or {}).get('owner')
    if g == 'UNKNOWN': return None  # 不计入 owner accuracy（单独算 UNKNOWN P/R）
    return p == g

def ref_hit(r):
    g = r['gold'].get('reference')
    p = (r.get('pred') or {}).get('reference')
    if g in (None, 'NONE'): return None
    if g == 'UNKNOWN': return None  # ref unknown 单独看
    return p == g

# Owner accuracy（gold owner 已知）
ok = [owner_hit(r) for r in valid if owner_hit(r) is not None]
print(f'Owner Accuracy: {sum(ok)}/{len(ok)} = {sum(ok)/len(ok)*100:.1f}%')

# Reference accuracy（gold ref 已知）
rk = [ref_hit(r) for r in valid if ref_hit(r) is not None]
print(f'Reference Accuracy: {sum(rk)}/{len(rk)} = {sum(rk)/len(rk)*100:.1f}%')

# Joint（owner+ref 都已知且都对）
joint = [(owner_hit(r), ref_hit(r)) for r in valid if owner_hit(r) is not None and ref_hit(r) is not None]
j = sum(1 for o, rr in joint if o and rr)
print(f'Joint Accuracy: {j}/{len(joint)} = {j/len(joint)*100:.1f}%')

# UNKNOWN precision/recall（gold owner UNKNOWN 的用例）
unk_gold = [r for r in valid if r['gold'].get('owner') == 'UNKNOWN']
unk_pred = [r for r in valid if (r.get('pred') or {}).get('owner') == 'UNKNOWN']
tp_u = sum(1 for r in unk_gold if (r.get('pred') or {}).get('owner') == 'UNKNOWN')
print(f'UNKNOWN Precision: {tp_u}/{len(unk_pred)} = {tp_u/len(unk_pred)*100:.1f}%' if unk_pred else 'UNKNOWN Precision: n/a')
print(f'UNKNOWN Recall: {tp_u}/{len(unk_gold)} = {tp_u/len(unk_gold)*100:.1f}%')

# Observer/causer 错误（G6-2）：gold observer_causer != owner，模型把 observer 当 owner
obs_ok = obs_tot = 0
for r in valid:
    obs = r['gold'].get('observer_causer')
    if not obs: continue
    obs_tot += 1
    p = (r.get('pred') or {}).get('owner')
    if p != obs: obs_ok += 1
print(f'Observer/Causer not-as-owner: {obs_ok}/{obs_tot} = {obs_ok/obs_tot*100:.0f}% (G6-2 要求 100%)')

# 按类别 owner accuracy
print('\nby category owner acc:')
for cat in sorted(set(r['category'] for r in valid)):
    sub = [r for r in valid if r['category'] == cat and owner_hit(r) is not None]
    if not sub: continue
    h = sum(1 for r in sub if owner_hit(r))
    print(f'  {cat:<18}: {h}/{len(sub)} = {h/len(sub)*100:.0f}%')

# 错误样本抽检
print('\n=== owner 错误样本（前 15）===')
errs = [r for r in valid if owner_hit(r) is False]
for r in errs[:15]:
    print(f"#{r['idx']} [{r['category']}] gold={r['gold']} pred={r.get('pred')}")
    print(f"  T: {r['text'][:90]}")

print(f'\ntotal owner errors: {len(errs)}')