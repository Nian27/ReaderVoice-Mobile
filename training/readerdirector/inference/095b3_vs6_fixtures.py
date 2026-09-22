# -*- coding: utf-8 -*-
"""TASK-095-VS6.3 — hard binding fixtures 构造（用户 15 类清单）+ gold。

覆盖：direct owner / observer / reference imitation / causer / speaker attribution /
possessive / pronoun / multi-person / passive / embodiment-control / listener trap /
UNKNOWN owner / SET+ref unknown / CLEAR / PERFORMANCE（不进持久 binder）。

候选 opaque：C0/C1/C2/C3（张三/李四/王五/赵六）；gold 每例：owner/ref/observer-causer。
输出：runs/task095/vs6_binding_fixtures.jsonl
"""
import json
import os

OUT = os.path.join(os.path.dirname(os.path.abspath(__file__)), "..", "..", "..", "training", "readerdirector", "runs", "task095")

NAMES = {"C0": "张三", "C1": "李四", "C2": "王五", "C3": "赵六"}

# (category, text, owner, reference, observer_or_causer(None 可), expected_pending=False)
CASES = [
    # --- direct owner ---
    ("direct_owner", "李四的声音突然变得苍老", "C1", None, None),
    ("direct_owner", "王五的嗓音完全变成了另一个人", "C2", None, None),
    ("direct_owner", "赵六的声线一下子换了", "C3", None, None),
    ("direct_owner", "张三的嗓子突然哑了", "C0", None, None),
    ("direct_owner", "李四的声音里多了一分阴冷", "C1", None, None),
    # --- observer（owner 不是句首）---
    ("observer", "张三听见李四的声音变了", "C1", None, "C0"),
    ("observer", "王五听到赵六的嗓音突然嘶哑", "C3", None, "C2"),
    ("observer", "张三看着李四的声音一点点消失", "C1", None, "C0"),
    ("observer", "李四发现王五的声音完全换了", "C2", None, "C1"),
    # --- reference imitation ---
    ("reference", "李四模仿王五的声音说话", "C1", "C2", None),
    ("reference", "张三学着赵六的嗓音开口", "C0", "C3", None),
    ("reference", "王五装成李四的声音回答", "C2", "C1", None),
    ("reference", "赵六模仿张三的腔调", "C3", "C0", None),
    # --- causer（causer 不是 owner）---
    ("causer", "张三让李四学着王五的声音说话", "C1", "C2", "C0"),
    ("causer", "李四吩咐赵六换成老人的声音", "C3", None, "C1"),
    # --- speaker attribution（前句对话归属）---
    ("speaker_attr", "“我没事。”李四突然换了副嗓子", "C1", None, None),
    ("speaker_attr", "“别过来！”王五的声音忽然变得尖锐", "C2", None, None),
    # --- possessive ---
    ("possessive", "李四的声音变得温柔起来", "C1", None, None),
    ("possessive", "王五的嗓子恢复了原状", "C2", None, None),
    ("possessive", "赵六的声线变得沙哑", "C3", None, None),
    # --- pronoun ---
    ("pronoun", "李四清了清嗓子。他忽然恢复了自己的声音", "C1", None, None),  # 前文先行词
    ("pronoun", "张三迟疑片刻。她把自己的声音压低，装成男人", "C0", None, None),
    ("pronoun", "李四望着张三。他模仿着她的声音说", "C1", "C0", None),
    # --- multi-person ---
    ("multi_person", "张三、李四和王五坐在堂上，李四突然模仿起王五的声音", "C1", "C2", "C0"),
    ("multi_person", "李四听着王五和赵六说话，忽然王五的声音变成了老人的声音", "C2", None, "C1"),
    # --- passive ---
    ("passive", "李四的嗓音被处理成了机器声", "C1", None, None),
    ("passive", "王五的声音被调成了女声", "C2", None, None),
    # --- embodiment/control ---
    ("embodiment", "魔尊控制着林雪的身体，用自己的声音说道", "UNKNOWN", None, None),  # owner=发声主体未知(魔尊/林雪之争) → PENDING
    ("embodiment", "狐妖附在赵六身上，却用赵六本来的声音说话", "C3", None, None),
    ("embodiment", "附身的厉鬼借张三的喉咙发出嘶哑的嗓音", "UNKNOWN", None, None),
    # --- listener trap ---
    ("listener_trap", "张三对李四说，王五的声音真奇怪", "C2", None, "C0"),
    ("listener_trap", "李四告诉张三，赵六的声音好像换了个人", "C3", None, "C1"),
    # --- UNKNOWN owner ---
    ("unknown_owner", "不知从哪传来一道变了调的声音", "UNKNOWN", None, None),
    ("unknown_owner", "黑暗中，有人突然用嘶哑的声音开口", "UNKNOWN", None, None),
    # --- SET + reference unknown ---
    ("set_ref_unknown", "李四开始模仿刚才那个男人的声音", "C1", "UNKNOWN", None),
    ("set_ref_unknown", "王五学着记忆中某位故人的嗓音", "C2", "UNKNOWN", None),
    # --- CLEAR ---
    ("clear", "李四恢复了原本的声音", "C1", None, None),
    ("clear", "王五的声音恢复正常", "C2", None, None),
    ("clear", "赵六不再捏着嗓子说话", "C3", None, None),
    # --- PERFORMANCE（不进入持久 binder）---
    ("performance", "李四压低声音说：别出声", "C1", None, None),
    ("performance", "王五冷冷地说道", "C2", None, None),
    ("performance", "赵六颤抖着声音回答", "C3", None, None),
    # --- 更多 reference/direct 变化 ---
    ("direct_owner", "李四的声音变成了一种奇怪的金属音", "C1", None, None),
    ("reference", "张三模仿王五的嗓音，惟妙惟肖", "C0", "C2", None),
    ("observer", "赵六听见李四的声音变得阴森", "C1", None, "C3"),
    ("causer", "王五命令李四换个女人的声音", "C1", None, "C2"),
    ("pronoun", "李四松了口气。他叹了口气，声音恢复了正常", "C1", None, None),
    ("multi_person", "三人围坐，张三忽然用王五的嗓音说话", "C0", "C2", None),
    ("passive", "李四的声线被法器改变", "C1", None, None),
    ("embodiment", "山神借李四之口，用王五的声音发话", "UNKNOWN", "C2", None),
    ("listener_trap", "张三对王五抱怨：李四的声音越来越怪", "C1", None, "C0"),
    ("unknown_owner", "一个陌生的声音忽然响起，带着明显的变调", "UNKNOWN", None, None),
    ("set_ref_unknown", "赵六开始学一个不知名者的嗓音", "C3", "UNKNOWN", None),
    ("clear", "李四终于恢复了原声", "C1", None, None),
    ("performance", "王五轻声细语地回答", "C2", None, None),
    ("direct_owner", "赵六的声音里带上了哭腔", "C3", None, None),
    ("reference", "李四模仿张三的声音，连语气都像", "C1", "C0", None),
    ("observer", "张三看着赵六，听见他的声音变了", "C3", None, "C0"),
    ("causer", "李四让王五把声音压低", "C2", None, "C1"),
    ("pronoun", "张三开口。她忽然用沙哑的嗓音说话", "C0", None, None),
    ("multi_person", "李四和王五对视一眼，王五突然学起了李四的声音", "C2", "C1", None),
    ("passive", "王五的嗓音被阵法扭曲", "C2", None, None),
    ("embodiment", "魔尊占据林雪身体，发声时却是林雪自己的声音", "UNKNOWN", None, None),
    ("listener_trap", "李四对赵六说：你听见张三换声音了吗", "C0", None, "C1"),
    ("unknown_owner", "背后传来一个变过调的声音", "UNKNOWN", None, None),
    ("set_ref_unknown", "李四模仿着记忆里那个陌生人的声音", "C1", "UNKNOWN", None),
    ("clear", "王五的声音又变回原来的样子", "C2", None, None),
    ("performance", "赵六笑着说：哪有的事", "C3", None, None),
    ("direct_owner", "李四的嗓音变得沙哑而低沉", "C1", None, None),
    ("reference", "王五学着张三的腔调应答", "C2", "C0", None),
    ("observer", "张三听见王五的嗓音突然换了", "C2", None, "C0"),
    ("causer", "赵六让李四别再捏嗓子", "C1", None, "C3"),
    ("pronoun", "李四一怔。他的声音忽然消失，又忽然响起", "C1", None, None),
    ("multi_person", "四人中，王五率先换了一副嗓音", "C2", None, None),
    ("passive", "李四的声音被夺舍者压制", "UNKNOWN", None, None),
    ("embodiment", "神明降临，用李四的嗓子说出不属于他的话", "UNKNOWN", None, None),
    ("listener_trap", "李四问王五：张三的声音是不是变了", "C0", None, "C1"),
    ("unknown_owner", "一声干涩的变调嗓音从角落传来", "UNKNOWN", None, None),
    ("set_ref_unknown", "张三模仿起那个蒙面人的声音", "C0", "UNKNOWN", None),
    ("clear", "赵六的声音恢复了正常", "C3", None, None),
    ("performance", "李四小声嘀咕了一句", "C1", None, None),
    ("direct_owner", "王五的声线带着一丝颤抖", "C2", None, None),
    ("reference", "赵六学着李四的嗓音，模仿得极像", "C3", "C1", None),
    ("observer", "李四看到王五的嗓子变了", "C2", None, "C1"),
    ("causer", "张三授意李四用老人的声音回话", "C1", None, "C0"),
    ("pronoun", "李四站定。他清了清嗓子，用低沉的声音说", "C1", None, None),
    ("multi_person", "李四、王五、赵六面面相觑，王五忽然用女声开口", "C2", None, None),
    ("passive", "赵六的嗓音被幻术改动", "C3", None, None),
    ("embodiment", "器灵附身李四，说话时却是器灵自己的声音", "UNKNOWN", None, None),
    ("listener_trap", "赵六对李四说：王五的声音听着不对劲", "C2", None, "C3"),
    ("unknown_owner", "有个声音在暗处变着调子说话", "UNKNOWN", None, None),
    ("set_ref_unknown", "王五学着方才那陌生嗓音", "C2", "UNKNOWN", None),
    ("clear", "李四不再用老人的声音", "C1", None, None),
    ("performance", "王五平静地说完", "C2", None, None),
    ("direct_owner", "李四的声音忽然拔高", "C1", None, None),
    ("reference", "张三模仿王五的嗓音说话", "C0", "C2", None),
    ("observer", "赵六听见李四的声音变得粗犷", "C1", None, "C3"),
    ("causer", "王五叫李四学猫叫", "C1", None, "C2"),
    ("pronoun", "张三压低身子。她压低了声音，仿佛怕人听见", "C0", None, None),
    ("multi_person", "众人之中，只有李四换上了老人的嗓音", "C1", None, None),
    ("passive", "王五的声音被掐得又细又尖", "C2", None, None),
    ("embodiment", "老魔借张三之身，声音却是老魔的", "UNKNOWN", None, None),
    ("listener_trap", "李四低声对王五说：张三的声音怪怪的", "C0", None, "C1"),
    ("unknown_owner", "远处飘来一道走调的声音", "UNKNOWN", None, None),
    ("set_ref_unknown", "赵六开始模仿那位故人的声线", "C3", "UNKNOWN", None),
    ("clear", "王五恢复了自己本来的嗓音", "C2", None, None),
    ("performance", "赵六提高音量喊道", "C3", None, None),
    ("direct_owner", "李四的嗓子像换了个人", "C1", None, None),
    ("reference", "王五模仿赵六的嗓音", "C2", "C3", None),
    ("observer", "张三听见赵六的声音完全变了", "C3", None, "C0"),
    ("causer", "李四让赵六别再用假声", "C3", None, "C1"),
    ("pronoun", "他恢复了自己原本的声线", "C1", None, None),
    ("multi_person", "张三和李四并肩，张三忽然用李四的声音说话", "C0", "C1", None),
    ("passive", "李四的嗓音被魔音侵蚀", "C1", None, None),
    ("embodiment", "神明俯身王五，却用王五的声音宣告", "C2", None, None),
    ("listener_trap", "赵六告诉李四：王五的声音变味了", "C2", None, "C3"),
    ("unknown_owner", "一道陌生的变调嗓音响起", "UNKNOWN", None, None),
    ("set_ref_unknown", "李四学着梦中那人的声音", "C1", "UNKNOWN", None),
    ("clear", "赵六换回了自己的声音", "C3", None, None),
    ("performance", "李四冷冷道", "C1", None, None),
    ("direct_owner", "王五的声音里带着金属摩擦感", "C2", None, None),
    ("reference", "赵六模仿王五的嗓音应答", "C3", "C2", None),
    ("observer", "李四听见王五的声音飘忽不定", "C2", None, "C1"),
    ("causer", "张三让王五换上李四的嗓音", "C2", "C1", "C0"),
    ("pronoun", "他的声音低沉了下去", "C1", None, None),
    ("multi_person", "李四看着张三和王五，忽然王五模仿起张三的声音", "C2", "C0", "C1"),
    ("passive", "赵六的嗓音被夺去", "UNKNOWN", None, None),
    ("embodiment", "魔尊操纵李四身躯，发出魔尊自己的声音", "UNKNOWN", None, None),
    ("listener_trap", "王五对赵六说：李四的声音听起来不像他", "C1", None, "C2"),
    ("unknown_owner", "一道被处理过的声音传来", "UNKNOWN", None, None),
    ("set_ref_unknown", "王五模仿着那个神秘客的声音", "C2", "UNKNOWN", None),
    ("clear", "李四的声音恢复正常了", "C1", None, None),
    ("performance", "王五柔声道", "C2", None, None),
    ("direct_owner", "赵六的嗓音低沉如钟", "C3", None, None),
    ("reference", "李四模仿王五的腔调", "C1", "C2", None),
    ("observer", "张三看着李四的喉咙，声音确实变了", "C1", None, "C0"),
    ("causer", "王五逼李四换声", "C1", None, "C2"),
    ("pronoun", "张三眨眼。她捏着嗓子，装出小女孩的声音", "C0", None, None),
    ("multi_person", "李四与赵六交谈，赵六忽然用了李四的声音", "C3", "C1", None),
    ("passive", "王五的声线被药力改变", "C2", None, None),
    ("embodiment", "狐仙借王五肉身，开口是狐仙的声音", "UNKNOWN", None, None),
    ("listener_trap", "李四告诉王五：赵六的声音变了", "C3", None, "C1"),
    ("unknown_owner", "耳边响起一道怪异的变声", "UNKNOWN", None, None),
    ("set_ref_unknown", "赵六学着一个无名者的声音", "C3", "UNKNOWN", None),
    ("clear", "王五不再模仿别人，声音恢复如初", "C2", None, None),
    ("performance", "赵六急促地说道", "C3", None, None),
    ("direct_owner", "李四的声音变得陌生", "C1", None, None),
    ("reference", "张三学着王五的嗓音", "C0", "C2", None),
    ("observer", "赵六发觉王五的声音换了", "C2", None, "C3"),
    ("causer", "李四要求赵六用童声说话", "C3", None, "C1"),
    ("pronoun", "李四皱眉。他换了副嗓音", "C1", None, None),
    ("multi_person", "四人中李四的声音最先变了", "C1", None, None),
    ("passive", "赵六的声音被妖力扭曲", "C3", None, None),
    ("embodiment", "灵魂出窍的张三借李四的声音说话", "UNKNOWN", None, None),
    ("listener_trap", "王五对李四说：你听赵六的声音", "C3", None, "C2"),
    ("unknown_owner", "一声怪异的嗓音突然出现", "UNKNOWN", None, None),
    ("set_ref_unknown", "李四开始模仿那个神秘嗓音", "C1", "UNKNOWN", None),
    ("clear", "赵六的嗓音终于恢复", "C3", None, None),
    ("performance", "李四笑着说", "C1", None, None),
    ("direct_owner", "王五的声音出现了一丝裂纹", "C2", None, None),
]

def main():
    rows = []
    for i, (cat, text, owner, ref, obs) in enumerate(CASES):
        rows.append({
            "idx": i, "category": cat, "text": text,
            "gold": {"owner": owner, "reference": ref, "observer_causer": obs},
            "candidates": [{"role": k, "name": v} for k, v in NAMES.items()],
        })
    op = os.path.join(OUT, "vs6_binding_fixtures.jsonl")
    with open(op, "w", encoding="utf-8") as f:
        for r in rows:
            f.write(json.dumps(r, ensure_ascii=False) + chr(10))
    from collections import Counter
    print("total:", len(rows))
    print("by category:", dict(Counter(r["category"] for r in rows)))
    print("saved:", op)

if __name__ == "__main__":
    main()