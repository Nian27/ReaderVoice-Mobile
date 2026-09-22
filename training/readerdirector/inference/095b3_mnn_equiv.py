# -*- coding: utf-8 -*-
"""MOBILE-000B Step 3: HF(merged) <-> MNN(device) task-level equivalence on VS8 EVENT.

Usage:
  python 095b3_mnn_equiv.py --mode subset            # build subset + prompts.jsonl for device
  python 095b3_mnn_equiv.py --mode pc                # run PC (merged HF) -> pc_out.jsonl
  python 095b3_mnn_equiv.py --mode compare --pc pc_out.jsonl --dev device_results.jsonl
"""
import argparse, json, os, re, sys, random
sys.stdout.reconfigure(encoding="utf-8", errors="replace")

ROOT = os.path.abspath(os.path.join(os.path.dirname(__file__), "..", "..", ".."))
DEV = os.path.join(ROOT, "training", "readerdirector", "runs", "task095", "vs7_dev_fresh.jsonl")
MERGE = os.path.join(ROOT, "training", "models", "qwen3.5-0.8b-rd361-merged")
EVAL_F = os.path.join(os.path.dirname(__file__), "095b3_vs8_dev_eval.py")
OUT = os.path.join(ROOT, "runs", "mnn_llm", "equiv_vs8")

EV_MAP = {"VOICE_OVERRIDE_SET": "SET", "VOICE_OVERRIDE_CLEAR": "CLEAR",
          "PERFORMANCE": "PERF", "NONE": "NONE"}

def extract_prompt(name):
    src = open(EVAL_F, encoding="utf-8").read()
    m = re.search(name + r' = """(.*?)"""', src, re.S)
    return m.group(1) if m else None

def parse_event(raw):
    m = re.search(r"V=(SET|CLEAR|PERF|NONE)", raw or "")
    if not m:
        return None, None
    e = re.search(r"E=(.+)", raw or "")
    return m.group(1), (e.group(1).strip() if e else None)

def subset():
    os.makedirs(OUT, exist_ok=True)
    recs = [json.loads(l) for l in open(DEV, encoding="utf-8")]
    by_cls = {}
    for r in recs:
        by_cls.setdefault(r["event_type"], []).append(r)
    plan = [("VOICE_OVERRIDE_SET", 12), ("NONE", 8), ("VOICE_OVERRIDE_CLEAR", 5), ("PERFORMANCE", 5)]
    picks = []
    for cls, n in plan:
        pool = by_cls.get(cls, [])
        random.seed(7)
        random.shuffle(pool)
        picks.extend(pool[:n])
    random.seed(7)
    random.shuffle(picks)
    sub = os.path.join(OUT, "subset.jsonl")
    with open(sub, "w", encoding="utf-8") as f:
        for r in picks:
            f.write(json.dumps(r, ensure_ascii=False) + "\n")
    ev = extract_prompt("EVENT_PROMPT")
    prompts = os.path.join(OUT, "prompts.jsonl")
    with open(prompts, "w", encoding="utf-8") as f:
        for r in picks:
            f.write(json.dumps({"id": r["sample_id"], "prompt": ev.replace("{text}", r["text"])}, ensure_ascii=False) + "\n")
    print(f"subset {len(picks)} -> {sub} / {prompts}")

def pc():
    import torch
    from transformers import AutoModelForCausalLM, AutoTokenizer
    tok = AutoTokenizer.from_pretrained(MERGE)
    model = AutoModelForCausalLM.from_pretrained(MERGE, torch_dtype=torch.bfloat16).cuda().eval()
    prompts = [json.loads(l) for l in open(os.path.join(OUT, "prompts.jsonl"), encoding="utf-8")]
    rows = []
    for p in prompts:
        msgs = [{"role": "user", "content": p["prompt"]}]
        try:
            text = tok.apply_chat_template(msgs, tokenize=False, add_generation_prompt=True, enable_thinking=False)
        except Exception:
            text = tok.apply_chat_template(msgs, tokenize=False, add_generation_prompt=True)
        ids = tok(text, return_tensors="pt").input_ids.cuda()
        with torch.no_grad():
            out = model.generate(ids, max_new_tokens=48, do_sample=False, temperature=None,
                                 pad_token_id=tok.pad_token_id if tok.pad_token_id is not None else tok.eos_token_id)
        raw = tok.decode(out[0][ids.shape[1]:], skip_special_tokens=True)
        rows.append({"id": p["id"], "output": raw})
        print(f"[pc] {p['id']}: {raw.strip()[:80]}", flush=True)
    with open(os.path.join(OUT, "pc_out.jsonl"), "w", encoding="utf-8") as f:
        for r in rows:
            f.write(json.dumps(r, ensure_ascii=False) + "\n")
    print("pc done ->", os.path.join(OUT, "pc_out.jsonl"))

def compare(pc_f, dev_f):
    golds = {r["sample_id"]: EV_MAP.get(r["event_type"]) for r in (json.loads(l) for l in open(DEV, encoding="utf-8"))}
    pc = {r["id"]: r["output"] for r in (json.loads(l) for l in open(pc_f, encoding="utf-8"))}
    dev = {r["id"]: r["output"] for r in (json.loads(l) for l in open(dev_f, encoding="utf-8"))}
    n = v_ok = e_ok = e_cont = 0
    fails = []
    for rid in sorted(pc.keys() & dev.keys()):
        n += 1
        pv, pe = parse_event(pc[rid])
        dv, de = parse_event(dev[rid])
        if pv == dv:
            v_ok += 1
        if pe is not None and de is not None:
            if pe == de:
                e_ok += 1
            if pe in de or de in pe:
                e_cont += 1
        if pv != dv or (pe or "") != (de or ""):
            fails.append({"id": rid, "gold": golds.get(rid), "pc": pc[rid].strip()[:120], "dev": dev[rid].strip()[:120]})
    print(f"== compare n={n}")
    print(f"V exact: {v_ok}/{n} = {v_ok/n*100:.1f}%")
    print(f"E exact: {e_ok}/{n} = {e_ok/n*100:.1f}%  (contain: {e_cont}/{n})")
    for f in fails[:10]:
        print("DIFF", json.dumps(f, ensure_ascii=False))

def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--mode", required=True, choices=["subset", "pc", "compare"])
    ap.add_argument("--pc", default=os.path.join(OUT, "pc_out.jsonl"))
    ap.add_argument("--dev", default=os.path.join(OUT, "dev_out.jsonl"))
    a = ap.parse_args()
    if a.mode == "subset":
        subset()
    elif a.mode == "pc":
        pc()
    else:
        compare(a.pc, a.dev)

if __name__ == "__main__":
    main()
