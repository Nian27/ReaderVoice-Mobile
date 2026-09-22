# -*- coding: utf-8 -*-
"""PC-001-A 语料准备：3 书 × 100 段（70 旁白 + 30 对白）。"""
import json
import os
import random
import re

BOOKS = {
    "urban": r"E:\AndroidStudioProjects\ReaderVoiceMobile\books_private\从离婚开始的文娱 作者：会发光的风.txt",
    "fantasy": r"E:\AndroidStudioProjects\ReaderVoiceMobile\books_private\柯南：这个修理工不太柯学 作者：白糖烟叶.txt",
    "xianxia": r"E:\AndroidStudioProjects\ReaderVoiceMobile\books_private\我在修仙界坚韧不拔 作者：披着马甲的羊羊羊.txt",
}
OUT = r"E:\AndroidStudioProjects\ReaderVoiceMobile\training\readerdirector\runs\voice_state\voice_factory\pc001"
TARGET = {"NARRATION": 70, "DIALOGUE": 30}
CH_NUM = "第[0-9一二三四五六七八九十百千零]+章|序章|楔子|番外"
QUOTES = ("\u201c", "\u201d", '"', "\u2018", "\u2019")


def is_chapter(line):
    s = line.strip()
    return bool(re.match(CH_NUM, s)) or bool(re.search("第[0-9]+章", s))


def split_para(line):
    return line.strip().strip("\u3000").strip()


def classify(line):
    for q in QUOTES:
        if q in line:
            inner = re.findall(r"[\u201c\"]([^\u201d\"]{1,120})[\u201d\"]", line)
            if inner and sum(len(x) for x in inner) >= 2:
                return "DIALOGUE"
            break
    return "NARRATION"


def extract(book, path, want_chapters=6):
    txt = open(path, encoding="utf-8", errors="ignore").read()
    lines = txt.split("\n")
    start = 0
    for i, l in enumerate(lines):
        if is_chapter(l):
            start = i
            break
    paras = []
    chapter_no = 0
    for l in lines[start:]:
        s = split_para(l)
        if not s:
            continue
        if is_chapter(s):
            chapter_no += 1
            if chapter_no > want_chapters:
                break
            continue
        if len(s) < 8 or len(s) > 300:
            continue
        if re.fullmatch(r"[\s=—-]*", s):
            continue
        paras.append({"book": book, "chapter": chapter_no, "text": s, "type": classify(s)})
    return paras


def main():
    os.makedirs(OUT, exist_ok=True)
    stats = {}
    for book, path in BOOKS.items():
        paras = extract(book, path)
        narr = [p for p in paras if p["type"] == "NARRATION"]
        dial = [p for p in paras if p["type"] == "DIALOGUE"]
        stats[book] = {"total": len(paras), "NARRATION": len(narr), "DIALOGUE": len(dial)}
        rng = random.Random(42)
        rng.shuffle(narr)
        rng.shuffle(dial)
        picked = narr[:TARGET["NARRATION"]] + dial[:TARGET["DIALOGUE"]]
        rng.shuffle(picked)
        rows = []
        for i, p in enumerate(picked):
            rows.append({"paragraph_id": "%s_p%03d" % (book, i),
                         "book": book, "chapter": p["chapter"],
                         "segment_type": p["type"], "text": p["text"]})
        out = os.path.join(OUT, book + ".jsonl")
        with open(out, "w", encoding="utf-8") as f:
            for r in rows:
                f.write(json.dumps(r, ensure_ascii=False) + "\n")
        print(book, "-> rows:", len(rows), flush=True)
    print("stats:", json.dumps(stats, ensure_ascii=False), flush=True)
    print("DONE", flush=True)


if __name__ == "__main__":
    main()
