# -*- coding: utf-8 -*-
"""VS8-SMOKE 训练集：Voice 双任务 + 旧任务 replay。

比例（用户 §8 正式版，smoke 简化版同构）：
  Voice（EVENT+BIND）530 条（100%）
  + 旧任务 replay 采样（MENTION/SPEAKER/IDENTITY/TRUE_UNKNOWN 等）
  → smoke 不需要大，验证可学性即可；replay 按 1:1 取 530 条
"""
import json
import os
import random

ROOT = os.path.abspath(os.path.join(os.path.dirname(__file__), "..", "..", ".."))
DS = os.path.join(ROOT, "training", "readerdirector", "dataset")

def main():
    voice = [json.loads(l) for l in open(os.path.join(DS, "vs8_smoke_voice.jsonl"), encoding="utf-8")]
    old = [json.loads(l) for l in open(os.path.join(DS, "sft_v2_mention_v2.jsonl"), encoding="utf-8")]
    # 旧任务按 task 分层采样（保留各类代表），取 530 条
    from collections import defaultdict
    by_task = defaultdict(list)
    for r in old:
        by_task[r.get("task", "?")].append(r)
    rng = random.Random(42)
    replay = []
    # 目标分布：MENTION 40% / SPEAKER 30% / IDENTITY 10% / VOICE_STATE(旧) 10% / MENTION_NONE 10%
    targets = [("MENTION", 0.40), ("SPEAKER", 0.30), ("IDENTITY", 0.10), ("VOICE_STATE", 0.10), ("MENTION_NONE", 0.10)]
    for task, frac in targets:
        pool = by_task.get(task, [])
        n = int(530 * frac)
        rng.shuffle(pool)
        replay.extend(pool[:n])
    # 组合 + shuffle
    combined = voice + replay
    rng.shuffle(combined)
    op = os.path.join(DS, "vs8_smoke.jsonl")
    with open(op, "w", encoding="utf-8") as f:
        for r in combined:
            f.write(json.dumps(r, ensure_ascii=False) + chr(10))
    from collections import Counter
    print(f"saved {op}: {len(combined)} samples")
    print("task dist:", dict(Counter(r.get('task') for r in combined)))

if __name__ == "__main__":
    main()