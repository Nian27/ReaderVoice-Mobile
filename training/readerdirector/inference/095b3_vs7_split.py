# -*- coding: utf-8 -*-
import hashlib, io, json, random
from collections import defaultdict, Counter

rows = [json.loads(l) for l in open('runs/task095/vs7_corpus.jsonl', encoding='utf-8')]

# 泄漏剔除（exact + 归一化 vs VS6）
vs6 = set(json.loads(l)['text'] for l in open('runs/task095/vs6_binding_fixtures.jsonl', encoding='utf-8'))
def norm(text):
    for name in ['张三', '李四', '王五', '赵六', '孙七', '周八', '石志康', '林雪']:
        text = text.replace(name, 'X')
    return text
vs6_norm = set(norm(json.loads(l)['text']) for l in open('runs/task095/vs6_binding_fixtures.jsonl', encoding='utf-8'))
clean = [r for r in rows if r['text'] not in vs6 and norm(r['text']) not in vs6_norm]
print(f'corpus {len(rows)} -> clean {len(clean)}')

rng = random.Random(7)
by_src = defaultdict(list)
for r in clean:
    by_src[r['source']].append(r)
dev, test = [], []
for src, items in by_src.items():
    rng.shuffle(items)
    k = max(1, int(len(items) * 0.8))
    dev.extend(items[:k])
    test.extend(items[k:])

def dump(path, items):
    with open(path, 'w', encoding='utf-8') as f:
        for r in items:
            f.write(json.dumps(r, ensure_ascii=False) + chr(10))
    return hashlib.sha256(open(path, 'rb').read()).hexdigest()

h_dev = dump('runs/task095/vs7_dev_fresh.jsonl', dev)
h_test = dump('runs/task095/voice_state_test_v1.jsonl', test)
print(f'dev_fresh: {len(dev)} sha256={h_dev[:16]}')
print(f'test_v1: {len(test)} sha256={h_test[:16]}')

manifest = {
    'name': 'VoiceState-Test-v1', 'version': 'v1', 'created': '2026-08-19',
    'status': 'FROZEN', 'cases': len(test), 'sha256': h_test,
    'split': 'by-source-stratified-80-20-seed7',
    'leakage_check': 'exact+normalized vs VS6 removed',
    'label_policy': '六层 gold；expected_transition 由 reducer 算；VS8 调参期间禁止读取',
    'event_dist': dict(Counter(r['event_type'] for r in test)),
    'source_dist': dict(Counter(r['source'] for r in test)),
}
with open('runs/task095/voice_state_test_v1_manifest.json', 'w', encoding='utf-8') as f:
    json.dump(manifest, f, ensure_ascii=False, indent=1)
print('\ndev event:', dict(Counter(r['event_type'] for r in dev)))
print('test event:', dict(Counter(r['event_type'] for r in test)))
print('dev sources:', dict(Counter(r['source'] for r in dev)))
# 终检泄漏
dev_norm = [norm(r['text']) for r in dev]
test_norm = [norm(r['text']) for r in test]
print(f'dev vs6 leak: {sum(1 for t in dev_norm if t in vs6_norm)} | test vs6 leak: {sum(1 for t in test_norm if t in vs6_norm)}')