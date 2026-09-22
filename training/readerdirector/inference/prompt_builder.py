# -*- coding: utf-8 -*-
"""TASK-080 prompt builder：sample JSON → 渲染 prompt（确定性、不截断 target）。"""
import json
import os

_PROMPT_DIR = os.path.join(os.path.dirname(__file__), "..", "prompts")


def load_prompts(version="v1"):
    with open(os.path.join(_PROMPT_DIR, f"speaker_{version}.txt"), encoding="utf-8") as f:
        speaker = f.read()
    with open(os.path.join(_PROMPT_DIR, f"identity_{version}.txt"), encoding="utf-8") as f:
        identity = f.read()
    with open(os.path.join(_PROMPT_DIR, f"voice_state_{version}.txt"), encoding="utf-8") as f:
        voice = f.read()
    return {"SPEAKER": speaker, "IDENTITY": identity, "VOICE_STATE": voice}


def _fmt(v):
    if v is None or v == {} or v == []:
        return "-"
    if isinstance(v, (dict, list)):
        return json.dumps(v, ensure_ascii=False)
    return str(v)


def build_prompt(task, sample, templates, max_input_tokens_hint=None):
    """确定性渲染；超长时按 §14 优先级丢弃最远历史，绝不切 target。返回 (prompt, dropped_fields)。"""
    inp = sample["input"]
    if task == "SPEAKER":
        recent = list(inp.get("recent_context") or [])
        dropped = []
        # §14 优先删：最远历史（从后往前保留 8 条）
        keep = 8
        if len(recent) > keep:
            dropped.append(f"recent_context[{len(recent) - keep}]")
            recent = recent[-keep:]
        candidates = "\n".join(
            f"- {c['role']} (name={c.get('name', '?')}, distance={c['distance']})" for c in (inp.get("rule_candidates") or [])
        ) or "-"
        constraints = "\n".join(f"- {c}" for c in (inp.get("identity_constraints") or [])) or "-"
        # 后置 cue（TASK-090.1）：target 段落之后的叙述（"钱二答道。"）——对模型可见
        post = inp.get("post_cue") or ""
        tpl = templates["SPEAKER"]
        tpl = tpl.replace("{post_cue}", f"AFTER_TARGET:\n{post}" if post else "")
        return tpl.format(
            recent="\n".join(f"- {t}" for t in recent) or "-",
            target=inp.get("text") or "",
            candidates=candidates,
            constraints=constraints,
        ), dropped
    if task == "IDENTITY":
        rel = inp.get("relationship_evidence") or {}
        return templates["IDENTITY"].format(
            entity_a=inp.get("entity_a") or "-",
            aliases_a=", ".join(inp.get("aliases_a") or []) or "-",
            entity_b=inp.get("entity_b") or "-",
            aliases_b=", ".join(inp.get("aliases_b") or []) or "-",
            positive_evidence=inp.get("positive_evidence", 0),
            negative_evidence=inp.get("negative_evidence", 0),
            relationship_evidence=_fmt(rel),
            co_presence=inp.get("co_presence", 0),
            hard_block=bool(inp.get("hard_block")),
            user_constraints=", ".join(inp.get("user_constraints") or []) or "-",
        ), []
    if task == "VOICE_STATE":
        return templates["VOICE_STATE"].format(
            target=inp.get("text") or "",
            speaker=inp.get("identity_id") or "UNKNOWN",
            current_state=inp.get("current_state") or "-",
        ), []
    raise ValueError(f"unknown task {task}")


def count_tokens(text, tokenizer):
    try:
        return len(tokenizer.encode(text))
    except Exception:
        return max(1, len(text) // 2)
