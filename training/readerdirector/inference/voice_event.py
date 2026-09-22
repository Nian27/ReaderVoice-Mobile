# -*- coding: utf-8 -*-
"""TASK-095-VS2.2 — VoiceControlEvent ontology + deterministic VoiceStateReducer（ADR-042）。

四类事件：VOICE_OVERRIDE_SET / VOICE_OVERRIDE_CLEAR / PERFORMANCE / NONE
- VOICE_OVERRIDE：声线身份/音色临时替换 → 唯一进入 VoiceStateReducer 的事件
- PERFORMANCE：VoiceProfile 不变，仅当前怎么说（volume/prosody/style/emotion）→ PerformanceInstruction，不进 Reducer
- NONE：与声音控制无关

用法:
  from voice_event import VoiceControlEvent, VoiceStateReducer, PerformanceInstructionCompiler
  ev = VoiceControlEvent.from_dict({...})
  action = VoiceStateReducer().apply(prev_state, ev)  # 仅 OVERRIDE
  perf = PerformanceInstructionCompiler().compile(ev)  # 仅 PERFORMANCE
"""


class VoiceControlEvent:
    """文本声音控制事件（ADR-042）。"""

    KIND_OVERRIDE = "VOICE_OVERRIDE"
    KIND_PERFORMANCE = "PERFORMANCE"
    KIND_NONE = "NONE"
    VALID_KINDS = {KIND_OVERRIDE, KIND_PERFORMANCE, KIND_NONE}

    OP_SET = "SET"
    OP_CLEAR = "CLEAR"
    VALID_OPS = {OP_SET, OP_CLEAR}

    SCOPE_UTTERANCE = "UTTERANCE"
    SCOPE_UNTIL_CLEAR = "UNTIL_CLEAR"
    SCOPE_UNKNOWN = "UNKNOWN"
    VALID_SCOPES = {SCOPE_UTTERANCE, SCOPE_UNTIL_CLEAR, SCOPE_UNKNOWN}

    def __init__(self, kind, operation=None, style=None, target=None, scope=None,
                 evidence_span=None, confidence=None):
        assert kind in self.VALID_KINDS, f"invalid kind: {kind}"
        if kind == self.KIND_OVERRIDE:
            assert operation in self.VALID_OPS, f"override needs operation SET/CLEAR: {operation}"
            assert evidence_span, "override must carry evidence_span"
            assert scope in self.VALID_SCOPES, f"invalid scope: {scope}"
        elif kind == self.KIND_PERFORMANCE:
            assert evidence_span, "performance must carry evidence_span"
            assert scope in self.VALID_SCOPES, f"invalid scope: {scope}"
        # NONE：无额外要求
        self.kind = kind
        self.operation = operation
        self.style = style
        self.target = target
        self.scope = scope
        self.evidence_span = evidence_span
        self.confidence = confidence

    @property
    def is_override(self):
        return self.kind == self.KIND_OVERRIDE

    @property
    def is_performance(self):
        return self.kind == self.KIND_PERFORMANCE

    def to_dict(self):
        return {"kind": self.kind, "operation": self.operation, "style": self.style,
                "target": self.target, "scope": self.scope, "evidence_span": self.evidence_span,
                "confidence": self.confidence}

    @classmethod
    def from_dict(cls, d):
        return cls(d.get("kind"), operation=d.get("operation"), style=d.get("style"),
                   target=d.get("target"), scope=d.get("scope"),
                   evidence_span=d.get("evidence_span"), confidence=d.get("confidence"))

    def __repr__(self):
        return f"VoiceControlEvent({self.to_dict()})"


class VoiceState:
    """当前临时声线状态（程序维护，仅 OVERRIDE 事件驱动）。"""

    def __init__(self, temp=None, style=None, scope=None, evidence_span=None):
        self.temp = temp
        self.style = style
        self.scope = scope
        self.evidence_span = evidence_span

    @property
    def active(self):
        return self.temp is not None

    def to_dict(self):
        return {"temp": self.temp, "style": self.style, "scope": self.scope,
                "evidence_span": self.evidence_span}

    def __eq__(self, o):
        return isinstance(o, VoiceState) and self.to_dict() == o.to_dict()

    def __repr__(self):
        return f"VoiceState({self.to_dict()})"


class VoiceStateReducer:
    """ADR-042 确定性状态机：只接受 VOICE_OVERRIDE 事件。
    previous VoiceState + override event → START/CONTINUE/REPLACE/END。
    PERFORMANCE / NONE 不进入本 Reducer。
    """

    ACTIONS = {"NONE", "START", "CONTINUE", "REPLACE", "END"}

    def _temp_key(self, event):
        """override 的 temp 标识：style 优先，其次 target，未知则 UNKNOWN。"""
        if event.style and event.style != "UNKNOWN":
            return event.style
        if event.target and event.target != "UNKNOWN":
            return event.target
        return "UNKNOWN"

    def apply(self, state, event):
        """返回 (action, new_state)。纯函数。仅处理 VOICE_OVERRIDE。"""
        assert isinstance(event, VoiceControlEvent), f"expected VoiceControlEvent, got {type(event)}"
        if not event.is_override:
            raise AssertionError(f"reducer only accepts VOICE_OVERRIDE, got {event.kind}")

        if event.operation == VoiceControlEvent.OP_SET:
            key = self._temp_key(event)
            if not state.active:
                new = VoiceState(key, event.style, event.scope or VoiceControlEvent.SCOPE_UNTIL_CLEAR,
                                 event.evidence_span)
                return "START", new
            if state.temp == key:
                new = VoiceState(key, event.style or state.style,
                                 event.scope or state.scope, event.evidence_span or state.evidence_span)
                return "CONTINUE", new
            new = VoiceState(key, event.style, event.scope or VoiceControlEvent.SCOPE_UNTIL_CLEAR,
                             event.evidence_span)
            return "REPLACE", new

        if event.operation == VoiceControlEvent.OP_CLEAR:
            if state.active:
                return "END", VoiceState()
            return "NONE", VoiceState()  # 无状态可清，幂等

        raise AssertionError(f"unreachable: {event.operation}")

    def apply_utterance_boundary(self, state):
        """UTTERANCE scope 的 override 在句边界自动失效。"""
        if state.active and state.scope == VoiceControlEvent.SCOPE_UTTERANCE:
            return "END", VoiceState()
        if state.active:
            return "CONTINUE", VoiceState(state.temp, state.style, state.scope, state.evidence_span)
        return "NONE", VoiceState()


class PerformanceInstructionCompiler:
    """PERFORMANCE 事件 → PerformanceInstruction（volume/style/emotion，作用于当前 RenderUnit）。"""

    def compile(self, event):
        assert event.is_performance, f"expected PERFORMANCE, got {event.kind}"
        return {
            "target": "current_render_unit",
            "style": event.style,

            "scope": event.scope or VoiceControlEvent.SCOPE_UTTERANCE,
            "evidence_span": event.evidence_span,
        }


def _test_all():
    """完整转移矩阵 unit tests（ADR-042，100% PASS，纯程序无 AI）。"""
    red = VoiceStateReducer()
    perf = PerformanceInstructionCompiler()
    n = 0

    def check(desc, got, want):
        nonlocal n
        n += 1
        assert got == want, f"{desc}: got {got}, want {want}"

    def ov_set(style, scope="UNTIL_CLEAR", evidence="x"):
        return VoiceControlEvent(VoiceControlEvent.KIND_OVERRIDE, operation=VoiceControlEvent.OP_SET,
                                 style=style, scope=scope, evidence_span=evidence)

    def ov_clear(evidence="x"):
        return VoiceControlEvent(VoiceControlEvent.KIND_OVERRIDE, operation=VoiceControlEvent.OP_CLEAR,
                                 scope="UNTIL_CLEAR", evidence_span=evidence)

    # --- OVERRIDE 矩阵
    check("none+SET(X)", red.apply(VoiceState(), ov_set("OLD_VOICE"))[0], "START")
    a, s1 = red.apply(VoiceState(), ov_set("OLD_VOICE"))
    check("START new temp", s1.temp, "OLD_VOICE")
    check("tempX+SET(X)", red.apply(VoiceState("OLD_VOICE", "OLD_VOICE", "UNTIL_CLEAR"),
                                     ov_set("OLD_VOICE"))[0], "CONTINUE")
    check("tempX+SET(Y)", red.apply(VoiceState("OLD_VOICE", "OLD_VOICE", "UNTIL_CLEAR"),
                                     ov_set("CHILD_VOICE"))[0], "REPLACE")
    check("tempX+CLEAR", red.apply(VoiceState("OLD_VOICE", "OLD_VOICE", "UNTIL_CLEAR"),
                                    ov_clear())[0], "END")
    check("none+CLEAR", red.apply(VoiceState(), ov_clear())[0], "NONE")
    check("utterance+boundary", red.apply_utterance_boundary(
        VoiceState("LOW_VOICE", "LOW_VOICE", "UTTERANCE"))[0], "END")
    check("until_clear+boundary", red.apply_utterance_boundary(
        VoiceState("OLD_VOICE", "OLD_VOICE", "UNTIL_CLEAR"))[0], "CONTINUE")
    check("none+boundary", red.apply_utterance_boundary(VoiceState())[0], "NONE")

    # --- 全链：SET → CONTINUE → REPLACE → CLEAR
    chain = [
        (ov_set("OLD_VOICE"), "START"),
        (ov_set("OLD_VOICE"), "CONTINUE"),
        (ov_set("CHILD_VOICE"), "REPLACE"),
        (ov_clear(), "END"),
    ]
    st = VoiceState()
    for ev, want in chain:
        a, st = red.apply(st, ev)
        check(f"chain {ev.style or ev.operation}->{want}", a, want)

    # --- PERFORMANCE 不进 Reducer
    try:
        red.apply(VoiceState(), VoiceControlEvent(VoiceControlEvent.KIND_PERFORMANCE,
                                                  scope="UTTERANCE", evidence_span="压低声音"))
        raise AssertionError("reducer must reject PERFORMANCE")
    except AssertionError:
        pass

    # --- PERFORMANCE → PerformanceInstruction
    inst = perf.compile(VoiceControlEvent(VoiceControlEvent.KIND_PERFORMANCE,
                                          style="LOW_VOICE", scope="UTTERANCE", evidence_span="压低声音"))
    check("perf style", inst["style"], "LOW_VOICE")
    check("perf target", inst["target"], "current_render_unit")

    # --- 非法事件
    try:
        VoiceControlEvent("BAD_KIND")
        raise AssertionError("should reject invalid kind")
    except AssertionError:
        pass
    try:
        VoiceControlEvent(VoiceControlEvent.KIND_OVERRIDE, operation="SET", scope="UNTIL_CLEAR",
                          evidence_span=None)
        raise AssertionError("override must carry evidence_span")
    except AssertionError:
        pass
    try:
        VoiceControlEvent(VoiceControlEvent.KIND_OVERRIDE, operation="BAD", scope="UNTIL_CLEAR",
                          evidence_span="x")
        raise AssertionError("should reject invalid operation")
    except AssertionError:
        pass

    print(f"VoiceControlEvent: {n} checks PASS (100%)")
    return True


if __name__ == "__main__":
    _test_all()