# -*- coding: utf-8 -*-
"""A/B/C 对比实验 Step 4：人工标注表导出（含三路预测 + 待标 ground truth）。

输出 runs/task100/abc_human_label_200.md（人类可读）+ .jsonl（机器可读）。
标注格式：
  | 条目 | 内容 |
  human_speaker 列填：真说话人名字或 UNKNOWN（对照 TARGET 对白与 RECENT 上下文）。
"""
import json
import os

ROOT = os.path.abspath(os.path.join(os.path.dirname(__file__), "..", "..", ".."))
RUNS = os.path.join(ROOT, "training", "readerdirector", "runs", "task100")


def main():
    results = {}
    rp = os.path.join(RUNS, "abc_results.jsonl")
    for l in open(rp, encoding="utf-8"):
        r = json.loads(l)
        results[r["sample_id"]] = r
    cases = [json.loads(l) for l in open(os.path.join(RUNS, "abc_cases_200.jsonl"), encoding="utf-8")]
    md = ["# A/B/C 候选架构对比 — 200 条人工标注表",
          "",
          "规则：判断 TARGET 对白**实际是谁说的**（利用 RECENT 上下文与 AFTER_TARGET 后置 cue）。",
          "填 human_speaker：角色名（**真名，不要用 A 的候选 id**）或 UNKNOWN。若上下文不足可标 UNKNOWN。",
          "",
          "| # | 书 | TARGET | RECENT（截断） | A候选 | B候选 | A预测 | B预测 | C预测 | human_speaker |",
          "|---|----|--------|---------------|-------|-------|-------|-------|-------|---------------|"]
    out = []
    for i, c in enumerate(cases, 1):
        r = results.get(c["sample_id"])
        recent = " ‖ ".join(c["recent_context"][-3:])[:140]
        cands_a = ",".join(c["cands_a"][:6])[:80]
        cands_b = ",".join(c["cands_b"][:6])[:80] or "∅"
        a = (r or {}).get("a_answer") or ""
        b = (r or {}).get("b_answer") or ""
        cc = (r or {}).get("c_answer") or ""
        text = c["text"][:60]
        row = f"| {i} | {c['book'][3:8]} | {text} | {recent} | {cands_a} | {cands_b} | {a} | {b} | {cc} |  |"
        md.append(row)
        out.append({
            "idx": i, "sample_id": c["sample_id"], "book": c["book"], "layer": c["layer"],
            "text": c["text"], "recent_context": c["recent_context"], "post_cue": c.get("post_cue"),
            "cands_a": c["cands_a"], "cands_b": c["cands_b"], "char_history_c": c["char_history_c"],
            "a_answer": a, "b_answer": b, "c_answer": cc,
            "human_speaker": None,
        })
    with open(os.path.join(RUNS, "abc_human_label_200.md"), "w", encoding="utf-8") as f:
        f.write("\n".join(md) + "\n")
    with open(os.path.join(RUNS, "abc_human_label_200.jsonl"), "w", encoding="utf-8") as f:
        for o in out:
            f.write(json.dumps(o, ensure_ascii=False) + "\n")
    print(f"exported {len(out)} -> abc_human_label_200.md / .jsonl")


if __name__ == "__main__":
    main()
