# -*- coding: utf-8 -*-
"""TASK-095B.3-A5 — VOICE_STATE 独立 dev 扩充集（动作分层均衡）。

背景：dev.jsonl 的 VOICE_STATE 19 条中 gold 仅 7 条且动作偏斜（NONE 15，其余各 1）
→ 0.286 vs 0.714 无法区分真遗忘与噪声（2/7 vs 5/7 一步即 14pp）。
本脚本复刻 build_sft_v1.build_voice_state 的确定性状态机（seed=42、同转移表、同状态推进），
输出动作分层均衡的 VOICE_STATE dev：每动作 15 条 × 5 动作 = 75 条，全部 EXPLICIT_RULE_GOLD + future-leakage sentinel。
输出：dataset/dev_voice_state_v1.jsonl（eval_checkpoint.py 可消费的 input/target 格式）
"""
import hashlib
import json
import os
import random
import sys

sys.path.insert(0, os.path.join(os.path.dirname(os.path.abspath(__file__)), "..", "sft"))
sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
from prompt_builder import load_prompts, build_prompt

ROOT = os.path.abspath(os.path.join(os.path.dirname(__file__), "..", "..", ".."))
DS = os.path.join(ROOT, "training", "readerdirector", "dataset")
PROMPTS = load_prompts()
PER_ACTION = 15


def sid(*parts):
    return hashlib.sha256("|".join(parts).encode()).hexdigest()


def main():
    base = "phase=ADULT"
    rng = random.Random(42)
    transitions = {
        "NONE": ["START"] * 6 + ["NONE"] * 4,
        "START": ["CONTINUE"] * 7 + ["REPLACE"] * 7 + ["END"] * 6,
        "CONTINUE": ["CONTINUE"] * 8 + ["REPLACE"] * 6 + ["END"] * 6,
        "REPLACE": ["CONTINUE"] * 5 + ["END"] * 5,
        "END": ["NONE"] * 5 + ["START"] * 5,
    }
    counts = {}
    i = 0
    guard = 0
    out = []
    while len(out) < PER_ACTION * 5 and guard < 8000:
        guard += 1
        state = base
        cur_act = "NONE"
        steps = rng.randint(4, 7)
        for j in range(steps):
            cur_act = rng.choice(transitions[cur_act])
            action, current = cur_act, state
            if counts.get(action, 0) >= PER_ACTION:
                if action == "START":
                    state = "phase=ADULT;temp=START:{}"
                elif action == "CONTINUE":
                    state = "phase=ADULT;temp=CONTINUE:"
                elif action == "REPLACE":
                    state = 'phase=ADULT;temp=REPLACE:{"src":"x"}'
                elif action == "END":
                    state = base
                continue
            sample = {"input": {
                "text": f"“第{j + 1}句。”", "segment_type": "speech", "text_ref": f"syn-vs/{i}/{j}",
                "identity_id": f"uid-sm-{i}", "current_state": current,
            }}
            rec = {
                "input": sample["input"],
                "task": "VOICE_STATE",
                "provenance": "EXPLICIT_RULE_GOLD",
                "sample_id": sid("vs", str(i), str(j)),
                "book_hash": "syn-vs-a5",
                "legacy_label": False,
                "target": {"action": action,
                           "voice_state_ref": current,
                           "future_leakage_sentinel": j == 0 and action == "NONE"},
            }
            out.append(rec)
            counts[action] = counts.get(action, 0) + 1
            if action == "START":
                state = "phase=ADULT;temp=START:{}"
            elif action == "CONTINUE":
                state = "phase=ADULT;temp=CONTINUE:"
            elif action == "REPLACE":
                state = 'phase=ADULT;temp=REPLACE:{"src":"x"}'
            elif action == "END":
                state = base
            i += 1

    from collections import Counter
    print("actions:", dict(Counter(s["target"]["action"] for s in out)), "total:", len(out))
    op = os.path.join(DS, "dev_voice_state_v1.jsonl")
    with open(op, "w", encoding="utf-8") as f:
        for s in out:
            f.write(json.dumps(s, ensure_ascii=False) + chr(10))
    print("saved:", op, "sha256=", hashlib.sha256(open(op, "rb").read()).hexdigest())


if __name__ == "__main__":
    main()
