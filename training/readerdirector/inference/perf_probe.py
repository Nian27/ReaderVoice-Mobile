# -*- coding: utf-8 -*-
"""TASK-080 G13 perf probe：load_s / tokens/s / peak VRAM / p50/p95 latency（DESKTOP_MODEL_BASELINE）。"""
import argparse
import json
import os
import sys
import time

REPO_ROOT = os.path.abspath(os.path.join(os.path.dirname(__file__), "..", "..", ".."))
sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
from prompt_builder import load_prompts, build_prompt

PROMPTS = load_prompts()


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--model_dir", required=True)
    ap.add_argument("--model_name", required=True)
    args = ap.parse_args()

    from transformers import AutoModelForCausalLM, AutoTokenizer
    t0 = time.perf_counter()
    tok = AutoTokenizer.from_pretrained(args.model_dir, trust_remote_code=True)
    model = AutoModelForCausalLM.from_pretrained(args.model_dir, trust_remote_code=True, torch_dtype="auto", device_map="auto")
    model.eval()
    load_s = time.perf_counter() - t0
    torch = __import__("torch")

    samples = [json.loads(l) for l in open(os.path.join(REPO_ROOT, "training", "readerdirector", "dataset", "test.jsonl"), encoding="utf-8")][:20]
    lat = []
    n_tok_in = n_tok_out = 0
    t_gen0 = time.perf_counter()
    for s in samples:
        prompt, _ = build_prompt(s["task"], s, PROMPTS)
        msgs = [{"role": "user", "content": prompt}]
        try:
            text = tok.apply_chat_template(msgs, tokenize=False, add_generation_prompt=True, enable_thinking=False)
        except Exception:
            text = tok.apply_chat_template(msgs, tokenize=False, add_generation_prompt=True)
        ids = tok(text, return_tensors="pt").input_ids.to(model.device)
        n_tok_in += ids.shape[1]
        t0 = time.perf_counter()
        with torch.inference_mode():
            out = model.generate(ids, max_new_tokens=32, do_sample=False, temperature=None,
                                 pad_token_id=tok.pad_token_id if tok.pad_token_id is not None else tok.eos_token_id)
        lat.append((time.perf_counter() - t0) * 1000)
        n_tok_out += out.shape[1] - ids.shape[1]
    gen_s = time.perf_counter() - t_gen0
    lat.sort()
    peak_mb = torch.cuda.max_memory_allocated() / 1048576
    perf = {
        "model": args.model_name,
        "mode": "DESKTOP_MODEL_BASELINE (RTX 4060 Ti 16GB, fp16, transformers 5.12.1, torch 2.11.0+cu128)",
        "load_s": round(load_s, 1),
        "samples": len(samples),
        "prompt_tokens_s": round(n_tok_in / gen_s, 1),
        "generation_tokens_s": round(n_tok_out / gen_s, 1),
        "median_latency_ms": round(lat[len(lat) // 2], 1),
        "p95_latency_ms": round(lat[int(len(lat) * 0.95)], 1),
        "peak_vram_mb": round(peak_mb, 0),
        "avg_input_tokens": round(n_tok_in / len(samples), 0),
        "avg_output_tokens": round(n_tok_out / len(samples), 0),
    }
    print(json.dumps(perf, indent=1, ensure_ascii=False))
    out_dir = os.path.join(REPO_ROOT, "training", "readerdirector", "runs", "task080", args.model_name)
    os.makedirs(out_dir, exist_ok=True)
    json.dump(perf, open(os.path.join(out_dir, "perf.json"), "w"), indent=1)


if __name__ == "__main__":
    main()
