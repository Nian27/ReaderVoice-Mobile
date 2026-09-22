# -*- coding: utf-8 -*-
import sys, io
sys.stdout = io.TextIOWrapper(sys.stdout.buffer, encoding='utf-8', errors='replace')
import json
rows = {r['idx']: r for r in [json.loads(l) for l in open('runs/task095/vs2_annotated_v31.jsonl', encoding='utf-8')]}
for i in [257, 83, 88, 290]:
    r = rows.get(i)
    if not r: continue
    print(f"#{i} [{r.get('kind')}] err={r.get('error')}")
    print(f"  T: {r['text']}")
    if r.get('teacher_event'): print(f"  teacher_event: {json.dumps(r['teacher_event'], ensure_ascii=False)}")
    if r.get('teacher_raw'): print(f"  raw: {r['teacher_raw'][:300]}")
    print()