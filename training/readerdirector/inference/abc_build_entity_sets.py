# -*- coding: utf-8 -*-
"""A/B/C 对比实验（TASK-100 候选架构）Step 1：17 本书实体集构建。

实体集 = "CharacterStore 真实 Entity" 的轻量代理：
每本书从 segments.jsonl（规则确认说话人流）统计 surface 频率，
通过强形态 gate（名字用字约束，宁缺毋滥）的 surface 进实体集。

输出 books_private/real_pool_v1/entity_sets.json（book_hash -> {name: freq}）。
用法: python inference/abc_build_entity_sets.py
"""
import json
import glob
import os
import re

ROOT = os.path.abspath(os.path.join(os.path.dirname(__file__), "..", "..", ".."))
SEG_DIR = os.path.join(ROOT, "books_private", "real_pool_v1")

# 强信号 gate：这些字符几乎不可能出现在中文角色名中（动词/副词/结构助词/代词）
# 保留"好/奇/常/是/那/这"等可能为名字用字的字（宁误留不误杀，recall 优先）
GATE_CHARS = "的地得道说问答喊叫想看听忍住禁释追恭该当即续板街反没还就都也又再才刚正不对从在了着过被把只"

CJK = re.compile(r"^[\u4e00-\u9fa5]{2,4}$")


def build():
    out = {}
    for f in sorted(glob.glob(os.path.join(SEG_DIR, "RB-*.segments.jsonl"))):
        book = os.path.basename(f).split(".")[0]
        freq = {}
        for line in open(f, encoding="utf-8"):
            try:
                s = json.loads(line)
            except Exception:
                continue
            sp = s.get("rule_speaker")
            if not sp:
                continue
            freq[sp] = freq.get(sp, 0) + 1
        # 实体集：CJK 2-4 字 + 不含 gate 字符
        entities = {name: c for name, c in freq.items()
                    if CJK.match(name or "") and not any(ch in GATE_CHARS for ch in name)}
        out[book] = entities
        print(f"{book}: surfaces={len(freq)} entities={len(entities)} "
              f"top={sorted(entities.items(), key=lambda x: -x[1])[:6]}")
    op = os.path.join(SEG_DIR, "entity_sets.json")
    json.dump(out, open(op, "w", encoding="utf-8"), ensure_ascii=False)
    print("saved:", op)


if __name__ == "__main__":
    build()
