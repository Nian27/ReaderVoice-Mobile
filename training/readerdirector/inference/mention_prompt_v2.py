# -*- coding: utf-8 -*-
"""MENTION teacher prompts（ADR-039）。

- PROMPT_FS_V2：mention_fs_v2——非实体清单规则文本版（Dev-QC 结果：部分修复，
  但负例规则文本对 4B 跟随弱、NONE 桶 over-extract，已弃用为 v3）
- PROMPT_FS_V3：mention_fs_v3——示例化负例 + 收紧代词 + 无 cue 具名人物示例（当前版）
"""
PROMPT_FS_V2 = (
    "你是 ReaderDirector。找出下面文本中所有\u201c人物/可发声实体\u201d的提及（mention）："
    "人物、群体、职位/称谓、昵称、非人智能体等。\n"
    "规则：\n"
    "- 只输出原文中逐字存在的片段（surface），不要创造、改写、省略或翻译名字\n"
    "- 叙述中的主语（\u201cX做了某事\u201d）也是 mention\n"
    "- 对话开头的称呼（呼语，如\u201c维迪，\u201d\u201c远，\u201d\u201cB1，\u201d）是 mention\n"
    "- 叙述中的代词（她/他/我）明确指代场景内人物时是 mention；泛称\u201c你/你们/自己\u201d不算\n"
    "- 群体（众家丁/将士们/孩子们）也是 mention；机构/公司/国家作为行为主体时算（如\u201cSBS公开道歉\u201d）\n"
    "- 长名字和完整称号必须整体复制，不要截断（如\u201c亚历山大\u00b7谢尔盖耶维奇\u00b7普希金\u201d\u201c守夜人总司令琼恩\u00b7雪诺\u201d）\n"
    "- 侮辱性称呼、特征指代（\u201c苁母狗\u201d\u201c胖胖的中年女人\u201d\u201c那小子\u201d）是 mention\n"
    "- 每个 mention 输出 surface 和 type（PERSON/GROUP/NON_PERSON_AGENT/ROLE_MENTION/UNKNOWN_AGENT）\n"
    "- 没有则输出空列表\n"
    "不是 mention（禁止输出）：作品/影视/游戏/杂志名称（如\u201c《楚门的世界》\u201d\u201cPokeball 2\u201d）、"
    "物品/产品/武器/装备（如\u201c武装机器狗\u201d\u201cA 片\u201d\u201c碧罗三清秘卷\u201d）、"
    "地点/建筑（如\u201c指挥使府\u201d\u201c战神殿\u201d）、时间/朝代/年号（如\u201c嘉靖年间\u201d）、"
    "律法/典籍/功法名（如\u201c大明律\u201d）、疾病/病毒（如\u201cMERS\u201d）、会议/活动名、"
    "泛指职业/阶层类别（如\u201c练气士\u201d\u201c采药人\u201d\u201c演员\u201d\u201c领导\u201d）、"
    "比喻/典故引用（如\u201c像小龙女一样\u201d）、纯头衔名词（如\u201c冥界圣人\u201d作名词用）\n"
    '输出 JSON: {"mentions": [{"surface": "...", "type": "..."}]}\n\n'
    "示例1：\n文本：雪千寻递过去一把精致的火铳，叶洋在一旁看着。\n"
    '输出：{"mentions": [{"surface": "雪千寻", "type": "PERSON"}, {"surface": "叶洋", "type": "PERSON"}]}\n\n'
    "示例2：\n文本：众家丁退下，只留下了护院首领李四，他沉声道：\u201c都下去吧。\u201d\n"
    '输出：{"mentions": [{"surface": "众家丁", "type": "GROUP"}, {"surface": "李四", "type": "PERSON"}, {"surface": "他", "type": "PERSON"}]}\n\n'
    "示例3（非实体负例）：\n文本：他最近迷上了《精灵luna》和Pokeball 2，还说起MERS疫情。\n"
    '输出：{"mentions": [{"surface": "他", "type": "PERSON"}]}\n\n'
    "示例4（呼语+机构）：\n文本：\u201c远，你来了。\u201dSBS随后公开道歉。\n"
    '输出：{"mentions": [{"surface": "远", "type": "PERSON"}, {"surface": "SBS", "type": "NON_PERSON_AGENT"}]}\n\n'
    "文本：\n{text}"
)

# -*- coding: utf-8 -*-
"""MENTION teacher prompt v3（mention_fs_v3，ADR-039 + v2 Dev-QC 归因）。

v3 相对 v2（Dev-QC 222 条实测）：
1. 规则文本精简（v2 过长 → 4B 跟随差、NONE 桶 over-extract）
2. 非实体负例从规则改为示例（few-shot 示例跟随优于规则文本）
3. 代词规则收紧：只算明确指代场景实体的她/他/我；泛称你/你们/自己/两人不算
4. 补"无 cue 叙述中具名人物"示例（#13/#16 型 MISS）
"""
PROMPT_FS_V3 = (
    "你是 ReaderDirector。找出下面文本中所有\u201c人物/可发声实体\u201d的提及（mention）。\n"
    "要点：\n"
    "- 只输出原文中逐字存在的片段（surface），不要创造、改写、省略或翻译\n"
    "- 人物/群体/职位称谓/昵称/非人智能体都算；机构/公司/国家作为行为主体时也算\n"
    "- 对话开头的称呼（呼语）算；叙述中明确指代场景人物的\u201c她/他/我\u201d算；泛称\u201c你/你们/自己/两人\u201d不算\n"
    "- 作品名/物品名/产品名/地点/建筑/时间/年号/律法/典籍/疾病/会议名/泛指职业类别/比喻引用都不算\n"
    "- 每个 mention 输出 surface 和 type（PERSON/GROUP/NON_PERSON_AGENT/ROLE_MENTION/UNKNOWN_AGENT）；没有则输出空列表\n"
    '输出 JSON: {"mentions": [{"surface": "...", "type": "..."}]}\n\n'
    "示例1：\n文本：郑仲夫道：\u201c金敦中之父当年幽禁陛下。\u201d\n"
    '输出：{"mentions": [{"surface": "郑仲夫", "type": "PERSON"}, {"surface": "金敦中", "type": "PERSON"}]}\n\n'
    "示例2：\n文本：雪千寻递过去一把精致的火铳，叶洋在一旁看着。他沉声道：\u201c都下去吧。\u201d\n"
    '输出：{"mentions": [{"surface": "雪千寻", "type": "PERSON"}, {"surface": "叶洋", "type": "PERSON"}, {"surface": "他", "type": "PERSON"}]}\n\n'
    "示例3（呼语）：\n文本：\u201c远，没看出来，你还挺有正义感。\u201d\n"
    '输出：{"mentions": [{"surface": "远", "type": "PERSON"}]}\n\n'
    "示例4（非实体负例）：\n文本：他最近迷上了《精灵luna》和Pokeball 2，还说起MERS疫情和《大明律》。\n"
    '输出：{"mentions": [{"surface": "他", "type": "PERSON"}]}\n\n'
    "示例5（泛指类别+地点负例）：\n文本：练气士们不会去惊雁宫，采药人也绕开指挥使府。\n"
    '输出：{"mentions": [{"surface": "练气士们", "type": "GROUP"}]}\n\n'
    "示例6（机构）：\n文本：SBS公开道歉，璀璨娱乐公司随即跟进。\n"
    '输出：{"mentions": [{"surface": "SBS", "type": "NON_PERSON_AGENT"}, {"surface": "璀璨娱乐公司", "type": "NON_PERSON_AGENT"}]}\n\n'
    "文本：\n{text}"
)