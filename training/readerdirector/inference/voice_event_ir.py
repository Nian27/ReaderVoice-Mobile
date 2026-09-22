# -*- coding: utf-8 -*-
"""TASK-095-VS3 — VoiceEvent IR（正式事件记录，ADR-042 落地）。

每个 VoiceEvent 必须可回答：来自哪一段？哪条 evidence？哪个角色？哪个模型版本？
字段：event_id / book_id / segment_id / source_start / source_end / event_type /
      evidence_span / evidence_start / evidence_end / subject_role_id / subject_resolution /
      confidence / model_version / prompt_version / revision

AI 不负责创建角色 ID：subject_role_id 由 CharacterStore/Speaker Resolver 提供（UNKNOWN_SUBJECT 合法）。
"""
import hashlib
import json
import re

EVENT_TYPES = {"VOICE_OVERRIDE_SET", "VOICE_OVERRIDE_CLEAR", "PERFORMANCE", "NONE"}

class VoiceEvent:
    """VoiceEvent IR（不可变记录）。"""

    def __init__(self, event_id=None, book_id=None, segment_id=None, source_text=None,
                 source_start=None, source_end=None, event_type=None,
                 evidence_span=None, evidence_start=None, evidence_end=None,
                 subject_role_id=None, subject_resolution="UNRESOLVED",
                 confidence=None, model_version=None, prompt_version=None, revision=1):
        assert event_type in EVENT_TYPES, f"invalid event_type: {event_type}"
        if event_type != "NONE":
            assert evidence_span, "non-NONE event must carry evidence_span"
            assert evidence_start is not None and evidence_end is not None, "non-NONE event must carry evidence offsets"
        self.event_id = event_id or VoiceEvent._gen_id(book_id, segment_id, evidence_span)
        self.book_id = book_id
        self.segment_id = segment_id
        self.source_text = source_text
        self.source_start = source_start
        self.source_end = source_end
        self.event_type = event_type
        self.evidence_span = evidence_span
        self.evidence_start = evidence_start
        self.evidence_end = evidence_end
        self.subject_role_id = subject_role_id
        self.subject_resolution = subject_resolution
        self.confidence = confidence
        self.model_version = model_version
        self.prompt_version = prompt_version
        self.revision = revision

    @staticmethod
    def _gen_id(book_id, segment_id, span):
        raw = "|".join(str(x) for x in (book_id, segment_id, span))
        return "ve-" + hashlib.sha256(raw.encode()).hexdigest()[:16]

    def validate_evidence(self):
        """host 校验：evidence_span 逐字连续存在于 source_text，offsets 对齐。"""
        if self.event_type == "NONE":
            return True, None
        if not self.source_text:
            return False, "no source_text"
        span = self.evidence_span
        if span not in self.source_text:
            return False, "evidence_span not in source_text"
        idx = self.source_text.find(span)
        if self.evidence_start is not None and idx != self.evidence_start:
            return False, f"evidence_start mismatch: {idx} != {self.evidence_start}"
        if self.evidence_end is not None and idx + len(span) != self.evidence_end:
            return False, f"evidence_end mismatch"
        return True, None

    def to_dict(self):
        return {k: getattr(self, k) for k in (
            "event_id", "book_id", "segment_id", "source_start", "source_end",
            "event_type", "evidence_span", "evidence_start", "evidence_end",
            "subject_role_id", "subject_resolution", "confidence",
            "model_version", "prompt_version", "revision")}

    @classmethod
    def from_dict(cls, d):
        return cls(**d)

    def __repr__(self):
        return f"VoiceEvent({self.event_type}@{self.segment_id} {self.evidence_span!r})"


def event_from_annotated(rec, book_id, model_version="qwen3.5-4b", prompt_version=None):
    """从 095b3_vs2_annotate 输出（validated 字段）构造 VoiceEvent IR。"""
    v = rec.get("validated") or {}
    kind = v.get("kind", "NONE")
    text = rec["text"]
    if kind == "NONE":
        return VoiceEvent(book_id=book_id, segment_id=rec["idx"], source_text=text,
                          source_start=0, source_end=len(text), event_type="NONE",
                          model_version=model_version, prompt_version=prompt_version or rec.get("prompt_version"))
    op = v.get("operation")
    etype = f"VOICE_OVERRIDE_{op}" if kind == "VOICE_OVERRIDE" else "PERFORMANCE"
    span = v.get("evidence_span")
    start = text.find(span) if span else None
    return VoiceEvent(book_id=book_id, segment_id=rec["idx"], source_text=text,
                      source_start=0, source_end=len(text), event_type=etype,
                      evidence_span=span, evidence_start=start,
                      evidence_end=start + len(span) if start is not None else None,
                      subject_role_id=None, subject_resolution="UNRESOLVED",
                      confidence=v.get("confidence"),
                      model_version=model_version, prompt_version=prompt_version or rec.get("prompt_version"))


def _test_all():
    n = 0
    def check(desc, got, want):
        nonlocal n
        n += 1
        assert got == want, f"{desc}: got {got}, want {want}"

    # 构造 + 校验
    ev = VoiceEvent(book_id="b1", segment_id=3, source_text="他模仿着父亲的声音说话",
                    source_start=0, source_end=11, event_type="VOICE_OVERRIDE_SET",
                    evidence_span="模仿着父亲的声音", evidence_start=1, evidence_end=9,
                    subject_role_id=None, subject_resolution="UNRESOLVED",
                    confidence=0.95, model_version="qwen3.5-4b", prompt_version="v33")
    ok, err = ev.validate_evidence()
    check("validate ok", ok, True)
    # 错误 offset 应失败
    bad = VoiceEvent(book_id="b1", segment_id=3, source_text="他模仿着父亲的声音说话",
                     source_start=0, source_end=11, event_type="VOICE_OVERRIDE_SET",
                     evidence_span="模仿着父亲的声音", evidence_start=5, evidence_end=9)
    ok2, _ = bad.validate_evidence()
    check("bad offset rejected", ok2, False)
    # NONE 不需要 evidence
    none_ev = VoiceEvent(book_id="b1", segment_id=1, event_type="NONE")
    ok3, _ = none_ev.validate_evidence()
    check("none ok", ok3, True)
    # event_id 确定性
    e1 = VoiceEvent(book_id="b", segment_id=1, event_type="NONE")
    e2 = VoiceEvent(book_id="b", segment_id=1, event_type="NONE")
    check("id deterministic", e1.event_id == e2.event_id, True)
    # from_annotated 转换
    rec = {"idx": 5, "text": "他故意压低嗓音，模仿张三的声音说道", "prompt_version": "v33",
           "validated": {"kind": "VOICE_OVERRIDE", "operation": "SET",
                          "style": "IMITATION", "scope": "UNTIL_CLEAR",
                          "evidence_span": "模仿张三的声音"}}
    ev4 = event_from_annotated(rec, book_id="b9")
    check("annotated type", ev4.event_type, "VOICE_OVERRIDE_SET")
    ok4, err4 = ev4.validate_evidence()
    check("annotated evidence", ok4, True)
    # 序列化往返
    d = ev4.to_dict()
    ev5 = VoiceEvent.from_dict(d)
    check("roundtrip id", ev5.event_id, ev4.event_id)
    check("roundtrip type", ev5.event_type, ev4.event_type)
    print(f"VS3 VoiceEvent IR: {n} checks PASS (100%)")
    return True


if __name__ == "__main__":
    _test_all()