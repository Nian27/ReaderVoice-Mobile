# -*- coding: utf-8 -*-
"""TASK-095B.2 — Cross-domain Mention Challenge Set 构造（150 条，15 域）。"""
import json
import os
from collections import Counter

OUT = os.path.join(os.path.dirname(os.path.abspath(__file__)), "..", "..", "..",
                   "training", "readerdirector", "runs", "task095")
os.makedirs(OUT, exist_ok=True)

cases = []


def add(domain, text, mentions, note=""):
    n = sum(1 for c in cases if c["domain"] == domain) + 1
    cases.append({"id": f"{domain}-{n:02d}", "domain": domain,
                  "text": text, "mentions": mentions, "note": note})


# 1. 中国人名
for t, m in [
    ("张居正看着葛守礼的背影，才对游七说道：\u201c你先回去。\u201d", ["张居正", "葛守礼", "游七"]),
    ("陈解拍了拍巴颂的肩膀，笑道：\u201c上次让你跑了。\u201d", ["陈解", "巴颂"]),
    ("潘筠拉着妙和缓缓走近，俩人这才发现她们穿着道袍。", ["潘筠", "妙和"]),
    ("萧思衡冷哼一声，看向虚若无：\u201c岳丈还真是体贴呢！\u201d", ["萧思衡", "虚若无"]),
    ("朱翊钧吐了口浊气说道：\u201c这些贱儒不会明白。\u201d", ["朱翊钧"]),
    ("谭越三言两语指点，就让他和沈亮筹备的节目质量上升了数个档次。", ["谭越", "沈亮"]),
    ("叶洋谨慎地问道，目光中透露出一丝期待。", ["叶洋"]),
    ("雪千寻递过去一把精致的火铳。", ["雪千寻"]),
    ("刘茜茜喜欢小动物，对青蛙河鱼之类的东西丝毫不怵。", ["刘茜茜"]),
    ("王费隐这才继续道：\u201c明日初八，你们就下山去吧。\u201d", ["王费隐"]),
]:
    add("chinese_name", t, m)

# 2. 长中文人名（音译长名）
for t, m in [
    ("阿不思\u00b7珀西瓦尔\u00b7伍尔弗里克\u00b7布赖恩\u00b7邓布利多对哈利说道：\u201c勇气不是没有恐惧。\u201d",
     ["阿不思\u00b7珀西瓦尔\u00b7伍尔弗里克\u00b7布赖恩\u00b7邓布利多", "哈利"]),
    ("纳威\u00b7隆巴顿看了一眼麦格教授。", ["纳威\u00b7隆巴顿", "麦格教授"]),
    ("亚力山德罗\u00b7朱塞佩\u00b7安东尼奥\u00b7罗西深吸一口气，推开了门。", ["亚力山德罗\u00b7朱塞佩\u00b7安东尼奥\u00b7罗西"]),
    ("克里斯托弗\u00b7罗宾对百亩森林的朋友们说道：\u201c我们永远是朋友。\u201d", ["克里斯托弗\u00b7罗宾"]),
    ("叶卡捷琳娜\u00b7阿列克谢耶芙娜在镜前整理衣冠。", ["叶卡捷琳娜\u00b7阿列克谢耶芙娜"]),
    ("亚历山大\u00b7谢尔盖耶维奇\u00b7普希金提笔写道。", ["亚历山大\u00b7谢尔盖耶维奇\u00b7普希金"]),
    ("列夫\u00b7尼古拉耶维奇\u00b7托尔斯泰在书房踱步。", ["列夫\u00b7尼古拉耶维奇\u00b7托尔斯泰"]),
    ("玛丽亚\u00b7斯克沃多夫斯卡\u00b7居里擦拭着试管。", ["玛丽亚\u00b7斯克沃多夫斯卡\u00b7居里"]),
    ("小威廉\u00b7弗雷德里克\u00b7琼斯吹着口哨走进酒吧。", ["小威廉\u00b7弗雷德里克\u00b7琼斯"]),
    ("弗雷德里克\u00b7威廉\u00b7德\u00b7克拉克摇了摇头。", ["弗雷德里克\u00b7威廉\u00b7德\u00b7克拉克"]),
]:
    add("long_transliteration", t, m)

# 3. 中文复姓
for t, m in [
    ("欧阳戎刚换了身干净的襕衫出来，就听到有人说话。", ["欧阳戎"]),
    ("司马懿看着城下的诸葛亮，一言不发。", ["司马懿", "诸葛亮"]),
    ("上官玖酒和安锦秋等人也立刻开始纷纷祝贺起来。", ["上官玖酒", "安锦秋"]),
    ("慕容家的财产很多么？怜星好奇地问。", ["慕容家", "怜星"]),
    ("诸葛正我最在乎的，是维持局势的稳定。", ["诸葛正我"]),
    ("皇甫嵩领兵前往前线。", ["皇甫嵩"]),
    ("令狐冲接过酒壶，笑道：\u201c好酒！\u201d", ["令狐冲"]),
    ("东方不败的绣花针在烛光下泛着寒光。", ["东方不败"]),
    ("长孙无忌沉声道：\u201c此事不可再议。\u201d", ["长孙无忌"]),
    ("夏侯惇单臂持枪，立于阵前。", ["夏侯惇"]),
]:
    add("compound_surname", t, m)

# 4. 音译名（无点）
for t, m in [
    ("贝鲁奇在台上接受采访。", ["贝鲁奇"]),
    ("羽生秀树对此也不在乎，直接道：\u201c中野桑，我是羽生秀树。\u201d", ["羽生秀树", "中野桑"]),
    ("伊藤信介说到这露出个既无奈又恼火的表情。", ["伊藤信介"]),
    ("广桥浅子说着看向羽生秀树。", ["广桥浅子", "羽生秀树"]),
    ("北原惠理从走廊尽头走来。", ["北原惠理"]),
    ("岩田聪接过话筒，微微鞠躬。", ["岩田聪"]),
    ("坂本龙马在京都的街道上疾步而行。", ["坂本龙马"]),
    ("德川家康沉默地看着远方。", ["德川家康"]),
    ("织田信长哈哈大笑。", ["织田信长"]),
    ("丰臣秀吉的军队驻扎在城外。", ["丰臣秀吉"]),
]:
    add("transliterated", t, m)

# 5. 中间点
for t, m in [
    ("达\u00b7芬奇在画布前沉思。", ["达\u00b7芬奇"]),
    ("让\u00b7保罗\u00b7萨特点燃了烟斗。", ["让\u00b7保罗\u00b7萨特"]),
    ("皮埃尔\u00b7居里与妻子一同工作。", ["皮埃尔\u00b7居里"]),
    ("克劳德\u00b7莫奈在花园里支起画架。", ["克劳德\u00b7莫奈"]),
    ("弗朗索瓦\u00b7特吕弗举起摄影机。", ["弗朗索瓦\u00b7特吕弗"]),
    ("居伊\u00b7德\u00b7莫泊桑写下最后一个句号。", ["居伊\u00b7德\u00b7莫泊桑"]),
    ("安德烈\u00b7纪德翻阅着日记。", ["安德烈\u00b7纪德"]),
    ("马塞尔\u00b7普鲁斯特在床榻上写作。", ["马塞尔\u00b7普鲁斯特"]),
    ("保罗\u00b7高更在塔希提岛作画。", ["保罗\u00b7高更"]),
    ("路易\u00b7巴斯德举起试管。", ["路易\u00b7巴斯德"]),
]:
    add("middle_dot", t, m)

# 6. 英文姓名
for t, m in [
    ("Neville Longbottom glanced at Professor McGonagall.", ["Neville Longbottom", "Professor McGonagall"]),
    ("Harry Potter walked toward Dumbledore.", ["Harry Potter", "Dumbledore"]),
    ("Sherlock Holmes examined the room carefully.", ["Sherlock Holmes"]),
    ("James Bond ordered a martini.", ["James Bond"]),
    ("Elizabeth Bennet smiled at Mr. Darcy.", ["Elizabeth Bennet", "Mr. Darcy"]),
    ("Frodo Baggins carried the ring into Mordor.", ["Frodo Baggins"]),
    ("Atticus Finch closed his briefcase.", ["Atticus Finch"]),
    ("Holden Caulfield lit a cigarette.", ["Holden Caulfield"]),
    ("Jay Gatsby stared across the bay.", ["Jay Gatsby"]),
    ("Katniss Everdeen notched an arrow.", ["Katniss Everdeen"]),
]:
    add("english_name", t, m)

# 7. 英文连字符
for t, m in [
    ("Jean-Luc Picard turned toward O'Brien.", ["Jean-Luc Picard", "O'Brien"]),
    ("Mary-Jane Watson opened the door.", ["Mary-Jane Watson"]),
    ("Bilbo Baggins, son of Bungo, packed his things.", ["Bilbo Baggins", "Bungo"]),
    ("Charles-Emmanuel III ruled the region.", ["Charles-Emmanuel III"]),
    ("Anne-Marie had never seen such a sight.", ["Anne-Marie"]),
    ("The doctor, Jean-Pierre, arrived late.", ["Jean-Pierre"]),
    ("Louise-Marie looked out the window.", ["Louise-Marie"]),
    ("Pierre-Andre hesitated for a moment.", ["Pierre-Andre"]),
    ("Marie-Claire laughed at the joke.", ["Marie-Claire"]),
    ("Francois-Xavier bowed politely.", ["Francois-Xavier"]),
]:
    add("english_hyphen", t, m)

# 8. 英文 apostrophe
for t, m in [
    ("O'Brien checked his rifle.", ["O'Brien"]),
    ("D'Artagnan drew his sword.", ["D'Artagnan"]),
    ("O'Connell saluted the captain.", ["O'Connell"]),
    ("O'Neill waved from the dock.", ["O'Neill"]),
    ("D'Angelo smirked at the guard.", ["D'Angelo"]),
    ("M'Benga entered the medbay.", ["M'Benga"]),
    ("O'Malley tipped his hat.", ["O'Malley"]),
    ("L'Oreal was the talk of the town.", ["L'Oreal"]),
    ("D'Agostino adjusted his collar.", ["D'Agostino"]),
    ("O'Donnell signed the treaty.", ["O'Donnell"]),
]:
    add("apostrophe", t, m)

# 9. 职位/官职
for t, m in [
    ("西城县令高传式没给好脸色。", ["高传式"]),
    ("红衣主教缓缓开口：\u201c殿下，请留步。\u201d", ["红衣主教", "殿下"]),
    ("中野重政立刻来了精神，\u201c会长这么晚给我打电话，是有什么紧急的事情吗？\u201d", ["中野重政", "会长"]),
    ("白鸟警官笑着点了点头，跟着走进了屋内。", ["白鸟警官"]),
    ("小田切局长沉吟片刻。", ["小田切局长"]),
    ("护院首领李四退到一旁。", ["李四"]),
    ("守宫长老道：\u201c按照我与蛊母商谈的时间。\u201d", ["守宫长老", "蛊母"]),
    ("王骥手指虚点着他们，恨铁不成钢：\u201c一群蠢货！\u201d", ["王骥"]),
    ("大长老听了这话，想了想看看西王道：\u201c说的也是。\u201d", ["大长老", "西王"]),
    ("钱琛拿出州衙和司理院签发的文书。", ["钱琛"]),
]:
    add("title_office", t, m)

# 10. 昵称
for t, m in [
    ("老三把门推开了。", ["老三"]),
    ("\u201c阿杰你有完没完啊！\u201d赵晴空抱怨道。", ["阿杰", "赵晴空"]),
    ("亮子，你把东西搬过来。", ["亮子"]),
    ("老刘疑惑地看着他。", ["老刘"]),
    ("小柔依旧低着头不说话。", ["小柔"]),
    ("大梨子紧裹着酒店的白色被褥，呜咽着说道。", ["大梨子"]),
    ("老韩点了点头，示意他坐下。", ["老韩"]),
    ("阿空，包间里很闷啊，我们出去透透气吧。", ["阿空"]),
    ("馨馨，想要去哪个地方玩了吗？", ["馨馨"]),
    ("铁蛋在院子里追着鸡跑。", ["铁蛋"]),
]:
    add("nickname", t, m)

# 11. 称谓/呼语
for t, m in [
    ("\u201c贤侄女，你怎么把早膳堂的菜坛子给抱出来了？\u201d冲虚子好奇问。", ["贤侄女", "冲虚子"]),
    ("\u201c师父，弟子知错了。\u201d", ["师父", "弟子"]),
    ("\u201c女王陛下，船队已经出发了。\u201d", ["女王陛下"]),
    ("\u201c老爷？\u201d李四小心翼翼地询问。", ["老爷", "李四"]),
    ("\u201c公子，有什么需要的。\u201d老者热情道。", ["公子", "老者"]),
    ("\u201c雪姐姐，多谢了！\u201d", ["雪姐姐"]),
    ("\u201c爹，你去看看山神庙。\u201d", ["爹"]),
    ("\u201c小师叔要见他。\u201d妙和说道。", ["小师叔", "妙和"]),
    ("\u201c这位客官，可是要住店？\u201d周老板问。", ["客官", "周老板"]),
    ("\u201c岳丈还真是体贴呢！\u201d萧思衡拱手道。", ["岳丈", "萧思衡"]),
]:
    add("role_address", t, m)

# 12. 群体
for t, m in [
    ("执法队齐声道：\u201c遵命。\u201d", ["执法队"]),
    ("众家丁退下，只留下了护院首领李四。", ["众家丁", "李四"]),
    ("将士们高呼万岁。", ["将士们"]),
    ("三人连忙应是，把所有的事都推到那个女鬼身上。", ["三人", "女鬼"]),
    ("村民们默默记下陆安的话。", ["村民们", "陆安"]),
    ("孩子们眼睛大亮，立即伸手接过。", ["孩子们"]),
    ("一群蠢货！王骥恨铁不成钢。", ["王骥"]),
    ("众弟子低头不语。", ["众弟子"]),
    ("在场不少人的脸色都冷了下来。", ["不少人"]),
    ("小弟们低头不愿与其对视。", ["小弟们"]),
]:
    add("group", t, m)

# 13. 非人智能体
for t, m in [
    ("AI管家突然说道：\u201c欢迎回来，主人。\u201d", ["AI管家", "主人"]),
    ("系统提示音响起：\u201c任务完成，奖励已发放。\u201d", ["系统"]),
    ("剑灵嗡鸣一声：\u201c主人小心！\u201d", ["剑灵", "主人"]),
    ("器灵从鼎中浮现：\u201c我已经等了你三百年。\u201d", ["器灵"]),
    ("飞船的主控AI冷静地报告：\u201c前方检测到不明信号。\u201d", ["主控AI"]),
    ("药鼎里传来苍老的声音：\u201c此丹已成。\u201d", ["药鼎"]),
    ("古树的树灵叹了口气：\u201c时代变了。\u201d", ["树灵"]),
    ("那台老式收音机突然开口：\u201c请收听今天的新闻。\u201d", ["收音机"]),
    ("黑猫慵懒地开口：\u201c我可不是普通的猫。\u201d", ["黑猫"]),
    ("山神庙里的石像竟然眨了眨眼。", ["石像"]),
]:
    add("nonhuman_agent", t, m)

# 14. 西幻自造词/称号
for t, m in [
    ("瑟兰迪尔站在王座前，目光如炬。", ["瑟兰迪尔"]),
    ("血手屠夫格雷戈扛着斧头走进酒馆。", ["血手屠夫格雷戈"]),
    ("\u201c灰烬使者\u201d缓缓举起燃烧的长剑。", ["灰烬使者"]),
    ("黑暗魔君索伦的魔眼在高塔上凝视。", ["黑暗魔君索伦"]),
    ("龙母丹妮莉丝骑在龙背上。", ["龙母丹妮莉丝"]),
    ("风暴降生的卡丽熙望向远方。", ["风暴降生的卡丽熙"]),
    ("御前侍卫队长巴利斯坦行礼道。", ["御前侍卫队长巴利斯坦"]),
    ("无面者贾昆消失在人群中。", ["无面者贾昆"]),
    ("守夜人总司令琼恩\u00b7雪诺站在长城上。", ["守夜人总司令琼恩\u00b7雪诺"]),
    ("森林之子在幽暗的树林中低语。", ["森林之子"]),
]:
    add("fantasy_title", t, m)

# 15. 第一次出场（自我介绍）
for t, m in [
    ("\u201c羽生先生，我是国谷裕子，非常高兴能对您进行采访。\u201d", ["国谷裕子"]),
    ("\u201c我叫姙青。\u201d小仙尊轻声说道。", ["姙青", "小仙尊"]),
    ("\u201c在下陆小凤，江湖人称四条眉毛。\u201d", ["陆小凤"]),
    ("\u201c我是伊万，来自罗斯国。\u201d", ["伊万"]),
    ("\u201c本座乃魔尊，尔等还不速速退下。\u201d", ["魔尊"]),
    ("\u201c小女子姓聂，名小倩。\u201d", ["聂小倩"]),
    ("\u201c我是新来的编辑，叫我小林就好。\u201d", ["小林"]),
    ("\u201c咱叫阿贵，是这街上的更夫。\u201d", ["阿贵"]),
    ("\u201c本官乃江州司法参军燕六郎。\u201d", ["燕六郎"]),
    ("\u201c我是灰原哀，请多指教。\u201d", ["灰原哀"]),
]:
    add("first_appearance", t, m)

op = os.path.join(OUT, "challenge_mentions_150.jsonl")
with open(op, "w", encoding="utf-8") as f:
    for c in cases:
        f.write(json.dumps(c, ensure_ascii=False) + "\n")
print("challenge set:", len(cases), "cases")
print("domains:", dict(Counter(c["domain"] for c in cases)))
print("total gold mentions:", sum(len(c["mentions"]) for c in cases))
