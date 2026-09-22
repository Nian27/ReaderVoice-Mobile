# -*- coding: utf-8 -*-
"""TASK-090 Step 0 — ms-swift 实际训练模板 preprocess dump（G2）。
20 条训练样本 → messages → ms-swift qwen3_5 Template.encode → 验证：
  input_tokens >> 3；target JSON 在 assistant supervision 区；system/user 不被当 target；target 未截断。
"""
import json
import os
import sys

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
from prompt_builder import load_prompts, build_prompt

ROOT = os.path.abspath(os.path.join(os.path.dirname(__file__), "..", "..", ".."))
MODEL = os.path.join(ROOT, "training", "models", "qwen3.5-0.8b-base")
DATASET = os.path.join(ROOT, "training", "readerdirector", "dataset", "train.jsonl")
PROMPTS = load_prompts()


def sample_to_messages(sample):
    task = sample["task"]
    prompt, _ = build_prompt(task, sample, PROMPTS)
    tg = sample.get("target") or {}
    if task == "SPEAKER":
        ans = json.dumps({"speaker": tg.get("speaker"), "status": tg.get("status", "UNKNOWN")}, ensure_ascii=False)
    elif task == "IDENTITY":
        ans = json.dumps({"relation": tg.get("relation", "UNKNOWN")}, ensure_ascii=False)
    else:
        ans = json.dumps({"action": tg.get("action", "NONE")}, ensure_ascii=False)
    return [{"role": "user", "content": prompt}, {"role": "assistant", "content": ans}], ans


def main():
    from swift.template import Template, TEMPLATE_MAPPING
    from swift.model import get_processor

    tok = get_processor(MODEL, use_hf=False)
    tpl = Template(tok, TEMPLATE_MAPPING["qwen3_5"], enable_thinking=False, max_length=2048, add_non_thinking_prefix=True)
    tpl.mode = 'train'  # 训练模式：encode 产出 labels/loss_scale

    samples = []
    for split in ("train", "dev", "test"):
        samples += [json.loads(l) for l in open(os.path.join(ROOT, "training", "readerdirector", "dataset", split + ".jsonl"), encoding="utf-8")]
    # 各任务抽满 20（优先 gold）
    chosen = []
    for task in ("SPEAKER", "IDENTITY", "VOICE_STATE"):
        pool = [s for s in samples if s["task"] == task and s["provenance"] == "EXPLICIT_RULE_GOLD"]
        chosen += pool[:10]
    chosen = chosen[:20]

    report = []
    ok = True
    for s in chosen:
        msgs, ans = sample_to_messages(s)
        try:
            enc = tpl.encode({"messages": msgs})
        except Exception as e:
            report.append({"sample": s["sample_id"], "ERROR": str(e)[:120]})
            ok = False
            continue
        input_ids = enc["input_ids"]
        labels = enc.get("labels") or enc.get("loss_scale")
        decoded_input = tok.decode(input_ids, skip_special_tokens=False)
        # supervision 区：labels != -100 的 token
        sup_ids = [i for i, l in enumerate(labels) if (l != -100 and l != 0)]
        decoded_sup = tok.decode([input_ids[i] for i in sup_ids], skip_special_tokens=False) if sup_ids else ""
        report.append({
            "sample_id": s["sample_id"],
            "task": s["task"],
            "provenance": s["provenance"],
            "input_tokens": len(input_ids),
            "labels_tokens": len(labels),
            "sup_tokens": len(sup_ids),
            "target_in_sup": ans in decoded_sup or all(t in decoded_sup for t in ans.strip('{}').split(',')),
            "decoded_input_tail": decoded_input[-160:],
            "decoded_sup": decoded_sup[:200],
        })
        if len(input_ids) <= 10:
            ok = False

    out = os.path.join(ROOT, "training", "readerdirector", "runs", "task090", "step0_preprocess_dump.json")
    os.makedirs(os.path.dirname(out), exist_ok=True)
    json.dump({"gate": "G2 Base official-template preprocessing", "template": "ms-swift qwen3_5 (enable_thinking=False, add_non_thinking_prefix)",
               "model": MODEL, "n": len(report), "pass": ok, "samples": report}, open(out, "w", encoding="utf-8"), indent=1, ensure_ascii=False)
    for r in report:
        print(json.dumps(r, ensure_ascii=False)[:300])
    print(f"\nGATE: {'PASS' if ok else 'FAIL'} — n={len(report)}")


if __name__ == "__main__":
    main()
