# -*- coding: utf-8 -*-
"""VS7.5 — Dataset QC（D1-D6 全项）。"""
import sys, io, json, re, hashlib
sys.stdout = io.TextIOWrapper(sys.stdout.buffer, encoding='utf-8', errors='replace')
from collections import Counter

dev = [json.loads(l) for l in open('runs/task095/vs7_dev_fresh.jsonl', encoding='utf-8')]
test = [json.loads(l) for l in open('runs/task095/voice_state_test_v1.jsonl', encoding='utf-8')]

print('=== D1 Event balance ===')
for name, rows in [('dev', dev), ('test', test)]:
    d = dict(Counter(r['event_type'] for r in rows))
    print(f'  {name}: {d}')

print('\n=== D2 Binding family coverage ===')
for name, rows in [('dev', dev), ('test', test)]:
    d = dict(Counter(r['source'] for r in rows))
    print(f'  {name}: {d}')

print('\n=== D3 UNKNOWN coverage ===')
for name, rows in [('dev', dev), ('test', test)]:
    u = sum(1 for r in rows if r['voice_state_owner'] == 'UNKNOWN')
    print(f'  {name}: UNKNOWN owner {u}/{len(rows)}')

print('\n=== D4 Surface validity（100% host valid）===')
bad = 0
for r in dev + test:
    if r['event_type'] != 'NONE':
        if not r.get('evidence_span') or r['evidence_span'] not in r['text']:
            bad += 1
            print(f'  BAD evidence: {r["text"][:50]} | {r.get("evidence_span")}')
    if r['event_type'] != 'NONE' and r['voice_state_owner'] not in ('UNKNOWN',) and r['voice_state_owner'] not in r['text']:
        bad += 1
        print(f'  BAD owner surface: {r["text"][:50]} | {r["voice_state_owner"]}')
    if r['reference_voice'] not in ('NONE', 'UNKNOWN', None) and r['reference_voice'] not in r['text']:
        bad += 1
        print(f'  BAD ref surface: {r["text"][:50]} | {r["reference_voice"]}')
print(f'  surface violations: {bad} (要求 0)')

print('\n=== D5 Book split ===')
print('  synthetic corpus（无真实书）→ 无 book 泄漏风险；Tier B 真实书数据加入时按书 split')

print('\n=== D6 No case leakage ===')
all_texts = [r['text'] for r in dev + test]
dup = len(all_texts) - len(set(all_texts))
print(f'  exact duplicates: {dup} (要求 0)')
# VS6 泄漏（dev 已验 0，test 再验）
vs6_norm = set()
for l in open('runs/task095/vs6_binding_fixtures.jsonl', encoding='utf-8'):
    t = json.loads(l)['text']
    for name in ['张三', '李四', '王五', '赵六', '孙七', '周八', '石志康', '林雪']:
        t = t.replace(name, 'X')
    vs6_norm.add(t)
def norm2(text):
    for name in ['张三', '李四', '王五', '赵六', '孙七', '周八', '石志康', '林雪']:
        text = text.replace(name, 'X')
    return text
test_leak = sum(1 for r in test if norm2(r['text']) in vs6_norm)
print(f'  test normalized VS6 leak: {test_leak} (要求 0)')

# 双任务视图可导出性
print('\n=== 双任务视图 ===')
from voice_corpus import VoiceSample
ok = 0
for r in dev[:20]:
    s = VoiceSample.from_dict({**r, 'sample_id': r.get('sample_id', 'x'), 'book_id': 'syn', 'segment_id': 0, 'context': []})
    e = s.event_task()
    b = s.bind_task()
    if e['target']['V'] and b['target']['O'] is not None: ok += 1
print(f'  event/bind view export ok: {ok}/20')
print('\nQC DONE')