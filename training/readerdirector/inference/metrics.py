# -*- coding: utf-8 -*-
"""TASK-080 metrics：Speaker/Identity/VoiceState + 协议系统指标（§25-§29）。"""
from collections import Counter


def _f1(tp, fp, fn):
    if tp + fp == 0 or tp + fn == 0:
        return 0.0
    p = tp / (tp + fp)
    r = tp / (tp + fn)
    return 2 * p * r / (p + r) if (p + r) > 0 else 0.0


def macro_f1(conf: Counter, labels):
    scores = []
    for lbl in labels:
        tp = conf.get((lbl, lbl), 0)
        fp = sum(v for (g, p), v in conf.items() if p == lbl and g != lbl)
        fn = sum(v for (g, p), v in conf.items() if g == lbl and p != lbl)
        scores.append(_f1(tp, fp, fn))
    return sum(scores) / len(scores) if scores else 0.0


def evaluate(records):
    """records: list of dict {task, gold, pred, pred_raw, verdict, candidates, extra_text, loop, eos, ...}"""
    out = {}
    n = len(records)
    out["n_samples"] = n
    out["strict_valid"] = sum(1 for r in records if r["verdict"] == "STRICT") / max(n, 1)
    out["repairable_valid"] = sum(1 for r in records if r["verdict"] in ("STRICT", "REPAIRABLE")) / max(n, 1)
    out["out_of_candidate"] = sum(1 for r in records if r["verdict"] == "OUT_OF_CANDIDATE_PROTOCOL_ERROR") / max(n, 1)
    out["extra_text_rate"] = sum(1 for r in records if r.get("extra_text")) / max(n, 1)
    out["loop_rate"] = sum(1 for r in records if r.get("loop")) / max(n, 1)
    out["eos_success"] = sum(1 for r in records if r.get("eos")) / max(n, 1)

    golds = [r for r in records if r.get("gold") is not None]
    g = len(golds)
    out["gold_subset"] = g
    if g:
        out["gold_accuracy"] = sum(1 for r in golds if r["pred"] == r["gold"]) / g
        conf = Counter((r["gold"], r["pred"]) for r in golds)
        labels = sorted({r["gold"] for r in golds} | {p for r in golds if (p := r["pred"]) is not None})
        out["gold_macro_f1"] = macro_f1(conf, labels)
        # UNKNOWN recall：gold=UNKNOWN 且 pred=UNKNOWN
        uk = [r for r in golds if r["gold"] == "UNKNOWN"]
        if uk:
            out["unknown_recall"] = sum(1 for r in uk if r["pred"] == "UNKNOWN") / len(uk)
        else:
            out["unknown_recall"] = None
    return out


def speaker_metrics(records):
    m = evaluate(records)
    golds = [r for r in records if r.get("gold") is not None]
    g = len(golds) or 1
    # Candidate Violation Rate：gold 在候选内但 pred 出候选 / 不可解析
    m["candidate_violation_rate"] = sum(
        1 for r in records
        if r["verdict"] == "OUT_OF_CANDIDATE_PROTOCOL_ERROR" or (r.get("pred") and r["pred"] != "UNKNOWN" and r["candidates"] and r["pred"] not in r["candidates"])
    ) / len(records)
    # Auto-confirm Precision：pred CONFIRMED 且正确
    conf_preds = [r for r in golds if r["pred"] == "CONFIRMED" and r.get("pred_status") == "CONFIRMED"]
    if conf_preds:
        m["auto_confirm_precision"] = sum(1 for r in conf_preds if r["pred"] == r["gold"]) / len(conf_preds)
    # Coverage：gold 子集占全部
    m["gold_coverage"] = sum(1 for r in records if r.get("gold") is not None) / len(records)
    # 校准（诚实标注）：无 gold 样本上模型的 UNKNOWN 率
    ungolded = [r for r in records if r.get("gold") is None]
    if ungolded:
        m["unknown_on_ungolded"] = sum(1 for r in ungolded if r["pred"] == "UNKNOWN") / len(ungolded)
    return m


def identity_metrics(records):
    m = evaluate(records)
    golds = [r for r in records if r.get("gold") is not None]
    # False Merge（一级错误）：gold=DIFFERENT pred=SAME；False Split：gold=SAME pred=DIFFERENT
    fm = sum(1 for r in golds if r["gold"] == "DIFFERENT" and r["pred"] == "SAME")
    fs = sum(1 for r in golds if r["gold"] == "SAME" and r["pred"] == "DIFFERENT")
    m["false_merge_rate"] = fm / max(len([r for r in golds if r["gold"] == "DIFFERENT"]), 1)
    m["false_split_rate"] = fs / max(len([r for r in golds if r["gold"] == "SAME"]), 1)
    m["weighted_identity_error"] = 5 * fm + 1 * fs
    # Hard-negative violation：ENTITY_NEQ（hard_block）却预测 SAME
    hn = [r for r in golds if r.get("hard_block")]
    m["hard_negative_violation"] = sum(1 for r in hn if r["pred"] == "SAME") / max(len(hn), 1)
    return m


def voice_metrics(records):
    m = evaluate(records)
    golds = [r for r in records if r.get("gold") is not None]
    for act in ("START", "CONTINUE", "REPLACE", "END", "NONE"):
        tp = sum(1 for r in golds if r["gold"] == act and r["pred"] == act)
        fp = sum(1 for r in golds if r["gold"] != act and r["pred"] == act)
        fn = sum(1 for r in golds if r["gold"] == act and r["pred"] != act)
        m[f"{act}_f1"] = _f1(tp, fp, fn)
    return m
