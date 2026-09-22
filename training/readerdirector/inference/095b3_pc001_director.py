# -*- coding: utf-8 -*-
"""PC-001 Step A: ReaderDirector 推理（MENTION -> SPEAKER -> EVENT），task080 venv。"""
import json
import os
import sys

HERE = os.path.dirname(os.path.abspath(__file__))
sys.path.insert(0, HERE)
from importlib import import_module
_m = import_module("095b2_eval_mention")
run = _m.run
parse_mentions = _m.parse_mentions
PROMPT = _m.PROMPT

sys.path.insert(0, os.path.join(HERE, ".."))
from prompt_builder import build_prompt, load_prompts
from parser import parse as parse_speaker
from collections import Counter

ROOT = r"E:\AndroidStudioProjects\ReaderVoiceMobile"
CKPT = r"E:\AndroidStudioProjects\ReaderVoiceMobile\training\readerdirector\runs\task095\vs8_2\v0-20260820-153040\checkpoint-361"
MODEL_DIR = r"E:\AndroidStudioProjects\ReaderVoiceMobile\training\models\qwen3.5-0.8b-base"
PC001 = r"E:\AndroidStudioProjects\ReaderVoiceMobile\training\readerdirector\runs\voice_state\voice_factory\pc001"
OUT = os.path.join(PC001, "director.jsonl")
PROMPTS = load_prompts("v2")

EVENT_PROMPT = """你是 ReaderDirector。判断文本中的声音控制事件。
- SET：声线身份被临时替换（模仿/借用/变成某人的声音）
- CLEAR：恢复原本的声音/停止变声
- PERF：仍是本来的声音，只是当前怎么说（音量/情绪/韵律）
- NONE：与声音控制无关

输出：V=SET|CLEAR|PERF|NONE; E=<原文中逐字存在的证据片段（NONE 时 E=null）>

文本：{text}

示例：
李四模仿王五的声音说话 → V=SET; E=模仿王五的声音
李四恢复了原本的声音 → V=CLEAR; E=恢复了原本的声音
李四压低声音说：别出声 → V=PERF; E=压低声音
李四说王五的声音很好听 → V=NONE; E=null

只输出 V=...; E=... 一行"""

import re


def parse_event(raw):
    m = re.search(r"V=(SET|CLEAR|PERF|NONE)", raw or "")
    if not m:
        return None, None
    e = re.search(r"E=(.+)", raw or "")
    return m.group(1), (e.group(1).strip() if e else None)


def load_model():
    from transformers import AutoModelForCausalLM, AutoTokenizer
    from peft import LoraConfig, get_peft_model
    from safetensors.torch import load_file
    tok = AutoTokenizer.from_pretrained(MODEL_DIR, trust_remote_code=True)
    model = AutoModelForCausalLM.from_pretrained(MODEL_DIR, trust_remote_code=True, torch_dtype="auto", device_map="auto")
    cfg = json.load(open(os.path.join(CKPT, "adapter_config.json"), encoding="utf-8"))
    sd = load_file(os.path.join(CKPT, "adapter_model.safetensors"))
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
    assert not unexpected, "unexpected: %s" % (unexpected[:3])
    missing = [m for m in missing if "lora" in m]
    assert not missing, "missing lora: %s" % (missing[:3])
    model.eval()
    return model, tok


def speaker_query(model, tok, text, candidates):
    """SPEAKER 归属：candidates 来自 MENTION。返回 surface 或 None。"""
    if not candidates:
        return None
    cands = [{"role": c, "name": c, "distance": i + 1} for i, c in enumerate(candidates)]
    sample = {"input": {"recent_context": [], "identity_constraints": [], "segment_type": "speech",
                        "text": text, "rule_candidates": cands, "embodiment": [], "overrides": []}}
    prompt, _ = build_prompt("SPEAKER", sample, PROMPTS)
    raw = run(model, tok, prompt, max_new_tokens=32)
    obj, verdict = parse_speaker(raw, "SPEAKER", candidates=[c["role"] for c in cands])
    return obj.get("speaker") if obj else None


def main():
    model, tok = load_model()
    print("model loaded", flush=True)
    rows = []
    books = ["urban", "fantasy", "xianxia"]
    stats = Counter()
    for book in books:
        segs = [json.loads(l) for l in open(os.path.join(PC001, book + ".jsonl"), encoding="utf-8")]
        for seg in segs:
            rec = {"paragraph_id": seg["paragraph_id"], "book": book,
                   "segment_type": seg["segment_type"], "text": seg["text"]}
            if seg["segment_type"] == "NARRATION":
                rec["route"] = "narrator"
                rec["speaker"] = None
                rec["voice_event"] = None
            else:
                raw = run(model, tok, PROMPT.replace("{text}", seg["text"]), max_new_tokens=200)
                mentions = parse_mentions(raw)
                names = [m for m in mentions if m and isinstance(m, str)]
                names = list(dict.fromkeys(names))
                speaker = speaker_query(model, tok, seg["text"], names)
                ev_raw = run(model, tok, EVENT_PROMPT.replace("{text}", seg["text"]), max_new_tokens=48)
                v, e = parse_event(ev_raw)
                rec["route"] = "character"
                rec["speaker"] = speaker
                rec["mentions"] = names
                rec["voice_event"] = {"type": v, "evidence": e}
                stats["dialogue"] += 1
                if speaker:
                    stats["speaker_found"] += 1
            rows.append(rec)
    with open(OUT, "w", encoding="utf-8") as f:
        for r in rows:
            f.write(json.dumps(r, ensure_ascii=False) + "\n")
    print("director.jsonl rows:", len(rows), flush=True)
    print("stats:", dict(stats), flush=True)
    print("DONE", flush=True)


if __name__ == "__main__":
    main()
