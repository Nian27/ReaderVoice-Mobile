# -*- coding: utf-8 -*-
"""TASK-095B.3-A3 — 200-case E2E MentionStore + Candidate Recall@K。

流程：0.8B MENTION → host exact-span 验证 → MentionStore（实体集匹配）
      → Candidate Compiler（CUE∪CURRENT∪RECENT∪SCENE∪NEW，union+dedup+rank）
      → Recall@K（All/2/4/6/8/12）+ gold rank + 候选规模 + provenance 分布。

输入：abc_cases_200.jsonl（200 条真实上下文，human_speaker 标注）
用法：python inference/095b3_e2e_store.py --checkpoint <ckpt> [--resume]
"""
import argparse, json, os, sys, re

BASE = os.path.dirname(os.path.abspath(__file__))
ROOT = os.path.abspath(os.path.join(BASE, '..', '..', '..'))
RUNS = os.path.join(ROOT, 'training', 'readerdirector', 'runs', 'task100')
TASK095 = os.path.join(ROOT, 'training', 'readerdirector', 'runs', 'task095')
BOOKS = os.path.join(ROOT, 'books_private', 'real_pool_v1')
sys.path.insert(0, BASE)

from importlib import import_module
_m = import_module('095b3_eval_lora')  # swift 加载链 + PROMPT

# 实体集（MentionStore 模拟）：book -> {name: conf}
def load_entity_sets():
    path = os.path.join(BOOKS, 'entity_sets_v4.json')
    if not os.path.exists(path):
        path = os.path.join(BOOKS, 'entity_sets.json')
    return json.load(open(path, encoding='utf-8'))

def recent_mentions(recent_context, entities):
    """RECENT_MENTION：recent_context 2-4 字 CJK 片段 ∩ 实体集（095A 方法）。"""
    CJK = re.compile(r'^[\u4e00-\u9fa5]{2,4}$')
    found = set()
    for rc in (recent_context or []):
        for blk in re.split(r'[，。！？；：、\s“”"《》]', rc):
            blk = blk.strip()
            if not blk:
                continue
            if CJK.match(blk) and blk in entities:
                found.add(blk)
            elif len(blk) > 4:
                for L in (4, 3, 2):
                    if CJK.match(blk[:L]) and blk[:L] in entities:
                        found.add(blk[:L])
                        break
    return found

def main():
    ap = argparse.ArgumentParser()
    ap.add_argument('--checkpoint', required=True)
    ap.add_argument('--resume', action='store_true')
    args = ap.parse_args()

    entity_sets = load_entity_sets()
    print('entity sets books:', len(entity_sets))

    model, tok = _m.load_model_with_adapter(args.checkpoint)
    print('0.8B MENTION loaded:', args.checkpoint, flush=True)

    cases = [json.loads(l) for l in open(os.path.join(RUNS, 'abc_cases_200.jsonl'), encoding='utf-8')]
    print('cases:', len(cases))

    tag = 'e2e_' + re.sub(r'[^0-9A-Za-z]', '', os.path.basename(args.checkpoint.rstrip('/\\')))
    out_path = os.path.join(TASK095, f'{tag}_results.jsonl')
    done = set()
    if args.resume and os.path.exists(out_path):
        for l in open(out_path, encoding='utf-8'):
            try: done.add(json.loads(l)['id'])
            except Exception: pass
        print('resume:', len(done))

    n = 0
    for c in cases:
        if args.resume and c['id'] in done:
            continue
        text = c.get('text', '')
        # 0.8B MENTION
        prompt = _m.PROMPT.replace('{text}', text)
        raw = _m.run(model, tok, prompt, max_new_tokens=200)
        mentions = [s for s in _m.parse_mentions(raw) if s and s in text]  # host 验证
        # MentionStore：本书实体集
        book_entities = entity_sets.get(c.get('book', ''), {}) or {}
        if isinstance(book_entities, dict):
            ent_names = set(book_entities.keys())
        else:
            ent_names = set(book_entities)
        # CANDIDATES：CUE ∪ CURRENT ∪ RECENT ∪ SCENE ∪ NEW
        cands = []
        seen = set()
        # CUE（原文 cue 候选：rule_candidates 若有）
        for rc in (c.get('cands_a') or []):
            if rc and rc not in seen:
                seen.add(rc); cands.append((rc, 'CUE'))
        # AI_MENTION（MENTION 输出 ∩ 实体集 = 已知实体候选）
        for mnt in mentions:
            if mnt in ent_names and mnt not in seen:
                seen.add(mnt); cands.append((mnt, 'AI_MENTION'))
        # RECENT_MENTION（上下文提及 ∩ 实体集）
        for r in sorted(recent_mentions(c.get('recent_context') or [], ent_names)):
            if r not in seen:
                seen.add(r); cands.append((r, 'RECENT_MENTION'))
        # SCENE_ACTIVE（上句说话者等）
        for s in (c.get('scene_active') or []):
            if s and s not in seen:
                seen.add(s); cands.append((s, 'SCENE_ACTIVE'))
        # NEW_MENTION（MENTION 输出但不在实体集 = 新实体/未收录）
        new_m = [mnt for mnt in mentions if mnt not in ent_names]
        rec = {
            'id': c['id'], 'book': c.get('book'), 'text': text,
            'human_speaker': c.get('human_speaker'),
            'mentions_ai': mentions, 'new_mentions': new_m[:5],
            'candidates': [x[0] for x in cands],
            'provenance': [x[1] for x in cands],
            'n_candidates': len(cands),
        }
        # gold rank
        gold = c.get('human_speaker') or ''
        rec['gold_rank'] = (cands.index((gold, next(p for p_, p in cands if p_ == gold))) + 1
                            if gold and any(p_ == gold for p_, _ in cands) else -1)
        with open(out_path, 'a', encoding='utf-8') as f:
            f.write(json.dumps(rec, ensure_ascii=False) + chr(10))
        n += 1
        if n % 20 == 0:
            print(f'  {n} done', flush=True)
    print('done', n, flush=True)

    # 汇总
    rows = [json.loads(l) for l in open(out_path, encoding='utf-8')]
    known = [r for r in rows if r['human_speaker'] and r['human_speaker'] != 'UNKNOWN']
    unknown = [r for r in rows if not (r['human_speaker'] and r['human_speaker'] != 'UNKNOWN')]
    print(f'known: {len(known)}, unknown: {len(unknown)}')
    for K in ('All', 2, 4, 6, 8, 12):
        hit = 0
        for r in known:
            if K == 'All':
                hit += 1 if r['gold_rank'] > 0 else 0
            else:
                hit += 1 if 0 < r['gold_rank'] <= K else 0
        print(f'Recall@{K}: {hit}/{len(known)} = {hit/max(len(known),1)*100:.1f}%')
    nc = [r['n_candidates'] for r in rows]
    nc_sorted = sorted(nc)
    import statistics
    p = lambda q: nc_sorted[min(len(nc_sorted)-1, int(q*len(nc_sorted)))]
    print(f'candidates: mean={statistics.mean(nc):.1f} median={statistics.median(nc)} p95={p(0.95)} max={max(nc)}')
    gr = [r['gold_rank'] for r in known if r['gold_rank'] > 0]
    if gr:
        print(f'gold rank: mean={statistics.mean(gr):.1f} p95={sorted(gr)[min(len(gr)-1, int(0.95*len(gr)))]}')
    from collections import Counter
    prov = Counter(p for r in rows for p in r['provenance'])
    print('provenance:', dict(prov))

if __name__ == '__main__':
    main()