# -*- coding: utf-8 -*-
"""TASK-095B.3 — MENTION 训练数据转 swift 格式 + 与旧任务混合（防灾难性遗忘）。

mention_train.jsonl（4B few-shot 蒸馏 + span 验证）
  + sft_v1.1.jsonl（SPEAKER/IDENTITY/VOICE_STATE 旧任务）
  → dataset/sft_v2_mention.jsonl（swift messages 格式，task 标记）
"""
import json
import os
import sys

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
from importlib import import_module
_m = import_module("095b2_eval_mention")
PROMPT_FS = _m.PROMPT

ROOT = os.path.abspath(os.path.join(os.path.dirname(__file__), "..", "..", ".."))
DS = os.path.join(ROOT, "training", "readerdirector", "dataset")
TASK095 = os.path.join(ROOT, "training", "readerdirector", "runs", "task095")


def main():
    # 1. 真实小说 Teacher Silver（Q1 协议：validated_mentions + offsets）
    # v2 重标数据（当前环境 4B + v1 prompt + 400 tokens，4250 条；历史 mention_train.jsonl 环境不一致，仅作证据保留）
    mention_rows = [json.loads(l) for l in open(os.path.join(TASK095, "mention_train_v2.jsonl"), encoding="utf-8")]
    # 2. Cross-domain Train（独立模板/名字，NONE 6%）
    cross = [json.loads(l) for l in open(os.path.join(TASK095, "crossdomain_train.jsonl"), encoding="utf-8")]

    def to_swift(text, mentions, sid, task):
        user = PROMPT_FS.replace("{text}", text)
        assistant = json.dumps({"mentions": [{"surface": s, "type": "PERSON"} for s in mentions]},
                               ensure_ascii=False)
        return {"sample_id": sid, "task": task, "provenance": "TEACHER_4B_SPAN_VALIDATED",
                "markers": [],
                "messages": [{"role": "user", "content": user},
                             {"role": "assistant", "content": assistant}]}

    mention_pool = []
    n_real_none = 0
    for r in mention_rows:
        mentions = r.get("validated_mentions") or []
        if not mentions:
            n_real_none += 1
        mention_pool.append(to_swift(
            r["source_text"], mentions, f"MENTION-{r['para_id'][:40]}",
            "MENTION_NONE" if not mentions else "MENTION"))
    for c in cross:
        mention_pool.append(to_swift(
            c["text"], c["mentions"], f"XD-{c['domain']}-{len(mention_pool)}",
            "MENTION_NONE" if not c["mentions"] else "MENTION"))

    # 旧任务 replay（防灾难性遗忘）
    old = [json.loads(l) for l in open(os.path.join(DS, "sft_v1.1.jsonl"), encoding="utf-8")]
    # IDENTITY/VOICE_STATE 小集上采样强化（防止 MENTION 挤占后遗忘；A2 预检：v1 783 VOICE_STATE 0.714→0.143 崩溃）
    # IDENTITY ×3、VOICE_STATE ×6（VOICE_STATE dev gold 仅 7 条，最易遗忘）
    import random
    rng = random.Random(42)
    old_up = []
    for s in old:
        old_up.append(s)
        if s.get("task") == "IDENTITY":
            old_up.extend([s] * 2)
        elif s.get("task") == "VOICE_STATE":
            old_up.extend([s] * 5)
    old = old_up

    # 比例控制：MENTION 占 40%（用户规格 40-45%；45% 致 VOICE_STATE 遗忘，A2 预检定案）
    target_mention = int(len(old) * 0.40 / 0.60)
    if len(mention_pool) > target_mention:
        mention_pool = rng.sample(mention_pool, target_mention)
    combined = mention_pool + old
    rng.shuffle(combined)
    op = os.path.join(DS, "sft_v2_mention_v2.jsonl")  # v2 重标数据（证据保留旧版）
    with open(op, "w", encoding="utf-8") as f:
        for s in combined:
            f.write(json.dumps(s, ensure_ascii=False) + "\n")
    from collections import Counter
    print(f"real-novel MENTION: {len(mention_rows)} (NONE: {n_real_none})")
    print(f"cross-domain: {len(cross)}")
    print(f"mention pool (sampled): {len(mention_pool)} | old task: {len(old)}")
    print(f"total: {len(combined)} | mention ratio: {len(mention_pool)/len(combined)*100:.0f}%")
    print(f"task dist: {dict(Counter(s['task'] for s in combined))}")
    import hashlib
    h = hashlib.sha256(open(op, "rb").read()).hexdigest()
    print(f"saved: {op} sha256={h}")


if __name__ == "__main__":
    main()