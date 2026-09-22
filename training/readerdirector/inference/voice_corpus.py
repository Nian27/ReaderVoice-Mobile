# -*- coding: utf-8 -*-
"""TASK-095-VS7.1 — VoiceState corpus schema（用户 §三/§四 定案）。

六层 gold（每样本）:
  AI 学：event_type / evidence_span / voice_state_owner / reference_voice
  诊断：evidence_object
  AI 不学（reducer 算）：expected_transition

两个任务视图（同一 corpus 导出）:
  TASK_VOICE_EVENT: V=SET|CLEAR|PERF|NONE; E=<exact evidence>
  TASK_VOICE_BIND : O=<exact surface>; R=<exact surface|NONE|UNKNOWN>

协议纪律：
  - owner_surface/reference_surface 必须是输入上下文 exact span 或 Host 已知 alias
  - surface → Host grounding（唯一→BOUND / 多义→AMBIGUOUS / 不存在→INVALID）
  - expected_transition 只由 VS4 reducer 算，禁止模型预测
"""

EVENT_TYPES = {"VOICE_OVERRIDE_SET", "VOICE_OVERRIDE_CLEAR", "PERFORMANCE", "NONE"}
TRANSITIONS = {"START", "CONTINUE", "REPLACE", "END", "ORPHAN_CLEAR", "NOOP"}

class VoiceSample:
    """一条 VoiceState 语料样本（六层 gold）。"""

    def __init__(self, sample_id, book_id, segment_id, text, context=None,
                 event_type=None, evidence_object=None, evidence_span=None,
                 voice_state_owner=None, reference_voice=None,
                 expected_transition=None, tier=None, source=None):
        self.sample_id = sample_id
        self.book_id = book_id
        self.segment_id = segment_id
        self.text = text
        self.context = context or []
        self.event_type = event_type
        self.evidence_object = evidence_object
        self.evidence_span = evidence_span
        self.voice_state_owner = voice_state_owner
        self.reference_voice = reference_voice
        self.expected_transition = expected_transition
        self.tier = tier
        self.source = source

    def validate(self):
        errs = []
        if self.event_type not in EVENT_TYPES:
            errs.append(f"invalid event_type {self.event_type}")
        if self.event_type != "NONE" and (not self.evidence_span or self.evidence_span not in self.text):
            errs.append(f"evidence_span not exact in text: {self.evidence_span!r}")
        if self.expected_transition and self.expected_transition not in TRANSITIONS:
            errs.append(f"invalid transition {self.expected_transition}")
        if self.tier not in ("A", "B", "C"):
            errs.append(f"invalid tier {self.tier}")
        return (len(errs) == 0, errs)

    def to_dict(self):
        return {k: getattr(self, k) for k in (
            "sample_id", "book_id", "segment_id", "text", "context",
            "event_type", "evidence_object", "evidence_span",
            "voice_state_owner", "reference_voice", "expected_transition",
            "tier", "source")}

    @classmethod
    def from_dict(cls, d):
        return cls(**d)

    def event_task(self):
        ev = {"VOICE_OVERRIDE_SET": "SET", "VOICE_OVERRIDE_CLEAR": "CLEAR",
               "PERFORMANCE": "PERF", "NONE": "NONE"}[self.event_type]
        return {"sample_id": self.sample_id, "task": "VOICE_EVENT",
                "text": self.text, "context": self.context,
                "target": {"V": ev, "E": self.evidence_span}}

    def bind_task(self):
        return {"sample_id": self.sample_id, "task": "VOICE_BIND",
                "text": self.text, "context": self.context,
                "frozen_event": self.event_type, "frozen_evidence": self.evidence_span,
                "target": {"O": self.voice_state_owner, "R": self.reference_voice}}

def _test_all():
    n = 0
    def check(desc, got, want):
        nonlocal n
        n += 1
        assert got == want, f"{desc}: got {got}, want {want}"

    s = VoiceSample(sample_id="s1", book_id="b1", segment_id=1,
                    text="李四模仿王五的声音说话", context=["张三在一旁听着。"],
                    event_type="VOICE_OVERRIDE_SET", evidence_object="VOICE_IDENTITY",
                    evidence_span="模仿王五的声音", voice_state_owner="李四",
                    reference_voice="王五", expected_transition="START", tier="C", source="synthetic")
    ok, errs = s.validate()
    check("valid", ok, True)
    check("no errs", errs, [])
    e = s.event_task()
    check("event V", e["target"]["V"], "SET")
    check("event E", e["target"]["E"], "模仿王五的声音")
    b = s.bind_task()
    check("bind O", b["target"]["O"], "李四")
    check("bind R", b["target"]["R"], "王五")
    check("bind frozen event", b["frozen_event"], "VOICE_OVERRIDE_SET")
    bad = VoiceSample(sample_id="s2", book_id="b1", segment_id=2,
                      text="张三恢复了声音", event_type="VOICE_OVERRIDE_CLEAR",
                      evidence_span="不存在的span", voice_state_owner="张三",
                      reference_voice="NONE", expected_transition="END", tier="A")
    ok2, errs2 = bad.validate()
    check("bad evidence rejected", ok2, False)
    none_s = VoiceSample(sample_id="s3", book_id="b1", segment_id=3,
                         text="天气很好。", event_type="NONE", evidence_object="OTHER",
                         evidence_span=None, voice_state_owner="UNKNOWN",
                         reference_voice="NONE", expected_transition="NOOP", tier="A")
    ok3, _ = none_s.validate()
    check("none ok", ok3, True)
    d = s.to_dict()
    s2 = VoiceSample.from_dict(d)
    check("roundtrip", s2.voice_state_owner, "李四")

    print(f"VS7.1 VoiceSample schema: {n} checks PASS (100%)")
    return True

if __name__ == "__main__":
    _test_all()