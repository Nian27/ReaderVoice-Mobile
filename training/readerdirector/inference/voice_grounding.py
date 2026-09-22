# -*- coding: utf-8 -*-
"""TASK-095-VS6-GROUNDING v2 — Host GroundingResolver（结构化 owner_surface 优先）。

用户 §9：AI 返回 owner_surface（原文逐字名字）+ owner(ID)；Host 校验一致性：
- owner_surface 能唯一映射候选 → 以此为准（HOST_CORRECTED）
- owner_surface 无法映射（代词/描述）→ 保留 AI 的 owner ID
- owner ID 与 surface 冲突 → surface 优先
"""

from voice_event_binding import VoiceEventBinding, BINDING_BOUND, BINDING_PENDING


class GroundingResolver:
    """Host 侧 ID 重对齐（结构化 surface 优先）。"""

    @staticmethod
    def surface_to_id(surface, candidates):
        """surface（名字）→ 唯一候选 ID。"""
        if not surface or surface in ("UNKNOWN", "NONE", None):
            return None
        matches = [c["role"] for c in candidates if c.get("name") == surface]
        if len(matches) == 1:
            return matches[0]
        return None

    def resolve(self, pred, candidates):
        """返回 (resolved_owner, status, note)。owner_surface 优先。"""
        owner = (pred or {}).get("owner")
        surf = (pred or {}).get("owner_surface")
        rid = self.surface_to_id(surf, candidates)
        if rid and rid != owner and rid != "UNKNOWN":
            return rid, "HOST_CORRECTED", f"surface {surf}->{rid} (was {owner})"
        if rid and owner in (None, "UNKNOWN"):
            return rid, "HOST_CORRECTED_FROM_UNKNOWN", f"surface {surf}->{rid}"
        # surface 无法映射：代词等 → 保留 AI 判断（§11）
        return owner, "UNCHANGED", None

    def resolve_reference(self, pred, candidates):
        """reference 同理。"""
        ref = (pred or {}).get("reference")
        surf = (pred or {}).get("reference_surface")
        rid = self.surface_to_id(surf, candidates)
        if rid and ref in ("UNKNOWN", None, "NONE"):
            return rid, "HOST_CORRECTED_FROM_UNKNOWN", f"ref surface {surf}->{rid}"
        if rid and rid != ref:
            return rid, "HOST_CORRECTED", f"ref surface {surf}->{rid} (was {ref})"
        return ref, "UNCHANGED", None


def _test_all():
    n = 0
    def check(desc, got, want):
        nonlocal n
        n += 1
        assert got == want, f"{desc}: got {got}, want {want}"

    cands = [{"role": "C0", "name": "张三"}, {"role": "C1", "name": "李四"},
             {"role": "C2", "name": "王五"}, {"role": "C3", "name": "赵六"}]
    res = GroundingResolver()

    # surface 正确、ID 错位 → 修正
    o, st, _ = res.resolve({"owner": "C2", "owner_surface": "赵六"}, cands)
    check("surface fixes id", o, "C3")
    check("status", st, "HOST_CORRECTED")

    # surface 与 ID 一致 → 不变
    o2, st2, _ = res.resolve({"owner": "C3", "owner_surface": "赵六"}, cands)
    check("consistent", o2, "C3")
    check("unchanged", st2, "UNCHANGED")

    # UNKNOWN + surface → 修正
    o3, st3, _ = res.resolve({"owner": "UNKNOWN", "owner_surface": "李四"}, cands)
    check("unknown->surface", o3, "C1")

    # 代词无 surface → 保留
    o4, st4, _ = res.resolve({"owner": "UNKNOWN", "owner_surface": None}, cands)
    check("pronoun kept", o4, "UNKNOWN")
    check("pronoun status", st4, "UNCHANGED")

    # reference 修正
    r5, st5, _ = res.resolve_reference({"reference": "C2", "reference_surface": "王五"}, cands)
    check("ref consistent", r5, "C2")
    r6, st6, _ = res.resolve_reference({"reference": "NONE", "reference_surface": "赵六"}, cands)
    check("ref unknown->surface", r6, "C3")
    check("ref status", st6, "HOST_CORRECTED_FROM_UNKNOWN")

    print(f"VS6-GROUNDING v2: {n} checks PASS (100%)")
    return True


if __name__ == "__main__":
    _test_all()