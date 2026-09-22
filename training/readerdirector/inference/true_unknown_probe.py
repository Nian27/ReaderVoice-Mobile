# -*- coding: utf-8 -*-
"""TASK-090 G11 — TRUE_UNKNOWN 独立指标 probe。
构造 50 条确实不可判定样本（无 cue/无 turn/无 lock），要求模型输出 UNKNOWN。
报告：true_unknown_rate（正确 UNKNOWN 比例）+ 强猜分布。
用法: python true_unknown_probe.py --checkpoint <dir>
"""
import argparse
import json
import os
import sys

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
from prompt_builder import build_prompt, load_prompts
from parser import parse
from collections import Counter

ROOT = os.path.abspath(os.path.join(os.path.dirname(__file__), "..", "..", ".."))
RUNS = os.path.join(ROOT, "training", "readerdirector", "runs", "task090")
PROMPTS = load_prompts("v2")

NEUTRAL_CTX = ["夜已深，灯下两个人影。", "山道上，一行人沉默前行。", "屋内炉火正旺。", "巷口的风吹过。", "桌边安静下来。"]
NEUTRAL_LINES = ["“走吧。”", "“嗯。”", "“知道了。”", "“行。”", "“好吧。”", "“别说了。”", "“过来。”", "“小心！”", "“没事。”", "“再说吧。”"]
NAMES = ["张三", "李四", "王五", "赵六", "钱七", "孙八"]


def build_probes(n=50, seed=7):
    import random
    rng = random.Random(seed)
    out = []
    for i in range(n):
        k = rng.randint(2, 4)
        cands = [{"role": f"uid-{nm}", "name": nm, "distance": d + 1} for d, nm in enumerate(rng.sample(NAMES, k))]
        out.append({"input": {
            "text": NEUTRAL_LINES[i % len(NEUTRAL_LINES)],
            "segment_type": "speech",
            "recent_context": [NEUTRAL_CTX[i % len(NEUTRAL_CTX)]],
            "rule_candidates": cands,
            "identity_constraints": [], "embodiment": [], "overrides": [],
        }})
    return out


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--checkpoint", required=True)
    ap.add_argument("--name", default=None)
    args = ap.parse_args()

    from transformers import AutoModelForCausalLM, AutoTokenizer
    from peft import LoraConfig, get_peft_model
    from safetensors.torch import load_file

    base = os.path.join(ROOT, "training", "models", "qwen3.5-0.8b-base")
    tok = AutoTokenizer.from_pretrained(base, trust_remote_code=True)
    model = AutoModelForCausalLM.from_pretrained(base, trust_remote_code=True, torch_dtype="auto", device_map="auto")
    cfg = json.load(open(os.path.join(args.checkpoint, "adapter_config.json"), encoding="utf-8"))
    sd = load_file(os.path.join(args.checkpoint, "adapter_model.safetensors"))
    remapped = {
        k.replace(".model.language_model.layers.", ".model.layers.")
         .replace(".lora_A.weight", ".lora_A.default.weight")
         .replace(".lora_B.weight", ".lora_B.default.weight")
        : v for k, v in sd.items()
    }
    model = get_peft_model(model, LoraConfig(r=cfg["r"], lora_alpha=cfg["lora_alpha"], target_modules="all-linear"))
    missing, unexpected = model.load_state_dict(remapped, strict=False)
    model.eval()

    probes = build_probes()
    correct = 0
    guesses = Counter()
    for p in probes:
        prompt, _ = build_prompt("SPEAKER", p, PROMPTS)
        msgs = [{"role": "user", "content": prompt}]
        try:
            text = tok.apply_chat_template(msgs, tokenize=False, add_generation_prompt=True, enable_thinking=False)
        except Exception:
            text = tok.apply_chat_template(msgs, tokenize=False, add_generation_prompt=True)
        ids = tok(text, return_tensors="pt").input_ids.to(model.device)
        out = model.generate(ids, max_new_tokens=32, do_sample=False, temperature=None,
                             pad_token_id=tok.pad_token_id if tok.pad_token_id is not None else tok.eos_token_id)
        raw = tok.decode(out[0][ids.shape[1]:], skip_special_tokens=True)
        cands = [c["role"] for c in p["input"]["rule_candidates"]]
        obj, verdict = parse(raw, "SPEAKER", candidates=cands)
        pred = obj.get("speaker") if obj else None
        if pred == "UNKNOWN":
            correct += 1
        guesses[str(pred)[:20]] += 1
    m = {
        "checkpoint": args.checkpoint,
        "probes": len(probes),
        "true_unknown_rate": correct / len(probes),
        "guesses": dict(guesses.most_common(6)),
    }
    name = args.name or os.path.basename(os.path.normpath(args.checkpoint))
    out = os.path.join(RUNS, "eval", f"{name}__true_unknown.json")
    json.dump(m, open(out, "w", encoding="utf-8"), indent=1, ensure_ascii=False)
    print(json.dumps(m, indent=1, ensure_ascii=False))


if __name__ == "__main__":
    main()
