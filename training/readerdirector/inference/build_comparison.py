# -*- coding: utf-8 -*-
"""TASK-090 最终对比表（spec §final table）：Rule / 0.8B post / 2B post / Base valid / LoRA r8/r16/r32。
从各 metrics.json + eval/*.json 汇总 → runs/task090/comparison.json + markdown 表。
"""
import json
import os

ROOT = os.path.abspath(os.path.join(os.path.dirname(__file__), "..", "..", ".."))
RUNS = os.path.join(ROOT, "training", "readerdirector", "runs", "task080")
RUNS90 = os.path.join(ROOT, "training", "readerdirector", "runs", "task090")


def load(path):
    if os.path.exists(path):
        return json.load(open(path, encoding="utf-8"))
    return None


rows = {}

# Rule（TASK-080）
rule = load(os.path.join(RUNS, "rule_baseline", "metrics.json"))
rows["rule"] = {"speaker_acc": rule["speaker_gold_agreement"], "speaker_coverage": rule["speaker_gold_coverage"],
                "identity_acc": rule["identity_gold_agreement"], "voice_acc": rule["voice_state_gold_agreement"],
                "false_merge": 0, "strict_json": 1.0, "vram_gb": None, "note": "coverage 0.818（gold 子集）"}

# TASK-080 zero-shot + valid Base probe
for name, mdir in [("0.8b_post", "qwen3.5-0.8b"), ("2b_post", "qwen3.5-2b")]:
    met = load(os.path.join(RUNS, mdir, "run_A", "metrics.json"))
    perf = load(os.path.join(RUNS, mdir, "perf.json"))
    rows[name] = {"speaker_acc": met["SPEAKER"]["gold_accuracy"], "speaker_macro_f1": met["SPEAKER"]["gold_macro_f1"],
                  "identity_acc": met["IDENTITY"]["gold_accuracy"], "voice_acc": met["VOICE_STATE"]["gold_accuracy"],
                  "false_merge": met["IDENTITY"]["false_merge_rate"], "strict_json": met["SPEAKER"]["strict_valid"],
                  "cand_violation": met["SPEAKER"]["out_of_candidate"],
                  "vram_gb": round(perf["peak_vram_mb"] / 1024, 2), "note": "zero-shot, TASK-080"}

base = load(os.path.join(RUNS, "qwen3.5-0.8b-base", "run_A", "metrics.json"))
rows["base_valid_prelora"] = {"probe": True, "strict_json": base["probe_valid"],
                              "cand_violation": base["probe_out_of_candidate"],
                              "eos": base["probe_eos"], "avg_out_tokens": base["probe_avg_output_tokens"],
                              "note": "VALID probe (template fixed), dev 100 samples"}

# LoRA 三 rank：dev eval + TRUE_UNKNOWN probe
for r in (8, 16, 32):
    ckpt_dir = os.path.join(ROOT, "training", "readerdirector", "runs", "task090", f"ablation_r{r}")
    versions = sorted(os.listdir(ckpt_dir)) if os.path.isdir(ckpt_dir) else []
    ev = tu = None
    if versions:
        v = versions[-1]
        ckpt = os.path.join(ckpt_dir, v)
        # 找最后一个 checkpoint 目录
        cps = sorted([d for d in os.listdir(ckpt) if d.startswith("checkpoint")])
        if cps:
            cp = os.path.join(ckpt, cps[-1])
            name = f"ablation_r{r}"
            ev = load(os.path.join(RUNS90, "eval", f"{name}__dev.json"))
            tu = load(os.path.join(RUNS90, "eval", f"{name}__true_unknown.json"))
    rows[f"lora_r{r}"] = {
        "speaker_acc": ev.get("SPEAKER", {}).get("accuracy") if ev else None,
        "speaker_macro_f1": ev.get("SPEAKER", {}).get("macro_f1") if ev else None,
        "permutation_consistency": ev.get("permutation_consistency") if ev else None,
        "identity_acc": ev.get("IDENTITY", {}).get("accuracy") if ev else None,
        "false_merge": ev.get("false_merge") if ev else None,
        "hard_neg_violation": ev.get("hard_negative_violation") if ev else None,
        "voice_acc": ev.get("VOICE_STATE", {}).get("accuracy") if ev else None,
        "strict_json": ev.get("strict_valid") if ev else None,
        "cand_violation": ev.get("candidate_violation") if ev else None,
        "true_unknown_rate": tu.get("true_unknown_rate") if tu else None,
        "note": "dev eval" if ev else "NOT EVALED YET",
    }

out = os.path.join(RUNS90, "comparison.json")
json.dump(rows, open(out, "w", encoding="utf-8"), indent=1, ensure_ascii=False)
print(json.dumps(rows, indent=1, ensure_ascii=False))
