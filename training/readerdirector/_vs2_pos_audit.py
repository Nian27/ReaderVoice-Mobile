# -*- coding: utf-8 -*-
import sys, io
sys.stdout = io.TextIOWrapper(sys.stdout.buffer, encoding='utf-8', errors='replace')
import json
rows = [json.loads(l) for l in open("runs/task095/vs2_annotated_v2.jsonl", encoding="utf-8")]
pos = [r for r in rows if (r.get("validated") or {}).get("event") in ("TEMP_SET", "TEMP_CLEAR")]
print("v2 positive count:", len(pos))
for r in pos:
    v = r["validated"]
    print(f"#{r['idx']} [{r['kind']}] {v.get('event')} style={v.get('style')} scope={v.get('scope')}")
    print(f"  T: {r['text'][:150]}")
    print(f"  E: {v.get('evidence_span')}")
    print()
# 另抽查 v1 判 TEMP_SET 而 v2 判 NO_EVENT 的 34 条中几条（确认降级是否合理）
v1 = [json.loads(l) for l in open("runs/task095/vs2_annotated_v1.jsonl", encoding="utf-8")]
v2m = {r['idx']: r for r in rows}
downgraded = [r for r in v1 if (r.get("validated") or {}).get("event") == "TEMP_SET" and (v2m[r['idx']].get("validated") or {}).get("event") == "NO_EVENT"]
print(f"=== downgraded v1 SET -> v2 NO_EVENT: {len(downgraded)}，抽查 10 ===")

for r in downgraded[:10]:
    v = r["validated"]
    print(f"#{r['idx']} [{r['kind']}] v1style={v.get('style')} E={v.get('evidence_span')}")
    print(f"  T: {r['text'][:140]}")
    print()