# -*- coding: utf-8 -*-
"""PC-001 v0.2: Character Importance Scorer + Alias 归并（数据驱动 + 规则）。
importance = 0.4*出场归一 + 0.3*对白比例 + 0.2*章节跨度归一 + 0.1*用户关注(0)。
"""
import json
import os
from collections import Counter

PC001 = r"E:\AndroidStudioProjects\ReaderVoiceMobile\training\readerdirector\runs\voice_state\voice_factory\pc001"

# v0.2 手工 alias 规则（数据驱动统计后人工确认；后续 Character DB 学习）
ALIAS_RULES = {
    "谭越": ["老谭", "小谭", "谭总", "谭公子"],
    "柯南": ["工藤", "小侦探", "柯南君"],
    "叶洋": ["叶师兄", "叶师弟"],
    "许诺": ["诺哥", "许诺哥"],
    "齐雪": ["齐小姐"],
}


def main():
    rows = [json.loads(l) for l in open(os.path.join(PC001, "director.jsonl"), encoding="utf-8")]
    seg_chapter = {}
    for b in ("urban", "fantasy", "xianxia"):
        for r in [json.loads(l) for l in open(os.path.join(PC001, b + ".jsonl"), encoding="utf-8")]:
            seg_chapter[r["paragraph_id"]] = r["chapter"]

    mention_cnt = Counter()
    speak_cnt = Counter()
    chap_set = {}
    for r in rows:
        ch = seg_chapter.get(r["paragraph_id"], 0)
        for m in r.get("mentions") or []:
            c = m.strip()
            if not c or len(c) > 12:
                continue
            mention_cnt[c] += 1
            chap_set.setdefault(c, set()).add(ch)
        if r.get("speaker"):
            s = r["speaker"].strip()
            if s and len(s) <= 12:
                speak_cnt[s] += 1
                chap_set.setdefault(s, set()).add(ch)

    # 归一化 importance（对白段总数 = 90）
    all_names = set(mention_cnt) | set(speak_cnt)
    max_mention = max(mention_cnt.values()) if mention_cnt else 1
    chars = []
    for n in all_names:
        mentions = mention_cnt.get(n, 0)
        speaks = speak_cnt.get(n, 0)
        dial_ratio = speaks / 90.0
        span = len(chap_set.get(n, set()))
        importance = 0.4 * (mentions / max_mention) + 0.3 * dial_ratio + 0.2 * min(span / 6.0, 1.0)
        chars.append({"surface": n, "mentions": mentions, "speaks": speaks,
                      "dial_ratio": round(dial_ratio, 3), "chapters": span,
                      "importance": round(importance, 3)})
    chars.sort(key=lambda c: -c["importance"])
    # alias 归并：命中规则 → canonical
    canon = {}
    for c in chars:
        key = c["surface"]
        for canonical, aliases in ALIAS_RULES.items():
            if key == canonical or key in aliases:
                key = canonical
                break
        canon.setdefault(key, []).append(c)
    out = []
    for canonical, group in canon.items():
        base = group[0]
        out.append({"character": canonical,
                    "mentions": sum(g["mentions"] for g in group),
                    "speaks": sum(g["speaks"] for g in group),
                    "dial_ratio": round(sum(g["speaks"] for g in group) / 90.0, 3),
                    "chapters": max(g["chapters"] for g in group),
                    "importance": round(max(g["importance"] for g in group), 3),
                    "surfaces": [g["surface"] for g in group]})
    out.sort(key=lambda c: -c["importance"])
    print("%-8s %6s %6s %8s %6s %8s %s" % ("character", "ment", "spk", "dialR", "chap", "import", "surfaces"))
    for c in out[:25]:
        print("%-8s %6d %6d %8.3f %6d %8.3f %s" % (
            c["character"], c["mentions"], c["speaks"], c["dial_ratio"],
            c["chapters"], c["importance"], ",".join(c["surfaces"][:6])))
    with open(os.path.join(PC001, "characters.json"), "w", encoding="utf-8") as f:
        json.dump(out, f, ensure_ascii=False, indent=1)
    print("characters.json saved, total canon:", len(out), flush=True)


if __name__ == "__main__":
    main()
