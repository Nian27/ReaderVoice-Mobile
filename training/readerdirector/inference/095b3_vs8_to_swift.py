# -*- coding: utf-8 -*-
"""VS8-SMOKE — VS7_DEV_FRESH → swift 双任务格式（TASK_VOICE_EVENT + TASK_VOICE_BIND）。"""
import json
import os

ROOT = os.path.abspath(os.path.join(os.path.dirname(__file__), "..", "..", ".."))
RUNS = os.path.join(ROOT, "training", "readerdirector", "runs", "task095")
DS = os.path.join(ROOT, "training", "readerdirector", "dataset")

EVENT_PROMPT = (
    "你是 ReaderDirector。判断文本中的声音控制事件。\n"
    "- SET：声线身份被临时替换（模仿/借用/变成某人的声音）\n"
    "- CLEAR：恢复原本的声音/停止变声\n"
    "- PERF：仍是本来的声音，只是当前怎么说（音量/情绪/韵律）\n"
    "- NONE：与声音控制无关\n"
    "\n"
    "输出：V=SET|CLEAR|PERF|NONE; E=<原文中逐字存在的证据片段（NONE 时 E=null）>\n"
    "\n"
    "文本：{text}\n"
    "\n"
    "示例：\n"
    "李四模仿王五的声音说话 → V=SET; E=模仿王五的声音\n"
    "李四恢复了原本的声音 → V=CLEAR; E=恢复了原本的声音\n"
    "李四压低声音说：别出声 → V=PERF; E=压低声音\n"
    "李四说王五的声音很好听 → V=NONE; E=null\n"
    "\n"
    "只输出 V=...; E=... 一行"
)

BIND_PROMPT = (
    "你是 ReaderDirector 的声音绑定器。判断声音事件属于谁。\n"
    "- O = 实际发声者（Voice State Owner）：谁的声音状态发生变化\n"
    "- R = 被模仿/借用的声音归属（reference voice），无则 NONE\n"
    "\n"
    "关键区分：\n"
    "- A用B的声音说话 → O=A（实际发声者），R=B\n"
    "- A让B用C的声音 → O=B，R=C\n"
    "- A听见B变声 → O=B（A是听见者，不选）\n"
    "- 不确定谁发声 → O=UNKNOWN\n"
    "\n"
    "输出：O=<原文中的名字或 UNKNOWN>; R=<原文中的名字或 NONE 或 UNKNOWN>\n"
    "\n"
    "文本：{text}\n"
    "\n"
    "示例：\n"
    "李四模仿王五的声音说话 → O=李四; R=王五\n"
    "王五听见李四变声 → O=李四; R=NONE\n"
    "不知从哪传来变调的声音 → O=UNKNOWN; R=UNKNOWN\n"
    "\n"
    "只输出 O=...; R=... 一行"
)

def main():
    rows = [json.loads(l) for l in open(os.path.join(RUNS, "vs7_dev_fresh.jsonl"), encoding="utf-8")]
    out = []
    for i, r in enumerate(rows):
        text = r["text"]
        ev = {"VOICE_OVERRIDE_SET": "SET", "VOICE_OVERRIDE_CLEAR": "CLEAR",
              "PERFORMANCE": "PERF", "NONE": "NONE"}[r["event_type"]]
        evidence = r.get("evidence_span") or "null"
        out.append({
            "messages": [
                {"role": "user", "content": EVENT_PROMPT.replace("{text}", text)},
                {"role": "assistant", "content": f"V={ev}; E={evidence}"},
            ],
            "task": "VOICE_EVENT",
            "provenance": "VS7_HARD_CORPUS",
            "sample_id": f"ve-{r.get('sample_id', i)}",
            "markers": {"source": r.get("source"), "tier": r.get("tier", "C")},
        })
        owner = r.get("voice_state_owner") or "UNKNOWN"
        ref = r.get("reference_voice") or "NONE"
        out.append({
            "messages": [
                {"role": "user", "content": BIND_PROMPT.replace("{text}", text)},
                {"role": "assistant", "content": f"O={owner}; R={ref}"},
            ],
            "task": "VOICE_BIND",
            "provenance": "VS7_HARD_CORPUS",
            "sample_id": f"vb-{r.get('sample_id', i)}",
            "markers": {"source": r.get("source"), "tier": r.get("tier", "C")},
        })
    op = os.path.join(DS, "vs8_smoke_voice.jsonl")
    with open(op, "w", encoding="utf-8") as f:
        for s in out:
            f.write(json.dumps(s, ensure_ascii=False) + chr(10))
    print(f"saved {op}: {len(out)} samples")

if __name__ == "__main__":
    main()