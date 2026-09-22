# -*- coding: utf-8 -*-
"""VS8-FORMAL DEV 评估 — 仅 VS7_DEV_FRESH (265, sha256 83f158e4c67d1e15)。

A. EVENT: SET/CLEAR/PERF/NONE 逐类 P/R/F1、hard-neg 泄漏、evidence 逐字有效性
B. BIND: owner/ref/joint 总览 + 分族(borrowing/causer/observer/multi/unknown/neg/clear/performance)、
   UNKNOWN precision、surface 有效性、host-grounded 与 G_ground
C. 失败样本(有界)供 failure-tree 诊断
"""
import argparse
import json
import os
import re
import sys

try:
    sys.stdout.reconfigure(encoding="utf-8", errors="replace")
    sys.stderr.reconfigure(encoding="utf-8", errors="replace")
except Exception:
    pass

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
from importlib import import_module
_m = import_module("095b2_eval_mention")
run = _m.run

ROOT = os.path.abspath(os.path.join(os.path.dirname(__file__), "..", "..", ".."))
RUNS = os.path.join(ROOT, "training", "readerdirector", "runs", "task095")
MODEL_DIR = os.path.join(ROOT, "training", "models", "qwen3.5-0.8b-base")

EVENT_PROMPT = """你是 ReaderDirector。判断文本中的声音控制事件。
- SET：声线身份被临时替换（模仿/借用/变成某人的声音）
- CLEAR：恢复原本的声音/停止变声
- PERF：仍是本来的声音，只是当前怎么说（音量/情绪/韵律）
- NONE：与声音控制无关

输出：V=SET|CLEAR|PERF|NONE; E=<原文中逐字存在的证据片段（NONE 时 E=null）>

文本：{text}

示例：
李四模仿王五的声音说话 → V=SET; E=模仿王五的声音
李四恢复了原本的声音 → V=CLEAR; E=恢复了原本的声音
李四压低声音说：别出声 → V=PERF; E=压低声音
李四说王五的声音很好听 → V=NONE; E=null

只输出 V=...; E=... 一行"""

BIND_PROMPT = """你是 ReaderDirector 的声音绑定器。判断声音事件属于谁。
- O = 实际发声者（Voice State Owner）
- R = 被模仿/借用的声音归属，无则 NONE

关键区分：
- A用B的声音说话 → O=A（实际发声者），R=B
- A让B用C的声音 → O=B，R=C
- A听见B变声 → O=B（A是听见者，不选）
- 不确定谁发声 → O=UNKNOWN

输出：O=<原文中的名字或 UNKNOWN>; R=<原文中的名字或 NONE 或 UNKNOWN>

文本：{text}

示例：
李四模仿王五的声音说话 → O=李四; R=王五
王五听见李四变声 → O=李四; R=NONE
不知从哪传来变调的声音 → O=UNKNOWN; R=UNKNOWN

只输出 O=...; R=... 一行"""

EV_MAP = {"VOICE_OVERRIDE_SET": "SET", "VOICE_OVERRIDE_CLEAR": "CLEAR",
          "PERFORMANCE": "PERF", "NONE": "NONE"}
CLASSES = ["SET", "CLEAR", "PERF", "NONE"]
FAM_LABEL = {"borrowing_pos": "borrowing", "borrowing_causer": "borrowing",
             "borrowing_obs": "borrowing"}


def parse_event(raw):
    m = re.search(r"V=(SET|CLEAR|PERF|NONE)", raw or "")
    if not m:
        return None, None
    e = re.search(r"E=(.+)", raw or "")
    return m.group(1), (e.group(1).strip() if e else None)


def parse_bind(raw):
    o = re.search(r"O=([^;]+)", raw or "")
    r2 = re.search(r"R=([^;]+)", raw or "")
    return (o.group(1).strip() if o else None, r2.group(1).strip() if r2 else None)


def load_dev():
    return [json.loads(l) for l in open(os.path.join(RUNS, "vs7_dev_fresh.jsonl"), encoding="utf-8")]


def load_file_named(fn):
    return [json.loads(l) for l in open(os.path.join(RUNS, fn), encoding="utf-8")]


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--checkpoint", required=True)
    ap.add_argument("--name", default="vs8_dev")
    ap.add_argument("--max_new_tokens", type=int, default=48)
    ap.add_argument("--data", default=None, help="覆盖 dev 文件（如 voice_state_test_v1.jsonl）")
    ap.add_argument("--limit", type=int, default=0, help="0 = 全部")
    args = ap.parse_args()

    from transformers import AutoModelForCausalLM, AutoTokenizer
    from peft import LoraConfig, get_peft_model
    from safetensors.torch import load_file
    tok = AutoTokenizer.from_pretrained(MODEL_DIR, trust_remote_code=True)
    model = AutoModelForCausalLM.from_pretrained(MODEL_DIR, trust_remote_code=True,
                                                 torch_dtype="auto", device_map="auto")
    cfg = json.load(open(os.path.join(args.checkpoint, "adapter_config.json"), encoding="utf-8"))
    sd = load_file(os.path.join(args.checkpoint, "adapter_model.safetensors"))
    remapped = {
        k.replace(".model.language_model.layers.", ".model.layers.")
         .replace(".lora_A.weight", ".lora_A.default.weight")
         .replace(".lora_B.weight", ".lora_B.default.weight")
        : v for k, v in sd.items()
    }
    lora_cfg = LoraConfig(r=cfg["r"], lora_alpha=cfg["lora_alpha"], target_modules="all-linear",
                          lora_dropout=cfg.get("lora_dropout", 0.0), bias=cfg.get("bias", "none"))
    model = get_peft_model(model, lora_cfg)
    missing, unexpected = model.load_state_dict(remapped, strict=False)
    assert not unexpected, "unexpected: %s" % (unexpected[:3])
    missing = [m for m in missing if "lora" in m]
    assert not missing, "missing lora: %s" % (missing[:3])
    model.eval()
    print("loaded", args.checkpoint, flush=True)

    dev = load_file_named(args.data) if args.data else load_dev()
    if args.limit:
        dev = dev[:args.limit]
    n = len(dev)
    from collections import Counter, defaultdict

    # ============ A. EVENT ============
    ev_cm = Counter()
    hardneg_leak = hardneg_tot = 0
    ev_ok = ev_tot = 0
    evid_ok = evid_tot = 0
    ev_fails = []
    for r in dev:
        gold = EV_MAP[r["event_type"]]
        raw = run(model, tok, EVENT_PROMPT.replace("{text}", r["text"]),
                  max_new_tokens=args.max_new_tokens)
        pred, pev = parse_event(raw)
        ev_cm[(gold, pred)] += 1
        ev_tot += 1
        if pred == gold:
            ev_ok += 1
        if gold not in ("SET", "CLEAR"):
            hardneg_tot += 1
            if pred in ("SET", "CLEAR"):
                hardneg_leak += 1
        if pev:
            evid_tot += 1
            if pev in ("null", "NONE"):
                if pred == "NONE":
                    evid_ok += 1
            elif pev in r["text"]:
                evid_ok += 1
        if pred != gold:
            ev_fails.append((gold, pred, r["sample_id"], r["text"], raw.strip()[:100]))

    print("\n===== A. EVENT (VS7_DEV_FRESH, n=%d) =====" % n, flush=True)
    print("%-6s %6s %8s %8s %8s" % ("gold", "n", "P", "R", "F1"))
    per_class = {}
    for cls in CLASSES:
        tot = sum(v for (g, p), v in ev_cm.items() if g == cls)
        pred_tot = sum(v for (g, p), v in ev_cm.items() if p == cls)
        ok = sum(v for (g, p), v in ev_cm.items() if g == cls and p == cls)
        p = ok / pred_tot if pred_tot else 0.0
        r = ok / tot if tot else 0.0
        f1 = 2 * p * r / (p + r) if p + r else 0.0
        per_class[cls] = {"n": tot, "P": round(p, 4), "R": round(r, 4), "F1": round(f1, 4)}
        print("%-6s %6d %7.1f%% %7.1f%% %7.1f%%" % (cls, tot, p * 100, r * 100, f1 * 100))
    acc = ev_ok / ev_tot if ev_tot else 0.0
    print("EVENT acc: %.1f%%" % (acc * 100))
    print("hard-neg leakage (gold!=SET/CLEAR -> pred SET/CLEAR): %d/%d = %.1f%%" % (
        hardneg_leak, hardneg_tot, hardneg_leak / hardneg_tot * 100 if hardneg_tot else 0))
    evid = evid_ok / evid_tot if evid_tot else 0.0
    print("evidence validity (pred E verbatim in text): %d/%d = %.2f%%" % (evid_ok, evid_tot, evid * 100))
    print("  pred dist:", dict(Counter(p for (g, p), v in ev_cm.items() for _ in range(v))))

    # ============ B. BIND ============
    bo_ok = bo_tot = br_ok = br_tot = joint_ok = joint_tot = 0
    unk_ok = unk_tot = 0
    surf_invalid = 0
    fam = defaultdict(lambda: {"ok": 0, "tot": 0, "okr": 0, "totr": 0, "jok": 0, "jtot": 0})
    ground_ok = ground_tot = 0
    bind_fails = []
    for r in dev:
        raw = run(model, tok, BIND_PROMPT.replace("{text}", r["text"]),
                  max_new_tokens=args.max_new_tokens)
        po, pr = parse_bind(raw)
        src = r.get("source", "?")
        go, gr = r.get("voice_state_owner"), r.get("reference_voice")
        for surf in (po, pr):
            if surf and surf not in ("UNKNOWN", "NONE") and surf not in r["text"]:
                surf_invalid += 1
        f = fam[src]
        if go == "UNKNOWN":
            unk_tot += 1
            if po == "UNKNOWN":
                unk_ok += 1
            else:
                bind_fails.append(("UNKNOWN", r["sample_id"], src, "O=UNKNOWN", po, r["text"]))
        else:
            bo_tot += 1
            f["tot"] += 1
            if po == go:
                bo_ok += 1
                f["ok"] += 1
            else:
                bind_fails.append(("OWNER", r["sample_id"], src, go, po, r["text"]))
            ground_tot += 1
            if po == go and po in r["text"]:
                ground_ok += 1
        if gr and gr not in ("NONE", "UNKNOWN"):
            br_tot += 1
            f["totr"] += 1
            if pr == gr:
                br_ok += 1
                f["okr"] += 1
            if go != "UNKNOWN":
                joint_tot += 1
                f["jtot"] += 1
                if po == go and pr == gr:
                    joint_ok += 1
                    f["jok"] += 1

    print("\n===== B. BIND (VS7_DEV_FRESH) =====", flush=True)
    oacc = bo_ok / bo_tot if bo_tot else 0.0
    racc = br_ok / br_tot if br_tot else 0.0
    jacc = joint_ok / joint_tot if joint_tot else 0.0
    up = unk_ok / unk_tot if unk_tot else 0.0
    gacc = ground_ok / ground_tot if ground_tot else 0.0
    print("owner semantic acc: %d/%d = %.1f%%" % (bo_ok, bo_tot, oacc * 100))
    print("ref acc:            %d/%d = %.1f%%" % (br_ok, br_tot, racc * 100))
    print("joint acc:          %d/%d = %.1f%%" % (joint_ok, joint_tot, jacc * 100))
    print("UNKNOWN precision:  %d/%d = %.1f%%" % (unk_ok, unk_tot, up * 100))
    print("surface invalid:    %d" % surf_invalid)
    print("host-grounded owner: %.1f%% | G_ground = %+.4f (host-grounded - semantic)" % (gacc * 100, gacc - oacc))
    print("  note: dev gold 是 surface 名（无 ID 槽），grounding 映射恒等 → G_ground 恒=0；host 侧真实 Gate 是 surface 有效性")
    print("\n  per-family (O=owner acc, R=ref acc, J=joint):")
    agg = {"ok": 0, "tot": 0, "okr": 0, "totr": 0, "jok": 0, "jtot": 0}
    for src in sorted(fam):
        f = fam[src]
        label = FAM_LABEL.get(src, src)
        if label == "borrowing":
            for k in agg:
                agg[k] += f[k]
        if f["tot"] or f["totr"]:
            oa = f["ok"] / f["tot"] * 100 if f["tot"] else 0
            ra = f["okr"] / f["totr"] * 100 if f["totr"] else 0
            ja = f["jok"] / f["jtot"] * 100 if f["jtot"] else 0
            print("  %-16s n=%-3d O=%.1f%% (%d/%d)  R=%.1f%% (%d/%d)  J=%.1f%% (%d/%d)" % (
                label, f["tot"], oa, f["ok"], f["tot"], ra, f["okr"], f["totr"],
                ja, f["jok"], f["jtot"]))
    oa = agg["ok"] / agg["tot"] * 100 if agg["tot"] else 0
    print("  %-16s n=%-3d O=%.1f%% (%d/%d)  R=%.1f%% (%d/%d)  J=%.1f%% (%d/%d)" % (
        "borrowing(ALL)", agg["tot"], oa, agg["ok"], agg["tot"],
        agg["okr"] / agg["totr"] * 100 if agg["totr"] else 0, agg["okr"], agg["totr"],
        agg["jok"] / agg["jtot"] * 100 if agg["jtot"] else 0, agg["jok"], agg["jtot"]))

    # ============ C. summary json + failure samples ============
    ev_miss = Counter((g, p) for (g, p, _s, _t, _r) in ev_fails)
    summary = {
        "name": args.name,
        "checkpoint": args.checkpoint,
        "dev": "vs7_dev_fresh.jsonl",
        "n": n,
        "event": {
            "acc": round(acc, 4),
            "per_class": per_class,
            "hardneg_leak": {"leak": hardneg_leak, "tot": hardneg_tot},
            "evidence_validity": {"ok": evid_ok, "tot": evid_tot, "rate": round(evid, 4)},
            "pred_dist": dict(Counter(p for (g, p), v in ev_cm.items() for _ in range(v))),
            "miss_dist": {("%s->%s" % k): v for k, v in ev_miss.most_common()},
        },
        "bind": {
            "owner_acc": round(oacc, 4),
            "ref_acc": round(racc, 4),
            "joint_acc": round(jacc, 4),
            "unknown_precision": round(up, 4),
            "surface_invalid": surf_invalid,
            "host_grounded_acc": round(gacc, 4),
            "G_ground": round(gacc - oacc, 4),
            "by_family": {k: dict(v) for k, v in sorted(fam.items())},
        },
        "failures": {
            "event": [[g, p, sid] for (g, p, sid, _t, _r) in ev_fails[:80]],
            "bind": [[k, sid, src, go, po] for (k, sid, src, go, po, _t) in bind_fails[:80]],
        },
    }
    out = os.path.join(RUNS, "vs8_dev_eval_%s.json" % args.name)
    with open(out, "w", encoding="utf-8") as fp:
        json.dump(summary, fp, ensure_ascii=False, indent=1)
    print("\nsummary ->", out, flush=True)

    print("\n-- EVENT fails, gold SET/CLEAR (max 25) --", flush=True)
    shown = 0
    for gold, pred, sid, text, raw in ev_fails:
        if gold not in ("SET", "CLEAR"):
            continue
        print("[%s] gold=%s pred=%s | %s | raw=%s" % (sid, gold, pred, text, raw))
        shown += 1
        if shown >= 25:
            break
    print("-- BIND fails (max 25) --", flush=True)
    for kind, sid, src, gold, pred, text in bind_fails[:25]:
        print("[%s|%s] %s gold=%s pred=%s | %s" % (sid, src, kind, gold, pred, text))
    print("done", flush=True)


if __name__ == "__main__":
    main()
