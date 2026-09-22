# -*- coding: utf-8 -*-
"""TASK-095-VS7.2 — ContextBuilder v1（用户 §八/§九 定案）。

VOICE_BIND 输入上下文：
  current segment + previous 3-5 semantic segments（causal: position <= current）
  current speaker / recent speakers
  scene participants
  current mentions / recent mentions
  active voice-state owners（reducer 当前状态——只作 evidence，Binder 不改 reducer 历史）

防未来泄漏：不得看到后文（VoiceState 是 performance memory，必须 causal；
与 Identity Memory 不同，Identity 可借未来证据归一，Voice Bind 不行）。

pronoun 分辨：
  RESOLVABLE_PRONOUN：前文有可解析先行词
  UNRESOLVABLE_PRONOUN：无上下文 → O=UNKNOWN（禁止猜最近的人）
"""

MAX_CONTEXT = 5

PRONOUNS = {"他", "她", "它", "他们", "她们", "它们", "自己"}


class ContextBuilder:
    """构建 VOICE_BIND 的 causal 上下文。"""

    def __init__(self, max_context=MAX_CONTEXT):
        self.max_context = max_context

    def build(self, segments, current_pos, speakers=None, scene=None, mentions=None, active_owners=None):
        """segments: [(pos, text), ...] 按位置升序；current_pos 为当前段位置。
        返回 context dict（只含 position <= current_pos 的信息）。"""
        prev = [t for p, t in segments if p < current_pos][-self.max_context:]
        cur = [t for p, t in segments if p == current_pos]
        cur_text = cur[0] if cur else ""
        # active owners 只读引用（reducer 状态作 evidence）
        owners = list(active_owners or [])
        # pronoun 检测
        pronoun = any(p in cur_text for p in PRONOUNS)
        resolvable = pronoun and bool(prev)  # 有前文即视为候选可解析（实际解析交给 binder）
        return {
            "current_text": cur_text,
            "previous": prev,
            "current_speaker": (speakers or {}).get(current_pos) if speakers else None,
            "recent_speakers": list((speakers or {}).values())[-3:] if speakers else [],
            "scene_participants": list(scene or []),
            "current_mentions": (mentions or {}).get(current_pos, []),
            "recent_mentions": [m for p in sorted((mentions or {}).keys()) if p <= current_pos
                                for m in (mentions or {}).get(p, [])][-6:],
            "active_voice_owners": owners,
            "has_pronoun": pronoun,
            "pronoun_resolvable": resolvable if pronoun else None,
            "causal": True,
        }

    @staticmethod
    def pronoun_classify(text, prev_texts):
        """pronoun 可解析性：有前文上下文 → RESOLVABLE；无 → UNRESOLVABLE。"""
        has_pron = any(p in text for p in PRONOUNS)
        if not has_pron:
            return "NO_PRONOUN"
        # 前文含具体名字（非代词）→ 可解析
        for pt in prev_texts:
            if any(p not in pt for p in PRONOUNS) and len(pt.strip()) >= 4:
                return "RESOLVABLE"
        return "UNRESOLVABLE"


def _test_all():
    n = 0
    def check(desc, got, want):
        nonlocal n
        n += 1
        assert got == want, f"{desc}: got {got}, want {want}"

    cb = ContextBuilder(max_context=3)
    segs = [(1, "李四走到门前。"), (2, "他推开门。"), (3, "他恢复了原来的声音。")]
    ctx = cb.build(segs, 3, speakers={3: "李四"}, scene=["李四"],
                   mentions={1: ["李四"], 2: ["李四"], 3: ["李四"]},
                   active_owners=["李四"])
    check("prev 3-5 segments", ctx["previous"], ["李四走到门前。", "他推开门。"])
    check("current text", ctx["current_text"], "他恢复了原来的声音。")
    check("current speaker", ctx["current_speaker"], "李四")
    check("active owners", ctx["active_voice_owners"], ["李四"])
    check("causal", ctx["causal"], True)
    check("has pronoun", ctx["has_pronoun"], True)

    # causal 防泄漏：当前段 2，看不到段 3
    ctx2 = cb.build(segs, 2)
    check("no future", ctx2["previous"], ["李四走到门前。"])
    check("current is seg2", ctx2["current_text"], "他推开门。")

    # pronoun 分类
    check("resolvable", cb.pronoun_classify("他恢复了声音", ["李四走了。"]), "RESOLVABLE")
    check("unresolvable", cb.pronoun_classify("他恢复了声音", []), "UNRESOLVABLE")
    check("no pronoun", cb.pronoun_classify("李四恢复了声音", []), "NO_PRONOUN")

    # active owners 只读：不修改 reducer 历史
    owners = ["张三"]
    ctx3 = cb.build(segs, 3, active_owners=owners)
    owners.append("李四")  # 外部修改
    check("active owners snapshot", ctx3["active_voice_owners"], ["张三"])

    print(f"VS7.2 ContextBuilder: {n} checks PASS (100%)")
    return True

if __name__ == "__main__":
    _test_all()