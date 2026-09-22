# -*- coding: utf-8 -*-
"""TASK-095B.3 — 0.8B MENTION LoRA 评估（challenge_mentions_150 frozen set）。

用 swift 训练链加载（SftArguments.get_model_processor + SwiftSft.prepare_model，
swift tuner 才能匹配 model.language_model.* target_modules——裸 PeftModel 不行）。

指标（对照 095B.2 三路）：
- Mention Recall exact：gold surface ∈ pred
- Mention Recall contain：gold ∈ pred 或 pred ∈ gold（宽松）
- Mention Precision（宽松）
- Exact-span validity：pred surface 逐字存在于原文（造词检查）
- 分域报告

用法:
  python inference/095b3_eval_lora.py --adapters <ckpt_dir> [--resume]
"""
import argparse
import json
import os
import re

ROOT = os.path.abspath(os.path.join(os.path.dirname(__file__), "..", "..", ".."))
OUT = os.path.join(ROOT, "training", "readerdirector", "runs", "task095")
MODEL_DIR = os.path.join(ROOT, "training", "models")

PROMPT = (
    "你是 ReaderDirector。找出下面文本中所有“人物/可发声实体”的提及（mention）："
    "人物、群体、职位/称谓、昵称、非人智能体等。\n"
    "规则：\n"
    "- 只输出原文中逐字存在的片段（surface），不要创造、改写、省略或翻译名字\n"
    "- 叙述中的主语（“X做了某事”）也是 mention\n"
    "- 群体（众家丁/将士们/孩子们）也是 mention\n"
    "- 长名字和完整称号必须整体复制，不要截断（如“亚历山大·谢尔盖耶维奇·普希金”“守夜人总司令琼恩·雪诺”）\n"
    "- 每个 mention 输出 surface 和 type（PERSON/GROUP/ROLE/AGENT）\n"
    "- 没有则输出空列表\n"
    '输出 JSON: {"mentions": [{"surface": "...", "type": "..."}]}\n\n'
    "示例1：\n文本：雪千寻递过去一把精致的火铳，叶洋在一旁看着。\n"
    '输出：{"mentions": [{"surface": "雪千寻", "type": "PERSON"}, {"surface": "叶洋", "type": "PERSON"}]}\n\n'
    "示例2：\n文本：众家丁退下，只留下了护院首领李四，他沉声道：“都下去吧。”\n"
    '输出：{"mentions": [{"surface": "众家丁", "type": "GROUP"}, {"surface": "李四", "type": "PERSON"}]}\n\n'
    "示例3：\n文本：亚历山大·谢尔盖耶维奇·普希金提笔写道：“致大海。”\n"
    '输出：{"mentions": [{"surface": "亚历山大·谢尔盖耶维奇·普希金", "type": "PERSON"}]}\n\n'
    "文本：\n{text}"
)


def load_challenge():
    return [json.loads(l) for l in open(os.path.join(OUT, "challenge_mentions_150.jsonl"), encoding="utf-8")]


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


def load_model_with_adapter(adapters):
    from swift.arguments import SftArguments
    from swift.pipelines.train import SwiftSft

    args = SftArguments(
        model=os.path.join(MODEL_DIR, "qwen3.5-0.8b-base"),
        model_type="qwen3_5",
        torch_dtype="bfloat16",
        tuner_type="lora",
        adapters=[adapters],
        dataset=["dummy_eval_placeholder.jsonl"],  # 仅绕过 __post_init__ 非空校验；评估不加载数据集
    )
    model, processor = args.get_model_processor()
    model = SwiftSft.prepare_model(args, model)
    model.eval()
    # qwen3.5 processor 是多模态（vision），tok(text) 会把文本当图像解码而崩溃；
    # 评估只用纯文本 tokenizer（与 095b2 的 AutoTokenizer 路径一致）
    return model, processor.tokenizer


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


def report(results, tag):
    total_gold = total_hit_exact = total_hit_contain = 0
    total_pred = total_pred_valid = 0
    by_domain = {}
    for r in results:
        gold = set(r["gold"])
        pred = set(r["pred"])
        total_gold += len(gold)
        total_hit_exact += sum(1 for g in gold if g in pred)
        total_hit_contain += sum(1 for g in gold if any(g in p or p in g for p in pred))
        total_pred += len(pred)
        total_pred_valid += sum(1 for p in pred if p in r["text"])
        d = by_domain.setdefault(r["domain"], [0, 0, 0])
        d[0] += len(gold)
        d[1] += sum(1 for g in gold if g in pred)
    print(f"=== {tag} ===")
    print(f"Mention Recall exact:   {total_hit_exact}/{total_gold} = {total_hit_exact/total_gold*100:.1f}%")
    print(f"Mention Recall contain: {total_hit_contain}/{total_gold} = {total_hit_contain/total_gold*100:.1f}%")
    print(f"Mention Precision(cont): {total_hit_contain}/{total_pred} = {total_hit_contain/total_pred*100:.1f}%")
    print(f"Exact-span validity:    {total_pred_valid}/{total_pred} = {total_pred_valid/total_pred*100:.1f}%")
    print("by domain (exact recall):")
    for d, (g, h, _) in sorted(by_domain.items()):
        print(f"  {d:<22}: {h}/{g} = {h/g*100:.0f}%")
    print(flush=True)


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--adapters", required=True, help="LoRA checkpoint dir (swift format)")
    ap.add_argument("--resume", action="store_true")
    args = ap.parse_args()

    tag = "qwen3.5-0.8b-base_lora" + re.sub(r"[^0-9A-Za-z]", "", os.path.basename(args.adapters.rstrip("/\\")))
    res_path = os.path.join(OUT, f"mention_{tag}_fs_results.jsonl")

    model, processor = load_model_with_adapter(args.adapters)
    print(f"loaded base + adapters: {args.adapters} (processor.tokenizer)", flush=True)

    cases = load_challenge()
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
        raw = run(model, processor, PROMPT.replace("{text}", c["text"]))
        rec = {"id": c["id"], "domain": c["domain"], "text": c["text"],
               "gold": c["mentions"], "pred": parse_mentions(raw), "raw": raw[:300]}
        with open(res_path, "a", encoding="utf-8") as f:
            f.write(json.dumps(rec, ensure_ascii=False) + "\n")
        n += 1
        if n % 10 == 0:
            print(f"  {n} done", flush=True)
    print(f"done {n}, saved {res_path}", flush=True)

    results = [json.loads(l) for l in open(res_path, encoding="utf-8")]
    report(results, tag)


if __name__ == "__main__":
    main()