# -*- coding: utf-8 -*-
"""TASK-090 Step 2 — ReaderDirector SFT Dataset v1（G5/G7/G9-G13 数据侧）。
组成：
  A. SPEAKER gold × candidate permutation（4 阶，首候选固定=最近发言，其余轮转）→ 打掉 distance/榜首锚定
  B. TRUE_UNKNOWN_GOLD：构造的确实不可判定样本（无 cue/无 turn/无 lock）→ target=UNKNOWN（带 true_unknown 标记）
  C. IDENTITY 合成：SAME(alias) / DIFFERENT(hard: group≠person, control≠identity, same-name) / UNKNOWN(关系陷阱)
  D. VOICE_STATE 状态机 gold：NONE/START/CONTINUE/REPLACE/END 全转移 + 未来泄漏 sentinel
输出：sft_v1.jsonl（messages 格式，ms-swift 直接消费）+ TASK090_SFT_MANIFEST.json
"""
import hashlib
import itertools
import json
import os
import sys

sys.path.insert(0, os.path.join(os.path.dirname(os.path.abspath(__file__)), "..", "inference"))
from prompt_builder import load_prompts, build_prompt

ROOT = os.path.abspath(os.path.join(os.path.dirname(__file__), "..", "..", ".."))
DS = os.path.join(ROOT, "training", "readerdirector", "dataset")
PROMPTS = load_prompts()
RNG = __import__("random").Random(42)  # 全流程确定性

SPEAKER_ANS = {"CONFIRMED": "CONFIRMED", "PROVISIONAL": "PROVISIONAL"}
NEUTRAL_CTX = ["夜已深，灯下两个人影。", "山道上，一行人沉默前行。", "屋内炉火正旺。", "巷口的风吹过。", "桌边安静下来。"]
NEUTRAL_LINES = ["“走吧。”", "“嗯。”", "“知道了。”", "“行。”", "“好吧。”", "“别说了。”", "“过来。”", "“小心！”", "“没事。”", "“再说吧。”"]


def sid(*parts):
    h = hashlib.sha256("|".join(parts).encode()).hexdigest()
    return h[:16]


def permute_candidates(cands, k):
    """保持 cands[0]（最近发言）固定，其余轮转 k 次。"""
    if len(cands) < 2:
        return cands
    tail = cands[1:]
    r = k % len(tail)
    return [cands[0]] + tail[r:] + tail[:r]


def speaker_answer(sample):
    tg = sample["target"]
    return json.dumps({"speaker": tg.get("speaker"), "status": tg.get("status", "UNKNOWN")}, ensure_ascii=False)


def build_speaker_gold():
    out = []
    lines = [json.loads(l) for l in open(os.path.join(DS, "train.jsonl"), encoding="utf-8")]
    gold = [s for s in lines if s["task"] == "SPEAKER" and s["provenance"] == "EXPLICIT_RULE_GOLD"]
    for s in gold:
        cands = s["input"].get("rule_candidates") or []
        true_id = s["target"].get("speaker")
        true_idx = next((i for i, c in enumerate(cands) if c["role"] == true_id), 0)
        for k in range(4):
            variant = dict(s)
            variant["input"] = dict(s["input"])
            variant["input"]["rule_candidates"] = permute_candidates(cands, k)
            prompt, _ = build_prompt("SPEAKER", variant, PROMPTS)
            idx = next((i for i, c in enumerate(variant["input"]["rule_candidates"]) if c["role"] == true_id), -1)
            out.append({
                "messages": [{"role": "user", "content": prompt},
                             {"role": "assistant", "content": speaker_answer(s)}],
                "task": "SPEAKER", "provenance": "EXPLICIT_RULE_GOLD",
                "sample_id": sid(s["sample_id"], "perm", str(k)),
                "markers": {"permutation": k, "true_unknown": False,
                            "hard_set": idx >= 2, "true_speaker_index": idx},
            })
    return out


def build_true_unknown(n=300):
    out = []
    names = ["张三", "李四", "王五", "赵六", "钱七", "孙八", "周九", "吴十"]
    for i in range(n):
        k = RNG.randint(2, 4)
        cands = [{"role": f"uid-{nm}", "name": nm, "distance": d + 1} for d, nm in
                 enumerate(RNG.sample(names, k))]
        sample = {"input": {
            "text": NEUTRAL_LINES[i % len(NEUTRAL_LINES)],
            "segment_type": "speech",
            "recent_context": [NEUTRAL_CTX[i % len(NEUTRAL_CTX)]],
            "rule_candidates": cands,
            "identity_constraints": [],
            "embodiment": [], "overrides": [],
        }}
        prompt, _ = build_prompt("SPEAKER", sample, PROMPTS)
        out.append({
            "messages": [{"role": "user", "content": prompt},
                         {"role": "assistant", "content": '{"speaker": "UNKNOWN", "status": "UNKNOWN"}'}],
            "task": "SPEAKER", "provenance": "EXPLICIT_RULE_GOLD",
            "sample_id": sid("true_unknown", str(i)),
            "markers": {"true_unknown": True, "permutation": 0, "hard_set": False, "true_speaker_index": -1},
        })
    return out


def identity_sample(a, b, rel, aliases_a, aliases_b, hard, pos, neg, rel_ev, co, extra_note=""):
    sample = {"input": {
        "entity_a": a, "entity_b": b,
        "aliases_a": aliases_a, "aliases_b": aliases_b,
        "positive_evidence": pos, "negative_evidence": neg,
        "relationship_evidence": rel_ev, "co_presence": co,
        "hard_block": hard, "user_constraints": [],
        "text": "-", "segment_type": "identity", "text_ref": "syn",
    }}
    prompt, _ = build_prompt("IDENTITY", sample, PROMPTS)
    return {
        "messages": [{"role": "user", "content": prompt},
                     {"role": "assistant", "content": json.dumps({"relation": rel}, ensure_ascii=False)}],
        "task": "IDENTITY", "provenance": "EXPLICIT_RULE_GOLD",
        "sample_id": sid("id", a, b, rel, extra_note),
        "markers": {"identity": rel, "hard_set": hard, "note": extra_note},
    }


def build_identity():
    out = []
    # SAME：alias（50）
    alias_pairs = [("张明", "老张", ["老张", "张明"]), ("李雪", "雪儿", ["雪儿", "李雪"]),
                   ("赵铁柱", "铁柱", ["铁柱", "赵铁柱"]), ("王老", "王老爷子", ["王老爷子"]),
                   ("林雪", "小雪", ["小雪"]), ("钱二", "二爷", ["二爷"])]
    for a, b, al in alias_pairs:
        for _ in range(8):
            out.append(identity_sample(a, b, "SAME", al, al, False, 2, 0, {}, 1))
    # DIFFERENT hard：group≠person / control≠identity / possession≠identity / 师徒 / same-name 不同人（20）
    hard_pairs = [("执法队", "张明"), ("宿主", "控制者"), ("魔尊", "林雪"), ("师父", "徒弟"),
                  ("李四", "二爷"), ("王五", "雪儿"), ("周老板", "孙三娘"), ("林雪", "林雪")]
    for a, b in hard_pairs:
        for _ in range(3):
            note = "same-name-different-person" if a == b else ("control-neq" if a == "宿主" else "")
            out.append(identity_sample(a, b, "DIFFERENT", [], [], True, 0, 2, {}, 0, note))
    # UNKNOWN 陷阱：关系≠identity（KINSHIP/CONTROL/POSSESSION/共现 only）（20）
    traps = [("王老", "王明", {"KINSHIP": 1}), ("张明", "林雪", {"POSSESSION": 1}),
             ("师父", "张明", {"TITLE": 1}), ("钱二", "赵铁柱", {"CO_PRESENCE": 2}),
             ("宿主", "控制者", {"CONTROL": 1})]
    for a, b, ev in traps:
        for _ in range(4):
            out.append(identity_sample(a, b, "UNKNOWN", [], [], False, 0, 0, ev, ev.get("CO_PRESENCE", 0)))
    return out


def build_voice_state(target_per_action=40):
    """状态机 gold：确定性随机转移 + 每动作配额均衡（修复 CONTINUE 过预测）。
    覆盖 NONE/START/CONTINUE/REPLACE/END；sentinel 样本 current_state 不含未来事件。"""
    out = []
    base = "phase=ADULT"
    rng = __import__("random").Random(42)
    # 转移概率偏向稀有机会（REPLACE 参与率提升）
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
    while len(out) < target_per_action * 5 and guard < 8000:
        guard += 1
        state = base
        cur_act = "NONE"
        steps = rng.randint(4, 7)
        for j in range(steps):
            cur_act = rng.choice(transitions[cur_act])
            action, current = cur_act, state
            # 配额已满：推进状态后跳过（保持序列连续性）
            if counts.get(action, 0) >= target_per_action:
                if action == "START":
                    state = "phase=ADULT;temp=START:{}"
                elif action == "CONTINUE":
                    state = "phase=ADULT;temp=CONTINUE:"
                elif action == "REPLACE":
                    state = "phase=ADULT;temp=REPLACE:{\"src\":\"x\"}"
                elif action == "END":
                    state = base
                continue
            sample = {"input": {
                "text": f"“第{j + 1}句。”", "segment_type": "speech", "text_ref": f"syn-vs/{i}/{j}",
                "identity_id": f"uid-sm-{i}", "current_state": current,
            }}
            prompt, _ = build_prompt("VOICE_STATE", sample, PROMPTS)
            out.append({
                "messages": [{"role": "user", "content": prompt},
                             {"role": "assistant", "content": json.dumps({"action": action}, ensure_ascii=False)}],
                "task": "VOICE_STATE", "provenance": "EXPLICIT_RULE_GOLD",
                "sample_id": sid("vs", str(i), str(j)),
                "markers": {"state_machine": True, "action": action,
                            "future_leakage_sentinel": j == 0 and action == "NONE"},
            })
            counts[action] = counts.get(action, 0) + 1
            if action == "START":
                state = "phase=ADULT;temp=START:{}"
            elif action == "CONTINUE":
                state = "phase=ADULT;temp=CONTINUE:"
            elif action == "REPLACE":
                state = "phase=ADULT;temp=REPLACE:{\"src\":\"x\"}"
            elif action == "END":
                state = base
            i += 1
    return out


def build_id_echo(n=400):
    """id≠name 回声样本：真实书 gold 候选重映射为 opaque id（role=c-xxx, name=原名），answer=role。
    真实书样本 id==name（无实体回退），模型从未见过 id≠name → 学成输出名字；
    这些构造样本教会"输出候选 id"协议（TASK-080 候选服从的直接修复）。"""
    out = []
    lines = [json.loads(l) for l in open(os.path.join(DS, "train.jsonl"), encoding="utf-8")]
    gold = [s for s in lines if s["task"] == "SPEAKER" and s["provenance"] == "EXPLICIT_RULE_GOLD"]
    for i, s in enumerate(gold[:n]):
        cands = s["input"].get("rule_candidates") or []
        if not cands:
            continue
        true_id = s["target"].get("speaker")
        mapping = {c["name"]: f"c-{i:04d}-{j}" for j, c in enumerate(cands)}
        new_cands = [{"role": mapping[c["name"]], "name": c["name"], "distance": c["distance"]} for c in cands]
        variant = dict(s)
        variant["input"] = dict(s["input"])
        variant["input"]["rule_candidates"] = new_cands
        prompt, _ = build_prompt("SPEAKER", variant, PROMPTS)
        ans = json.dumps({"speaker": mapping[true_id], "status": s["target"].get("status", "CONFIRMED")},
                         ensure_ascii=False)
        out.append({
            "messages": [{"role": "user", "content": prompt},
                         {"role": "assistant", "content": ans}],
            "task": "SPEAKER", "provenance": "EXPLICIT_RULE_GOLD",
            "sample_id": sid(s["sample_id"], "idecho", str(i)),
            "markers": {"id_echo": True, "permutation": 0, "true_unknown": False, "hard_set": False,
                        "true_speaker_index": 0},
        })
    return out


def main():
    parts = [build_speaker_gold(), build_true_unknown(), build_identity(), build_voice_state(), build_id_echo()]
    samples = [s for p in parts for s in p]
    # 确定性顺序：按 sample_id 排序
    samples.sort(key=lambda s: s["sample_id"])
    out_path = os.path.join(DS, "sft_v1.jsonl")
    with open(out_path, "w", encoding="utf-8") as f:
        for s in samples:
            f.write(json.dumps(s, ensure_ascii=False) + "\n")
    digest = hashlib.sha256(open(out_path, "rb").read()).hexdigest()
    from collections import Counter
    manifest = {
        "dataset": "sft_v1", "frozen_at": "2026-08-12", "sha256": digest,
        "counts": {"total": len(samples)},
        "by_task": dict(Counter(s["task"] for s in samples)),
        "by_marker": {
            "true_unknown": sum(1 for s in samples if s["markers"].get("true_unknown")),
            "hard_set": sum(1 for s in samples if s["markers"].get("hard_set")),
            "state_machine": sum(1 for s in samples if s["markers"].get("state_machine")),
            "id_echo": sum(1 for s in samples if s["markers"].get("id_echo")),
            "identity_SAME": sum(1 for s in samples if s["markers"].get("identity") == "SAME"),
            "identity_DIFFERENT": sum(1 for s in samples if s["markers"].get("identity") == "DIFFERENT"),
            "identity_UNKNOWN": sum(1 for s in samples if s["markers"].get("identity") == "UNKNOWN"),
            "future_leakage_sentinel": sum(1 for s in samples if s["markers"].get("future_leakage_sentinel")),
        },
        "source": "train gold SPEAKER (permuted ×4) + TRUE_UNKNOWN synthetic + IDENTITY synthetic + VOICE_STATE state machine",
        "note": "G1: UNLABELED_POOL 未进入；G12: VoiceState 5 action 全覆盖；sentinel 样本 current_state 不含未来事件",
    }
    json.dump(manifest, open(os.path.join(DS, "TASK090_SFT_MANIFEST.json"), "w", encoding="utf-8"), indent=1, ensure_ascii=False)
    print(json.dumps(manifest, indent=1, ensure_ascii=False))


if __name__ == "__main__":
    main()
