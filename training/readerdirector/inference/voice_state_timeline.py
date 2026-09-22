# -*- coding: utf-8 -*-
"""TASK-095-VS5 — VoiceStateTimeline（角色级状态时间线，按 narrative position 可重建）。

要点（用户规格 §19-24）：
- 不能只存 character.currentVoiceState；必须存 interval 时间线
- VoiceState 由 Narrative Position 决定，不是 Playback History（用户可跳章/倒序播放）
- OVERRIDE_SET 跨章持续直到 CLEAR（章末不自动清除）
- 不跨 book（新书重新建立）
- 重新打开 APP：从事件序列重放重建 timeline
"""

from voice_event import VoiceControlEvent
from voice_state_engine import VoiceStateEngine, VoiceLayer


class VoiceStateInterval:
    """一段有效状态区间。"""

    def __init__(self, role_id, start_segment_id, end_segment_id=None,
                 state_type=None, override_descriptor=None, source_event_id=None, revision=1):
        self.role_id = role_id
        self.start_segment_id = start_segment_id
        self.end_segment_id = end_segment_id  # None = 开放区间（直到 CLEAR/书末）
        self.state_type = state_type  # BASE/PHASE/OVERRIDE
        self.override_descriptor = override_descriptor
        self.source_event_id = source_event_id
        self.revision = revision

    def to_dict(self):
        return {k: getattr(self, k) for k in (
            "role_id", "start_segment_id", "end_segment_id", "state_type",
            "override_descriptor", "source_event_id", "revision")}

    def __repr__(self):
        return f"Interval({self.state_type} {self.override_descriptor} [{self.start_segment_id}~{self.end_segment_id}])"


class VoiceStateTimeline:
    """按角色 + book 维护 interval 时间线；按 segment 查询有效状态。"""

    def __init__(self, book_id):
        self.book_id = book_id
        self.intervals = []  # 全量区间（有序）
        self._events = []    # 原始事件（用于重建）

    def ingest(self, event, segment_id, role_id):
        """摄入一个 VoiceEvent IR（按事件顺序）。"""
        self._events.append({"segment_id": segment_id, "role_id": role_id, "event": event})

    def rebuild(self, engine_factory=None):
        """从头重放所有事件，重建 intervals。返回 (n_intervals, n_events)。"""
        self.intervals = []
        engine = engine_factory() if engine_factory else VoiceStateEngine()
        for rec in self._events:
            seg, role, ev = rec["segment_id"], rec["role_id"], rec["event"]
            result = engine.apply(ev)
            if result.action in ("START", "REPLACE"):
                # 关闭旧 open override interval（REPLACE 时在 segment-1 关闭；START 时无旧区间）
                if result.action == "REPLACE":
                    self._close_open(role, end=seg)
                else:
                    self._close_open(role)
                self.intervals.append(VoiceStateInterval(
                    role_id=role, start_segment_id=seg, state_type="OVERRIDE",
                    override_descriptor=engine.layer.override.to_dict() if engine.layer.override else None,
                    source_event_id=getattr(ev, "event_id", None), revision=1))
            elif result.action == "END":
                self._close_open(role, end=seg)
            elif result.action == "ORPHAN_CLEAR":
                # 记录诊断：无活动 override 的 CLEAR（不改变状态）
                self.intervals.append(VoiceStateInterval(
                    role_id=role, start_segment_id=seg, end_segment_id=seg,
                    state_type="ORPHAN_CLEAR", override_descriptor=None,
                    source_event_id=getattr(ev, "event_id", None), revision=1))
        return len(self.intervals), len(self._events)

    def _close_open(self, role_id, end=None):
        for iv in self.intervals:
            if iv.role_id == role_id and iv.state_type == "OVERRIDE" and iv.end_segment_id is None:
                iv.end_segment_id = end
                break

    def effective_at(self, segment_id, role_id):
        """查询某角色在某 segment 的有效 override（无则 None）。"""
        for iv in reversed(self.intervals):
            if iv.role_id != role_id or iv.state_type != "OVERRIDE":
                continue
            if iv.start_segment_id <= segment_id and (iv.end_segment_id is None or segment_id < iv.end_segment_id):
                return iv.override_descriptor
        return None

    def override_active_at(self, segment_id, role_id):
        return self.effective_at(segment_id, role_id) is not None

    def to_json(self):
        return [iv.to_dict() for iv in self.intervals]


def _test_all():
    n = 0
    def check(desc, got, want):
        nonlocal n
        n += 1
        assert got == want, f"{desc}: got {got}, want {want}"

    def ov_set(style, scope="UNTIL_CLEAR", evidence="x"):
        return VoiceControlEvent("VOICE_OVERRIDE", operation="SET", style=style, scope=scope, evidence_span=evidence)

    def ov_clear(evidence="x"):
        return VoiceControlEvent("VOICE_OVERRIDE", operation="CLEAR", scope="UNTIL_CLEAR", evidence_span=evidence)

    def none_ev():

        return VoiceControlEvent("NONE")

    # 跨段：SET@5 → (NONE 6..50) → CLEAR@51
    tl = VoiceStateTimeline(book_id="b1")
    tl.ingest(ov_set("OLD_MAN"), 5, "role-A")
    for s in range(6, 51):
        tl.ingest(none_ev(), s, "role-A")
    tl.ingest(ov_clear(), 51, "role-A")
    ni, ne = tl.rebuild()
    check("rebuild intervals", ni, 1)
    check("rebuild events", ne, 47)
    check("active at 5", tl.override_active_at(5, "role-A"), True)
    check("active at 30", tl.override_active_at(30, "role-A"), True)
    check("active at 50", tl.override_active_at(50, "role-A"), True)
    check("inactive at 51", tl.override_active_at(51, "role-A"), False)
    check("inactive at 100", tl.override_active_at(100, "role-A"), False)
    # 其他角色不受影响
    check("role-B unaffected", tl.override_active_at(30, "role-B"), False)

    # 跨章持续（无 CLEAR 直到书末）
    tl2 = VoiceStateTimeline(book_id="b2")
    tl2.ingest(ov_set("WOMAN"), 100, "role-X")
    tl2.rebuild()
    check("cross-chapter persist", tl2.override_active_at(9999, "role-X"), True)
    check("open interval", tl2.intervals[0].end_segment_id, None)

    # 跳章/倒序：按 segment 查询与播放顺序无关
    check("seek random 700", tl2.override_active_at(700, "role-X"), True)
    check("seek before 100", tl2.override_active_at(99, "role-X"), False)

    # REPLACE：SET(A)@1 → SET(B)@10 → 旧区间关闭
    tl3 = VoiceStateTimeline(book_id="b3")
    tl3.ingest(ov_set("A"), 1, "role-Y")
    tl3.ingest(ov_set("B"), 10, "role-Y")
    ni3, _ = tl3.rebuild()
    check("replace intervals", ni3, 2)
    check("A closed", tl3.intervals[0].end_segment_id, 10)
    check("B open", tl3.intervals[1].end_segment_id, None)
    check("A at 5", tl3.effective_at(5, "role-Y")["temp"], "A")
    check("B at 10", tl3.effective_at(10, "role-Y")["temp"], "B")

    # ORPHAN_CLEAR 记录
    tl4 = VoiceStateTimeline(book_id="b4")
    tl4.ingest(ov_clear(), 7, "role-Z")
    ni4, _ = tl4.rebuild()
    check("orphan interval", ni4, 1)
    check("orphan type", tl4.intervals[0].state_type, "ORPHAN_CLEAR")

    # JSON 序列化
    j = tl3.to_json()
    check("json len", len(j), 2)
    check("json fields", set(j[0].keys()), {"role_id", "start_segment_id", "end_segment_id", "state_type", "override_descriptor", "source_event_id", "revision"})

    print(f"VS5 VoiceStateTimeline: {n} checks PASS (100%)")
    return True


if __name__ == "__main__":
    _test_all()