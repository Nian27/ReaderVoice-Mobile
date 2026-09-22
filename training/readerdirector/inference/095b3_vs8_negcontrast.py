# -*- coding: utf-8 -*-
"""VS8.1 Negative-Contrast Patch（failure-tree A/C 分支）。

背景（VS8-FORMAL dev 结果）：UNKNOWN precision 0/28（BIND 训练仅 5/662 O=UNKNOWN，
且 borrowing_neg 族在 BIND 视图为 0 —— load_vs7_hard 对 NONE event 跳过 BIND 视图）；
EVENT hard-neg 泄漏 24.3%（NONE->SET 9/37，记得/被听见/听到 族）；PERF->NONE 5
（不耐烦/笑着/悲伤 情绪方式族训练缺失）；observer-trap 2（看到/注意到 族）。

本 patch：全新模板（非 dev 句子），冻结 roster，seed 20260820。
dedup：exact + 归一化（人名->{P}）双层 vs vs7_dev_fresh。
provenance = VS8_1_NEGCONTRAST。
"""
import hashlib
import json
import os
import random
import sys

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
from importlib import import_module
_m = import_module("095b3_vs8_train_v1")
EVENT_PROMPT = _m.EVENT_PROMPT
BIND_PROMPT = _m.BIND_PROMPT

ROOT = os.path.abspath(os.path.join(os.path.dirname(__file__), "..", "..", ".."))
RUNS = os.path.join(ROOT, "training", "readerdirector", "runs", "task095")
DS = os.path.join(ROOT, "training", "readerdirector", "dataset")

NAMES = ["张三", "李四", "赵六", "王五", "孙七", "周八"]


def norm(t):
    for n in NAMES:
        t = t.replace(n, "{P}")
    return t


def load_dev_block():
    rows = [json.loads(l) for l in open(os.path.join(RUNS, "vs7_dev_fresh.jsonl"), encoding="utf-8")]
    return {r["text"] for r in rows}, {norm(r["text"]) for r in rows}


def main():
    dev_exact, dev_norm = load_dev_block()
    rng = random.Random(20260820)
    six = []          # VS7 六层行（sidecar，可审计）
    seen_ev = set()
    seen_bd = set()

    def new_persona_pair():
        a, b = rng.sample(NAMES, 2)
        return a, b

    OBJ = {"VOICE_OVERRIDE_SET": "VOICE_IDENTITY", "VOICE_OVERRIDE_CLEAR": "VOICE_IDENTITY",
           "PERFORMANCE": "VOICE_PERFORMANCE", "NONE": "OTHER"}
    TR = {"VOICE_OVERRIDE_SET": "START", "VOICE_OVERRIDE_CLEAR": "ORPHAN_CLEAR",
          "PERFORMANCE": "PERFORMANCE", "NONE": "NONE"}

    def add(text, event_type, owner, ref, evidence, family, view="ev"):
        assert event_type in ("VOICE_OVERRIDE_SET", "VOICE_OVERRIDE_CLEAR", "PERFORMANCE", "NONE")
        assert evidence == "null" or evidence in text, ("evidence not verbatim", evidence, text)
        if text in dev_exact:
            return False
        if norm(text) in dev_norm:
            return False
        seen = seen_ev if view == "ev" else seen_bd
        if text in seen:
            return False
        seen.add(text)
        six.append({
            "text": text, "event_type": event_type,
            "voice_state_owner": owner, "reference_voice": ref,
            "evidence_object": OBJ[event_type], "tier": "P", "source": family,
            "sample_id": "vs81-%05d" % len(six),
            "evidence_span": evidence, "expected_transition": TR[event_type],
            "provenance": "VS8_1_NEGCONTRAST",
        })
        return True

    # ---- 1. EVENT NONE voice-mention（neg_none）----
    none_tmpl = [
        lambda X, Y: "%s记起了%s的声音" % (X, Y),
        lambda X, Y: "%s忘不了%s的声音" % (X, Y),
        lambda X, Y: "%s还记得%s的声音" % (X, Y),
        lambda X, Y: "%s的声音传进了%s的耳朵" % (Y, X),
        lambda X, Y: "%s的声音在%s耳边响起" % (Y, X),
        lambda X, Y: "%s的声音在屋里回响" % (Y,),
        lambda X, Y: "%s觉得%s的声音很熟悉" % (X, Y),
        lambda X, Y: "%s的声音带着疲惫" % (Y,),
        lambda X, Y: "远处传来%s的声音，%s没有回头" % (Y, X),
        lambda X, Y: "%s沉默着，耳边是%s的声音" % (X, Y),
        lambda X, Y: "%s听出那是%s的声音" % (X, Y),
        lambda X, Y: "%s循着%s的声音找了过去" % (X, Y),
        lambda X, Y: "%s喜欢%s的声音" % (X, Y),
        lambda X, Y: "%s的声音戛然而止，%s愣住了" % (Y, X),
        lambda X, Y: "%s说%s的声音像%s的" % (X, Y, Y),
        lambda X, Y: "%s的声音忽远忽近" % (Y,),
    ]
    n_none = 0
    want_none = 80
    guard = 0
    while n_none < want_none and guard < 4000:
        guard += 1
        X, Y = new_persona_pair()
        t = none_tmpl[guard % len(none_tmpl)](X, Y)
        if add(t, "NONE", "UNKNOWN", "NONE", "null", "neg_none"):
            n_none += 1
    print("EVENT NONE neg_none:", n_none)

    # ---- 2. EVENT PERF emotion-manner（perf_emotion）----
    perf_tmpl = [
        lambda X, Y: "%s冷冷地说道" % (X,),
        lambda X, Y: "%s温柔地说" % (X,),
        lambda X, Y: "%s兴奋地说道" % (X,),
        lambda X, Y: "%s疲惫地说" % (X,),
        lambda X, Y: "%s平静地回应" % (X,),
        lambda X, Y: "%s疑惑地问" % (X,),
        lambda X, Y: "%s低沉地开口" % (X,),
        lambda X, Y: "%s声音发颤地说" % (X,),
        lambda X, Y: "%s声音沙哑地说" % (X,),
        lambda X, Y: "%s带着哭腔说" % (X,),
        lambda X, Y: "%s低声问道" % (X,),
        lambda X, Y: "%s嗓音有些沙哑地开口" % (X,),
    ]
    n_perf = 0
    want_perf = 30
    guard = 0
    while n_perf < want_perf and guard < 2000:
        guard += 1
        X, _ = new_persona_pair()
        t = perf_tmpl[guard % len(perf_tmpl)](X, None)
        if add(t, "PERFORMANCE", X, "NONE", t, "perf_emotion"):
            n_perf += 1
    print("EVENT PERF perf_emotion:", n_perf)

    # ---- 3. EVENT SET contrast（set_contrast）----
    set_tmpl = [
        lambda X, Y: "%s借%s的嗓子说话" % (X, Y),
        lambda X, Y: "%s拿%s的声音说话" % (X, Y),
        lambda X, Y: "%s扮作%s的声音开口" % (X, Y),
        lambda X, Y: "%s改用了%s的声音" % (X, Y),
        lambda X, Y: "%s学着%s的嗓音说话" % (X, Y),
        lambda X, Y: "%s用%s的腔调应答" % (X, Y),
        lambda X, Y: "%s的嗓子里发出%s的声音" % (X, Y),
        lambda X, Y: "%s模仿起%s的声音" % (X, Y),
    ]
    n_set = 0
    want_set = 20
    guard = 0
    while n_set < want_set and guard < 2000:
        guard += 1
        X, Y = new_persona_pair()
        t = set_tmpl[guard % len(set_tmpl)](X, Y)
        if add(t, "VOICE_OVERRIDE_SET", X, Y, t, "set_contrast"):
            n_set += 1
    print("EVENT SET set_contrast:", n_set)

    # ---- 4. BIND UNKNOWN（bind_unknown：neg_none 句子 + 黑暗/陌生）----
    # neg_none 六层行 -> BIND 视图 O=UNKNOWN R=NONE
    n_bu = 0
    for r in six:
        if r["source"] != "neg_none":
            continue
        if add(r["text"], r["event_type"], "UNKNOWN", "NONE", "null", "bind_unknown", view="bd"):
            n_bu += 1
    dark_tmpl = [
        lambda X, Y: "黑暗中有人突然开口",
        lambda X, Y: "远处有人用嘶哑的声音说话",
        lambda X, Y: "门后传来一个陌生的声音",
        lambda X, Y: "黑暗里响起一声低语",
        lambda X, Y: "屋外有人压着嗓子说了句什么",
    ]
    n_dark = 0
    for i in range(len(dark_tmpl)):
        t = dark_tmpl[i](None, None)
        if add(t, "VOICE_OVERRIDE_SET", "UNKNOWN", "UNKNOWN", t, "bind_unknown", view="bd"):
            n_dark += 1
    print("BIND UNKNOWN bind_unknown:", n_bu + n_dark, "(neg_none %d + dark %d)" % (n_bu, n_dark))

    # ---- 5. BIND observer-trap（bind_observer）----
    obs_tmpl = [
        lambda X, Y: "%s看到%s的嗓音变了调" % (X, Y),
        lambda X, Y: "%s注意到%s的声音和平时不一样" % (X, Y),
        lambda X, Y: "%s发觉%s的声音变了" % (X, Y),
        lambda X, Y: "%s察觉到%s在变声" % (X, Y),
        lambda X, Y: "%s听到%s的声音变得陌生" % (X, Y),
        lambda X, Y: "%s看见%s的嗓音发哑" % (X, Y),
    ]
    n_obs = 0
    want_obs = 15
    guard = 0
    while n_obs < want_obs and guard < 2000:
        guard += 1
        X, Y = new_persona_pair()
        t = obs_tmpl[guard % len(obs_tmpl)](X, Y)
        if add(t, "VOICE_OVERRIDE_SET", Y, "NONE", t, "bind_observer", view="bd"):
            n_obs += 1
    print("BIND observer bind_observer:", n_obs)

    # ---- 6. BIND SET owner（bind_set：set_contrast 句子）----
    n_bs = 0
    for r in six:
        if r["source"] != "set_contrast":
            continue
        if add(r["text"], r["event_type"], r["voice_state_owner"], r["reference_voice"], r["evidence_span"], "bind_set", view="bd"):
            n_bs += 1
    print("BIND SET bind_set:", n_bs)

    # ---- 汇总 + 写出 ----
    ev_rows = [r for r in six if r["source"] in ("neg_none", "perf_emotion", "set_contrast")]
    bd_rows = [r for r in six if r["source"] in ("bind_unknown", "bind_observer", "bind_set")]
    print("six-layer total:", len(six), "| EVENT views:", len(ev_rows), "| BIND views:", len(bd_rows))

    out_jsonl = os.path.join(RUNS, "vs8_1_negcontrast.jsonl")
    with open(out_jsonl, "w", encoding="utf-8") as f:
        for r in six:
            f.write(json.dumps(r, ensure_ascii=False) + "\n")
    print("six-layer ->", out_jsonl)

    # 消息格式行（与 v1 同构：messages/task/provenance）
    msg_rows = []
    for r in ev_rows:
        ev = {"VOICE_OVERRIDE_SET": "SET", "VOICE_OVERRIDE_CLEAR": "CLEAR",
              "PERFORMANCE": "PERF", "NONE": "NONE"}[r["event_type"]]
        evidence = r["evidence_span"]
        msg_rows.append({"messages": [
            {"role": "user", "content": EVENT_PROMPT.replace("{text}", r["text"])},
            {"role": "assistant", "content": "V=%s; E=%s" % (ev, evidence)},
        ], "task": "VOICE_EVENT", "provenance": "VS8_1_NEGCONTRAST"})
    for r in bd_rows:
        msg_rows.append({"messages": [
            {"role": "user", "content": BIND_PROMPT.replace("{text}", r["text"])},
            {"role": "assistant", "content": "O=%s; R=%s" % (r["voice_state_owner"], r["reference_voice"])},
        ], "task": "VOICE_BIND", "provenance": "VS8_1_NEGCONTRAST"})
    print("message rows:", len(msg_rows))

    v1_path = os.path.join(DS, "vs8_train_v1.jsonl")
    v1 = [json.loads(l) for l in open(v1_path, encoding="utf-8")]
    v1p = v1 + msg_rows
    v1p_path = os.path.join(DS, "vs8_train_v1p.jsonl")
    with open(v1p_path, "w", encoding="utf-8") as f:
        for r in v1p:
            f.write(json.dumps(r, ensure_ascii=False) + "\n")
    sha = hashlib.sha256()
    for r in v1p:
        sha.update((json.dumps(r, ensure_ascii=False) + "\n").encode("utf-8"))
    digest = sha.hexdigest()
    man = {
        "dataset": "vs8_train_v1p.jsonl", "rows": len(v1p),
        "base": {"file": "vs8_train_v1.jsonl", "rows": len(v1), "sha256": "6767ac1fb0fdbec2"},
        "patch": {"file": "vs8_1_negcontrast.jsonl", "rows": len(six), "provenance": "VS8_1_NEGCONTRAST"},
        "sha256": digest, "seed": 20260820,
    }
    with open(os.path.join(DS, "vs8_train_v1p_manifest.json"), "w", encoding="utf-8") as f:
        json.dump(man, f, ensure_ascii=False, indent=1)
    print("v1p ->", v1p_path, "rows:", len(v1p), "sha256:", digest)


if __name__ == "__main__":
    main()
