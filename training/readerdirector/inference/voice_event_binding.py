# -*- coding: utf-8 -*-
"""TASK-095-VS6.1 — VoiceEventBinding schema + validator（ADR-042 扩展）。

三个角色严格分离（用户定案）：
  voice_state_owner_role_id : 谁的声音状态发生变化（Voice State Owner）
  reference_role_id         : 模仿/变成谁的声音（Reference Voice，可空）
  observer/causer           : 谁听见/看见/促成（不进入 binding，仅作候选上下文）

subject_role_id 语义 = Voice State Owner（兼容旧字段）。

binding_status: BOUND / PENDING_BINDING / AMBIGUOUS / INVALID
- owner UNKNOWN → PENDING_BINDING（不修改 timeline）
- owner 已知、reference UNKNOWN → BOUND（可建 timeline，TTS 阶段再解 reference）

候选纪律：owner ∈ candidates ∪ {UNKNOWN}；reference ∈ candidates ∪ {NONE, UNKNOWN}；
禁止自由名字输出；禁止生成候选不存在的人（host 校验）。
"""

BINDING_BOUND = "BOUND"
BINDING_PENDING = "PENDING_BINDING"
BINDING_AMBIGUOUS = "AMBIGUOUS"
BINDING_INVALID = "INVALID"
VALID_STATUSES = {BINDING_BOUND, BINDING_PENDING, BINDING_AMBIGUOUS, BINDING_INVALID}


class VoiceEventBinding:
    """事件-角色绑定记录。"""

    def __init__(self, event_id, voice_state_owner_role_id=None, reference_role_id=None,
                 owner_confidence=None, reference_confidence=None,
                 owner_evidence_span=None, binding_source=None, binding_status=BINDING_PENDING,
                 candidates=None, note=None):
        self.event_id = event_id
        self.voice_state_owner_role_id = voice_state_owner_role_id  # C0..Cn 或 UNKNOWN
        self.reference_role_id = reference_role_id                # C0..Cn 或 NONE/UNKNOWN
        self.owner_confidence = owner_confidence
        self.reference_confidence = reference_confidence
        self.owner_evidence_span = owner_evidence_span
        self.binding_source = binding_source  # DETERMINISTIC_SEED / AI_4B / USER_LOCKED
        self.binding_status = binding_status
        self.candidates = candidates or []
        self.note = note

    @property
    def is_bound(self):
        return self.binding_status == BINDING_BOUND

    @property
    def owner_known(self):
        return self.voice_state_owner_role_id not in (None, "UNKNOWN")

    @property
    def reference_known(self):
        return self.reference_role_id not in (None, "NONE", "UNKNOWN")

    def to_dict(self):
        return {k: getattr(self, k) for k in (
            "event_id", "voice_state_owner_role_id", "reference_role_id",
            "owner_confidence", "reference_confidence", "owner_evidence_span",
            "binding_source", "binding_status", "candidates", "note")}

    @classmethod
    def from_dict(cls, d):
        return cls(**d)

    def __repr__(self):
        return f"Binding({self.binding_status} owner={self.voice_state_owner_role_id} ref={self.reference_role_id})"


def validate_binding(binding, candidate_ids, event_type):
    """host 权威校验（G6-6）。返回 (ok, error)。
    candidate_ids: 该事件上下文中的合法 opaque ID 集合（不含 UNKNOWN/NONE）。
    """
    errs = []
    cands = set(candidate_ids)

    # 1. status 合法
    if binding.binding_status not in VALID_STATUSES:
        return False, f"invalid binding_status: {binding.binding_status}"

    # 2. owner ∈ candidates ∪ {UNKNOWN}；禁止自由名字（非 C 前缀且非 UNKNOWN → 幻觉）
    owner = binding.voice_state_owner_role_id
    if owner not in (None, "UNKNOWN"):
        if owner not in cands:
            errs.append(f"owner {owner} not in candidates {sorted(cands)}")
    # UNKNOWN/NONE 合法（PENDING_BINDING）
    if owner == "UNKNOWN" and binding.binding_status == BINDING_BOUND:
        errs.append("owner UNKNOWN cannot be BOUND; must be PENDING_BINDING")

    # 3. reference ∈ candidates ∪ {NONE, UNKNOWN}
    ref = binding.reference_role_id
    if ref not in (None, "NONE", "UNKNOWN"):
        if ref not in cands:
            errs.append(f"reference {ref} not in candidates {sorted(cands)}")

    # 4. 幻觉检查：任何非 C 前缀的 ID（非 UNKNOWN/NONE）→ 非法
    for x in (owner, ref):
        if x in (None, "UNKNOWN", "NONE"):
            continue
        if not (isinstance(x, str) and (x.startswith("C") or x.startswith("c"))):
            errs.append(f"free-form id not allowed: {x!r}")

    # 5. PERFORMANCE 事件不应进入持久 binder（G6 纪律：binder 不重判 event；但 PERFORMANCE 无 owner 语义）
    if event_type == "PERFORMANCE" and binding.binding_status == BINDING_BOUND and owner is not None:
        # PERFORMANCE 也可以有 owner（谁在表演），但不影响 timeline；允许但不强求
        pass

    if errs:
        return False, "; ".join(errs)
    return True, None


def _test_all():
    n = 0
    def check(desc, got, want):
        nonlocal n
        n += 1
        assert got == want, f"{desc}: got {got}, want {want}"

    cands = ["C0", "C1", "C2"]

    # 合法：BOUND owner=C1 ref=C2
    b = VoiceEventBinding(event_id="ve-1", voice_state_owner_role_id="C1", reference_role_id="C2",
                          binding_status=BINDING_BOUND, binding_source="DETERMINISTIC_SEED", candidates=cands)
    ok, err = validate_binding(b, cands, "VOICE_OVERRIDE_SET")
    check("bound ok", ok, True)
    check("no err", err, None)

    # 合法：owner UNKNOWN → PENDING
    b2 = VoiceEventBinding(event_id="ve-2", voice_state_owner_role_id="UNKNOWN", reference_role_id="C2",
                           binding_status=BINDING_PENDING, binding_source="AI_4B", candidates=cands)
    ok2, _ = validate_binding(b2, cands, "VOICE_OVERRIDE_SET")
    check("pending ok", ok2, True)

    # 非法：owner UNKNOWN 但 status=BOUND
    b3 = VoiceEventBinding(event_id="ve-3", voice_state_owner_role_id="UNKNOWN", reference_role_id=None,
                           binding_status=BINDING_BOUND, binding_source="AI_4B", candidates=cands)
    ok3, e3 = validate_binding(b3, cands, "VOICE_OVERRIDE_SET")
    check("unknown cannot bound", ok3, False)

    # 非法：自由名字（幻觉）
    b4 = VoiceEventBinding(event_id="ve-4", voice_state_owner_role_id="李四", reference_role_id=None,
                           binding_status=BINDING_BOUND, binding_source="AI_4B", candidates=cands)
    ok4, e4 = validate_binding(b4, cands, "VOICE_OVERRIDE_SET")
    check("free name rejected", ok4, False)
    check("free name msg", "free-form" in e4, True)

    # 非法：owner 不在候选
    b5 = VoiceEventBinding(event_id="ve-5", voice_state_owner_role_id="C9", reference_role_id=None,
                           binding_status=BINDING_BOUND, binding_source="AI_4B", candidates=cands)
    ok5, e5 = validate_binding(b5, cands, "VOICE_OVERRIDE_SET")
    check("non-candidate rejected", ok5, False)

    # 合法：reference NONE
    b6 = VoiceEventBinding(event_id="ve-6", voice_state_owner_role_id="C1", reference_role_id="NONE",
                           binding_status=BINDING_BOUND, binding_source="DETERMINISTIC_SEED", candidates=cands)
    ok6, _ = validate_binding(b6, cands, "VOICE_OVERRIDE_CLEAR")
    check("ref none ok", ok6, True)

    # 合法：owner 已知、reference UNKNOWN（可建 timeline）
    b7 = VoiceEventBinding(event_id="ve-7", voice_state_owner_role_id="C1", reference_role_id="UNKNOWN",
                           binding_status=BINDING_BOUND, binding_source="AI_4B", candidates=cands)
    ok7, _ = validate_binding(b7, cands, "VOICE_OVERRIDE_SET")
    check("owner known ref unknown ok", ok7, True)
    check("owner known", b7.owner_known, True)
    check("ref unknown", b7.reference_known, False)

    # INVALID status
    b8 = VoiceEventBinding(event_id="ve-8", voice_state_owner_role_id="C1", reference_role_id=None,
                           binding_status="WEIRD", binding_source="AI_4B", candidates=cands)
    ok8, _ = validate_binding(b8, cands, "VOICE_OVERRIDE_SET")
    check("invalid status", ok8, False)

    # 序列化往返
    d = b.to_dict()
    b9 = VoiceEventBinding.from_dict(d)
    check("roundtrip owner", b9.voice_state_owner_role_id, "C1")
    check("roundtrip status", b9.binding_status, BINDING_BOUND)

    print(f"VS6.1 VoiceEventBinding: {n} checks PASS (100%)")
    return True


if __name__ == "__main__":
    _test_all()