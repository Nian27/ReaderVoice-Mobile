# -*- coding: utf-8 -*-
"""TASK-095-VS4 — deterministic VoiceStateEngine（ADR-041/042 完整落地）。

三层持久状态：BaseVoice → VoicePhase → TemporaryOverride
EffectiveVoice = BaseVoice + VoicePhase + TemporaryOverride（渲染用）
Performance（单句）不进入持久栈，独立叠加。

动作语义：
  NONE         state unchanged, performance = neutral
  PERFORMANCE  persistent unchanged, performance = event（下一句默认清除）
  OVERRIDE_SET none→START；同 key→CONTINUE；异 key→REPLACE
  OVERRIDE_CLEAR active→END 恢复下层；无 active→ORPHAN_CLEAR（诊断，state unchanged）
"""

from voice_event import VoiceControlEvent, VoiceState


class VoiceLayer:
    """三层状态容器。"""

    def __init__(self, base_voice=None, phase=None, override=None):
        self.base_voice = base_voice  # 角色长期基础音色
        self.phase = phase            # VoicePhase（如 成年/老年/黑化 阶段）
        self.override = override      # TemporaryOverride（VoiceState）

    @property
    def effective(self):
        return {"base_voice": self.base_voice, "phase": self.phase,
                "override": self.override.to_dict() if self.override else None}

    def __repr__(self):
        return f"VoiceLayer(base={self.base_voice}, phase={self.phase}, override={self.override})"


class ReducerResult:
    """reducer 输出：动作 + 新状态 + 诊断。"""

    def __init__(self, action, layer, diagnostic=None, performance=None):
        self.action = action              # NONE/START/CONTINUE/REPLACE/END/ORPHAN_CLEAR
        self.layer = layer
        self.diagnostic = diagnostic      # ORPHAN_CLEAR 等
        self.performance = performance    # PERFORMANCE 事件编译结果（单句）

    def __repr__(self):
        return f"ReducerResult({self.action})"


class VoiceStateEngine:
    """确定性状态引擎：previous VoiceLayer + VoiceControlEvent → ReducerResult。"""

    def __init__(self, base_voice=None):
        self.layer = VoiceLayer(base_voice=base_voice)

    def apply(self, event):
        """应用一个事件到当前引擎状态（就地更新）。"""
        assert isinstance(event, VoiceControlEvent), f"expected VoiceControlEvent, got {type(event)}"

        # UTTERANCE scope 的 override 已过句边界：任何新事件到达时自动失效（END）
        if self.layer.override is not None and self.layer.override.scope == VoiceControlEvent.SCOPE_UTTERANCE:
            self.layer.override = None
            return ReducerResult("END", self.layer,
                                  diagnostic={"code": "UTTERANCE_BOUNDARY", "note": "utterance-scope override auto-expired"})

        # NONE：不变
        if event.kind == VoiceControlEvent.KIND_NONE:
            return ReducerResult("NONE", self.layer)

        # PERFORMANCE：持久不变，性能只作用于当前句
        if event.kind == VoiceControlEvent.KIND_PERFORMANCE:
            perf = {"style": event.style, "scope": event.scope or "UTTERANCE",
                    "evidence_span": event.evidence_span}
            return ReducerResult("PERFORMANCE", self.layer, performance=perf)

        # VOICE_OVERRIDE
        if event.kind == VoiceControlEvent.KIND_OVERRIDE:
            if event.operation == VoiceControlEvent.OP_SET:
                key = self._temp_key(event)
                cur = self.layer.override
                if cur is None:
                    self.layer.override = VoiceState(key, event.style, event.scope or "UNTIL_CLEAR", event.evidence_span)
                    return ReducerResult("START", self.layer)
                if cur.temp == key:
                    cur.style = event.style or cur.style
                    cur.scope = event.scope or cur.scope
                    cur.evidence_span = event.evidence_span or cur.evidence_span
                    return ReducerResult("CONTINUE", self.layer)
                self.layer.override = VoiceState(key, event.style, event.scope or "UNTIL_CLEAR", event.evidence_span)
                return ReducerResult("REPLACE", self.layer)

            if event.operation == VoiceControlEvent.OP_CLEAR:
                if self.layer.override is None:
                    # 无 active override：ORPHAN_CLEAR 诊断（state unchanged，进 Problem Inbox）
                    return ReducerResult("ORPHAN_CLEAR", self.layer,
                                          diagnostic={"code": "ORPHAN_CLEAR",
                                                      "evidence": event.evidence_span,
                                                      "note": "CLEAR without active override; state unchanged"})
                self.layer.override = None
                # 恢复下层：若 phase 存在恢复 phase，否则 base
                restored = self.layer.phase or self.layer.base_voice
                return ReducerResult("END", self.layer, diagnostic={"restored": restored})

        raise AssertionError(f"unreachable: {event.kind}/{event.operation}")

    @staticmethod
    def _temp_key(event):
        if event.style and event.style != "UNKNOWN":
            return event.style
        if event.target and event.target != "UNKNOWN":
            return event.target
        return "UNKNOWN"

    def apply_sequence(self, events):
        """批量应用事件序列，返回逐条结果。"""
        return [self.apply(e) for e in events]

    @property
    def effective_voice(self):
        """当前有效声音（渲染用）。"""
        return self.layer.effective


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

    def perf(style, evidence="p"):
        return VoiceControlEvent("PERFORMANCE", style=style, scope="UTTERANCE", evidence_span=evidence)

    def none_ev():

        return VoiceControlEvent("NONE")

    # 1. NONE：不变
    eng = VoiceStateEngine(base_voice="v17")
    r = eng.apply(none_ev())
    check("none action", r.action, "NONE")
    check("none no override", eng.layer.override, None)

    # 2. SET：START
    r = eng.apply(ov_set("OLD_VOICE"))
    check("set->START", r.action, "START")
    check("override temp", eng.layer.override.temp, "OLD_VOICE")

    # 3. PERFORMANCE：持久不变 + 单句性能
    r = eng.apply(perf("LOW_VOICE", "压低声音"))
    check("perf action", r.action, "PERFORMANCE")
    check("perf style", r.performance["style"], "LOW_VOICE")
    check("perf 不改 override", eng.layer.override.temp, "OLD_VOICE")

    # 4. 同 key SET：CONTINUE
    r = eng.apply(ov_set("OLD_VOICE"))
    check("same key -> CONTINUE", r.action, "CONTINUE")

    # 5. 异 key SET：REPLACE
    r = eng.apply(ov_set("CHILD_VOICE"))
    check("diff key -> REPLACE", r.action, "REPLACE")
    check("replaced temp", eng.layer.override.temp, "CHILD_VOICE")

    # 6. CLEAR：END + 恢复 base
    r = eng.apply(ov_clear("恢复原声"))
    check("clear -> END", r.action, "END")
    check("override cleared", eng.layer.override, None)

    # 7. 无 active 的 CLEAR：ORPHAN_CLEAR（state unchanged）
    r = eng.apply(ov_clear("恢复原声"))
    check("orphan clear", r.action, "ORPHAN_CLEAR")
    check("orphan diag", r.diagnostic["code"], "ORPHAN_CLEAR")

    # 8. PERFORMANCE 不泄漏到下一句（关键泄漏测试）
    eng2 = VoiceStateEngine(base_voice="v17")
    eng2.apply(perf("LOW_VOICE", "压低声音"))
    # 下一句无事件 → NONE，性能不带入
    r = eng2.apply(none_ev())
    check("perf not leak", r.action, "NONE")
    check("perf not in state", eng2.layer.override, None)

    # 9. 跨段序列：SET → NONE×2 → REPLACE → CLEAR（模拟 53 章长链）
    eng3 = VoiceStateEngine(base_voice="v1")
    chain = [
        (ov_set("OLD_MAN"), "START"),
        (none_ev(), "NONE"),
        (none_ev(), "NONE"),
        (ov_set("WOMAN"), "REPLACE"),
        (ov_clear(), "END"),
        (none_ev(), "NONE"),
    ]
    for ev, want in chain:
        r = eng3.apply(ev)
        check(f"chain {ev.kind}->{want}", r.action, want)
    check("final no override", eng3.layer.override, None)

    # 10. UTTERANCE scope override 句边界自动失效
    eng4 = VoiceStateEngine(base_voice="v1")
    eng4.apply(ov_set("LOW_VOICE", scope="UTTERANCE"))
    r = eng4.apply(none_ev())  # 句边界
    check("utterance boundary -> END", r.action, "END")
    check("utterance override cleared", eng4.layer.override, None)

    print(f"VS4 VoiceStateEngine: {n} checks PASS (100%)")
    return True


if __name__ == "__main__":
    _test_all()