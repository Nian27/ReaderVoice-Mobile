# -*- coding: utf-8 -*-
"""TASK-095B.2 — Mention Discovery 三路评估（Rule vs 2B post vs 4B Teacher）。

指标（每 text）：
- Mention Recall：gold surface（exact）∈ 提取集
- Mention Precision：提取 surface 中属于 gold 的比例（宽松：gold 包含提取 或 提取包含 gold）
- Exact-span validity：AI 输出的 surface 是否逐字存在于原文（造词检查）
- 分域报告

用法:
  python inference/095b2_eval_mention.py --rule
  python inference/095b2_eval_mention.py --model qwen3.5-2b [--resume]
  python inference/095b2_eval_mention.py --model qwen3.5-4b
"""
import argparse
import json
import os
import re
import sys

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
from importlib import import_module

ROOT = os.path.abspath(os.path.join(os.path.dirname(__file__), "..", "..", ".."))
OUT = os.path.join(ROOT, "training", "readerdirector", "runs", "task095")
MODEL_DIR = os.path.join(ROOT, "training", "models")

PROMPT = (
    "你是 ReaderDirector。找出下面文本中所有\u201c人物/可发声实体\u201d的提及（mention）："
    "人物、群体、职位/称谓、昵称、非人智能体等。\n"
    "规则：\n"
    "- 只输出原文中逐字存在的片段（surface），不要创造、改写、省略或翻译名字\n"
    "- 叙述中的主语（\u201cX做了某事\u201d）也是 mention\n"
    "- 群体（众家丁/将士们/孩子们）也是 mention\n"
    "- 长名字和完整称号必须整体复制，不要截断（如\u201c亚历山大\u00b7谢尔盖耶维奇\u00b7普希金\u201d\u201c守夜人总司令琼恩\u00b7雪诺\u201d）\n"
    "- 每个 mention 输出 surface 和 type（PERSON/GROUP/ROLE/AGENT）\n"
    "- 没有则输出空列表\n"
    '输出 JSON: {"mentions": [{"surface": "...", "type": "..."}]}\n\n'
    "示例1：\n文本：雪千寻递过去一把精致的火铳，叶洋在一旁看着。\n"
    '输出：{"mentions": [{"surface": "雪千寻", "type": "PERSON"}, {"surface": "叶洋", "type": "PERSON"}]}\n\n'
    "示例2：\n文本：众家丁退下，只留下了护院首领李四，他沉声道：\u201c都下去吧。\u201d\n"
    '输出：{"mentions": [{"surface": "众家丁", "type": "GROUP"}, {"surface": "李四", "type": "PERSON"}]}\n\n'
    "示例3：\n文本：亚历山大\u00b7谢尔盖耶维奇\u00b7普希金提笔写道：\u201c致大海。\u201d\n"
    '输出：{"mentions": [{"surface": "亚历山大\u00b7谢尔盖耶维奇\u00b7普希金", "type": "PERSON"}]}\n\n'
    "文本：\n{text}"
)


def load_challenge():
    return [json.loads(l) for l in open(os.path.join(OUT, "challenge_mentions_150.jsonl"), encoding="utf-8")]


# ---------------- Rule baseline ----------------
def rule_extract(text):
    m = import_module("095b2_lexicon")
    out = set()
    for s in m.bounded_cue_subjects(text):
        out.add(s)
    for s in m.vocative_names(text):
        out.add(s)
    for s in m.self_intro_names(text):
        out.add(s)
    # 叙述块首 2-4 字（近似）
    for blk in re.split(r"[，。！？；：、\s“”\"《》]", text):
        b = blk.strip()
        if re.match(r"^[\u4e00-\u9fa5]{2,4}$", b):
            out.add(b)
        elif len(b) > 4:
            for L in (4, 3, 2):
                if re.match(r"^[\u4e00-\u9fa5]{%d}$" % L, b[:L]):
                    out.add(b[:L])
                    break
    return out


# ---------------- AI extraction ----------------
def run(model, tok, prompt, max_new_tokens=200):
    msgs = [{"role": "user", "content": prompt}]
    try:
        text = tok.apply_chat_template(msgs, tokenize=False, add_generation_prompt=True, enable_thinking=False)
    except Exception:
        text = tok.apply_chat_template(msgs, tokenize=False, add_generation_prompt=True)
    ids = tok(text, return_tensors="pt").input_ids.to(model.device)
    out = model.generate(ids, max_new_tokens=max_new_tokens, do_sample=False, temperature=None,
                         pad_token_id=tok.pad_token_id if tok.pad_token_id is not None else tok.eos_token_id)
    return tok.decode(out[0][ids.shape[1]:], skip_special_tokens=True)


def parse_mentions(raw):
    raw = raw.strip()
    try:
        obj = json.loads(raw)
        return [x.get("surface", "") for x in (obj.get("mentions") or []) if x.get("surface")]
    except Exception:
        m = re.search(r"\{.*\}", raw, re.S)
        if not m:
            return []
        try:
            obj = json.loads(m.group(0))
            return [x.get("surface", "") for x in (obj.get("mentions") or []) if x.get("surface")]
        except Exception:
            return []


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--rule", action="store_true")
    ap.add_argument("--model", default=None, help="qwen3.5-2b / qwen3.5-4b")
    ap.add_argument("--resume", action="store_true")
    ap.add_argument("--adapters", default=None, help="LoRA checkpoint dir to load on top of --model")
    args = ap.parse_args()

    cases = load_challenge()

    if args.rule:
        total_gold = 0
        total_hit = 0
        total_pred = 0
        total_correct_pred = 0
        by_domain = {}
        for c in cases:
            pred = rule_extract(c["text"])
            gold = set(c["mentions"])
            hit = sum(1 for g in gold if g in pred)
            correct = sum(1 for p in pred if p in gold)
            total_gold += len(gold)
            total_hit += hit
            total_pred += len(pred)
            total_correct_pred += correct
            d = by_domain.setdefault(c["domain"], [0, 0, 0])
            d[0] += len(gold); d[1] += hit
        print("=== Rule baseline ===")
        print(f"Mention Recall: {total_hit}/{total_gold} = {total_hit/total_gold*100:.1f}%")
        print(f"Mention Precision: {total_correct_pred}/{total_pred} = {total_correct_pred/total_pred*100:.1f}%")
        print("by domain (recall):")
        for d, (g, h, _) in sorted(by_domain.items()):
            print(f"  {d:<22}: {h}/{g} = {h/g*100:.0f}%")
        return

    # AI 路径
    from transformers import AutoModelForCausalLM, AutoTokenizer
    model_name = args.model
    tok = AutoTokenizer.from_pretrained(os.path.join(MODEL_DIR, model_name), trust_remote_code=True)
    model = AutoModelForCausalLM.from_pretrained(
        os.path.join(MODEL_DIR, model_name), trust_remote_code=True,
        torch_dtype="auto", device_map="auto")
    print(f"{model_name} loaded", flush=True)
    tag = model_name
    if args.adapters:
        from peft import LoraConfig, get_peft_model
        from safetensors.torch import load_file
        cfg = json.load(open(os.path.join(args.adapters, "adapter_config.json"), encoding="utf-8"))
        sd = load_file(os.path.join(args.adapters, "adapter_model.safetensors"))
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
        tag = model_name + "_lora"
        print(f"LoRA adapters loaded: {args.adapters}", flush=True)

    res_path = os.path.join(OUT, f"mention_{tag}_fs_results.jsonl")
    done_ids = set()
    if args.resume and os.path.exists(res_path):
        for l in open(res_path, encoding="utf-8"):
            try:
                done_ids.add(json.loads(l)["id"])
            except Exception:
                pass
        print(f"resume: {len(done_ids)} done", flush=True)

    n = 0
    for c in cases:
        if args.resume and c["id"] in done_ids:
            continue
        raw = run(model, tok, PROMPT.replace("{text}", c["text"]))
        surfaces = parse_mentions(raw)
        rec = {"id": c["id"], "domain": c["domain"], "text": c["text"],
               "gold": c["mentions"], "pred": surfaces, "raw": raw[:300]}
        with open(res_path, "a", encoding="utf-8") as f:
            f.write(json.dumps(rec, ensure_ascii=False) + "\n")
        n += 1
        if n % 10 == 0:
            print(f"  {n} done", flush=True)
    print(f"done {n}, saved {res_path}")


if __name__ == "__main__":
    main()