# -*- coding: utf-8 -*-
"""TASK-080 §23/§24 严格协议解析：STRICT / REPAIRABLE / FAIL 三层。

- STRICT：输出本身即合法 JSON 且枚举/候选合法（允许首尾少量空白）。
- REPAIRABLE：提取首个 {...} 花括号块后合法。
- 其余 STRICT_PROTOCOL_FAIL。
"""
import json
import re

_BRACE = re.compile(r"\{.*\}", re.S)

SPEAKER_STATUS = {"CONFIRMED", "PROVISIONAL", "UNKNOWN"}
IDENTITY_REL = {"SAME", "DIFFERENT", "UNKNOWN"}
VOICE_ACTION = {"START", "CONTINUE", "REPLACE", "END", "NONE"}

ENUMS = {
    "SPEAKER": {"status": SPEAKER_STATUS, "value_key": "speaker"},
    "IDENTITY": {"relation": IDENTITY_REL, "value_key": "relation"},
    "VOICE_STATE": {"action": VOICE_ACTION, "value_key": "action"},
}


def parse(raw, task, candidates=None):
    """返回 (result_dict|None, verdict: str)。verdict ∈ {STRICT, REPAIRABLE, STRICT_PROTOCOL_FAIL}。"""
    s = raw.strip()
    try:
        obj = json.loads(s)
        verdict = "STRICT"
    except Exception:
        m = _BRACE.search(s)
        if not m:
            return None, "STRICT_PROTOCOL_FAIL"
        try:
            obj = json.loads(m.group(0))
            verdict = "REPAIRABLE"
        except Exception:
            return None, "STRICT_PROTOCOL_FAIL"
    if not isinstance(obj, dict):
        return None, "STRICT_PROTOCOL_FAIL"
    spec = ENUMS[task]
    key = spec["value_key"]
    if key not in obj:
        return None, "STRICT_PROTOCOL_FAIL"
    enum_key = "status" if task == "SPEAKER" else ("relation" if task == "IDENTITY" else "action")
    if enum_key in obj and obj[enum_key] not in spec[enum_key]:
        return None, "STRICT_PROTOCOL_FAIL"
    # Speaker 候选约束（§7）：只能选候选或 UNKNOWN
    if task == "SPEAKER":
        val = obj[key]
        if val != "UNKNOWN" and candidates is not None and val not in candidates:
            return None, "OUT_OF_CANDIDATE_PROTOCOL_ERROR"
    return obj, verdict
