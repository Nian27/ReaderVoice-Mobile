# -*- coding: utf-8 -*-
"""VS8-SMOKE 评估（A-D 四项）。"""
import argparse
import json
import os
import re
import sys

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
from importlib import import_module
_m = import_module("095b2_eval_mention")
run = _m.run

ROOT = os.path.abspath(os.path.join(os.path.dirname(__file__), "..", "..", ".."))
RUNS = os.path.join(ROOT, "training", "readerdirector", "runs", "task095")
MODEL_DIR = os.path.join(ROOT, "training", "models", "qwen3.5-0.8b-base")

EVENT_PROMPT = (
    "你是 ReaderDirector。判断文本中的声音控制事件。\n"
    "- SET：声线身份被临时替换\n"
    "- CLEAR：恢复原本的声音/停止变声\n"
    "- PERF：仍是本来的声音，只是当前怎么说\n"
    "- NONE：与声音控制无关\n"
    "输出：V=SET|CLEAR|PERF|NONE; E=<evidence>\n"
    "文本：{text}\n"
    "只输出 V=...; E=... 一行"
)

BIND_PROMPT = (
    "你是 ReaderDirector 的声音绑定器。\n"
    "- O = 实际发声者\n"
    "- R = 被模仿/借用的声音归属，无则 NONE\n"
    "- A用B的声音 → O=A, R=B；A让B用C的声音 → O=B, R=C；A听见B变声 → O=B\n"
    "- 不确定 → O=UNKNOWN\n"
    "输出：O=<名字或 UNKNOWN>; R=<名字或 NONE 或 UNKNOWN>\n"
    "文本：{text}\n"
    "只输出 O=...; R=... 一行"
)

def parse_event(raw):
    m = re.search(r"V=(SET|CLEAR|PERF|NONE)", raw or "")
    return m.group(1) if m else None

def parse_bind(raw):
    o = re.search(r"O=([^;]+)", raw or "")
    r2 = re.search(r"R=([^;]+)", raw or "")
    return (o.group(1).strip() if o else None, r2.group(1).strip() if r2 else None)

def load_dev():
    return [json.loads(l) for l in open(os.path.join(RUNS, "vs7_dev_fresh.jsonl"), encoding="utf-8")]

def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--checkpoint", required=True)
    ap.add_argument("--name", default="smoke")
    args = ap.parse_args()

    from transformers import AutoModelForCausalLM, AutoTokenizer
    from peft import LoraConfig, get_peft_model
    from safetensors.torch import load_file
    tok = AutoTokenizer.from_pretrained(MODEL_DIR, trust_remote_code=True)
    model = AutoModelForCausalLM.from_pretrained(MODEL_DIR, trust_remote_code=True, torch_dtype="auto", device_map="auto")
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
    assert not unexpected, f"unexpected: {unexpected[:3]}"
    missing = [m for m in missing if "lora" in m]
    assert not missing, f"missing lora: {missing[:3]}"
    model.eval()
    print("loaded", args.checkpoint, flush=True)

    dev = load_dev()
    from collections import Counter
    ev_ok = ev_tot = 0
    ev_cm = Counter()
    for r in dev:
        gold = {"VOICE_OVERRIDE_SET": "SET", "VOICE_OVERRIDE_CLEAR": "CLEAR",
                "PERFORMANCE": "PERF", "NONE": "NONE"}[r["event_type"]]
        raw = run(model, tok, EVENT_PROMPT.replace("{text}", r["text"]), max_new_tokens=40)
        pred = parse_event(raw)
        ev_cm[(gold, pred)] += 1
        if pred == gold:
            ev_ok += 1
        ev_tot += 1
    print(f"\nA. EVENT accuracy: {ev_ok}/{ev_tot} = {ev_ok/ev_tot*100:.1f}% (before-LoRA 对照：随机 ~25%)")
    for cls in ["SET", "CLEAR", "PERF", "NONE"]:
        tot = sum(v for (g, p), v in ev_cm.items() if g == cls)
        ok = sum(v for (g, p), v in ev_cm.items() if g == cls and p == cls)
        if tot:
            print(f"  {cls}: {ok}/{tot} = {ok/tot*100:.0f}%")
    print("  pred dist:", dict(Counter(p for (g, p), v in ev_cm.items() for _ in range(v))))

    bo_ok = bo_tot = br_ok = br_tot = unk_ok = unk_tot = surf_invalid = 0
    borrow_ok = borrow_tot = 0
    for r in dev:
        if r["event_type"] == "NONE":
            continue
        raw = run(model, tok, BIND_PROMPT.replace("{text}", r["text"]), max_new_tokens=40)
        po, pr = parse_bind(raw)
        for surf in (po, pr):
            if surf and surf not in ("UNKNOWN", "NONE") and surf not in r["text"]:
                surf_invalid += 1
        go, gr = r.get("voice_state_owner"), r.get("reference_voice")
        if go == "UNKNOWN":
            unk_tot += 1
            if po == "UNKNOWN":
                unk_ok += 1
            continue
        bo_tot += 1
        if po == go:
            bo_ok += 1
        if gr and gr not in ("NONE", "UNKNOWN"):
            br_tot += 1
            if pr == gr:
                br_ok += 1
        if r.get("source", "").startswith("borrowing"):
            borrow_tot += 1
            if po == go:
                borrow_ok += 1
    print(f"B. BIND owner: {bo_ok}/{bo_tot} = {bo_ok/bo_tot*100:.1f}%")
    if br_tot:
        print(f"   BIND reference: {br_ok}/{br_tot} = {br_ok/br_tot*100:.1f}%")
    if unk_tot:
        print(f"   UNKNOWN precision: {unk_ok}/{unk_tot} = {unk_ok/unk_tot*100:.1f}%")
    print(f"   surface invalid: {surf_invalid}")
    print(f"C. borrowing owner: {borrow_ok}/{borrow_tot} = {borrow_ok/borrow_tot*100:.1f}% (vs 4B few-shot 95.1%)")
    print("\nD. 旧任务哨兵：全量回归见 eval_checkpoint（MENTION/SPEAKER/IDENTITY/TRUE_UNKNOWN）")

if __name__ == "__main__":
    main()