# -*- coding: utf-8 -*-
"""TASK-095-VS6.3 — 4B few-shot semantic binder 评估（160 hard fixtures）。

输出协议（用户 §8）：O=C1;R=C2;C=H（H/M/L 置信）或 O=UNKNOWN;R=NONE;C=L
宿主校验：owner/ref ∈ candidates ∪ {UNKNOWN,NONE}；自由名字=幻觉拒绝；
PENDING（owner UNKNOWN）合法。

指标：Owner Accuracy（gold owner 已知子集）/ Reference Accuracy / Joint /
      UNKNOWN Precision·Recall / observer-causer 错误（G6-2 必须 0）/ host validity。
"""
import argparse
import json
import os
import re
import sys

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
from importlib import import_module
_m = import_module("095b2_eval_mention")
run = _m.run

ROOT = os.path.abspath(os.path.join(os.path.dirname(__file__), "..", "..", ".."))
OUT = os.path.join(ROOT, "training", "readerdirector", "runs", "task095")
MODEL_DIR = os.path.join(ROOT, "training", "models", "qwen3.5-4b")

PROMPT = (
    "你是 ReaderDirector 的声音绑定器。给定文本和候选角色，判断：\n"
    "- 谁的声音状态发生变化（voice state owner）\n"
    "- 模仿/变成谁的声音（reference voice，若无则 NONE）\n"
    "\n"
    "关键区分（必须遵守）：\n"
    "- owner = 声音归属于谁（谁的嗓音/声线/声音发生了变化），不是谁造成的变化、不是谁听见、不是谁指使\n"
    "- \"X的声音变了/换/恢复/嘶哑\" → owner=X（声音归属者，即使没有\"主动改变\"字样）\n"
    "- 听见/看到/发现 的人 = observer（不选为 owner）\n"
    "- 让/命令/吩咐 的人 = causer（不选为 owner）\n"
    "- 模仿/学着/装成 X的声音 → owner=模仿动作发出者，reference=X\n"
    "- 附身/控制：明确以自己声音发声（\"用自己的声音\"）→ owner=发声身份；不确定 → O=UNKNOWN\n"
    "- 代词（他/她）有前文上下文时按前文解析；单句无上下文 → O=UNKNOWN（不猜）\n"
    "- 使役结构（X让/命令/吩咐/要求Y换声/压低/模仿）→ owner=Y（实际发声者），X是causer不选为owner\n"
    "- 回答前先核对候选列表中的名字-ID 对应关系（C0=张三 等），必须引用列表中的准确 ID\n"
    "\n"
    "候选（只能选这些或 UNKNOWN/NONE，禁止输出其他名字）：\n{candidates}\n"
    "\n"
    "成对示例（重要，学习区分）：\n"
    "对1：孙七让钱八模仿周九的声音说话 → owner=钱八（发声者），reference=周九（被模仿者），孙七是指使者不选为 owner\n"
    "对2：孙七听见钱八的声音变了 → owner=钱八（声音归属者），孙七是听见者不选为 owner\n"
    "对3：孙七对钱八说周九的声音很奇怪 → owner=周九（被讨论的声音归属者），孙七钱八都不选\n"
    "对4：钱八忽然换成了周九的嗓音 → owner=钱八，reference=周九\n"
    "对8：王五用/借/模仿李四的声音说话 → owner=王五（发声者，其声音状态临时变成李四的），reference=李四（被借用的声音归属）；不是 owner=李四\n"
    "对5：钱八恢复了本来的声音 → owner=钱八，reference=NONE\n"
    "对6：李四开始模仿刚才那个男人的声音 → owner=李四（模仿者明确），reference=UNKNOWN（被模仿者未明）——owner 已知即可 BOUND，reference 未知不阻止 timeline\n"
    "对7：李四压低声音说别出声（PERFORMANCE）→ owner=李四，reference=NONE；PERFORMANCE 事件同样有 owner，不判 UNKNOWN\n"
    "\n"
    "文本：{text}\n"
    "\n"
    "输出 JSON：{\"owner\": \"C0..Cn 或 UNKNOWN\", \"owner_surface\": \"原文中逐字存在的 owner 名字片段（如 赵六），UNKNOWN 时为 null\", \"reference\": \"C0..Cn 或 NONE 或 UNKNOWN\", \"reference_surface\": \"原文逐字存在的 reference 名字片段，无则为 null\", \"confidence\": \"H\" | \"M\" | \"L\", \"reason\": \"一句话理由\"}"
)

def parse_binding(raw):
    raw = raw.strip()
    try:
        obj = json.loads(raw)
        return obj
    except Exception:
        m = re.search(r"\{.*\}", raw, re.S)
        if m:
            try:
                return json.loads(m.group(0))
            except Exception:
                return None
    return None

def host_validate(cands, obj):
    """host 校验（G6-6）。返回 (valid, err)。"""
    ids = {c["role"] for c in cands} | {"UNKNOWN", "NONE"}
    o = (obj or {}).get("owner")
    r = (obj or {}).get("reference")
    if o is None or o not in ids:
        return False, f"invalid owner {o!r}"
    if r is not None and r not in ids:
        return False, f"invalid reference {r!r}"
    return True, None

def main():

    ap = argparse.ArgumentParser()
    ap.add_argument("--resume", action="store_true")
    args = ap.parse_args()

    from transformers import AutoModelForCausalLM, AutoTokenizer
    tok = AutoTokenizer.from_pretrained(MODEL_DIR, trust_remote_code=True)
    model = AutoModelForCausalLM.from_pretrained(MODEL_DIR, trust_remote_code=True,
                                                 torch_dtype="auto", device_map="auto")
    print("4B loaded", flush=True)

    rows = [json.loads(l) for l in open(os.path.join(OUT, "vs6_binding_fixtures.jsonl"), encoding="utf-8")]
    op = os.path.join(OUT, "vs6_binding_results.jsonl")
    done = set()
    if args.resume and os.path.exists(op):
        for l in open(op, encoding="utf-8"):
            try:
                done.add(json.loads(l)["idx"])

            except Exception:
                pass
        print(f"resume: {len(done)} done", flush=True)

    n = 0
    for row in rows:
        if args.resume and row["idx"] in done:
            continue
        cands_txt = "\n".join(f"{c['role']} = {c['name']}" for c in row["candidates"])
        p = PROMPT.replace("{candidates}", cands_txt).replace("{text}", row["text"])
        raw = run(model, tok, p, max_new_tokens=120)
        obj = parse_binding(raw)
        valid, err = host_validate(row["candidates"], obj)
        rec = {"idx": row["idx"], "category": row["category"], "text": row["text"],
               "gold": row["gold"], "candidates": row["candidates"],
               "raw": raw, "pred": obj, "valid": valid, "error": err}
        with open(op, "a", encoding="utf-8") as f:
            f.write(json.dumps(rec, ensure_ascii=False) + chr(10))
        n += 1
        if n % 20 == 0:
            print(f"  {n} done", flush=True)
    print(f"done {n}, saved {op}", flush=True)

if __name__ == "__main__":
    main()