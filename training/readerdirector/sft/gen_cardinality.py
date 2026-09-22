# -*- coding: utf-8 -*-
"""TASK-090.1 — Candidate-Cardinality 程序化生成（确定性 seed，不基于 TASK080 test 句子）。

产出：
  1. TASK090_TEST_V2（book D/E）：全新 synthetic locked test（2-6 候选全档，前置/后置 cue、
     最近≠speaker、TRUE_UNKNOWN、alias、embodiment、hard negative、voice state）。训练前生成并 hash 锁定。
  2. DEV-CARD A/B/C：dev cardinality 评估集（SpeakerAcc@K / perm@K / ΔK）。
  3. SFT cardinality 样本（cardinality × position 正交化，~1000 条）→ SFT v1.1。

用法: python gen_cardinality.py --test_v2 --dev_card --sft
"""
import argparse
import hashlib
import json
import os
import sys

sys.path.insert(0, os.path.join(os.path.dirname(os.path.abspath(__file__)), "..", "inference"))
from prompt_builder import load_prompts, build_prompt

ROOT = os.path.abspath(os.path.join(os.path.dirname(__file__), "..", "..", ".."))
DS = os.path.join(ROOT, "training", "readerdirector", "dataset")
PROMPTS = load_prompts("v2")

SCENES = ["夜已深，灯下两个人影。", "山道上，一行人沉默前行。", "屋内炉火正旺。", "巷口的风吹过。",
          "桌边安静下来。", "雨声渐密。", "门帘掀动。", "烛火摇曳。"]
NAMES = ["张三", "李四", "王五", "赵六", "钱七", "孙八", "周九", "吴十", "郑十一", "冯十二"]
LINES = ["走吧", "嗯", "知道了", "行", "好吧", "别说了", "过来", "小心", "没事", "再说吧",
         "这雨怕是要下一整夜", "明天再说", "你看着办", "我不同意", "先吃饭", "别闹了"]
CUE_VERBS = ["说道", "说", "问道", "答道", "沉声道", "笑道"]


def sid(*parts):
    return hashlib.sha256("|".join(parts).encode()).hexdigest()[:16]


def book_hash(samples):
    payload = "\n".join(json.dumps(s, ensure_ascii=False, sort_keys=True) for s in samples)
    return hashlib.sha256(payload.encode()).hexdigest()


def rng_for(seed):
    import random
    return random.Random(seed)


def make_candidates(rng, K, book, speaker_idx=None, alias_for=None):
    """K 个 opaque 候选；speaker_idx 指定 gold 位置（None=随机）；alias_for: {cand_idx: 别名名}。"""
    names = rng.sample(NAMES, K)
    cands = []
    for i in range(K):
        role = f"c-{book}-{i:02d}"
        nm = names[i]
        cands.append({"role": role, "name": nm, "distance": i + 1})
    return cands


def speaker_sample(book, K, pattern, rng, text=None, seed_tag=""):
    """构造一个 SPEAKER 样本。pattern ∈ preposed_pos1/preposed_mid/preposed_last/postposed/recent_neq/true_unknown/alias/embodiment"""
    cands = make_candidates(rng, K, book)
    names = [c["name"] for c in cands]
    speaker_idx = rng.randrange(K)
    scene = rng.choice(SCENES)
    line = text or f"“{rng.choice(LINES)}。”"
    constraints = [f"{c['role']} != {d['role']}" for i, c in enumerate(cands) for d in cands[i + 1:]]
    inp = {"text": line, "segment_type": "speech", "text_ref": f"syn-{book}/{seed_tag}",
           "recent_context": [scene], "rule_candidates": cands,
           "identity_constraints": constraints, "embodiment": [], "overrides": []}
    # 注意：tgt 必须在 pattern 分支确定最终 speaker_idx 之后再构造（否则 gold 与 cue 错位）
    if pattern == "preposed_pos1":
        speaker_idx = 0
        inp["recent_context"] = [scene, f"{names[speaker_idx]}{rng.choice(CUE_VERBS)}："]
    elif pattern == "preposed_mid":
        speaker_idx = max(1, K // 2)
        inp["recent_context"] = [scene, f"{names[speaker_idx]}{rng.choice(CUE_VERBS)}："]
    elif pattern == "preposed_last":
        speaker_idx = K - 1
        inp["recent_context"] = [scene, f"{names[speaker_idx]}{rng.choice(CUE_VERBS)}："]
    elif pattern == "postposed":
        speaker_idx = rng.randrange(K)
        inp["post_cue"] = f"{names[speaker_idx]}{rng.choice(CUE_VERBS)}。"
    elif pattern == "recent_neq":
        # 最近发言者 = candidates[0]，但 cue 指向另一人
        speaker_idx = rng.randrange(1, K)
        inp["recent_context"] = [scene, f"{names[0]}{rng.choice(CUE_VERBS)}："]
        inp["recent_context"] += [f"“{rng.choice(LINES)}”"]
        inp["recent_context"] += [f"{names[speaker_idx]}{rng.choice(CUE_VERBS)}："]
    elif pattern == "true_unknown":
        inp["recent_context"] = [scene]
    elif pattern == "alias":
        # speaker 的候选名用别名展示
        speaker_idx = rng.randrange(K)
        alias = "老" + names[speaker_idx][-1] + ("子" if rng.random() < 0.5 else "")
        cands[speaker_idx]["name"] = alias
        inp["rule_candidates"] = cands
        inp["recent_context"] = [scene, f"{alias}{rng.choice(CUE_VERBS)}："]
    elif pattern == "embodiment":
        speaker_idx = rng.randrange(K)
        body = names[speaker_idx]
        acting = rng.choice(["魔尊", "白无常", "剑灵"])
        inp["recent_context"] = [scene, f"{body}开口："]
        inp["embodiment"] = [{"surface": body, "acting": f"uid-{acting}", "state": "POSSESSION"}]
    # tgt 在 pattern 分支之后统一构造（gold 必须 = 最终 speaker_idx 对应候选）
    if pattern == "true_unknown":
        tgt = {"speaker": "UNKNOWN", "status": "UNKNOWN"}
    else:
        tgt = {"speaker": cands[speaker_idx]["role"], "status": "CONFIRMED"}
    tgt["surface"] = cands[speaker_idx]["name"] if tgt["speaker"] != "UNKNOWN" else "UNKNOWN"
    return {"task": "SPEAKER", "provenance": "EXPLICIT_RULE_GOLD",
            "book_hash": book, "sample_id": sid(book, seed_tag, pattern, str(K)),
            "input": inp, "target": tgt,
            "meta": {"K": K, "pattern": pattern, "speaker_idx": speaker_idx,
                     "true_unknown": pattern == "true_unknown"}}


def identity_samples(rng, book, tag):
    out = []
    # SAME alias
    a, b = "张明", "老张"
    out.append({"task": "IDENTITY", "provenance": "EXPLICIT_RULE_GOLD", "book_hash": book,
                "sample_id": sid(book, tag, "id-same"),
                "input": {"entity_a": a, "entity_b": b, "aliases_a": ["老张"], "aliases_b": [],
                          "positive_evidence": 2, "negative_evidence": 0, "relationship_evidence": {},
                          "co_presence": 3, "hard_block": False, "user_constraints": [],
                          "text": "-", "segment_type": "identity", "text_ref": f"syn-{book}/{tag}/id-same"},
                "target": {"relation": "SAME", "entity_a_id": f"c-{book}-a", "entity_b_id": f"c-{book}-b"}})
    # hard negative DIFFERENT
    out.append({"task": "IDENTITY", "provenance": "EXPLICIT_RULE_GOLD", "book_hash": book,
                "sample_id": sid(book, tag, "id-diff"),
                "input": {"entity_a": "宿主", "entity_b": "控制者", "aliases_a": [], "aliases_b": [],
                          "positive_evidence": 0, "negative_evidence": 2, "relationship_evidence": {"CONTROL": 1},
                          "co_presence": 0, "hard_block": True, "user_constraints": [],
                          "text": "-", "segment_type": "identity", "text_ref": f"syn-{book}/{tag}/id-diff"},
                "target": {"relation": "DIFFERENT", "entity_a_id": f"c-{book}-c", "entity_b_id": f"c-{book}-d"}})
    # kinship trap UNKNOWN
    out.append({"task": "IDENTITY", "provenance": "EXPLICIT_RULE_GOLD", "book_hash": book,
                "sample_id": sid(book, tag, "id-unk"),
                "input": {"entity_a": "王老", "entity_b": "王明", "aliases_a": [], "aliases_b": [],
                          "positive_evidence": 0, "negative_evidence": 0, "relationship_evidence": {"KINSHIP": 1},
                          "co_presence": 2, "hard_block": False, "user_constraints": [],
                          "text": "-", "segment_type": "identity", "text_ref": f"syn-{book}/{tag}/id-unk"},
                "target": {"relation": "UNKNOWN", "entity_a_id": f"c-{book}-e", "entity_b_id": f"c-{book}-f"}})
    return out


def voice_samples(rng, book, tag):
    out = []
    base = "phase=ADULT"
    seqs = [["NONE", "START", "CONTINUE", "END"], ["NONE", "START", "REPLACE", "END"], ["NONE", "START", "END"]]
    i = 0
    for seq in seqs:
        state = base
        for j, act in enumerate(seq):
            out.append({"task": "VOICE_STATE", "provenance": "EXPLICIT_RULE_GOLD", "book_hash": book,
                        "sample_id": sid(book, tag, "vs", str(i), str(j)),
                        "input": {"text": f"“第{j + 1}句。”", "segment_type": "speech",
                                  "text_ref": f"syn-{book}/{tag}/vs{i}{j}",
                                  "identity_id": f"uid-{book}-vs{i}", "current_state": state},
                        "target": {"action": act, "voice_state_ref": state}})
            state = ("phase=ADULT;temp=START:{}" if act == "START" else
                     ("phase=ADULT;temp=CONTINUE:" if act == "CONTINUE" else
                      ("phase=ADULT;temp=REPLACE:{}" if act == "REPLACE" else base)))
            i += 1
    return out


def gen_test_v2():
    """TASK090_TEST_V2：book D + E，训练前锁定。"""
    rng = rng_for(20260813)
    samples = []
    for book in ("D", "E"):
        tag = 0
        for K in (2, 3, 4, 5, 6):
            for pat in ("preposed_pos1", "preposed_mid", "preposed_last", "postposed", "recent_neq",
                        "true_unknown", "alias", "embodiment"):
                samples.append(speaker_sample(book, K, pat, rng, seed_tag=f"{tag}"))
                tag += 1
        samples += identity_samples(rng, book, f"id-{tag}")
        samples += voice_samples(rng, book, f"vs-{tag}")
    # 排序确定性 + book_hash
    samples.sort(key=lambda s: s["sample_id"])
    for s in samples:
        s["book_hash"] = f"TESTV2-{s['book_hash']}"
    h = book_hash(samples)
    manifest = {"name": "TASK090_TEST_V2", "generated_before_training": True, "frozen_at": "2026-08-13",
                "books": ["D", "E"], "sha256": h, "n": len(samples),
                "note": "全新 synthetic（非 TASK080 test 改写）；2-6 候选全档；锁定后禁止查看标签级错误直到 checkpoint 冻结"}
    os.makedirs(DS, exist_ok=True)
    with open(os.path.join(DS, "test_v2.jsonl"), "w", encoding="utf-8") as f:
        for s in samples:
            f.write(json.dumps(s, ensure_ascii=False) + "\n")
    json.dump(manifest, open(os.path.join(DS, "TASK090_TEST_V2_MANIFEST.json"), "w", encoding="utf-8"), indent=1, ensure_ascii=False)
    print(json.dumps(manifest, indent=1, ensure_ascii=False))
    return h


def gen_dev_card():
    """DEV-CARD A/B/C：Acc@K 与 perm@K 评估集。"""
    rng = rng_for(20260814)
    samples = []
    for book in ("A", "B", "C"):
        tag = 0
        for K in (2, 3, 4, 5, 6):
            for pat in ("preposed_pos1", "preposed_mid", "preposed_last", "postposed", "recent_neq", "true_unknown"):
                for rep in range(3):
                    samples.append(speaker_sample(f"CARDC-{book}", K, pat, rng, seed_tag=f"{tag}-{rep}"))
                    tag += 1
    samples.sort(key=lambda s: s["sample_id"])
    with open(os.path.join(DS, "dev_card.jsonl"), "w", encoding="utf-8") as f:
        for s in samples:
            f.write(json.dumps(s, ensure_ascii=False) + "\n")
    print(f"dev_card: {len(samples)} samples, sha256={book_hash(samples)}")


def gen_sft_cardinality():
    """cardinality × position 正交化（gold 在 1..K 全位置 × 前置/后置/最近≠ 模式），~1000 条。"""
    rng = rng_for(20260815)
    out = []
    tag = 0
    # 主正交化：K × gold位置 × 前置/后置/最近≠ × 10 rep
    for K in (2, 3, 4, 5, 6):
        for pos in range(K):
            for pat in ("preposed", "postposed", "recent_neq"):
                for rep in range(10):
                    p = pat if pat != "preposed" else ("preposed_pos1" if pos == 0 else ("preposed_last" if pos == K - 1 else "preposed_mid"))
                    s = speaker_sample(f"SFT", K, p, rng, seed_tag=f"c{tag}")
                    # 强制 gold 位置 = pos（正交化）
                    cands = s["input"]["rule_candidates"]
                    gold = s["target"]["speaker"]
                    idx = next(i for i, c in enumerate(cands) if c["role"] == gold)
                    cands[pos], cands[idx] = cands[idx], cands[pos]
                    s["input"]["rule_candidates"] = cands
                    s["meta"]["speaker_idx"] = pos
                    prompt, _ = build_prompt("SPEAKER", s, PROMPTS)
                    ans = json.dumps({"speaker": s["target"]["speaker"], "status": s["target"]["status"]}, ensure_ascii=False)
                    out.append({"messages": [{"role": "user", "content": prompt},
                                             {"role": "assistant", "content": ans}],
                                "task": "SPEAKER", "provenance": "EXPLICIT_RULE_GOLD",
                                "sample_id": sid("card", str(K), str(pos), pat, str(rep)),
                                "markers": {"cardinality": True, "K": K, "gold_pos": pos, "pattern": pat}})
                    tag += 1
    # alias/embodiment 模式补充（无位置正交，rep 6）
    for K in (3, 4, 5, 6):
        for pat in ("alias", "embodiment"):
            for rep in range(6):
                s = speaker_sample("SFT", K, pat, rng, seed_tag=f"ae{tag}")
                prompt, _ = build_prompt("SPEAKER", s, PROMPTS)
                ans = json.dumps({"speaker": s["target"]["speaker"], "status": s["target"]["status"]}, ensure_ascii=False)
                out.append({"messages": [{"role": "user", "content": prompt},
                                         {"role": "assistant", "content": ans}],
                            "task": "SPEAKER", "provenance": "EXPLICIT_RULE_GOLD",
                            "sample_id": sid("card-ae", str(K), pat, str(rep)),
                            "markers": {"cardinality": True, "K": K, "gold_pos": -2, "pattern": pat}})
                tag += 1
    # TRUE_UNKNOWN 补充（无 gold 位置）
    for rep in range(60):
        s = speaker_sample("SFT", rng.choice((2, 3, 4, 5, 6)), "true_unknown", rng, seed_tag=f"tu{rep}")
        prompt, _ = build_prompt("SPEAKER", s, PROMPTS)
        out.append({"messages": [{"role": "user", "content": prompt},
                                 {"role": "assistant", "content": '{"speaker": "UNKNOWN", "status": "UNKNOWN"}'}],
                    "task": "SPEAKER", "provenance": "EXPLICIT_RULE_GOLD",
                    "sample_id": sid("card-tu", str(rep)),
                    "markers": {"cardinality": True, "K": s["meta"]["K"], "gold_pos": -1, "pattern": "true_unknown"}})
    out.sort(key=lambda s: s["sample_id"])
    with open(os.path.join(DS, "sft_cardinality.jsonl"), "w", encoding="utf-8") as f:
        for s in out:
            f.write(json.dumps(s, ensure_ascii=False) + "\n")
    print(f"sft_cardinality: {len(out)} samples, sha256={book_hash(out)}")
    return out


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--test_v2", action="store_true")
    ap.add_argument("--dev_card", action="store_true")
    ap.add_argument("--sft", action="store_true")
    args = ap.parse_args()
    if args.test_v2:
        gen_test_v2()
    if args.dev_card:
        gen_dev_card()
    if args.sft:
        gen_sft_cardinality()


if __name__ == "__main__":
    main()
