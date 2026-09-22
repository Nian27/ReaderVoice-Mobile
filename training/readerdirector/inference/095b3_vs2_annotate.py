# -*- coding: utf-8 -*-
"""TASK-095-VS2.1 — 4B few-shot VOICE_EVENT 标注（Prompt v1/v2 A/B）。

VS2.1 纪律：候选集（410）、retrieval cue、模型、temperature、评估脚本全部不变，只改 Teacher prompt。

输入: runs/task095/vs2_candidates.jsonl
输出: runs/task095/vs2_annotated_{v1,v2}.jsonl

用法: python inference/095b3_vs2_annotate.py --prompt_version v2 [--resume]
"""
import argparse
import json
import os
import re
import sys

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
from importlib import import_module
_m = import_module("095b2_eval_mention")
run = _m.run

ROOT = os.path.abspath(os.path.join(os.path.dirname(__file__), "..", "..", ".."))
OUT = os.path.join(ROOT, "training", "readerdirector", "runs", "task095")
MODEL_DIR = os.path.join(ROOT, "training", "models", "qwen3.5-4b")

# ============ Prompt v1（原样保留，A/B 对照） ============
PROMPT_V1 = (
    "你是 ReaderDirector。判断下面文本中是否发生了\u201c临时声音/变声事件\u201d。\n"
    "\n"
    "定义：\n"
    "- TEMP_SET：角色开始使用与平时不同的声音/声线/腔调（模仿他人、装老/装嫩、捏嗓子、压低声音伪装、故意用某种语气说话等）。\n"
    "- TEMP_CLEAR：角色恢复自己原本的声音（\u201c恢复原声\u201d\u201c变回自己的声音\u201d\u201c恢复正常\u201d等）。\n"
    "- NO_EVENT：没有临时变声事件。\n"
    "\n"
    "重要排除（不是 TEMP_SET）：\n"
    "- 情绪/语气词（愤怒地喊、冷冷道、大声说、笑着说、哭着说）只是情绪/prosody，不是变声\n"
    "- 轻声/低声/小声说（仅音量调整）不是变声，除非明确是伪装身份\n"
    "- 退场/换场/场景切换不是变声（角色离开不代表声音恢复）\n"
    "\n"
    "输出 JSON：{\"event\": \"NO_EVENT\" | \"TEMP_SET\" | \"TEMP_CLEAR\", \"style\": \"变声风格描述或 null\", \"scope\": \"UTTERANCE\" | \"UNTIL_CLEAR\" | \"UNKNOWN\", \"evidence_span\": \"原文中逐字存在的证据片段，NO_EVENT 时为 null\"}\n"
    "\n"
    "示例1：她故意模仿起母亲的声音，细声细气地说：\u201c女儿见过母亲。\u201d\n"
    "输出：{\"event\": \"TEMP_SET\", \"style\": \"IMITATION\", \"scope\": \"UNTIL_CLEAR\", \"evidence_span\": \"模仿起母亲的声音\"}\n"
    "\n"
    "示例2：他恢复了原本的声音，声音低沉而浑厚。\n"
    "输出：{\"event\": \"TEMP_CLEAR\", \"style\": null, \"scope\": \"UNTIL_CLEAR\", \"evidence_span\": \"恢复了原本的声音\"}\n"
    "\n"
    "示例3：他压低声音说：\u201c别出声。\u201d\n"
    "输出：{\"event\": \"TEMP_SET\", \"style\": \"LOW_VOICE\", \"scope\": \"UTTERANCE\", \"evidence_span\": \"压低声音\"}\n"
    "\n"
    "示例4：张三愤怒地喊道：\u201c你给我站住！\u201d\n"
    "输出：{\"event\": \"NO_EVENT\", \"style\": null, \"scope\": null, \"evidence_span\": null}\n"
    "\n"
    "文本：\n{text}"
)

# ============ Prompt v2（VS2.1：四类硬负例 + TEMP_CLEAR 严格定义 + 正例收窄） ============
PROMPT_V2 = (
    "你是 ReaderDirector。判断下面文本中是否发生了\u201c临时声线事件\u201d。\n"
    "\n"
    "核心定义（必须严格遵守）：\n"
    "TEMP_SET = 文本明确表示说话者\u201c主动/明确地\u201d发生声线、嗓音、声音身份的临时改变，并且这种改变会跨句子持续（可能影响后续 TTS 音色渲染）。例如：故意压低嗓音、学着母亲的声音说话、换成一个苍老男人的声音、捏着嗓子说话、用假声说话。\n"
    "TEMP_CLEAR = 文本明确表示解除/停止此前的临时声线状态（恢复自己的声音、不再捏着嗓子、那种苍老嗓音消失了、变回原来的声音）。\n"
    "NO_EVENT = 其他所有情况。\n"
    "\n"
    "以下四类一律是 NO_EVENT（硬负例）：\n"
    "1. 固有音色：沙哑/嘶哑/苍老/低沉的声音若只是人物本来声音（没有主动改变）→ NO_EVENT。\n"
    "2. 生理/物理原因：嘴里含东西、受伤、生病导致声音异常 → NO_EVENT（不是主动变声）。\n"
    "3. 情绪/普通韵律（EMOTION/PROSODY/VOLUME）：冰冷、愤怒、诱惑、悲伤、低声、轻声、大声、故意拉长语调等，只影响情绪/音量/韵律，不改变声线身份 → NO_EVENT。\u201c低声说话\u201d默认只是音量，不建立跨句变声状态。\n"
    "4. 行为/身份伪装：装样子、模仿动作/术法/称呼方式、恢复秩序/精神/平静、恢复正常（非声音）→ 不是声音变化 → NO_EVENT。\n"
    "\n"
    "判断原则：只有\u201c声线/嗓音/声音身份\u201d本身的临时切换才算 TEMP_SET；单句的情绪、音量、语速、普通韵律不算；\u201c恢复正常\u201d除非明确指恢复声音，否则不算 TEMP_CLEAR。\n"
    "\n"
    "输出 JSON：{\"event\": \"NO_EVENT\" | \"TEMP_SET\" | \"TEMP_CLEAR\", \"style\": \"变声风格描述或 null\", \"scope\": \"UTTERANCE\" | \"UNTIL_CLEAR\" | \"UNKNOWN\", \"evidence_span\": \"原文中逐字存在的证据片段，NO_EVENT 时为 null\"}\n"
    "\n"
    "示例1（正例）：他故意压低嗓音，装作另一个人的口气说道：\u201c我是你爹。\u201d\n"
    "输出：{\"event\": \"TEMP_SET\", \"style\": \"LOW_VOICE_IMITATION\", \"scope\": \"UNTIL_CLEAR\", \"evidence_span\": \"故意压低嗓音\"}\n"
    "\n"
    "示例2（正例）：她学着母亲的声音说道：\u201c女儿见过母亲。\u201d\n"
    "输出：{\"event\": \"TEMP_SET\", \"style\": \"IMITATION\", \"scope\": \"UNTIL_CLEAR\", \"evidence_span\": \"学着母亲的声音\"}\n"
    "\n"
    "示例3（正例）：他换成一个苍老男人的声音，缓缓开口。\n"
    "输出：{\"event\": \"TEMP_SET\", \"style\": \"OLD_MAN_VOICE\", \"scope\": \"UNTIL_CLEAR\", \"evidence_span\": \"换成一个苍老男人的声音\"}\n"
    "\n"
    "示例4（正例）：她捏着嗓子说道：\u201c人家好怕怕哦。\u201d\n"
    "输出：{\"event\": \"TEMP_SET\", \"style\": \"PINCHED_VOICE\", \"scope\": \"UNTIL_CLEAR\", \"evidence_span\": \"捏着嗓子\"}\n"
    "\n"
    "示例5（正例）：他恢复了自己的声音，沉声道：\u201c够了。\u201d\n"
    "输出：{\"event\": \"TEMP_CLEAR\", \"style\": null, \"scope\": \"UNTIL_CLEAR\", \"evidence_span\": \"恢复了自己的声音\"}\n"
    "\n"
    "示例6（硬负例）：他的声音冰冷。\n"
    "输出：{\"event\": \"NO_EVENT\", \"style\": null, \"scope\": null, \"evidence_span\": null}\n"
    "\n"
    "示例7（硬负例）：他声音沙哑，显然是感冒了。\n"
    "输出：{\"event\": \"NO_EVENT\", \"style\": null, \"scope\": null, \"evidence_span\": null}\n"
    "\n"
    "示例8（硬负例）：她愤怒地喊道：\u201c你给我站住！\u201d\n"
    "输出：{\"event\": \"NO_EVENT\", \"style\": null, \"scope\": null, \"evidence_span\": null}\n"
    "\n"
    "示例9（硬负例）：他低声说道：\u201c走。\u201d\n"
    "输出：{\"event\": \"NO_EVENT\", \"style\": null, \"scope\": null, \"evidence_span\": null}\n"
    "\n"
    "示例10（硬负例）：他装出一副正经的样子，模仿着师傅的招式。\n"
    "输出：{\"event\": \"NO_EVENT\", \"style\": null, \"scope\": null, \"evidence_span\": null}\n"
    "\n"
    "示例11（硬负例）：一切恢复正常，众人松了口气。\n"
    "输出：{\"event\": \"NO_EVENT\", \"style\": null, \"scope\": null, \"evidence_span\": null}\n"
    "\n"
    "文本：\n{text}"
)

PROMPT_V3 = (
    "你是 ReaderDirector。判断下面文本中的\u201c声音控制事件\u201d（对 TTS 渲染有意义的信息）。\n"
    "\n"
    "四类事件（必须区分）：\n"
    "1. VOICE_OVERRIDE_SET：角色的\u201c有效声线身份/音色\u201d被临时替换（模仿他人声音、换成一个苍老男人的嗓音、捏着嗓子装作女人说话、用假声冒充别人）。声线身份改变 → 可能持续影响后续多个句子/渲染单元。\n"
    "2. VOICE_OVERRIDE_CLEAR：明确解除此前的声线替换（恢复成自己本来的声音、不再捏着嗓子、那种苍老嗓音消失了）。\n"
    "3. PERFORMANCE：仍是这个人的声音（VoiceProfile 不变），只是\u201c当前怎么说\u201d——音量（压低声音/低声/大喊/轻声）、韵律（颤抖、拉长语调）、情绪语气（冰冷、愤怒、诱惑、悲伤）等。只影响当前/有限渲染单元。\n"
    "4. NONE：与声音控制无关——装样子/模仿动作或术法/恢复秩序精神面貌/固有音色描述（他声音一直很沙哑）。\n"
    "\n"
    "判断原则：\n"
    "- 只有\u201c声线身份/音色替换\u201d才算 OVERRIDE；\u201c怎么说话\u201d（音量/情绪/韵律）是 PERFORMANCE；两者必须分清。\n"
    "- 固有音色（一直沙哑/天生低沉）是人物属性 → NONE；因受伤/生病/嘴里含东西导致的临时声音异常 → PERFORMANCE。\n"
    "- 恢复正常/恢复平静/恢复秩序 若未明确指声音 → NONE；恢复/变回/切换成\u201c本来的声音\u201d → VOICE_OVERRIDE_CLEAR。\n"
    "- 模仿/学着：必须作用在\u201c声音/嗓音/声线/语气腔调\u201d上才是 OVERRIDE；模仿动作/招式/术法/称呼 → NONE。\n"
    "\n"
    "输出 JSON：{\"kind\": \"VOICE_OVERRIDE\" | \"PERFORMANCE\" | \"NONE\", \"operation\": \"SET\" | \"CLEAR\"（仅 OVERRIDE，其余 null）, \"style\": \"风格描述或 null\", \"scope\": \"UTTERANCE\" | \"UNTIL_CLEAR\" | \"UNKNOWN\"（NONE 为 null）, \"evidence_span\": \"原文逐字证据，NONE 时为 null\"}\n"
    "\n"
    "成对示例（重点学习区分）：\n"
    "\n"
    "对1a：他压低声音说道：\u201c走。\u201d\n"
    "输出：{\"kind\": \"PERFORMANCE\", \"operation\": null, \"style\": \"LOW_VOICE\", \"scope\": \"UTTERANCE\", \"evidence_span\": \"压低声音\"}\n"
    "对1b：他压低嗓音，故意模仿张三的声音说道：\u201c我是你爹。\u201d\n"
    "输出：{\"kind\": \"VOICE_OVERRIDE\", \"operation\": \"SET\", \"style\": \"IMITATION\", \"scope\": \"UNTIL_CLEAR\", \"evidence_span\": \"故意模仿张三的声音\"}\n"
    "\n"
    "对2a：她的声音冰冷。\n"
    "输出：{\"kind\": \"PERFORMANCE\", \"operation\": null, \"style\": \"COLD\", \"scope\": \"UTTERANCE\", \"evidence_span\": \"声音冰冷\"}\n"
    "对2b：她忽然换成一个苍老妇人的声音说话。\n"
    "输出：{\"kind\": \"VOICE_OVERRIDE\", \"operation\": \"SET\", \"style\": \"OLD_WOMAN_VOICE\", \"scope\": \"UNTIL_CLEAR\", \"evidence_span\": \"换成一个苍老妇人的声音\"}\n"
    "\n"
    "对3a：他恢复正常，众人松了口气。\n"
    "输出：{\"kind\": \"NONE\", \"operation\": null, \"style\": null, \"scope\": null, \"evidence_span\": null}\n"
    "对3b：他恢复了原本的声音。\n"
    "输出：{\"kind\": \"VOICE_OVERRIDE\", \"operation\": \"CLEAR\", \"style\": null, \"scope\": \"UNTIL_CLEAR\", \"evidence_span\": \"恢复了原本的声音\"}\n"
    "\n"
    "对4a：他模仿着电影里的动作。\n"
    "输出：{\"kind\": \"NONE\", \"operation\": null, \"style\": null, \"scope\": null, \"evidence_span\": null}\n"
    "对4b：他模仿着父亲的声音。\n"
    "输出：{\"kind\": \"VOICE_OVERRIDE\", \"operation\": \"SET\", \"style\": \"IMITATION\", \"scope\": \"UNTIL_CLEAR\", \"evidence_span\": \"模仿着父亲的声音\"}\n"
    "\n"
    "对5a：他的声音一直很沙哑，这是他的老毛病。\n"
    "输出：{\"kind\": \"NONE\", \"operation\": null, \"style\": null, \"scope\": null, \"evidence_span\": null}\n"
    "对5b：他因为受伤，声音变得沙哑。\n"
    "输出：{\"kind\": \"PERFORMANCE\", \"operation\": null, \"style\": \"HOARSE\", \"scope\": \"UTTERANCE\", \"evidence_span\": \"声音变得沙哑\"}\n"
    "\n"
    "文本：\n{text}"
)

PROMPT_V31 = (
    "你是 ReaderDirector。判断下面文本中的\u201c声音控制事件\u201d（对 TTS 渲染有意义的信息）。\n"
    "\n"
    "四类事件：\n"
    "1. VOICE_OVERRIDE_SET：角色的\u201c有效声线身份/音色\u201d被临时替换（模仿他人声音、换成一个苍老男人的嗓音、捏着嗓子装作女人说话、用假声冒充别人）。声线身份改变，可能持续影响后续多个句子。\n"
    "2. VOICE_OVERRIDE_CLEAR：明确解除此前的声线替换（恢复成自己本来的声音、不再捏着嗓子、那种苍老嗓音消失了）。\n"
    "3. PERFORMANCE：仍是这个人的声音（VoiceProfile 不变），只是\u201c当前怎么说\u201d——音量（压低声音/低声/大喊/轻声）、韵律（颤抖、拉长语调）、情绪语气（冰冷、愤怒、诱惑、悲伤）。只影响当前/有限渲染单元。\n"
    "4. NONE：与声音控制无关。\n"
    "\n"
    "【第一判定纪律：证据作用对象绑定（最重要，必须遵守）】\n"
    "- OVERRIDE_SET / OVERRIDE_CLEAR 必须存在明确的\u201c声音对象\u201d证据。\n"
    "- \u201c伪装、变回、恢复、模仿、切换、改变\u201d等动作词本身不足以构成 Override；动作必须明确作用于：声音、嗓音、声线、音色、说话声音或等价声学身份。\n"
    "先判断动作改变的对象是什么：\n"
    "- VOICE_IDENTITY：声音/嗓音/声线/音色（如\u201c伪装成石志康的声音\u201d\u201c模仿张三的嗓音\u201d\u201c恢复本来的声音\u201d）→ 才允许 OVERRIDE_SET / OVERRIDE_CLEAR。\n"
    "- VOICE_PERFORMANCE：当前怎么说（音量/情绪/韵律）→ PERFORMANCE。\n"
    "- APPEARANCE：面貌/模样/长相（如\u201c恢复本来面貌\u201d\u201c伪装成石志康的模样\u201d）→ NONE。\n"
    "- IDENTITY：身份/角色（如\u201c伪装成石志康\u201d）→ NONE。\n"
    "- BODY/OTHER：身体/状态/其他（如\u201c恢复正常\u201d\u201c恢复秩序\u201d）→ NONE。\n"
    "\n"
    "【第二判定纪律：evidence 必须逐字连续】\n"
    "evidence_span 必须是输入文本中的逐字连续原文片段；不得改写、补词、概括或生成同义表达。\n"
    "\n"
    "【内部判定顺序（只用于推理，不输出中间字段）】\n"
    "Step 1 定位证据短语。\n"
    "Step 2 判断证据改变的是什么属性：voice/acoustic identity / momentary vocal performance / appearance/body/identity/other。\n"
    "Step 3 只有 voice/acoustic identity 才可选 OVERRIDE_SET 或 OVERRIDE_CLEAR。\n"
    "Step 4 只改变当前说话方式 → PERFORMANCE。\n"
    "Step 5 其他 → NONE。\n"
    "\n"
    "输出 JSON：{\"kind\": \"VOICE_OVERRIDE\" | \"PERFORMANCE\" | \"NONE\", \"operation\": \"SET\" | \"CLEAR\"（仅 OVERRIDE，其余 null）, \"style\": \"风格描述或 null\", \"scope\": \"UTTERANCE\" | \"UNTIL_CLEAR\" | \"UNKNOWN\"（NONE 为 null）, \"evidence_span\": \"原文逐字连续证据，NONE 时为 null\"}\n"
    "\n"
    "对象绑定示例（重点）：\n"
    "- 伪装成石志康 → 身份对象 → NONE：{\"kind\": \"NONE\", \"operation\": null, \"style\": null, \"scope\": null, \"evidence_span\": null}\n"
    "- 伪装成石志康的模样 → 外貌对象 → NONE（同上）\n"
    "- 恢复本来的面目 → 外貌对象 → NONE（同上）\n"
    "- 恢复本来面貌 → 外貌对象 → NONE（同上）\n"
    "- 伪装成石志康的声音 → 声音对象 → {kind: VOICE_OVERRIDE, operation: SET, style: IMITATION, scope: UNTIL_CLEAR, evidence_span: 伪装成石志康的声音}\n"
    "- 模仿石志康的嗓音 → 声音对象 → {kind: VOICE_OVERRIDE, operation: SET, style: IMITATION, scope: UNTIL_CLEAR, evidence_span: 模仿石志康的嗓音}\n"
    "- 恢复本来的声音 → 声音对象 → {kind: VOICE_OVERRIDE, operation: CLEAR, style: null, scope: UNTIL_CLEAR, evidence_span: 恢复本来的声音}\n"
    "- 嗓音恢复成自己原来的样子 → 声音对象 → {kind: VOICE_OVERRIDE, operation: CLEAR, style: null, scope: UNTIL_CLEAR, evidence_span: 嗓音恢复成自己原来的样子}\n"
    "\n"
    "成对示例：\n"
    "对1a：他压低声音说道：\u201c走。\u201d\n"
    "输出：{\"kind\": \"PERFORMANCE\", \"operation\": null, \"style\": \"LOW_VOICE\", \"scope\": \"UTTERANCE\", \"evidence_span\": \"压低声音\"}\n"
    "对1b：他压低嗓音，故意模仿张三的声音说道：\u201c我是你爹。\u201d\n"
    "输出：{\"kind\": \"VOICE_OVERRIDE\", \"operation\": \"SET\", \"style\": \"IMITATION\", \"scope\": \"UNTIL_CLEAR\", \"evidence_span\": \"模仿张三的声音\"}\n"
    "\n"
    "对2a：她的声音冰冷。\n"
    "输出：{\"kind\": \"PERFORMANCE\", \"operation\": null, \"style\": \"COLD\", \"scope\": \"UTTERANCE\", \"evidence_span\": \"声音冰冷\"}\n"
    "对2b：她忽然换成一个苍老妇人的声音说话。\n"
    "输出：{\"kind\": \"VOICE_OVERRIDE\", \"operation\": \"SET\", \"style\": \"OLD_WOMAN_VOICE\", \"scope\": \"UNTIL_CLEAR\", \"evidence_span\": \"换成一个苍老妇人的声音\"}\n"
    "\n"
    "对3a：他恢复正常，众人松了口气。\n"
    "输出：{\"kind\": \"NONE\", \"operation\": null, \"style\": null, \"scope\": null, \"evidence_span\": null}\n"
    "对3b：他恢复了原本的声音。\n"
    "输出：{\"kind\": \"VOICE_OVERRIDE\", \"operation\": \"CLEAR\", \"style\": null, \"scope\": \"UNTIL_CLEAR\", \"evidence_span\": \"恢复了原本的声音\"}\n"
    "\n"
    "对4a：他模仿着电影里的动作。\n"
    "输出：{\"kind\": \"NONE\", \"operation\": null, \"style\": null, \"scope\": null, \"evidence_span\": null}\n"
    "对4b：他模仿着父亲的声音。\n"
    "输出：{\"kind\": \"VOICE_OVERRIDE\", \"operation\": \"SET\", \"style\": \"IMITATION\", \"scope\": \"UNTIL_CLEAR\", \"evidence_span\": \"模仿着父亲的声音\"}\n"
    "\n"
    "对5a：他的声音一直很沙哑，这是他的老毛病。\n"
    "输出：{\"kind\": \"NONE\", \"operation\": null, \"style\": null, \"scope\": null, \"evidence_span\": null}\n"
    "对5b：他因为受伤，声音变得沙哑。\n"
    "输出：{\"kind\": \"PERFORMANCE\", \"operation\": null, \"style\": \"HOARSE\", \"scope\": \"UTTERANCE\", \"evidence_span\": \"声音变得沙哑\"}\n"
    "\n"
    "文本：\n{text}"
)

PROMPT_V32 = (
    "你是 ReaderDirector。判断下面文本中的\u201c声音控制事件\u201d（对 TTS 渲染有意义的信息）。\n"
    "\n"
    "四类事件：\n"
    "1. VOICE_OVERRIDE_SET：角色的\u201c有效声线身份/音色\u201d被临时替换（模仿他人声音、换成一个苍老男人的嗓音、捏着嗓子装作女人说话、用假声冒充别人）。声线身份改变，可能持续影响后续多个句子。\n"
    "2. VOICE_OVERRIDE_CLEAR：明确解除此前的声线替换（恢复成自己本来的声音、不再捏着嗓子、那种苍老嗓音消失了）。\n"
    "3. PERFORMANCE：仍是这个人的声音（VoiceProfile 不变），只是\u201c当前怎么说\u201d——音量（压低声音/低声/大喊/轻声）、韵律（颤抖、拉长语调）、情绪语气（冰冷、愤怒、诱惑、悲伤）、职业惯例（太监宣旨时吊着嗓子、唱戏的假嗓）。只影响当前/有限渲染单元。\n"
    "4. NONE：与声音控制无关。\n"
    "\n"
    "【第一判定纪律：证据作用对象绑定（最重要）】\n"
    "- OVERRIDE_SET / OVERRIDE_CLEAR 必须存在明确的\u201c声音对象\u201d证据。\n"
    "- \u201c伪装、变回、恢复、模仿、切换、改变\u201d等动作词本身不足以构成 Override；动作必须明确作用于：声音、嗓音、声线、音色、口音/腔调、说话声音或等价声学身份。\n"
    "先判断动作改变的对象：\n"
    "- VOICE_IDENTITY（声音/嗓音/声线/音色/口音/腔调）→ 才允许 OVERRIDE_SET / OVERRIDE_CLEAR。注意：模仿/假装某人的口音或腔调也属于声学身份变化，是 OVERRIDE_SET。\n"
    "- VOICE_PERFORMANCE（当前怎么说：音量/情绪/韵律/职业惯例发声）→ PERFORMANCE。\n"
    "- APPEARANCE（面貌/模样/长相）→ NONE。\n"
    "- IDENTITY（身份/角色）→ NONE。\n"
    "- BODY/OTHER（身体/状态/其他，如\u201c恢复正常\u201d\u201c恢复秩序\u201d）→ NONE。\n"
    "\n"
    "【第二判定纪律：evidence 必须逐字连续 + PERFORMANCE 也必须有 evidence】\n"
    "- evidence_span 必须是输入文本中的逐字连续原文片段；不得改写、补词、概括或生成同义表达。\n"
    "- **PERFORMANCE 同样必须输出 evidence_span（不能为 null）**；只有 NONE 时 evidence_span 才为 null。\n"
    "\n"
    "【内部判定顺序（只用于推理，不输出）】\n"
    "Step 1 定位证据短语。\n"
    "Step 2 判断证据改变什么属性：voice/acoustic identity / momentary vocal performance / appearance/body/identity/other。\n"
    "Step 3 只有 voice/acoustic identity 才可选 OVERRIDE_SET 或 OVERRIDE_CLEAR。\n"
    "Step 4 只改变当前说话方式（含职业惯例发声）→ PERFORMANCE。\n"
    "Step 5 其他 → NONE。\n"
    "\n"
    "输出 JSON：{\"kind\": \"VOICE_OVERRIDE\" | \"PERFORMANCE\" | \"NONE\", \"operation\": \"SET\" | \"CLEAR\"（仅 OVERRIDE，其余 null）, \"style\": \"风格描述或 null\", \"scope\": \"UTTERANCE\" | \"UNTIL_CLEAR\" | \"UNKNOWN\"（NONE 为 null）, \"evidence_span\": \"原文逐字连续证据；PERFORMANCE 必须有值；仅 NONE 为 null\"}\n"
    "\n"
    "对象绑定示例：\n"
    "- 伪装成石志康 → 身份对象 → NONE\n"
    "- 伪装成石志康的模样 → 外貌对象 → NONE\n"
    "- 恢复本来的面目 / 恢复本来面貌 → 外貌对象 → NONE\n"
    "- 伪装成石志康的声音 / 模仿石志康的嗓音 → 声音对象 → OVERRIDE_SET\n"
    "- 假装成福建人的口音 → 口音/腔调 = 声学身份 → OVERRIDE_SET\n"
    "- 恢复本来的声音 / 嗓音恢复成自己原来的样子 → 声音对象 → OVERRIDE_CLEAR\n"
    "\n"
    "成对示例：\n"
    "对1a：他压低声音说道：\u201c走。\u201d → {kind: PERFORMANCE, operation: null, style: LOW_VOICE, scope: UTTERANCE, evidence_span: 压低声音}\n"
    "对1b：他压低嗓音，故意模仿张三的声音说道 → {kind: VOICE_OVERRIDE, operation: SET, style: IMITATION, scope: UNTIL_CLEAR, evidence_span: 模仿张三的声音}\n"
    "对2a：\u201c天子出行！\u201d冯保一甩拂尘，吊着嗓子高声喊道（太监宣旨的职业惯例，不是身份替换） → {kind: PERFORMANCE, operation: null, style: RAISED_VOICE, scope: UTTERANCE, evidence_span: 吊着嗓子高声喊道}\n"
    "对2b：她忽然换成一个苍老妇人的声音说话 → {kind: VOICE_OVERRIDE, operation: SET, style: OLD_WOMAN_VOICE, scope: UNTIL_CLEAR, evidence_span: 换成一个苍老妇人的声音}\n"
    "对3a：他恢复正常，众人松了口气（未指明声音） → {kind: NONE, operation: null, style: null, scope: null, evidence_span: null}\n"
    "对3b：他却在一瞬间恢复正常，低声说道……（恢复正常未绑定声音词，后文是压低音量） → {kind: PERFORMANCE, operation: null, style: LOW_VOICE, scope: UTTERANCE, evidence_span: 低声说道}\n"
    "对3c：他恢复了原本的声音 → {kind: VOICE_OVERRIDE, operation: CLEAR, style: null, scope: UNTIL_CLEAR, evidence_span: 恢复了原本的声音}\n"
    "对4a：他模仿着电影里的动作 → NONE\n"
    "对4b：他模仿着父亲的声音 → {kind: VOICE_OVERRIDE, operation: SET, style: IMITATION, scope: UNTIL_CLEAR, evidence_span: 模仿着父亲的声音}\n"
    "对5a：他的声音一直很沙哑，这是他的老毛病（固有音色） → NONE\n"
    "对5b：他因为受伤，声音变得沙哑 → {kind: PERFORMANCE, operation: null, style: HOARSE, scope: UTTERANCE, evidence_span: 声音变得沙哑}\n"
    "\n"
    "文本：\n{text}"
)

PROMPT_V33 = (
    "你是 ReaderDirector。判断下面文本中的\u201c声音控制事件\u201d（对 TTS 渲染有意义的信息）。\n"
    "\n"
    "四类事件：\n"
    "1. VOICE_OVERRIDE_SET：角色的\u201c有效声线身份/音色\u201d被临时替换（模仿他人声音、换成一个苍老男人的嗓音、捏着嗓子装作女人说话、用假声冒充别人、模仿某人的语气/口音/腔调）。声线身份改变，可能持续影响后续多个句子。\n"
    "2. VOICE_OVERRIDE_CLEAR：明确解除此前的声线替换（恢复成自己本来的声音、不再捏着嗓子、那种苍老嗓音消失了）。\n"
    "3. PERFORMANCE：仍是这个人的声音（VoiceProfile 不变），只是\u201c当前怎么说\u201d——音量（压低声音/低声/大喊/轻声）、韵律（颤抖、拉长语调）、情绪语气（冰冷、愤怒、诱惑、悲伤）、职业惯例（太监宣旨吊嗓子、唱戏假嗓）。只影响当前/有限渲染单元。\n"
    "4. NONE：与声音控制无关。\n"
    "\n"
    "【第一判定纪律：证据作用对象绑定】\n"
    "- OVERRIDE_SET / OVERRIDE_CLEAR 必须存在明确的\u201c声音对象\u201d证据。\n"
    "- 动作（伪装/变回/恢复/模仿/切换/改变）必须明确作用于：声音、嗓音、声线、音色、口音/腔调、语气（模仿某人的说话方式）、说话声音或等价声学身份。\n"
    "- **一旦判定为模仿/切换某人的声音/嗓音/语气/口音（VOICE_IDENTITY 对象），后续附加的修饰词（低沉、冰冷、嘶哑、轻柔等）不影响判定——仍是 OVERRIDE_SET**；修饰词只进 style 描述。\n"
    "先判断动作改变的对象：\n"
    "- VOICE_IDENTITY（声音/嗓音/声线/音色/口音/腔调/模仿某人语气）→ 才允许 OVERRIDE_SET / OVERRIDE_CLEAR。\n"
    "- VOICE_PERFORMANCE（当前怎么说：音量/情绪/韵律/职业惯例发声）→ PERFORMANCE。\n"
    "- APPEARANCE（面貌/模样/长相）→ NONE。\n"
    "- IDENTITY（身份/角色）→ NONE。\n"
    "- BODY/OTHER（身体/状态/其他）→ NONE。\n"
    "\n"
    "【第二判定纪律：evidence 逐字连续 + PERFORMANCE 必须有 evidence】\n"
    "- evidence_span 必须是输入文本中的逐字连续原文片段；不得改写、补词、概括或生成同义表达。\n"
    "- **PERFORMANCE 同样必须输出 evidence_span（不能为 null）**；只有 NONE 时 evidence_span 才为 null。\n"
    "\n"
    "【内部判定顺序】\n"
    "Step 1 定位证据短语。\n"
    "Step 2 判断证据改变什么属性：voice/acoustic identity / momentary vocal performance / appearance/body/identity/other。\n"
    "Step 3 只有 voice/acoustic identity 才可选 OVERRIDE_SET 或 OVERRIDE_CLEAR。\n"
    "Step 4 只改变当前说话方式（含职业惯例发声）→ PERFORMANCE。\n"
    "Step 5 其他 → NONE。\n"
    "\n"
    "输出 JSON：{\"kind\": \"VOICE_OVERRIDE\" | \"PERFORMANCE\" | \"NONE\", \"operation\": \"SET\" | \"CLEAR\"（仅 OVERRIDE，其余 null）, \"style\": \"风格描述或 null\", \"scope\": \"UTTERANCE\" | \"UNTIL_CLEAR\" | \"UNKNOWN\"（NONE 为 null）, \"evidence_span\": \"原文逐字连续证据；PERFORMANCE 必须有值；仅 NONE 为 null\"}\n"
    "\n"
    "对象绑定示例：\n"
    "- 伪装成石志康 → 身份对象 → NONE\n"
    "- 伪装成石志康的模样 / 恢复本来面貌 → 外貌对象 → NONE\n"
    "- 伪装成石志康的声音 / 模仿石志康的嗓音 / 假装成福建人的口音 → 声音对象 → OVERRIDE_SET\n"
    "- 恢复本来的声音 / 嗓音恢复成自己原来的样子 → 声音对象 → OVERRIDE_CLEAR\n"
    "\n"
    "成对示例：\n"
    "对1a：他压低声音说道：\u201c走。\u201d → {kind: PERFORMANCE, style: LOW_VOICE, scope: UTTERANCE, evidence_span: 压低声音}\n"
    "对1b：他压低嗓音，故意模仿张三的声音说道 → {kind: VOICE_OVERRIDE, operation: SET, style: IMITATION, scope: UNTIL_CLEAR, evidence_span: 模仿张三的声音}\n"
    "对1c：\u201c邓布利多。\u201d他说，那语气模仿得惟妙惟肖，低沉，冰冷，带着蛇一样的嘶嘶声 → {kind: VOICE_OVERRIDE, operation: SET, style: IMITATION_HISS, scope: UNTIL_CLEAR, evidence_span: 那语气模仿得惟妙惟肖}\n"
    "对2a：\u201c天子出行！\u201d冯保一甩拂尘，吊着嗓子高声喊道（太监宣旨职业惯例） → {kind: PERFORMANCE, style: RAISED_VOICE, scope: UTTERANCE, evidence_span: 吊着嗓子高声喊道}\n"
    "对2b：她忽然换成一个苍老妇人的声音说话 → {kind: VOICE_OVERRIDE, operation: SET, style: OLD_WOMAN_VOICE, scope: UNTIL_CLEAR, evidence_span: 换成一个苍老妇人的声音}\n"
    "对3a：他恢复正常，众人松了口气（未指明声音） → NONE\n"
    "对3b：他却在一瞬间恢复正常，低声说道……（恢复正常未绑定声音词） → {kind: PERFORMANCE, style: LOW_VOICE, scope: UTTERANCE, evidence_span: 低声说道}\n"
    "对3c：他恢复了原本的声音 → {kind: VOICE_OVERRIDE, operation: CLEAR, style: null, scope: UNTIL_CLEAR, evidence_span: 恢复了原本的声音}\n"
    "对4a：他模仿着电影里的动作 → NONE\n"
    "对4b：他模仿着父亲的声音 → {kind: VOICE_OVERRIDE, operation: SET, style: IMITATION, scope: UNTIL_CLEAR, evidence_span: 模仿着父亲的声音}\n"
    "对5a：他的声音一直很沙哑，这是他的老毛病（固有音色） → NONE\n"
    "对5b：他因为受伤，声音变得沙哑 → {kind: PERFORMANCE, style: HOARSE, scope: UTTERANCE, evidence_span: 声音变得沙哑}\n"
    "\n"
    "文本：\n{text}"
)

PROMPTS = {"v1": PROMPT_V1, "v2": PROMPT_V2, "v3": PROMPT_V3, "v31": PROMPT_V31, "v32": PROMPT_V32, "v33": PROMPT_V33}

def parse_event(raw):
    raw = raw.strip()
    try:
        obj = json.loads(raw)
        return obj
    except Exception:
        m = re.search(r"\{.*\}", raw, re.S)
        if m:
            try:
                return json.loads(m.group(0))
            except Exception:
                return None
    return None

def host_validate(rec, obj, prompt_version="v1"):
    """host 权威校验。v1/v2 用 event 字段；v3/v31 用 kind/operation 字段（ADR-042）。"""
    text = rec["text"]
    if prompt_version in ("v1", "v2",):
        ev = (obj or {}).get("event")
        if ev not in ("NO_EVENT", "TEMP_SET", "TEMP_CLEAR"):
            return None, "invalid event"
        if ev == "NO_EVENT":
            return {"event": "NO_EVENT", "style": None, "scope": None, "evidence_span": None}, None
        span = (obj or {}).get("evidence_span")
        if not span or not isinstance(span, str) or span not in text:
            return None, "evidence_span not in text"
        scope = (obj or {}).get("scope")
        if scope not in ("UTTERANCE", "UNTIL_CLEAR", "UNKNOWN"):
            return None, "invalid scope"
        return {"event": ev, "style": (obj or {}).get("style"), "scope": scope, "evidence_span": span}, None
    # v3：四类 ontology
    kind = (obj or {}).get("kind")
    if kind not in ("VOICE_OVERRIDE", "PERFORMANCE", "NONE"):
        return None, "invalid kind"
    if kind == "NONE":
        return {"kind": "NONE", "operation": None, "style": None, "scope": None, "evidence_span": None}, None
    op = (obj or {}).get("operation")
    if kind == "VOICE_OVERRIDE" and op not in ("SET", "CLEAR"):
        return None, "invalid operation"
    if kind == "PERFORMANCE" and op is not None:
        op = None  # PERFORMANCE 不需要 operation
    span = (obj or {}).get("evidence_span")
    if not span or not isinstance(span, str) or span not in text:
        return None, "evidence_span not in text"
    scope = (obj or {}).get("scope")
    if scope not in ("UTTERANCE", "UNTIL_CLEAR", "UNKNOWN"):
        return None, "invalid scope"
    return {"kind": kind, "operation": op, "style": (obj or {}).get("style"),
            "scope": scope, "evidence_span": span}, None

def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--prompt_version", choices=["v1", "v2", "v3", "v31", "v32", "v33"], default="v1")
    ap.add_argument("--resume", action="store_true")
    args = ap.parse_args()
    PROMPT = PROMPTS[args.prompt_version]

    from transformers import AutoModelForCausalLM, AutoTokenizer
    tok = AutoTokenizer.from_pretrained(MODEL_DIR, trust_remote_code=True)
    model = AutoModelForCausalLM.from_pretrained(MODEL_DIR, trust_remote_code=True,
                                                 torch_dtype="auto", device_map="auto")
    print("4B loaded, prompt_version =", args.prompt_version, flush=True)

    cands = [json.loads(l) for l in open(os.path.join(OUT, "vs2_candidates.jsonl"), encoding="utf-8")]
    op = os.path.join(OUT, f"vs2_annotated_{args.prompt_version}.jsonl")
    done = set()
    if args.resume and os.path.exists(op):
        for l in open(op, encoding="utf-8"):
            try:
                done.add(json.loads(l)["idx"])
            except Exception:
                pass
        print(f"resume: {len(done)} done", flush=True)

    n = 0
    for i, c in enumerate(cands):
        if args.resume and i in done:
            continue
        raw = run(model, tok, PROMPT.replace("{text}", c["text"]), max_new_tokens=120)
        obj = parse_event(raw)
        valid, err = host_validate(c, obj, args.prompt_version)
        rec = {"idx": i, **c, "prompt_version": args.prompt_version,
               "teacher_raw": raw, "teacher_event": obj,
               "validated": valid, "error": err}
        with open(op, "a", encoding="utf-8") as f:
            f.write(json.dumps(rec, ensure_ascii=False) + chr(10))
        n += 1
        if n % 20 == 0:
            print(f"  {n} done ({err or 'ok'})", flush=True)
    print(f"done {n}, saved {op}", flush=True)

if __name__ == "__main__":
    main()