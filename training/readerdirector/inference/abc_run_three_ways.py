# -*- coding: utf-8 -*-
"""A/B/C 对比实验 Step 3：三路 4B 推理。

A: regex 候选（原 rule_candidates）→ 4B 选择
B: 实体候选（∩ 实体集）→ 4B 选择
C: 开放（角色历史参考，无候选约束）→ 4B 自由输出名字

增量落盘 + resume：每条写完立即 flush；--resume 跳过已完成的 sample_id。
用法: python inference/abc_run_three_ways.py [--resume]
"""
import argparse
import json
import os
import sys
import time

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
from prompt_builder import load_prompts
from parser import parse

ROOT = os.path.abspath(os.path.join(os.path.dirname(__file__), "..", "..", ".."))
RUNS = os.path.join(ROOT, "training", "readerdirector", "runs", "task100")
MODEL_DIR = os.path.join(ROOT, "training", "models", "qwen3.5-4b")
PROMPTS = load_prompts("v2")


def make_prompt(text, recent, post_cue, cands=None, history=None):
    recent_lines = "\n".join(f"- {t}" for t in (recent[-8:] or [])) or "-"
    post = f"AFTER_TARGET:\n{post_cue}" if post_cue else ""
    if cands is not None:
        cand_lines = "\n".join(f"- {c} (name={c}, distance=?)" for c in cands) or "-"
        tpl = (
            "You are ReaderDirector. Choose the speaker of TARGET.\n\n"
            "Rules:\n- Choose only from CANDIDATES or UNKNOWN.\n"
            "- Output the exact id from CANDIDATES, not the name.\n"
            "- Respect HARD_CONSTRAINTS.\n- Return JSON only.\n\n"
            "RECENT:\n{recent}\n\nTARGET:\n{target}\n{post_cue}\n\n"
            "CANDIDATES:\n{candidates}\n\nHARD_CONSTRAINTS:\n{constraints}\n\n"
            'Return: {{"speaker":"<exact candidate id>","status":"CONFIRMED"|"PROVISIONAL"|"UNKNOWN"}}'
        )
        return tpl.replace("{post_cue}", post).format(
            recent=recent_lines, target=text, candidates=cand_lines, constraints="-")
    hist = ", ".join(history[:40]) if history else "（未知）"
    return (
        "你是 ReaderDirector。判断 TARGET 对白的说话人。\n\n"
        f"RECENT:\n{recent_lines}\n\nTARGET:\n{text}\n{post}\n\n"
        f"本书角色参考（说话人应来自其中，或 UNKNOWN）:\n{hist}\n\n"
        '只输出 JSON，不要任何其他文字。Return: {"speaker":"<角色名或UNKNOWN>","status":"CONFIRMED"|"PROVISIONAL"|"UNKNOWN"}'
    )


def run(model, tok, prompt, max_new_tokens=20):
    msgs = [{"role": "user", "content": prompt}]
    try:
        text = tok.apply_chat_template(msgs, tokenize=False, add_generation_prompt=True, enable_thinking=False)
    except Exception:
        text = tok.apply_chat_template(msgs, tokenize=False, add_generation_prompt=True)
    ids = tok(text, return_tensors="pt").input_ids.to(model.device)
    out = model.generate(ids, max_new_tokens=max_new_tokens, do_sample=False, temperature=None,
                         pad_token_id=tok.pad_token_id if tok.pad_token_id is not None else tok.eos_token_id)
    return tok.decode(out[0][ids.shape[1]:], skip_special_tokens=True)


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--resume", action="store_true")
    ap.add_argument("--cases", default="abc_cases_200.jsonl", help="输入 cases 文件")
    ap.add_argument("--skip_c", action="store_true", help="跳过 C（开放生成已否决，095A 只跑 A/B）")
    args = ap.parse_args()

    from transformers import AutoModelForCausalLM, AutoTokenizer
    tok = AutoTokenizer.from_pretrained(MODEL_DIR, trust_remote_code=True)
    model = AutoModelForCausalLM.from_pretrained(
        MODEL_DIR, trust_remote_code=True, torch_dtype="auto", device_map="auto")
    print("4B loaded", flush=True)

    cases = [json.loads(l) for l in open(os.path.join(RUNS, args.cases), encoding="utf-8")]
    results_path = os.path.join(RUNS, "abc_results_v2.jsonl")
    done_ids = set()
    if args.resume and os.path.exists(results_path):
        for l in open(results_path, encoding="utf-8"):
            try:
                done_ids.add(json.loads(l)["sample_id"])
            except Exception:
                pass
        print(f"resume: {len(done_ids)} done", flush=True)

    t0 = time.perf_counter()
    n = 0
    for c in cases:
        if args.resume and c["sample_id"] in done_ids:
            continue
        text = c["text"]
        recent = c["recent_context"]
        post = c.get("post_cue")
        cands_a, cands_b = c["cands_a"], c["cands_b"]
        hist = c["char_history_c"]
        # A
        raw_a = run(model, tok, make_prompt(text, recent, post, cands=cands_a))
        obj_a, v_a = parse(raw_a, "SPEAKER", candidates=cands_a)
        c["a_answer"] = obj_a.get("speaker") if obj_a else None
        c["a_valid"] = v_a
        # B
        raw_b = run(model, tok, make_prompt(text, recent, post, cands=cands_b))
        obj_b, v_b = parse(raw_b, "SPEAKER", candidates=cands_b)
        c["b_answer"] = obj_b.get("speaker") if obj_b else None
        c["b_valid"] = v_b
        # C（--skip_c 时跳过；已否决，仅作对照保留）
        if not args.skip_c:
            raw_c = run(model, tok, make_prompt(text, recent, post, cands=None, history=hist))
            obj_c, _ = parse(raw_c, "SPEAKER", candidates=None)
            c["c_answer"] = obj_c.get("speaker") if obj_c else None
            c["c_valid"] = _ if isinstance(_, str) else "STRICT"
        n += 1
        with open(results_path, "a", encoding="utf-8") as f:
            f.write(json.dumps(c, ensure_ascii=False) + "\n")
        if n % 10 == 0:
            print(f"  {n + len(done_ids)}/200 ({time.perf_counter()-t0:.0f}s)", flush=True)
    print(f"done {n + len(done_ids)}/200, saved {results_path}", flush=True)


if __name__ == "__main__":
    main()
