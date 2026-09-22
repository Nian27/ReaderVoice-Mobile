# -*- coding: utf-8 -*-
"""TASK-096B — 24 VoicePersona Test Set（用户 §25）。

覆盖：男女 × 少年/青年/中年/老年 × 高/中/低 pitch × 明亮/低沉 等；
含 near-neighbor 对（中年低沉威严男 A/B）验证可分性。

字段（§23）：perceived_gender / voice_age / pitch_range / timbre / weight /
brightness / roughness / breathiness / authority / energy / speech_rate /
social_style / accent / special_traits + free_description
"""
import json
import os

OUT = os.path.join(os.path.dirname(os.path.abspath(__file__)), "..", "..", "..", "training", "readerdirector", "runs", "voice_state")

# 24 personas：先 16 基础网格 + 4 特殊 + 4 near-neighbor 对
PERSONAS = [
    # ---- 基础网格：性别 × 年龄 × pitch 主维 ----
    {"id": "P01", "perceived_gender": "male", "voice_age": "少年", "pitch_range": "高", "timbre": "清亮", "weight": "轻", "brightness": "明亮", "roughness": "光滑", "breathiness": "低", "authority": "低", "energy": "高", "speech_rate": "快", "social_style": "活泼", "accent": "普通话", "special_traits": [], "free_description": "阳光少年，语速快，声音清亮上扬"},
    {"id": "P02", "perceived_gender": "male", "voice_age": "青年", "pitch_range": "中", "timbre": "温润", "weight": "中", "brightness": "中等", "roughness": "光滑", "breathiness": "中", "authority": "中", "energy": "中", "speech_rate": "中", "social_style": "温和", "accent": "普通话", "special_traits": [], "free_description": "温润青年，书卷气，语调平稳"},
    {"id": "P03", "perceived_gender": "male", "voice_age": "中年", "pitch_range": "低", "timbre": "浑厚", "weight": "重", "brightness": "低沉", "roughness": "微粗", "breathiness": "低", "authority": "高", "energy": "中", "speech_rate": "慢", "social_style": "威严", "accent": "普通话", "special_traits": [], "free_description": "中年威严男，低沉浑厚，语速慢，有压迫感"},
    {"id": "P04", "perceived_gender": "male", "voice_age": "老年", "pitch_range": "低", "timbre": "苍老", "weight": "重", "brightness": "暗", "roughness": "粗糙", "breathiness": "高", "authority": "中", "energy": "低", "speech_rate": "慢", "social_style": "沉稳", "accent": "普通话", "special_traits": ["微颤"], "free_description": "苍老老人声，粗糙带颤，气息偏弱"},
    {"id": "P05", "perceived_gender": "female", "voice_age": "少女", "pitch_range": "高", "timbre": "清脆", "weight": "轻", "brightness": "明亮", "roughness": "光滑", "breathiness": "中", "authority": "低", "energy": "高", "speech_rate": "快", "social_style": "俏皮", "accent": "普通话", "special_traits": [], "free_description": "俏皮少女，清脆上扬，语速快"},
    {"id": "P06", "perceived_gender": "female", "voice_age": "青年", "pitch_range": "中高", "timbre": "甜美", "weight": "轻", "brightness": "明亮", "roughness": "光滑", "breathiness": "中", "authority": "中", "energy": "中", "speech_rate": "中", "social_style": "温柔", "accent": "普通话", "special_traits": [], "free_description": "温柔女青年，甜美圆润，语调柔和"},
    {"id": "P07", "perceived_gender": "female", "voice_age": "中年", "pitch_range": "中", "timbre": "醇厚", "weight": "中", "brightness": "中等", "roughness": "光滑", "breathiness": "低", "authority": "高", "energy": "中", "speech_rate": "中", "social_style": "干练", "accent": "普通话", "special_traits": [], "free_description": "干练中年女性，醇厚稳重，有主事感"},
    {"id": "P08", "perceived_gender": "female", "voice_age": "老年", "pitch_range": "中低", "timbre": "慈祥", "weight": "中", "brightness": "暗", "roughness": "微粗", "breathiness": "高", "authority": "低", "energy": "低", "speech_rate": "慢", "social_style": "慈爱", "accent": "普通话", "special_traits": [], "free_description": "慈祥老妇人，声音偏暗，语速慢"},
    # ---- pitch/timbre 变体 ----
    {"id": "P09", "perceived_gender": "male", "voice_age": "青年", "pitch_range": "高", "timbre": "尖细", "weight": "轻", "brightness": "明亮", "roughness": "光滑", "breathiness": "低", "authority": "低", "energy": "中", "speech_rate": "快", "social_style": "机灵", "accent": "普通话", "special_traits": [], "free_description": "机灵青年男，声音尖细偏亮"},
    {"id": "P10", "perceived_gender": "male", "voice_age": "青年", "pitch_range": "低", "timbre": "磁性", "weight": "重", "brightness": "暗", "roughness": "光滑", "breathiness": "中", "authority": "中", "energy": "低", "speech_rate": "慢", "social_style": "深沉", "accent": "普通话", "special_traits": [], "free_description": "磁性低音男，暗色调，深沉"},
    {"id": "P11", "perceived_gender": "female", "voice_age": "青年", "pitch_range": "低", "timbre": "沙哑", "weight": "中", "brightness": "暗", "roughness": "粗糙", "breathiness": "高", "authority": "中", "energy": "中", "speech_rate": "中", "social_style": "慵懒", "accent": "普通话", "special_traits": ["沙哑"], "free_description": "慵懒沙哑女声，烟嗓感"},
    {"id": "P12", "perceived_gender": "male", "voice_age": "中年", "pitch_range": "高", "timbre": "尖亮", "weight": "轻", "brightness": "明亮", "roughness": "光滑", "breathiness": "低", "authority": "中", "energy": "高", "speech_rate": "快", "social_style": "油滑", "accent": "北方口音", "special_traits": [], "free_description": "油滑中年男，尖亮语速快，带北方口音"},
    # ---- accent / 特殊 ----
    {"id": "P13", "perceived_gender": "male", "voice_age": "中年", "pitch_range": "中", "timbre": "厚实", "weight": "重", "brightness": "中等", "roughness": "光滑", "breathiness": "低", "authority": "中", "energy": "中", "speech_rate": "中", "social_style": "朴实", "accent": "四川口音", "special_traits": [], "free_description": "朴实中年男，四川口音，厚实"},
    {"id": "P14", "perceived_gender": "female", "voice_age": "少女", "pitch_range": "高", "timbre": "甜腻", "weight": "轻", "brightness": "明亮", "roughness": "光滑", "breathiness": "高", "authority": "低", "energy": "高", "speech_rate": "中", "social_style": "娇嗔", "accent": "普通话", "special_traits": ["嗲"], "free_description": "娇嗔少女，甜腻发嗲"},
    {"id": "P15", "perceived_gender": "male", "voice_age": "老年", "pitch_range": "高", "timbre": "尖哑", "weight": "轻", "brightness": "暗", "roughness": "粗糙", "breathiness": "高", "authority": "低", "energy": "低", "speech_rate": "慢", "social_style": "絮叨", "accent": "普通话", "special_traits": ["尖哑", "颤"], "free_description": "絮叨老头，尖哑带颤"},
    {"id": "P16", "perceived_gender": "female", "voice_age": "中年", "pitch_range": "低", "timbre": "暗哑", "weight": "重", "brightness": "暗", "roughness": "粗糙", "breathiness": "中", "authority": "高", "energy": "低", "speech_rate": "慢", "social_style": "强势", "accent": "普通话", "special_traits": [], "free_description": "强势中年女，暗哑低沉，压迫感"},
    # ---- near-neighbor 对（可分性验证）----
    {"id": "P17", "perceived_gender": "male", "voice_age": "中年", "pitch_range": "低", "timbre": "浑厚", "weight": "重", "brightness": "低沉", "roughness": "微粗", "breathiness": "低", "authority": "高", "energy": "中", "speech_rate": "慢", "social_style": "威严", "accent": "普通话", "special_traits": [], "free_description": "中年低沉威严男 A：沙场老将，粗粝豪迈"},
    {"id": "P18", "perceived_gender": "male", "voice_age": "中年", "pitch_range": "低", "timbre": "浑厚", "weight": "重", "brightness": "低沉", "roughness": "微粗", "breathiness": "低", "authority": "高", "energy": "中", "speech_rate": "慢", "social_style": "威严", "accent": "普通话", "special_traits": [], "free_description": "中年低沉威严男 B：朝堂重臣，端方持重"},
    {"id": "P19", "perceived_gender": "female", "voice_age": "青年", "pitch_range": "中高", "timbre": "甜美", "weight": "轻", "brightness": "明亮", "roughness": "光滑", "breathiness": "中", "authority": "低", "energy": "高", "speech_rate": "快", "social_style": "活泼", "accent": "普通话", "special_traits": [], "free_description": "活泼甜美少女 A：邻家小妹，元气"},
    {"id": "P20", "perceived_gender": "female", "voice_age": "青年", "pitch_range": "中高", "timbre": "甜美", "weight": "轻", "brightness": "明亮", "roughness": "光滑", "breathiness": "中", "authority": "低", "energy": "高", "speech_rate": "快", "social_style": "活泼", "accent": "普通话", "special_traits": [], "free_description": "活泼甜美少女 B：客栈小掌柜，利落精明"},
    # ---- 特殊声线 ----
    {"id": "P21", "perceived_gender": "male", "voice_age": "青年", "pitch_range": "中", "timbre": "温和", "weight": "中", "brightness": "中等", "roughness": "光滑", "breathiness": "低", "authority": "低", "energy": "低", "speech_rate": "慢", "social_style": "儒雅", "accent": "普通话", "special_traits": ["轻声"], "free_description": "儒雅青年，轻声细语，如沐春风"},
    {"id": "P22", "perceived_gender": "female", "voice_age": "老年", "pitch_range": "中", "timbre": "硬朗", "weight": "重", "brightness": "中等", "roughness": "微粗", "breathiness": "低", "authority": "高", "energy": "中", "speech_rate": "中", "social_style": "爽利", "accent": "普通话", "special_traits": [], "free_description": "硬朗老太太，爽利果断"},
    {"id": "P23", "perceived_gender": "male", "voice_age": "少年", "pitch_range": "中高", "timbre": "变声期", "weight": "轻", "brightness": "中等", "roughness": "微粗", "breathiness": "中", "authority": "低", "energy": "中", "speech_rate": "中", "social_style": "羞涩", "accent": "普通话", "special_traits": ["变声期"], "free_description": "变声期少年，声音偶尔破音，羞涩"},
    {"id": "P24", "perceived_gender": "female", "voice_age": "少女", "pitch_range": "中", "timbre": "清冷", "weight": "中", "brightness": "暗", "roughness": "光滑", "breathiness": "低", "authority": "中", "energy": "低", "speech_rate": "慢", "social_style": "清冷", "accent": "普通话", "special_traits": [], "free_description": "清冷少女，声线偏暗，疏离感"},
]

def main():
    os.makedirs(OUT, exist_ok=True)
    op = os.path.join(OUT, "voice_personas_v1.json")
    with open(op, "w", encoding="utf-8") as f:
        json.dump({"version": "v1", "count": len(PERSONAS), "personas": PERSONAS},
                  f, ensure_ascii=False, indent=1)
    print(f"saved: {op} ({len(PERSONAS)} personas)")
    # 分布检查
    from collections import Counter
    print("gender:", dict(Counter(p["perceived_gender"] for p in PERSONAS)))
    print("age:", dict(Counter(p["voice_age"] for p in PERSONAS)))

if __name__ == "__main__":
    main()