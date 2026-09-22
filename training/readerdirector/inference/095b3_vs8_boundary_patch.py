# -*- coding: utf-8 -*-
"""VS8.2 Boundary-Contrast Patch（VS8.1 残余三问题，2026-08-20）。

1. CLEAR 过用（P 46.7%）：8 条 "A要求B恢复原声"（gold=SET，causer-command 族）被标 CLEAR。
   冻结规则：命令/要求/让 + 恢复/换回/停止变声 → SET；已完成恢复（恢复了原状/原本的声音）→ CLEAR。
2. UNKNOWN 过用（4 条 false）：撤掉装出(CLEAR owner)、笑着回应(PERF owner)、注意到声线不对(observer owner)
   被标 UNKNOWN —— perf/clear/observer 的 BIND owner 视图不足。
3. UNKNOWN 漏报（5 条）："X说Y的声音很好听" 族（patch 有 说...像Z 但未迁移到 说...很好听）。

全部新模板，双层 dedup vs vs7_dev_fresh；provenance = VS8_2_BOUNDARY。
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


def main():
    dev_rows = [json.loads(l) for l in open(os.path.join(RUNS, "vs7_dev_fresh.jsonl"), encoding="utf-8")]
    dev_exact = {r["text"] for r in dev_rows}
    dev_norm = {norm(r["text"]) for r in dev_rows}
    rng = random.Random(20260821)
    six = []
    seen_ev = set()
    seen_bd = set()
    OBJ = {"VOICE_OVERRIDE_SET": "VOICE_IDENTITY", "VOICE_OVERRIDE_CLEAR": "VOICE_IDENTITY",
           "PERFORMANCE": "VOICE_PERFORMANCE", "NONE": "OTHER"}
    TR = {"VOICE_OVERRIDE_SET": "START", "VOICE_OVERRIDE_CLEAR": "ORPHAN_CLEAR",
          "PERFORMANCE": "PERFORMANCE", "NONE": "NONE"}

    def add(text, event_type, owner, ref, evidence, family, view="ev"):
        assert evidence == "null" or evidence in text, ("evidence", evidence, text)
        if text in dev_exact or norm(text) in dev_norm:
            return False
        seen = seen_ev if view == "ev" else seen_bd
        if text in seen:
            return False
        seen.add(text)
        six.append({
            "text": text, "event_type": event_type,
            "voice_state_owner": owner, "reference_voice": ref,
            "evidence_object": OBJ[event_type], "tier": "P", "source": family,
            "sample_id": "vs82-%05d" % len(six),
            "evidence_span": evidence, "expected_transition": TR[event_type],
            "provenance": "VS8_2_BOUNDARY",
        })
        return True

    # ---- 1. EVENT SET：causer-command 边界（命令/要求 → SET）----
    set_cmd = [
        lambda X, Y: "%s要求%s把声音换回去" % (X, Y),
        lambda X, Y: "%s命令%s恢复本来的声音" % (X, Y),
        lambda X, Y: "%s吩咐%s把声线调回来" % (X, Y),
        lambda X, Y: "%s让%s停止变声" % (X, Y),
        lambda X, Y: "%s要求%s别再变声" % (X, Y),
        lambda X, Y: "%s命令%s恢复原声说话" % (X, Y),
    ]
    n_sc = 0
    for i in range(120):
        if n_sc >= 20:
            break
        X, Y = rng.sample(NAMES, 2)
        t = set_cmd[i % len(set_cmd)](X, Y)
        if add(t, "VOICE_OVERRIDE_SET", Y, "NONE", t, "set_cmd"):
            n_sc += 1
    print("EVENT SET set_cmd:", n_sc)

    # ---- 2. EVENT CLEAR：实际恢复（→ CLEAR）----
    clear_dir = [
        lambda X, Y: "%s把装出的声音收了回去" % (X,),
        lambda X, Y: "%s的嗓音恢复了正常" % (X,),
        lambda X, Y: "%s恢复了本来的声音" % (X,),
        lambda X, Y: "%s把声音换了回来" % (X,),
        lambda X, Y: "%s停下了变声" % (X,),
    ]
    n_cd = 0
    for i in range(60):
        if n_cd >= 10:
            break
        X, _ = rng.sample(NAMES, 2)
        t = clear_dir[i % len(clear_dir)](X, None)
        if add(t, "VOICE_OVERRIDE_CLEAR", X, "NONE", t, "clear_direct"):
            n_cd += 1
    print("EVENT CLEAR clear_direct:", n_cd)

    # ---- 3. BIND：perf_emotion 六层行的 BIND 视图（O=X R=NONE，修 笑着回应→UNKNOWN）----
    n_pb = 0
    v1p_rows = [json.loads(l) for l in open(os.path.join(RUNS, "vs8_1_negcontrast.jsonl"), encoding="utf-8")]
    for r in v1p_rows:
        if r["source"] == "perf_emotion":
            if add(r["text"], r["event_type"], r["voice_state_owner"], "NONE",
                   r["evidence_span"], "bind_perf", view="bd"):
                n_pb += 1
    print("BIND perf-owner bind_perf:", n_pb)

    # ---- 4. BIND：set_cmd + clear_direct 的 BIND 视图（O=Y/X R=NONE）----
    n_cb = 0
    for r in six:
        if r["source"] in ("set_cmd", "clear_direct"):
            if add(r["text"], r["event_type"], r["voice_state_owner"], "NONE",
                   r["evidence_span"], "bind_cmd", view="bd"):
                n_cb += 1
    print("BIND cmd/clear-owner bind_cmd:", n_cb)

    # ---- 5. BIND UNKNOWN：说/夸/评价 声音（修 说...很好听 漏报）----
    unk_eval = [
        lambda X, Y: "%s夸%s的声音好听" % (X, Y),
        lambda X, Y: "%s称赞%s的嗓音" % (X, Y),
        lambda X, Y: "%s觉得%s的声音刺耳" % (X, Y),
        lambda X, Y: "%s嫌%s的声音太吵" % (X, Y),
        lambda X, Y: "%s感叹%s的声音真好听" % (X, Y),
        lambda X, Y: "%s说%s的声音像唱歌一样" % (X, Y),
    ]
    n_ue = 0
    for i in range(120):
        if n_ue >= 15:
            break
        X, Y = rng.sample(NAMES, 2)
        t = unk_eval[i % len(unk_eval)](X, Y)
        if add(t, "NONE", "UNKNOWN", "NONE", "null", "bind_unknown_eval", view="bd"):
            n_ue += 1
    print("BIND UNKNOWN bind_unknown_eval:", n_ue)

    # ---- 6. BIND observer：声线不对/发哑/变调 变体（修 注意到声线不对→UNKNOWN）----
    obs2 = [
        lambda X, Y: "%s发现%s的声线发哑" % (X, Y),
        lambda X, Y: "%s察觉到%s的嗓音变了调" % (X, Y),
        lambda X, Y: "%s听出%s的声音变了" % (X, Y),
        lambda X, Y: "%s看见%s的声线不对" % (X, Y),
        lambda X, Y: "%s发觉%s的嗓音发颤" % (X, Y),
    ]
    n_o2 = 0
    for i in range(80):
        if n_o2 >= 10:
            break
        X, Y = rng.sample(NAMES, 2)
        t = obs2[i % len(obs2)](X, Y)
        if add(t, "VOICE_OVERRIDE_SET", Y, "NONE", t, "bind_observer2", view="bd"):
            n_o2 += 1
    print("BIND observer bind_observer2:", n_o2)

    # ---- 汇总写出 ----
    ev_rows = [r for r in six if r["source"] in ("set_cmd", "clear_direct")]
    bd_rows = [r for r in six if r["source"] in ("bind_perf", "bind_cmd", "bind_unknown_eval", "bind_observer2")]
    print("six-layer total:", len(six), "| EVENT views:", len(ev_rows), "| BIND views:", len(bd_rows))

    out_jsonl = os.path.join(RUNS, "vs8_2_boundary.jsonl")
    with open(out_jsonl, "w", encoding="utf-8") as f:
        for r in six:
            f.write(json.dumps(r, ensure_ascii=False) + "\n")
    print("six-layer ->", out_jsonl)

    msg_rows = []
    for r in ev_rows:
        ev = {"VOICE_OVERRIDE_SET": "SET", "VOICE_OVERRIDE_CLEAR": "CLEAR",
              "PERFORMANCE": "PERF", "NONE": "NONE"}[r["event_type"]]
        msg_rows.append({"messages": [
            {"role": "user", "content": EVENT_PROMPT.replace("{text}", r["text"])},
            {"role": "assistant", "content": "V=%s; E=%s" % (ev, r["evidence_span"])},
        ], "task": "VOICE_EVENT", "provenance": "VS8_2_BOUNDARY"})
    for r in bd_rows:
        msg_rows.append({"messages": [
            {"role": "user", "content": BIND_PROMPT.replace("{text}", r["text"])},
            {"role": "assistant", "content": "O=%s; R=%s" % (r["voice_state_owner"], r["reference_voice"])},
        ], "task": "VOICE_BIND", "provenance": "VS8_2_BOUNDARY"})
    print("message rows:", len(msg_rows))

    v1p_path = os.path.join(DS, "vs8_train_v1p.jsonl")
    v1p = [json.loads(l) for l in open(v1p_path, encoding="utf-8")]
    v1p2 = v1p + msg_rows
    v1p2_path = os.path.join(DS, "vs8_train_v1p2.jsonl")
    with open(v1p2_path, "w", encoding="utf-8") as f:
        for r in v1p2:
            f.write(json.dumps(r, ensure_ascii=False) + "\n")
    sha = hashlib.sha256()
    for r in v1p2:
        sha.update((json.dumps(r, ensure_ascii=False) + "\n").encode("utf-8"))
    digest = sha.hexdigest()
    man = {
        "dataset": "vs8_train_v1p2.jsonl", "rows": len(v1p2),
        "base": {"file": "vs8_train_v1p.jsonl", "rows": len(v1p), "sha256": "6d36bf03c6beb2270af06d508b02d6df6473bc6fdc0a7855510ca1b84b3bc9f4"},
        "patch": {"file": "vs8_2_boundary.jsonl", "rows": len(six), "provenance": "VS8_2_BOUNDARY"},
        "sha256": digest, "seed": 20260821,
    }
    with open(os.path.join(DS, "vs8_train_v1p2_manifest.json"), "w", encoding="utf-8") as f:
        json.dump(man, f, ensure_ascii=False, indent=1)
    print("v1p2 ->", v1p2_path, "rows:", len(v1p2), "sha256:", digest)


if __name__ == "__main__":
    main()
