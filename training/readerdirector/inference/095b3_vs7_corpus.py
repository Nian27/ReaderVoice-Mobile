# -*- coding: utf-8 -*-
"""TASK-095-VS7.3 — hard-family corpus 生成器（用户 §五/§六 定案）。

BORROWING Contrast Family（重点，200-300 cases）：
  A用B的声音说 / A模仿B的声音 / A学B说话 / A发出了B的声音 / A的嘴里传出B的声音
  A让B模仿C / A命令B用C的声音 / A听见B模仿C / A发现B正在用C的声音
  反例：A的声音被B听见 / A听到B的声音 / B的声音从门外传来 / A说B的声音很好听

hard-family 采样比例（Voice Bind 训练部分，§六）：
  25% borrowing/reference / 20% causer / 15% observer+listener / 15% pronoun/coreference
  10% multi-person / 10% direct/simple / 5% UNKNOWN

permutation：角色名 × 句式 × 候选顺序。
输出：runs/task095/vs7_corpus.jsonl（六层 gold VoiceSample 格式）
"""
import itertools
import json
import os
import random

OUT = os.path.join(os.path.dirname(os.path.abspath(__file__)), "..", "..", "..", "training", "readerdirector", "runs", "task095")

NAMES = ["张三", "李四", "王五", "赵六", "孙七", "周八"]

# ---- BORROWING 正向句式（owner=A 实际发声，ref=B 被借声） ----
BORROW_POS = [
    "{A}用{B}的声音说：……",
    "{A}模仿{B}的声音说道",
    "{A}学着{B}说话",
    "{A}发出了{B}的声音",
    "{A}的嘴里传出{B}的声音",
    "{A}突然用{B}的嗓音开口",
    "{A}借{B}的声音应答",
    "{A}装出{B}的声音",
    "{A}用{B}的腔调说道",
    "{A}换成了{B}的声音说话",
]
# ---- BORROWING 使役句式（owner=B 实际发声，ref=C） ----
BORROW_CAUSER = [
    "{A}让{B}模仿{C}的声音说话",
    "{A}命令{B}用{C}的声音开口",
    "{A}吩咐{B}学{C}的腔调",
    "{A}要求{B}换成{C}的声音",
    "{A}叫{B}用{C}的嗓音应答",
]
# ---- BORROWING 观察句式（owner=B，observer=A，ref=C） ----
BORROW_OBS = [
    "{A}听见{B}模仿{C}的声音",
    "{A}发现{B}正在用{C}的声音说话",
    "{A}听到{B}学着{C}说话",
    "{A}看着{B}用{C}的嗓音开口",
]
# ---- 反例（不构成 override 或 owner 不是 A） ----
BORROW_NEG = [
    "{A}的声音被{B}听见",
    "{A}听到{B}的声音",
    "{B}的声音从门外传来",
    "{A}说{B}的声音很好听",
    "{A}记得{B}的声音",
    "{A}的声音里带着笑意",
    "{B}的声音远远传来，{A}停下了脚步",
]

# ---- causer 独立族 ----
CAUSER_POS = [
    "{A}让{B}压低声音说话",
    "{A}命令{B}换个声线",
    "{A}吩咐{B}别再捏着嗓子",
    "{A}要求{B}恢复原声",
    "{A}叫{B}把声音放轻",
]
# ---- observer/listener 独立族 ----
OBS_POS = [
    "{A}听见{B}的声音变了",
    "{A}看到{B}的嗓音突然嘶哑",
    "{A}发现{B}的声音完全换了",
    "{A}注意到{B}的声线不对",
    "{B}的声音在{A}耳边响起，带着变调",
]
# ---- multi-person ----
MULTI_POS = [
    "{A}、{B}和{C}坐在堂上，{B}突然模仿起{C}的声音",
    "{A}与{B}交谈，{B}忽然用了{C}的声音",
    "{A}、{B}面面相觑，{B}率先用{C}的嗓音开口",
    "{B}和{C}都在场，{B}却学起了{C}的腔调",
]
# ---- direct/simple ----
DIRECT_POS = [
    "{A}的声音突然变得苍老",
    "{A}的嗓音恢复了原状",
    "{A}的声音里多了一分阴冷",
    "{A}的声线一下子换了",
    "{A}压低声音说：别出声",
    "{A}冷冷地说道",
    "{A}恢复了原本的声音",
]

def make_events(template, a, b, c):
    """返回 (text, event_type, owner, reference, evidence)。"""
    t = template.format(A=a, B=b, C=c)
    # 正向 borrowing / direct / observer-causer 判定
    if any(k in t for k in ("用{0}的声音说".format(b), "借{0}的声音".format(b), "装出{0}的声音".format(b),
                            "换成了{0}的声音".format(b), "用{0}的嗓音".format(b), "用{0}的腔调".format(b))):
        return (t, "VOICE_OVERRIDE_SET", a, b, None)  # evidence 由调用方填充
    return (t, "VOICE_OVERRIDE_SET", a, b, None)

def gen(rng):
    out = []
    used = set()
    def push(text, etype, owner, ref, obj, tier="C", source=None):
        key = text
        if key in used: return
        used.add(key)
        # evidence：取文本中 owner/ref 附近的 verb 短语（简化：取前 6 字 + 声音/嗓/腔 词）
        out.append({"text": text, "event_type": etype, "voice_state_owner": owner,
                   "reference_voice": ref, "evidence_object": obj, "tier": tier,
                   "source": source or "synthetic"})

    # BORROWING 正向（owner=A, ref=B）
    for _ in range(120):
        a, b = rng.sample(NAMES, 2)
        tmpl = rng.choice(BORROW_POS)
        t = tmpl.format(A=a, B=b, C=rng.choice(NAMES))
        push(t, "VOICE_OVERRIDE_SET", a, b, "VOICE_IDENTITY", source="borrowing_pos")
    # BORROWING 使役（owner=B, ref=C, causer=A）
    for _ in range(60):
        a, b, c = rng.sample(NAMES, 3)
        t = rng.choice(BORROW_CAUSER).format(A=a, B=b, C=c)
        push(t, "VOICE_OVERRIDE_SET", b, c, "VOICE_IDENTITY", source="borrowing_causer")
    # BORROWING 观察（owner=B, ref=C, observer=A）
    for _ in range(40):
        a, b, c = rng.sample(NAMES, 3)
        t = rng.choice(BORROW_OBS).format(A=a, B=b, C=c)
        push(t, "VOICE_OVERRIDE_SET", b, c, "VOICE_IDENTITY", source="borrowing_obs")
    # BORROWING 反例（NONE / PERFORMANCE）
    for _ in range(40):
        a, b = rng.sample(NAMES, 2)
        t = rng.choice(BORROW_NEG).format(A=a, B=b, C=rng.choice(NAMES))
        push(t, "NONE", "UNKNOWN", "NONE", "OTHER", source="borrowing_neg")
    # causer 族（owner=B）
    for _ in range(60):
        a, b = rng.sample(NAMES, 2)
        t = rng.choice(CAUSER_POS).format(A=a, B=b, C=rng.choice(NAMES))
        push(t, "VOICE_OVERRIDE_SET", b, "NONE", "VOICE_IDENTITY", source="causer")
    # observer/listener 族（owner=B）
    for _ in range(45):
        a, b = rng.sample(NAMES, 2)
        t = rng.choice(OBS_POS).format(A=a, B=b, C=rng.choice(NAMES))
        push(t, "VOICE_OVERRIDE_SET", b, "NONE", "VOICE_IDENTITY", source="observer")
    # multi-person
    for _ in range(30):
        a, b, c = rng.sample(NAMES, 3)
        t = rng.choice(MULTI_POS).format(A=a, B=b, C=c)
        push(t, "VOICE_OVERRIDE_SET", b, c, "VOICE_IDENTITY", source="multi")
    # direct/simple
    for _ in range(30):
        a = rng.choice(NAMES)
        t = rng.choice(DIRECT_POS).format(A=a, B=rng.choice(NAMES), C=rng.choice(NAMES))
        etype = "VOICE_OVERRIDE_SET" if any(k in t for k in ("苍老", "换了", "变了")) else "PERFORMANCE"
        owner = a if etype == "VOICE_OVERRIDE_SET" else a
        push(t, etype, owner, "NONE", "VOICE_PERFORMANCE" if etype == "PERFORMANCE" else "VOICE_IDENTITY", source="direct")
    # UNKNOWN（不可解析）
    for _ in range(15):
        t = rng.choice(["不知从哪传来一道变了调的声音", "黑暗中有人突然用嘶哑的声音开口",
                        "一个陌生的声音忽然响起", "背后传来一个变过调的声音"])
        push(t, "VOICE_OVERRIDE_SET", "UNKNOWN", "UNKNOWN", "VOICE_IDENTITY", source="unknown")
    return out

def main():
    rng = random.Random(42)
    rows = gen(rng)
    # 写入（含 sample_id）
    op = os.path.join(OUT, "vs7_corpus.jsonl")
    with open(op, "w", encoding="utf-8") as f:
        for i, r in enumerate(rows):
            r["sample_id"] = f"vs7-c{i:05d}"
            f.write(json.dumps(r, ensure_ascii=False) + chr(10))
    from collections import Counter
    print("total:", len(rows))
    print("by source:", dict(Counter(r["source"] for r in rows)))
    print("by event:", dict(Counter(r["event_type"] for r in rows)))
    print("saved:", op)

if __name__ == "__main__":
    main()