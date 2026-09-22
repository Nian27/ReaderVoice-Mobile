# -*- coding: utf-8 -*-
"""TASK-095B.2 — Frequency-aware MentionLexicon & Candidate Ranking（Python 实验版）。

替代"裸 HashSet + 全滑窗"：
- MentionLexicon：每个 surface 带证据统计（呼语/自我介绍/cue 主语/叙述/章节覆盖/首末位置/称谓）
- Bounded Mention Extraction：cue 句定位 speech verb → verb 前 syntactic zone → 标点/功能词切块 → 少量 MentionCandidate（禁止无约束滑窗）
- Evidence-aware Match + 打分排序（source 证据 + 频率 + 噪声惩罚），截断在 ranking 之后
- 输出 Recall@K 曲线 + gold rank + 候选规模

用法: python inference/095b2_lexicon.py
"""
import glob
import json
import os
import re
from collections import Counter

ROOT = os.path.abspath(os.path.join(os.path.dirname(__file__), "..", "..", ".."))
SEG_DIR = os.path.join(ROOT, "books_private", "real_pool_v1")
RUNS = os.path.join(ROOT, "training", "readerdirector", "runs", "task100")
OUT = os.path.join(ROOT, "training", "readerdirector", "runs", "task095")

CJK = re.compile(r"^[\u4e00-\u9fa5]{2,4}$")
# speech verb：复合词优先
CUE_VERB = re.compile(
    r"(低声道|厉声道|沉声道|淡淡道|冷冷道|轻声说|低声说|大声说|小声说|柔声道|颤声道|"
    r"说道|答道|问道|喊道|叫道|怒道|笑道|哭道|叹道|开口道|回答说|回答|开口|说|问|答|喊|叫|道)")
# cue 前 zone：到句首（句边界），保证远主语（"方夫人扑上前来，跪在欧阳戎脚边，哭泣叫冤"）不被截断
ZONE = 40
SENT_BOUNDARY = re.compile(r"[。！？…；\n]")
# 块首功能词（剥除，循环）
HEAD_FUNC = (
    "忽然间", "突然之间", "忽然", "突然", "连忙", "赶紧", "立刻", "马上", "终于", "随后", "接着", "然后",
    "于是", "只好", "忍不住", "不禁", "好奇", "继续", "当即", "淡淡", "冷冷", "轻轻", "微微", "缓缓",
    "低声", "大声", "轻声", "厉声", "沉声", "尖声", "柔声", "颤声", "娇声", "齐声", "高声", "平静",
    "严肃", "认真", "喃喃", "急切", "狠狠", "默默", "气恼", "恼怒", "温和", "犹豫", "沉思", "叹息",
    "点头", "摇头", "抬头", "低头", "转身", "回头", "皱眉", "微笑", "苦笑", "冷笑", "大笑", "耸肩",
    "跪在", "站在", "坐在", "躺在", "立在", "走在", "躲在", "看着", "望着", "盯着",
    "这才", "只是", "只能", "还是", "又", "也", "还", "就", "都", "再", "才", "刚", "正", "便", "却",
    "忙", "即", "竟", "倒", "仍", "顿时", "一时间", "片刻后", "良久",
    "对", "向", "跟", "和", "与", "给", "为", "替", "从", "把", "被", "在", "于",
)
# 块尾功能词（剥除）
TAIL_FUNC = ("的", "地", "得", "着", "了", "过", "呢", "吗", "啊", "吧", "呀", "嘛", "哦", "嘿")
# 称谓后缀 -> base 名
TITLE_SUFFIX = (
    "先生", "小姐", "局长", "书记", "大人", "公子", "姑娘", "殿下", "陛下", "夫人", "太太", "兄台", "兄长",
    "大哥", "大姐", "兄弟", "老弟", "老师", "师傅", "道长", "将军", "老爷", "少爷", "婆婆", "奶奶", "爷爷",
    "女侠", "少侠", "掌门", "门主", "帮主", "教主", "盟主", "长老", "护法", "堂主", "掌柜", "老板", "经理",
    "总管", "员外", "相公", "娘子", "兄", "哥", "姐", "叔", "婶", "伯", "姨", "爷", "奶", "妹", "弟",
    "翁", "公", "桑", "君", "郎",
)
# 官职/身份前缀（西城县令高传式 -> 高传式）
TITLE_PREFIX = (
    "西城县令", "县令", "知府", "知州", "知军", "尚书", "侍郎", "御史", "将军", "都督", "总兵",
    "指挥使", "巡检", "主簿", "典史", "驿丞", "大祭司", "祭司", "皇子", "太子", "公主", "郡主",
    "王爷", "世子", "长老", "掌门", "教主", "帮主", "堂主", "护法", "使者",
)
# 动作短语（块内：主语 + 动作 + 宾语模式）
ACTION_PAT = re.compile(
    r"^(.*?)(?:看着|望着|盯着|打量着|看了看|看了一眼|扫了一眼|瞥了一眼|瞪了一眼|"
    r"看了看|看了|望了|瞧了|瞄了)(?:.{0,6}?)(?:的背影|的样子|的模样|的身形|的身影|的方向|的位置|一眼|一下)?$")
# 噪声/停用
STOP_PHRASE = set(
    "什么时候已经现在然后所以因为虽然但是如果就是只是可是还是或者而且于是接着随后终于忽然突然连忙赶紧立刻马上一边一直一向从来根本简直完全十分非常特别真的好像似乎仿佛几乎确实当然可能也许大概上下左右前后中间旁边里面外面头上脚下手里心中眼前身上背后面前身边手上口中眼中脸上"
    "呵呵哈哈嘿嘿咯咯啧啧嗯啊哦哼哎哟喂呀啊呀我的天哪老天爷天哪不好不好不对不对不错不错好的好的行吧算了罢了好吧没事没事没有没有怎么怎么了什么为什么"
)


class MentionSurfaceRecord:
    __slots__ = ("surface", "total", "vocative", "self_intro", "cue_subject", "narrative",
                 "chapter_buckets", "first_pos", "last_pos", "title_prefix", "title_suffix")

    def __init__(self, surface):
        self.surface = surface
        self.total = 0
        self.vocative = 0
        self.self_intro = 0
        self.cue_subject = 0
        self.narrative = 0
        self.chapter_buckets = set()
        self.first_pos = None
        self.last_pos = None
        self.title_prefix = None
        self.title_suffix = None

    def add(self, kind, pos, bucket):
        self.total += 1
        if kind == "vocative":
            self.vocative += 1
        elif kind == "self_intro":
            self.self_intro += 1
        elif kind == "cue":
            self.cue_subject += 1
        else:
            self.narrative += 1
        self.chapter_buckets.add(bucket)
        if self.first_pos is None:
            self.first_pos = pos
        self.last_pos = pos

    def confidence(self):
        s = 0.0
        s += self.vocative * 14
        s += self.self_intro * 16
        s += self.cue_subject * 12
        s += min(self.narrative, 10) * 4
        s += min(self.total, 50) * 1.2
        if len(self.chapter_buckets) >= 3:
            s += 3
        if self.title_suffix:
            s += 2
        if self.title_prefix:
            s += 2
        return s


def strip_head(blk):
    changed = True
    while changed and blk:
        changed = False
        for f in HEAD_FUNC:
            if blk.startswith(f):
                blk = blk[len(f):]
                changed = True
                break
    return blk


def strip_tail(blk):
    changed = True
    while changed and blk:
        changed = False
        for f in TAIL_FUNC:
            if blk.endswith(f):
                blk = blk[:-len(f)]
                changed = True
                break
    return blk


def bounded_cue_subjects(text):
    """cue 句 bounded 提取：verb 前到句首的 zone → 标点切块 → 功能词剥除 → 块首 2-4 字。"""
    out = []
    for m in CUE_VERB.finditer(text):
        # zone 起点：往前找句边界（。！？…；），无则 0；限长防超长
        start = max(text.rfind("。", 0, m.start()), text.rfind("！", 0, m.start()),
                    text.rfind("？", 0, m.start()), text.rfind("…", 0, m.start()),
                    text.rfind("；", 0, m.start()))
        start = max(start, 0)
        if m.start() - start > ZONE:
            start = m.start() - ZONE
        zone = text[start:m.start()]
        for blk in re.split(r"[，。！？；：、\s“”\"《》]", zone):
            blk = blk.strip()
            if not blk:
                continue
            # 动作短语：主语 + 看着X的Y → 取主语
            am = ACTION_PAT.match(blk)
            if am and am.group(1):
                blk = am.group(1)
            b = strip_head(blk)
            b = strip_tail(b)
            cands = []
            if CJK.match(b):
                cands.append(b)
            elif len(b) > 4:
                # 块首 4/3/2 字全提取（lexicon 证据打分负责筛选，不预判）
                for L in (4, 3, 2):
                    if CJK.match(b[:L]):
                        cands.append(b[:L])
            # "的"定语剥除（已过四旬的方夫人 -> 方夫人）
            if "的" in b:
                after = b.split("的")[-1]
                if CJK.match(after):
                    cands.append(after)
                elif len(after) > 4:
                    for L in (4, 3, 2):
                        if CJK.match(after[:L]):
                            cands.append(after[:L])
            # 称谓后缀：蒙江兄 -> 蒙江（base 名）
            for sfx in TITLE_SUFFIX:
                if b.endswith(sfx) and len(b) > len(sfx):
                    base = b[:-len(sfx)]
                    if 2 <= len(base) <= 4 and CJK.match(base):
                        cands.append(base)
                    break
            # 官职前缀：西城县令高传式 -> 高传式
            for p in TITLE_PREFIX:
                if b.startswith(p) and len(b) > len(p):
                    rest = b[len(p):]
                    for L in (4, 3, 2):
                        if CJK.match(rest[:L]):
                            cands.append(rest[:L])
                    break
            out.extend(cands)
    return out


def vocative_names(text):
    t = text.strip().lstrip("\u201c\u2018「『\"")
    m = re.search(r"[，,！!？?。.；;：:、\s]", t)
    if m and 2 <= m.start() <= 4:
        name = t[:m.start()]
        if CJK.match(name) and name[0] not in "你我他她咱嗯啊哦哼":
            return [name]
    return []


def self_intro_names(text):
    out = []
    for mm in re.finditer(r"(?:我是|我叫|在下|本座|本官|小女子|老娘|俺叫|我是你)([\u4e00-\u9fa5]{2,4})", text):
        nm = mm.group(1)
        if CJK.match(nm):
            out.append(nm)
    return out


def build_lexicon():
    lex = {}
    for f in sorted(glob.glob(os.path.join(SEG_DIR, "RB-*.segments.jsonl"))):
        book = os.path.basename(f).split(".")[0]
        lex.setdefault(book, {})
        n = 0
        for line in open(f, encoding="utf-8"):
            try:
                s = json.loads(line)
            except Exception:
                continue
            n += 1
            bucket = min(n // 500, 19)  # 位置桶（近似章节覆盖）
            pos = n / 600000.0
            # 对白：呼语 + 自我介绍
            t = s.get("text") or ""
            for name in vocative_names(t):
                rec = lex[book].setdefault(name, MentionSurfaceRecord(name))
                rec.add("vocative", pos, bucket)
            for name in self_intro_names(t):
                rec = lex[book].setdefault(name, MentionSurfaceRecord(name))
                rec.add("self_intro", pos, bucket)
            # 上下文：bounded cue 主语
            for rc in (s.get("recent_context") or []):
                for name in bounded_cue_subjects(rc):
                    rec = lex[book].setdefault(name, MentionSurfaceRecord(name))
                    rec.add("cue", pos, bucket)
    # 序列化
    out = {}
    for book, records in lex.items():
        out[book] = {r.surface: {
            "total": r.total, "vocative": r.vocative, "self_intro": r.self_intro,
            "cue": r.cue_subject, "narrative": r.narrative,
            "chapters": len(r.chapter_buckets), "first": r.first_pos, "last": r.last_pos,
            "conf": round(r.confidence(), 1),
        } for r in records.values() if r.total >= 1}
    os.makedirs(OUT, exist_ok=True)
    op = os.path.join(OUT, "mention_lexicon_v5.json")
    json.dump(out, open(op, "w", encoding="utf-8"), ensure_ascii=False)
    sizes = {b: len(v) for b, v in out.items()}
    print(f"lexicon saved: {op}")
    print(f"surfaces per book: mean={sum(sizes.values())/len(sizes):.0f} total={sum(sizes.values())}")
    # 目标检查
    targets = ["张居正", "石头", "维迪", "蒙江", "蒋娜", "高传式", "柳子安", "毒女", "小田切敏郎",
               "席兴白", "狄青麟", "陈玉虎", "吕双双", "佩佩", "方夫人", "刘杰", "柯南", "张远",
               "国谷裕子", "伊万", "谢令姜", "萧思衡"]
    books = {"张居正": "RB-760e5ce5", "石头": "RB-d8d9ceac", "维迪": "RB-75c90932", "蒙江": "RB-939afd83",
             "蒋娜": "RB-75c90932", "高传式": "RB-dec68ab7", "柳子安": "RB-6c937624", "毒女": "RB-7e2416ef",
             "小田切敏郎": "RB-0e25d606", "席兴白": "RB-89e404fd", "狄青麟": "RB-d5c179ed",
             "陈玉虎": "RB-8db14927", "吕双双": "RB-8db14927", "佩佩": "RB-b8e5ae56",
             "方夫人": "RB-6c937624", "刘杰": "RB-8ec497b6", "柯南": "RB-0e25d606", "张远": "RB-d1ed94ad",
             "国谷裕子": "RB-441905b4", "伊万": "RB-760e5ce5", "谢令姜": "RB-6c937624", "萧思衡": "RB-d5c179ed"}
    missing = [t for t in targets if t not in out.get(books[t], {})]
    print("targets missing from lexicon:", missing)


if __name__ == "__main__":
    build_lexicon()
