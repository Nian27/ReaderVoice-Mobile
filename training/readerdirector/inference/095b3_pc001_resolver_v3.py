# -*- coding: utf-8 -*-
"""PC-001 v0.4: Resolver v3 — A NULL 上下文恢复 / B 亲属称谓 / C 引号重分类。
读 director_v2.jsonl → director_v3.jsonl（speaker_v3/character_id/confidence/reclassify/relation）。
"""
import json
import os
from collections import Counter

PC001 = r"E:\AndroidStudioProjects\ReaderVoiceMobile\training\readerdirector\runs\voice_state\voice_factory\pc001"

ALIAS = {
    "谭越": "C001", "老谭": "C001", "小谭": "C001",
    "柯南": "C002", "工藤": "C002", "小侦探": "C002",
    "叶洋": "C003", "叶师兄": "C003", "叶师弟": "C003",
    "许诺": "C004", "目暮警官": "C005", "齐雪": "C006",
    "北川太太": "C007", "林枫": "C008", "阿笠博士": "C002", "博士": "C002",
}
BLACKLIST = {
    "她", "他", "我", "你", "您", "咱", "咱家", "我们", "你们", "他们", "她们", "我辈修士",
    "小的", "属下", "在下", "老夫", "老身", "师兄", "师父", "师傅", "先生", "女士",
    "少爷", "小姐", "大哥", "大姐", "大叔", "阿姨", "老佛爷", "老爷", "夫人",
    "老师", "同学", "首长", "老板", "掌柜", "总策划", "少儿频道", "软男", "女生", "男生",
    "这人", "那人", "其他人", "诸位", "大家", "众弟子", "师兄弟", "宗门", "人族", "诸族",
    "天下英雄", "我等", "媒妁", "小偷", "狗腿子", "破落户", "贩毒组织", "大哥哥", "爷爷奶奶",
    "老爸", "老妈", "老", "嫂子", "爷爷", "奶奶", "王师弟", "新进宗门的弟子", "师侄", "徒儿", "老婆",
}
# B: 亲属称谓（不当 speaker；除非 alias 表已含）
RELATION = {"老爸", "老妈", "爸爸", "妈妈", "爷爷", "奶奶", "外公", "外婆", "老公", "老婆",
            "儿子", "女儿", "兄弟", "姐妹", "大哥", "二哥", "妹妹", "弟弟", "嫂子", "岳父", "岳母", "公公", "婆婆"}
# C: 引号内容非直接对白（嘀咕/写着/传来/想起/念道 → 旁白类）
QUOTE_NARRATION_MARKERS = ("嘀咕", "写着", "传来", "想起", "心想", "念道", "念叨", "议论", "讨论", "传来声音", "写着", "标语", "歌词")


def main():
    rows = [json.loads(l) for l in open(os.path.join(PC001, "director_v2.jsonl"), encoding="utf-8")]
    out = []
    stats = {"dialogue": 0, "null_recovered": 0, "relation_filtered": 0, "reclassified": 0, "still_null": 0}
    active_speaker = None
    recent_speakers = []
    for i, r in enumerate(rows):
        rec = dict(r)
        if r["segment_type"] == "DIALOGUE":
            # C: 引号重分类（嘀咕/写着 等 → NARRATION）
            if any(m in r["text"] for m in QUOTE_NARRATION_MARKERS):
                rec["segment_type"] = "NARRATION"
                rec["reclassify"] = True
                rec["route"] = "narrator"
                rec["speaker_v3"] = None
                stats["reclassified"] += 1
                out.append(rec)
                active_speaker = None
                continue
            stats["dialogue"] += 1
            sp = r.get("speaker_v2")
            if sp and sp in RELATION and sp not in ALIAS:
                rec["speaker_v3"] = None
                rec["relation"] = sp
                stats["relation_filtered"] += 1
            elif sp:
                rec["speaker_v3"] = sp
                rec["confidence"] = 0.9
                active_speaker = sp
                recent_speakers.append(sp)
            elif r.get("filtered"):
                # 泛称/称谓被过滤：保持 NULL（不上下文恢复，避免错误绑定）
                rec["speaker_v3"] = None
                stats["still_null"] += 1
            else:
                # A: NULL 上下文恢复：最近对白 speaker → 场景 active → 前 5 段 mention 众数
                cand = active_speaker or (recent_speakers[-1] if recent_speakers else None)
                if not cand:
                    ctx = [m for prev in rows[max(0, i - 5):i]
                           for m in (prev.get("mentions") or []) if m not in BLACKLIST and m not in RELATION]
                    if ctx:
                        cand = Counter(ctx).most_common(1)[0][0]
                if cand:
                    rec["speaker_v3"] = cand
                    rec["confidence"] = 0.6
                    rec["context_recovered"] = True
                    active_speaker = cand
                    recent_speakers.append(cand)
                    stats["null_recovered"] += 1
                else:
                    rec["speaker_v3"] = None
                    stats["still_null"] += 1
            rec["character_id"] = ALIAS.get(rec.get("speaker_v3"))
        else:
            rec["speaker_v3"] = None
        out.append(rec)
    with open(os.path.join(PC001, "director_v3.jsonl"), "w", encoding="utf-8") as f:
        for r in out:
            f.write(json.dumps(r, ensure_ascii=False) + "\n")
    print("stats:", json.dumps(stats, ensure_ascii=False), flush=True)
    print("DONE", flush=True)


if __name__ == "__main__":
    main()
