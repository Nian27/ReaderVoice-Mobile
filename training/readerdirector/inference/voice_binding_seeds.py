# -*- coding: utf-8 -*-
"""TASK-095-VS6.2 — deterministic binding seeds（高 precision 规则，非全召回）。

只处理真正确定的情况（用户规格：高 precision seeds，不追求全覆盖）：
  1. 所有格 owner：X的声音/嗓音/声线 变化 → owner=X
  2. 模仿目标 reference：模仿/学着/装成/换成 X的声音/嗓音/语气 → owner=动作主语，ref=X
  3. CLEAR owner：X恢复了/变回了 自己的声音 → owner=X
  4. 附身/控制（初步）：魔尊控制着林雪的身体，用自己的声音 → 无法单句定 → 交 AI（本规则不处理）

输入：候选列表（opaque C0..Cn + surface 名），文本，事件。
输出：VoiceEventBinding 或 None（无法确定 → 交 AI）。
"""
import re

from voice_event_binding import VoiceEventBinding, BINDING_BOUND, BINDING_PENDING


def _surface_to_candidate(surface, candidates):
    """surface → candidate id；找不到返回 None。"""
    for c in candidates:
        if c.get("name") == surface or surface in c.get("name", ""):
            return c["role"]
    # 名字前缀匹配（张三 vs 张三丰：短名优先）
    best = None
    for c in candidates:
        n = c.get("name", "")
        if n and (n.startswith(surface) or surface.startswith(n)):
            if best is None or len(n) < len(best[1]):
                best = (c["role"], n)
    return best[0] if best else None


def deterministic_bind(event, text, candidates, segment_id=None, current_speaker=None):
    """返回 (binding, applied)。applied=False 表示规则无法确定，交 AI。"""
    cand_map = {c["role"]: c["name"] for c in candidates}
    etype = event.event_type if hasattr(event, "event_type") else str(event)
    event_id = getattr(event, "event_id", None) or f"ve-{segment_id}"

    # ---------- 模式 1：所有格 owner ----------
    # “X的声音/嗓音/声线/腔调 + 变化动词” → owner=X（含被动：“X的嗓音被处理成”）
    m = re.search(r"([\u4e00-\u9fa5A-Za-z·]{2,8})的(声音|嗓音|声线|音色|腔调|口音|嗓子)(?:突然|忽然|竟|却|已经|又)?(变|换|恢复|改变|模仿|压低|消失|被处理|被调)", text)
    if m:
        surf = m.group(1)
        if re.search("(听见|听到|看到|看见|发现|觉得|感觉|告诉|让|叫|令|使|对|向)", surf):
            m = None  # observer/causer prefix pollution -> skip rule 1
    if m and etype in ("VOICE_OVERRIDE_SET", "VOICE_OVERRIDE_CLEAR", "PERFORMANCE"):
        owner = _surface_to_candidate(m.group(1), candidates)
        if owner:
            return (VoiceEventBinding(event_id=event_id, voice_state_owner_role_id=owner,
                                      reference_role_id="NONE", binding_status=BINDING_BOUND,
                                      binding_source="DETERMINISTIC_SEED",
                                      owner_evidence_span=m.group(0), candidates=candidates), True)

    # ---------- 模式 2：模仿目标 reference ----------
    # “(主语)模仿/学着/装成/换成/假装 X的声音/嗓音/语气” → owner=动作主语（如可辨），ref=X
    m2 = re.search(r"([\u4e00-\u9fa5A-Za-z·]{2,8})(?:模仿|学着|学|装成|换成|假装|学着|扮作)([\u4e00-\u9fa5A-Za-z·]{2,8})的(声音|嗓音|声线|语气|腔调|口音)", text)
    if m2 and etype == "VOICE_OVERRIDE_SET":
        actor, target = m2.group(1), m2.group(2)
        ref = _surface_to_candidate(target, candidates)
        owner = _surface_to_candidate(actor, candidates)
        if ref:
            owner_id = owner if owner else "UNKNOWN"
            status = BINDING_BOUND if owner else BINDING_PENDING
            return (VoiceEventBinding(event_id=event_id, voice_state_owner_role_id=owner_id,
                                      reference_role_id=ref, binding_status=status,
                                      binding_source="DETERMINISTIC_SEED",
                                      owner_evidence_span=m2.group(0), candidates=candidates), True)

    # ---------- 模式 3：CLEAR owner ----------
    # “X恢复/变回(了)自己的声音/原声” → owner=X
    m3 = re.search(r"([\u4e00-\u9fa5A-Za-z·]{2,8})(?:恢复|变回|回到)(?:了|成)?(?:自己|原本|原来)?的?(声音|原声|嗓音|声线)", text)
    if m3 and etype == "VOICE_OVERRIDE_CLEAR":
        owner = _surface_to_candidate(m3.group(1), candidates)
        if owner:
            return (VoiceEventBinding(event_id=event_id, voice_state_owner_role_id=owner,
                                      reference_role_id="NONE", binding_status=BINDING_BOUND,
                                      binding_source="DETERMINISTIC_SEED",
                                      owner_evidence_span=m3.group(0), candidates=candidates), True)

    return (VoiceEventBinding(event_id=event_id, voice_state_owner_role_id="UNKNOWN",
                              reference_role_id="UNKNOWN", binding_status=BINDING_PENDING,
                              binding_source="UNRESOLVED", candidates=candidates), False)


def _test_all():
    n = 0
    def check(desc, got, want):
        nonlocal n
        n += 1
        assert got == want, f"{desc}: got {got}, want {want}"

    cands = [
        {"role": "C0", "name": "张三"},
        {"role": "C1", "name": "李四"},
        {"role": "C2", "name": "王五"},
    ]

    class FakeEvent:

        def __init__(self, t, eid=None):

            self.event_type = t
            self.event_id = eid

    # 所有格 owner
    b, app = deterministic_bind(FakeEvent("VOICE_OVERRIDE_SET"), "李四的声音突然变成了老人", cands)
    check("possessive applied", app, True)
    check("possessive owner", b.voice_state_owner_role_id, "C1")
    check("possessive bound", b.is_bound, True)

    # 模仿目标
    b2, app2 = deterministic_bind(FakeEvent("VOICE_OVERRIDE_SET"), "李四模仿王五的声音说话", cands)
    check("imitation applied", app2, True)
    check("imitation owner", b2.voice_state_owner_role_id, "C1")
    check("imitation ref", b2.reference_role_id, "C2")

    # 模仿目标：actor 不在候选 → owner UNKNOWN/PENDING，ref 仍绑定
    b3, app3 = deterministic_bind(FakeEvent("VOICE_OVERRIDE_SET"), "黑衣人模仿王五的声音说话", cands)
    check("unknown actor applied", app3, True)
    check("unknown actor owner", b3.voice_state_owner_role_id, "UNKNOWN")
    check("unknown actor pending", b3.binding_status, BINDING_PENDING)
    check("unknown actor ref", b3.reference_role_id, "C2")

    # CLEAR owner
    b4, app4 = deterministic_bind(FakeEvent("VOICE_OVERRIDE_CLEAR"), "李四恢复了原本的声音", cands)
    check("clear applied", app4, True)
    check("clear owner", b4.voice_state_owner_role_id, "C1")

    # 观察者陷阱：句首是 observer → 规则不适用（不能绑 observer），交 AI；但纯所有格不含感知词时仍可绑
    b5, app5 = deterministic_bind(FakeEvent("VOICE_OVERRIDE_SET"), "张三听见李四的声音突然变了", cands)
    check("observer trap not applied", app5, False)
    check("observer trap pending", b5.binding_status, BINDING_PENDING)
    check("observer trap owner unknown", b5.voice_state_owner_role_id, "UNKNOWN")

    # 无法确定 → UNRESOLVED
    b6, app6 = deterministic_bind(FakeEvent("VOICE_OVERRIDE_SET"), "不知怎的，那道声音又响了起来", cands)
    check("unresolved applied", app6, False)
    check("unresolved pending", b6.binding_status, BINDING_PENDING)

    # 候选校验：规则输出必须合法
    from voice_event_binding import validate_binding
    ok, err = validate_binding(b, [c["role"] for c in cands], "VOICE_OVERRIDE_SET")
    check("seed valid", ok, True)

    print(f"VS6.2 deterministic seeds: {n} checks PASS (100%)")
    return True


if __name__ == "__main__":
    _test_all()