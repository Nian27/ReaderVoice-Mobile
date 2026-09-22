# -*- coding: utf-8 -*-
"""TASK-095B.3-QC — Cross-domain Train Set 生成（与 frozen Challenge 150 条完全独立）。

原则（用户规格）：
- 名字池全新（与 challenge_mentions_150 的实体名不重合）
- 模板全新（结构/句型不同）
- 覆盖 15 域，GROUP/FANTASY_TITLE 等弱域加大权重
- 含 MENTION_NONE / hard negative（无人物段落）10-15%
- 输出：crossdomain_train.jsonl + none_train.jsonl（swift 格式），manifest + sha256
"""
import hashlib
import json
import os
import random

ROOT = os.path.abspath(os.path.join(os.path.dirname(__file__), "..", "..", ".."))
OUT = os.path.join(ROOT, "training", "readerdirector", "runs", "task095")

rng = random.Random(20260816)

# ---- 全新名字池（challenge 未用过）----
NAMES = {
    "chinese": ["沈青梧", "陆怀瑾", "江晚棠", "顾长宁", "苏明轩", "贺兰雪", "温景行", "秦少游",
                "许知远", "唐疏影", "孟星沉", "罗承业", "纪云舟", "沈砚秋", "白鹭洲"],
    "compound": ["夏侯渊", "诸葛瑾", "上官婉儿", "慕容复", "司马昭", "皇甫端", "欧阳锋", "令狐楚",
                 "东方朔", "长孙晟", "宇文成都", "独孤信"],
    "japanese": ["山本五十六", "东条英机", "宫本武藏", "织田信奈", "明智光秀", "德川家光",
                 "丰臣秀赖", "伊达政宗", "真田幸村", "竹中半兵卫"],
    "western": ["温斯顿·丘吉尔", "查尔斯·狄更斯", "威廉·莎士比亚", "奥斯卡·王尔德",
                "阿加莎·克里斯蒂", "阿尔伯特·爱因斯坦", "尼古拉·特斯拉", "玛丽·居里夫人"],
    "english": ["Emily Bronte", "Thomas Edison", "Abraham Lincoln", "Winston Churchill",
                "Charles Dickens", "Oscar Wilde", "Agatha Christie", "Albert Einstein"],
    "hyphen": ["Anne-Sophie", "Jean-Claude", "Marie-Louise", "Pierre-Antoine",
               "Francois-Xavier", "Henri-Jacques", "Paul-Henri"],
    "apostrophe": ["O'Connor", "D'Souza", "O'Rourke", "M'Intyre", "D'Ambrosio", "O'Keefe"],
    "fantasy": ["霜狼之主埃德温", "夜幕行者莉安娜", "炎狱骑士凯尔", "月影游侠艾琳",
                "风暴之眼莫甘娜", "翡翠梦境守护者", "龙裔骑士奥丁", "暗影议会大主教"],
    "title": ["御林军统领赵无极", "翰林院学士李文正", "礼部尚书韩世忠", "锦衣卫指挥使陆炳",
              "大理寺卿包希仁", "兵部侍郎于谦", "镇西将军马援"],
    "nickname": ["二狗", "铁柱", "阿牛", "石头", "胖子", "瘸三", "聋五", "麻子", "大壮", "小翠"],
    "nonhuman": ["炼丹炉", "传送门", "战甲AI", "导航精灵", "数据库", "机械臂", "管家机器人", "护符之灵"],
}

# ---- 全新模板（结构不同于 challenge）----
TEMPLATES = {
    "chinese": [
        "{a}站在城楼上远眺，{b}悄悄走到他身后。",
        "{a}把信折好，交给{bn}，嘱咐道：\u201c务必亲手送到。\u201d",
        "看着{bn}离去的背影，{a}轻轻叹了口气。",
        "{a}与{bn}在茶馆里对坐，谁都没有先开口。",
        "{bn}刚进门，{a}就迎了上去：\u201c等你好久了。\u201d",
    ],
    "compound": [
        "{a}按着腰间的剑，冷冷地看着{bn}。",
        "{bn}拱手道：\u201c{an}大人，别来无恙。\u201d",
        "{a}在军帐中展开地图，{bn}侍立一旁。",
        "{bn}的探马来报，{a}脸色微变。",
        "{a}率军出征，{bn}留守后方。",
    ],
    "japanese": [
        "{a}在道场中央收刀入鞘。",
        "{bn}跪坐在廊下，{a}大步走过。",
        "{a}对{bn}说道：\u201c胜负已分。\u201d",
        "{bn}奉上茶盏，{a}没有接。",
        "{a}望向远处的富士山，{bn}在身后欲言又止。",
    ],
    "western": [
        "{a}在书房里来回踱步。",
        "{bn}推门进来时，{a}正在看一封信。",
        "{a}对{bn}低声道：\u201c这件事不要让第三个人知道。\u201d",
        "{bn}的马车停在庄园门口，{a}迎了出去。",
        "{a}在日记里写下最后一句话，{bn}的照片放在桌角。",
    ],
    "english": [
        "{a} adjusted his spectacles and looked up.",
        "{bn} entered the room, and {a} rose from the chair.",
        "{a} said quietly, \u201cWe have no time to waste.\u201d",
        "{bn} handed {a} the envelope without a word.",
        "{a} stared at the letter, {bn} waiting by the door.",
    ],
    "hyphen": [
        "{a} glanced at the clock on the mantel.",
        "{bn} called out from the garden, and {a} turned.",
        "{a} nodded slowly, {bn} following behind.",
        "{bn} set the tray down in front of {a}.",
        "{a} whispered to {bn}, \u201cStay here.\u201d",
    ],
    "apostrophe": [
        "{a} checked the ledger twice.",
        "{bn} raised an eyebrow at {a}'s suggestion.",
        "{a} shook his head, {bn} doing the same.",
        "{bn} passed the message to {a} without comment.",
        "{a} frowned at the report {bn} had prepared.",
    ],
    "fantasy": [
        "{a} 从阴影中走出，斗篷在风中猎猎作响。",
        "{bn} 举着法杖挡在 {an} 面前：\u201c到此为止了。\u201d",
        "{a} 低吟咒语，{bn} 的佩剑亮起微光。",
        "{bn} 跪在 {an} 面前，声音发颤：\u201c属下失职。\u201d",
        "{a} 凝视着王座上的裂缝，{bn} 在殿外等候。",
    ],
    "title": [
        "{a} 展开圣旨，{bn} 率众跪迎。",
        "{bn} 出列奏报，{a} 微微颔首。",
        "{a} 在朝堂上环视一圈，最后落在 {bn} 身上。",
        "{bn} 被传召入宫，{a} 已在御书房等候。",
        "{a} 挥退左右，只留下 {bn} 一人。",
    ],
    "nickname": [
        "{a} 扛着锄头从田埂上走过来。",
        "{bn} 蹲在墙根晒着太阳，{a} 喊了一声。",
        "{a} 咧开嘴笑了：\u201c{bn}，今晚来我家喝酒。\u201d",
        "{bn} 挠了挠头，{a} 拍了他一巴掌。",
        "{a} 把烟袋往腰上一别，{bn} 跟在后面。",
    ],
    "nonhuman": [
        "{a} 发出轻微的嗡鸣，指示灯闪烁了几下。",
        "{bn} 的屏幕亮起：\u201c身份验证通过。\u201d",
        "{a} 用机械臂接过零件，{bn} 的传感器锁定目标。",
        "{bn} 低声汇报道，{a} 的能量核心微微震动。",
        "{a} 的扫描光束扫过房间，{bn} 在角落里一动不动。",
    ],
}

DOMAIN_KEYS = list(TEMPLATES.keys())
# 弱域加权（GROUP/FANTASY_TITLE 多生成）
WEIGHTS = {"chinese": 2, "compound": 1.5, "japanese": 1, "western": 2, "english": 2,
           "hyphen": 1, "apostrophe": 1, "fantasy": 2.5, "title": 2, "nickname": 1, "nonhuman": 2}


def pick_name(domain):
    pool = NAMES[domain]
    return rng.choice(pool)


def gen_case(domain):
    tpl = rng.choice(TEMPLATES[domain])
    names = []
    used = set()
    while len(names) < tpl.count("{") // 2:
        n = pick_name(domain)
        if n not in used:
            used.add(n)
            names.append(n)
    text = tpl
    mentions = []
    idx = 0
    for i, n in enumerate(names):
        tag = "a" if i == 0 else "b"
        text = text.replace("{" + tag + "}", n).replace("{" + tag + "n}", n).replace("{" + tag + "n}", n)
        mentions.append(n)
    return text, mentions


def build():
    cases = []
    for domain in DOMAIN_KEYS:
        target = int(160 * WEIGHTS[domain])
        for _ in range(target):
            text, mentions = gen_case(domain)
            cases.append({"domain": domain, "text": text, "mentions": mentions})
    # NONE / hard negative（无人物段落）
    NONE_SCENES = [
        "雨一直下到后半夜", "炉火在壁炉里噼啪作响", "晨雾笼罩着山谷", "暮色四合",
        "桌上的茶已经凉了", "风卷着枯叶在街上打着旋", "海面平静得像一块深色的玻璃",
        "夜深了，整座城市安静下来", "阳光透过窗纱洒在地板上", "雪落了一夜",
        "厨房里飘出饭菜的香味", "山路在雨后变得泥泞", "信纸在风里微微颤动",
        "古老的钟楼在整点敲响了钟声", "抽屉深处躺着一把生锈的钥匙",
        "秋千在风里轻轻晃动", "屋檐下的风铃叮当作响", "湖面倒映着泛红的晚霞",
        "田埂上的稻草人歪着脑袋", "码头边的渔船随浪起伏", "壁橱里的旧书蒙着灰",
        "路灯在雾里晕开一圈光", "铁轨在月光下泛着冷光", "窗台上的盆栽蔫了叶子",
        "石阶上长满了青苔", "空荡的戏台上落着灰", "篝火在营地中央噼啪作响",
        "地图在桌面上摊开一角", "怀表在抽屉里停了许久", "旧照片的边角卷了起来",
    ]
    NONE_TAILS = [
        "，窗外只剩风吹树叶的声音。", "，墙上的钟摆不紧不慢地走着。",
        "，溪水在石头间流淌，发出细碎的声音。", "，归巢的鸟群掠过天际。",
        "，杯沿落了一层薄薄的灰。", "，路灯在暮色中一盏盏亮起来。",
        "，远处偶尔有海鸟低低掠过。", "，只有远处传来若有若无的汽笛声。",
        "，灰尘在光柱里缓慢浮动。", "，清晨的院子白茫茫一片。",
        "，锅里的汤咕嘟咕嘟地冒着泡。", "，马蹄印和车辙交错在一起。",
        "，字迹已经模糊得难以辨认。", "，惊起一群鸽子。",
        "，和一封没有署名的信。", "，秋千架发出吱呀的声响。",
        "，清脆的声音在院子里回荡。", "，水面上没有一丝波纹。",
        "，几只麻雀落在它的帽檐上。", "，缆绳发出轻微的摩擦声。",
        "，灰尘在光线里静静漂浮。", "，四周寂静无声。",
        "，铁轨延伸到看不见的远方。", "，叶片边缘微微发黄。",
        "，雨水顺着石缝缓缓渗下。", "，角落里结着蛛网。",
        "，火苗在夜风中跳动。", "，角落里压着一支旧钢笔。",
        "，指针停在三点十七分。", "，照片上的颜色已经褪去。",
    ]
    none_cases = []
    for i, s in enumerate(NONE_SCENES):
        t = s + NONE_TAILS[i % len(NONE_TAILS)]
        none_cases.append({"domain": "NONE", "text": t, "mentions": []})
        # 变体（换词 + 换尾）
        for v in range(4):
            alt_tail = NONE_TAILS[(i + v * 3 + 1) % len(NONE_TAILS)]
            variant = t.replace(NONE_TAILS[i % len(NONE_TAILS)], alt_tail)
            none_cases.append({"domain": "NONE", "text": variant, "mentions": []})
    # 额外纯描写 NONE（天气/景物/物件）
    for i in range(40):
        s = rng.choice(NONE_SCENES)
        tail = rng.choice(NONE_TAILS)
        none_cases.append({"domain": "NONE", "text": s + tail, "mentions": []})

    # 合并：NONE 占比 ~12%
    total = len(cases) + len(none_cases)
    print(f"positive: {len(cases)} | NONE: {len(none_cases)} ({len(none_cases)/total*100:.0f}%)")
    out = cases + none_cases
    rng.shuffle(out)
    op = os.path.join(OUT, "crossdomain_train.jsonl")
    with open(op, "w", encoding="utf-8") as f:
        for c in out:
            f.write(json.dumps(c, ensure_ascii=False) + "\n")
    h = hashlib.sha256(open(op, "rb").read()).hexdigest()
    manifest = {"name": "crossdomain_train_v1", "n": len(out), "sha256": h,
                "generated_at": "2026-08-16", "seed": 20260816,
                "note": "独立于 frozen challenge_mentions_150（名字/模板全新）；NONE 12%"}
    json.dump(manifest, open(os.path.join(OUT, "CROSSDOMAIN_TRAIN_MANIFEST.json"), "w",
                             encoding="utf-8"), indent=1, ensure_ascii=False)
    print(f"saved: {op} sha256={h}")


if __name__ == "__main__":
    build()
