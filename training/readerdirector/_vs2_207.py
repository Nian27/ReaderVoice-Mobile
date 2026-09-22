# -*- coding: utf-8 -*-
import sys, io
sys.stdout = io.TextIOWrapper(sys.stdout.buffer, encoding='utf-8', errors='replace')
import json
for ver in ['v31', 'v32']:
    rows = {r['idx']: r for r in [json.loads(l) for l in open(f'runs/task095/vs2_annotated_{ver}.jsonl', encoding='utf-8')]}
    r = rows[207]
    print(f"=== {ver} #207 ===")
    print(f"  T: {r['text']}")
    print(f"  teacher_event: {json.dumps(r.get('teacher_event'), ensure_ascii=False)}")
    print(f"  validated: {json.dumps(r.get('validated'), ensure_ascii=False)} err={r.get('error')}")
    print()