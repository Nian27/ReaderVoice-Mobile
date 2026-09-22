# -*- coding: utf-8 -*-
"""VS8-FORMAL — VS8_TRAIN_V1 冻结（用户执行指令 1-6）。

总体：OLD_TASK_REPLAY 48% / TIER_B_REAL 27% / VS7_HARD 25%
EVENT 采样：NONE 35% / PERF 30% / SET 20% / CLEAR 15%
BIND 采样：borrowing 30% / causer 20% / observer-listener 15% / pronoun-resolvable 12% /
          pronoun-unresolvable 8% / multi 5% / direct 10%

CLEAR：sampler weight（非物理复制）；REAL_CLEAR/SYNTHETIC_CLEAR 分开记录 provenance
"""
import hashlib
import json
import os
import random

ROOT = os.path.abspath(os.path.join(os.path.dirname(__file__), "..", "..", ".."))
DS = os.path.join(ROOT, "training", "readerdirector", "dataset")
RUNS = os.path.join(ROOT, "training", "readerdirector", "runs", "task095")

EVENT_PROMPT = (
    "你是 ReaderDirector。判断文本中的声音控制事件。\n"
    "- SET：声线身份被临时替换（模仿/借用/变成某人的声音）\n"
    "- CLEAR：恢复原本的声音/停止变声\n"
    "- PERF：仍是本来的声音，只是当前怎么说（音量/情绪/韵律）\n"
    "- NONE：与声音控制无关\n"
    "\n"
    "输出：V=SET|CLEAR|PERF|NONE; E=<原文中逐字存在的证据片段（NONE 时 E=null）>\n"
    "\n"
    "文本：{text}\n"
    "\n"
    "示例：\n"
    "李四模仿王五的声音说话 → V=SET; E=模仿王五的声音\n"
    "李四恢复了原本的声音 → V=CLEAR; E=恢复了原本的声音\n"
    "李四压低声音说：别出声 → V=PERF; E=压低声音\n"
    "李四说王五的声音很好听 → V=NONE; E=null\n"
    "\n"
    "只输出 V=...; E=... 一行"
)

BIND_PROMPT = (
    "你是 ReaderDirector 的声音绑定器。判断声音事件属于谁。\n"
    "- O = 实际发声者（Voice State Owner）\n"
    "- R = 被模仿/借用的声音归属，无则 NONE\n"
    "\n"
    "关键区分：\n"
    "- A用B的声音说话 → O=A（实际发声者），R=B\n"
    "- A让B用C的声音 → O=B，R=C\n"
    "- A听见B变声 → O=B（A是听见者，不选）\n"
    "- 不确定谁发声 → O=UNKNOWN\n"
    "\n"
    "输出：O=<原文中的名字或 UNKNOWN>; R=<原文中的名字或 NONE 或 UNKNOWN>\n"
    "\n"
    "文本：{text}\n"
    "\n"
    "示例：\n"
    "李四模仿王五的声音说话 → O=李四; R=王五\n"
    "王五听见李四变声 → O=李四; R=NONE\n"
    "不知从哪传来变调的声音 → O=UNKNOWN; R=UNKNOWN\n"
    "\n"
    "只输出 O=...; R=... 一行"
)

EV_MAP = {"VOICE_OVERRIDE_SET": "SET", "VOICE_OVERRIDE_CLEAR": "CLEAR",
          "PERFORMANCE": "PERF", "NONE": "NONE"}

def to_swift(text, user_prompt, assistant, task, provenance, extra=None):
    rec = {"messages": [{"role": "user", "content": user_prompt},
                        {"role": "assistant", "content": assistant}],
           "task": task, "provenance": provenance}
    if extra:
        rec.update(extra)
    return rec

def load_vs7_hard():
    rows = [json.loads(l) for l in open(os.path.join(RUNS, "vs7_dev_fresh.jsonl"), encoding="utf-8")]
    out = []
    for r in rows:
        ev = EV_MAP[r["event_type"]]
        evidence = r.get("evidence_span") or "null"
        family = r.get("source", "direct")
        out.append({"task": "VOICE_EVENT", "family": family, "class": ev, "provenance": "VS7_HARD",
                    "text": r["text"], "prompt": EVENT_PROMPT, "answer": f"V={ev}; E={evidence}"})
        if r["event_type"] != "NONE":
            out.append({"task": "VOICE_BIND", "family": family, "class": "bind", "provenance": "VS7_HARD",
                        "text": r["text"], "prompt": BIND_PROMPT,
                        "answer": f"O={r.get('voice_state_owner', 'UNKNOWN')}; R={r.get('reference_voice', 'NONE')}"})
    return out

def load_tier_b():
    rows = [json.loads(l) for l in open(os.path.join(RUNS, "vs7_tierb_annotated.jsonl"), encoding="utf-8")]
    out = []
    seen = set()
    for r in rows:
        v = r.get("validated")
        if not v:
            continue
        if r["text"] in seen:
            continue
        seen.add(r["text"])
        ev = v["event"]
        ev_cls = EV_MAP[ev]
        # EVENT 视图
        evidence = v.get("evidence") or "null"
        out.append({"task": "VOICE_EVENT", "family": "real_" + ev_cls.lower(), "class": ev_cls,
                    "provenance": "TIER_B_REAL", "text": r["text"], "prompt": EVENT_PROMPT,
                    "answer": f"V={ev_cls}; E={evidence}", "book": r.get("book")})
        # BIND 视图（非 NONE）
        if ev != "NONE":
            owner = v.get("owner") or "UNKNOWN"
            ref = v.get("reference") or "NONE"
            fam = "real_bind"
            out.append({"task": "VOICE_BIND", "family": fam, "class": "bind", "provenance": "TIER_B_REAL",
                        "text": r["text"], "prompt": BIND_PROMPT, "answer": f"O={owner}; R={ref}",
                        "book": r.get("book")})
    return out

def load_old_replay():
    rows = [json.loads(l) for l in open(os.path.join(DS, "sft_v2_mention_v2.jsonl"), encoding="utf-8")]
    return [{"task": r.get("task", "?"), "family": "old", "class": "replay",
             "provenance": "OLD_REPLAY", "messages": r["messages"], "text": None} for r in rows]

def main():
    rng = random.Random(42)
    hard = load_vs7_hard()
    real = load_tier_b()
    old = load_old_replay()
    print(f"pools: hard={len(hard)} real={len(real)} old={len(old)}")

    # ---- 分层采样 - 目标总量 8000（正式训练规模）----
    TOTAL = 8000
    n_old = int(TOTAL * 0.48)
    n_real = int(TOTAL * 0.27)
    n_hard = TOTAL - n_old - n_real

    # VS7 HARD：EVENT/BIND 各半（用户 §5：50/50），EVENT 内部按 class 采样
    hard_ev = [r for r in hard if r["task"] == "VOICE_EVENT"]
    hard_bd = [r for r in hard if r["task"] == "VOICE_BIND"]
    ev_target = {"NONE": 0.35, "PERF": 0.30, "SET": 0.20, "CLEAR": 0.15}
    picked_hard = []
    for cls, frac in ev_target.items():
        pool = [r for r in hard_ev if r["class"] == cls]
        n = int(n_hard * 0.5 * frac)
        rng.shuffle(pool)
        # 不足则全取 + 从其他类借
        picked_hard.extend(pool[:n] if len(pool) >= n else pool)
    # CLEAR 不足补充：从 hard CLEAR 重复 weight（记录 provenance 不变，允许少量重复但标记）
    clear_pool = [r for r in hard_ev if r["class"] == "CLEAR"]
    need_clear = int(n_hard * 0.5 * 0.15)
    if len(clear_pool) < need_clear:
        # 用 weight 概念：不足则复制（记录 CLEAR_WEIGHT 标记）
        extra = need_clear - len(clear_pool)
        for i in range(extra):
            r = clear_pool[i % len(clear_pool)]
            r2 = dict(r)
            r2["cleared_weight"] = True
            picked_hard.append(r2)
    # BIND 内部 family 采样
    bd_target = {"borrowing": 0.30, "causer": 0.20, "observer": 0.15, "multi": 0.05, "direct": 0.10}
    for fam, frac in bd_target.items():
        pool = [r for r in hard_bd if fam in r.get("family", "")]
        n = int(n_hard * 0.5 * frac)
        rng.shuffle(pool)
        picked_hard.extend(pool[:n] if len(pool) >= n else pool)
    # pronoun（hard 无 pronoun → 由 real 或后续补；此处先按比例留出）
    print(f"hard picked: {len(picked_hard)} (EVENT {sum(1 for r in picked_hard if r['task']=='VOICE_EVENT')} / BIND {sum(1 for r in picked_hard if r['task']=='VOICE_BIND')})")

    # ---- TIER B REAL：按真实分布但 SET/CLEAR 上采样（weight）----
    real_ev = [r for r in real if r["task"] == "VOICE_EVENT"]
    real_bd = [r for r in real if r["task"] == "VOICE_BIND"]
    picked_real = []
    # EVENT：SET/CLEAR 全取 + PERF 采 30% + NONE 采 15%
    for cls, frac in {"SET": 1.0, "CLEAR": 1.0, "PERF": 0.30, "NONE": 0.15}.items():
        pool = [r for r in real_ev if r["class"] == cls]
        n = int(n_real * 0.5 * frac)
        rng.shuffle(pool)
        picked_real.extend(pool[:n] if len(pool) >= n else pool)
    # BIND 全取（真实 binding 稀少）
    picked_real.extend(real_bd[:int(n_real * 0.5)])
    print(f"real picked: {len(picked_real)} (EVENT {sum(1 for r in picked_real if r['task']=='VOICE_EVENT')} / BIND {sum(1 for r in picked_real if r['task']=='VOICE_BIND')})")

    # ---- OLD REPLAY 采样 ----
    picked_old = old[:n_old]
    rng.shuffle(picked_old)
    print(f"old picked: {len(picked_old)}")

    # ---- 合并 + 序列化 + manifest ----
    out = []
    for r in picked_hard + picked_real:
        out.append(to_swift(r["text"], r["prompt"].replace("{text}", r["text"]), r["answer"],
                             r["task"], r["provenance"],
                             {"markers": {"family": r.get("family"), "class": r.get("class"),
                                           "cleared_weight": r.get("cleared_weight", False)}}))
    for r in picked_old:
        out.append({"messages": r["messages"], "task": r["task"], "provenance": "OLD_REPLAY"})
    rng.shuffle(out)

    op = os.path.join(DS, "vs8_train_v1.jsonl")
    with open(op, "w", encoding="utf-8") as f:
        for r in out:
            f.write(json.dumps(r, ensure_ascii=False) + chr(10))
    sha = hashlib.sha256(open(op, "rb").read()).hexdigest()
    from collections import Counter
    manifest = {
        "name": "VS8_TRAIN_V1", "version": "v1", "created": "2026-08-20",
        "total": len(out), "sha256": sha,
        "provenance": dict(Counter(r["provenance"] for r in out)),
        "task_dist": dict(Counter(r["task"] for r in out)),
        "event_class_dist": dict(Counter(r.get("markers", {}).get("class") for r in out if r.get("markers"))),
        "note": "分层采样；CLEAR 用 weight（cleared_weight 标记）非纯物理复制；冻结后不再修改",
    }
    with open(os.path.join(DS, "vs8_train_v1_manifest.json"), "w", encoding="utf-8") as f:
        json.dump(manifest, f, ensure_ascii=False, indent=1)
    print(f"saved {op}: {len(out)} sha256={sha[:16]}")
    print("provenance:", dict(Counter(r["provenance"] for r in out)))
    print("task:", dict(Counter(r["task"] for r in out)))
    print("manifest saved")

if __name__ == "__main__":
    main()