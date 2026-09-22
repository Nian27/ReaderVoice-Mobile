# -*- coding: utf-8 -*-
"""TASK-095B.3-A1 — Dev-QC：v2 prompt 在 audit_300 子集上的验证（ADR-039）。"""
import json, sys, io, os
sys.stdout = io.TextIOWrapper(sys.stdout.buffer, encoding='utf-8', errors='replace')

BASE = os.path.dirname(os.path.abspath(__file__))
ROOT = os.path.abspath(os.path.join(BASE, '..', '..'))
OUT = os.path.join(ROOT, 'training', 'readerdirector', 'runs', 'task095')
MODEL_DIR = os.path.join(ROOT, 'training', 'models', 'qwen3.5-4b')
sys.path.insert(0, os.path.join(BASE, 'inference'))

import importlib.util
spec = importlib.util.spec_from_file_location('m', os.path.join(BASE, 'inference', '095b2_eval_mention.py'))
m = importlib.util.module_from_spec(spec)
spec.loader.exec_module(m)
import importlib.util as _ilu; _sp = importlib.util.spec_from_file_location("m2", os.path.join(BASE, "inference", "095b2_eval_mention.py")); _m2 = importlib.util.module_from_spec(_sp); _sp.loader.exec_module(_m2); PROMPT_FS_V3 = _m2.PROMPT

rows = [json.loads(l) for l in open(os.path.join(OUT, 'audit_300.jsonl'), encoding='utf-8')]
print('audit rows:', len(rows), '| OUT:', OUT, flush=True)

from transformers import AutoModelForCausalLM, AutoTokenizer
tok = AutoTokenizer.from_pretrained(MODEL_DIR, trust_remote_code=True)
model = AutoModelForCausalLM.from_pretrained(MODEL_DIR, trust_remote_code=True, torch_dtype='auto', device_map='auto')
print('4B loaded', flush=True)

out_path = os.path.join(OUT, 'audit_300_v1_dyn.jsonl')
done = set()
if os.path.exists(out_path):
    for l in open(out_path, encoding='utf-8'):
        try: done.add(json.loads(l)['idx'])
        except Exception: pass
    print('resume:', len(done), flush=True)

n = 0
for r in rows:
    if r['idx'] in done:
        continue
    try:
        p = PROMPT_FS_V3.replace('{text}', r['text'])
        raw = m.run(model, tok, p, max_new_tokens=(400 if len(r["text"]) > 150 else 200))
        pred = m.parse_mentions(raw)
        rec = {'idx': r['idx'], 'bucket': r['bucket'], 'text': r['text'],
               'teacher_v1': r['teacher_mentions'], 'teacher_v1dyn': pred}
        with open(out_path, 'a', encoding='utf-8') as f:
            f.write(json.dumps(rec, ensure_ascii=False) + chr(10))
        n += 1
        if n % 20 == 0:
            print('  ', n, 'done', flush=True)
    except Exception as e:
        print('ERR idx', r['idx'], str(e)[:100], flush=True)
print('DONE', flush=True)