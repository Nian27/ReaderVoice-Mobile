# -*- coding: utf-8 -*-
"""PC-001 v0.3: CharacterResolver v2（三层：alias 优先 -> ROLE_ADDRESS 过滤 -> MENTION 兜底）。
读 director.jsonl（MENTION+SPEAKER 原始输出），产出 speaker_v2 + character_id + 过滤统计。
"""
import json
import os

PC001 = r"E:\AndroidStudioProjects\ReaderVoiceMobile\training\readerdirector\runs\voice_state\voice_factory\pc001"

# alias（CharacterVoiceDB 同源；博士=阿笠博士 等语境角色加入）
ALIAS = {
    "谭越": "C001", "老谭": "C001", "小谭": "C001",
    "柯南": "C002", "工藤": "C002", "小侦探": "C002",
    "叶洋": "C003", "叶师兄": "C003", "叶师弟": "C003",
    "许诺": "C004", "目暮警官": "C005", "齐雪": "C006",
    "北川太太": "C007", "林枫": "C008", "阿笠博士": "C002", "博士": "C002",
}

# ROLE_ADDRESS / PRONOUN / 泛称 —— 不当角色（不进 VoiceFactory）
BLACKLIST = {
    "她", "他", "我", "你", "您", "咱", "咱家", "我们", "你们", "他们", "她们", "我辈修士",
    "老奴", "小的", "属下", "在下", "老夫", "老身", "师兄", "师父", "师傅", "先生", "女士",
    "老婆", "老公", "少爷", "小姐", "大哥", "大姐", "大叔", "阿姨", "老佛爷", "老爷", "夫人",
    "老师", "同学", "首长", "老板", "掌柜", "总策划", "少儿频道", "软男", "女生", "男生",
    "这人", "那人", "其他人", "诸位", "大家", "众弟子", "师兄弟", "宗门", "人族", "诸族",
    "天下英雄", "我等", "媒妁", "小偷", "狗腿子", "破落户", "贩毒组织", "大哥哥", "爷爷奶奶",
    "老爸", "老妈", "老", "嫂子", "爷爷", "奶奶", "王师弟", "新进宗门的弟子", "师侄", "徒儿",
}


def main():
    rows = [json.loads(l) for l in open(os.path.join(PC001, "director.jsonl"), encoding="utf-8")]
    stats = {"dialogue": 0, "alias_hit": 0, "blacklist_filtered": 0, "mention_fallback": 0, "null": 0}
    out = []
    for r in rows:
        rec = dict(r)
        if r["segment_type"] != "DIALOGUE":
            out.append(rec)
            continue
        stats["dialogue"] += 1
        sp = r.get("speaker")
        mentions = r.get("mentions") or []
        # L1: alias 优先
        if sp and sp in ALIAS:
            rec["speaker_v2"] = sp
            rec["character_id"] = ALIAS[sp]
            stats["alias_hit"] += 1
            out.append(rec)
            continue
        # L1b: mention 中命中 alias（MENTION 发现的别名）
        alias_hit = None
        for m in mentions:
            if m in ALIAS:
                alias_hit = m
                break
        if alias_hit:
            rec["speaker_v2"] = alias_hit
            rec["character_id"] = ALIAS[alias_hit]
            stats["alias_hit"] += 1
            out.append(rec)
            continue
        # L2: ROLE_ADDRESS / 泛称过滤
        if sp and sp in BLACKLIST:
            rec["speaker_v2"] = None
            rec["character_id"] = None
            rec["filtered"] = sp
            stats["blacklist_filtered"] += 1
            out.append(rec)
            continue
        if sp:
            rec["speaker_v2"] = sp
            rec["character_id"] = None
            out.append(rec)
            continue
        # L3: MENTION 兜底：唯一 PERSON mention → 采用
        person_mentions = [m for m in mentions if m not in BLACKLIST]
        if len(person_mentions) == 1:
            rec["speaker_v2"] = person_mentions[0]
            rec["character_id"] = ALIAS.get(person_mentions[0])
            stats["mention_fallback"] += 1
            out.append(rec)
            continue
        rec["speaker_v2"] = None
        rec["character_id"] = None
        stats["null"] += 1
        out.append(rec)
    with open(os.path.join(PC001, "director_v2.jsonl"), "w", encoding="utf-8") as f:
        for r in out:
            f.write(json.dumps(r, ensure_ascii=False) + "\n")
    print("stats:", json.dumps(stats, ensure_ascii=False), flush=True)
    print("DONE", flush=True)


if __name__ == "__main__":
    main()
