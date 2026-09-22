# -*- coding: utf-8 -*-
"""VF-003-B++: Role B 补强候选（加长参考文本 + instruct 调整）。"""
import json
import os
import time

import numpy as np
import torch
import soundfile as sf
from qwen_tts import Qwen3TTSModel

OUT = r"E:\AndroidStudioProjects\ReaderVoiceMobile\training\readerdirector\runs\voice_state\voice_factory\role_b2"

# B2: 加长参考文本（5-8s / 目标 100-150 tokens；含开放元音/高频辅音/情绪起伏/连续韵律）
REF_TEXT = "今晚的风有些凉，但我相信自己一定能够找到答案。山间的溪水在轻声流淌，像你说话时的温柔。"

# B3: instruct 避免"少女/萝莉"式表达（防止过度抬高 F0），强调温暖/清晰/自然情绪
INSTRUCTS = [
    "warm young female voice, clear pronunciation, soft resonance, natural emotional variation, slightly bright but not childish",
    "a gentle and clear young woman's voice, warm tone, steady and calm, with a hint of tenderness in every phrase",
    "soft female voice around twenty, resonant and smooth, expressive but restrained, bright without being shrill",
]


def main():
    attn = "flash_attention_2"
    try:
        import flash_attn  # noqa
    except Exception:
        attn = "sdpa"
    model = Qwen3TTSModel.from_pretrained(
        r"E:\AndroidStudioProjects\ReaderVoiceMobile\training\models\qwen3-tts\Qwen3-TTS-12Hz-1.7B-VoiceDesign",
        device_map="cuda:0", dtype=torch.bfloat16, attn_implementation=attn)
    print("model loaded", flush=True)
    os.makedirs(OUT, exist_ok=True)
    meta = {"character": "苏晚晴", "ref_text": REF_TEXT, "instructs": INSTRUCTS,
            "generated_at": time.strftime("%Y-%m-%dT%H:%M:%S")}
    for i, inst in enumerate(INSTRUCTS):
        wavs, sr = model.generate_voice_design(text=REF_TEXT, language="Auto", instruct=inst)
        wav = wavs[0]
        path = os.path.join(OUT, "candidate_%02d.wav" % (i + 1))
        sf.write(path, wav, sr)
        dur = len(wav) / sr
        peak = float(np.max(np.abs(wav)))
        print("candidate_%02d: sr=%d dur=%.2fs peak=%.3f" % (i + 1, sr, dur, peak), flush=True)
        meta["candidate_%02d" % (i + 1)] = {"instruct": inst, "sr": sr, "dur_s": round(dur, 2), "peak": round(peak, 3)}
    with open(os.path.join(OUT, "meta.json"), "w", encoding="utf-8") as f:
        json.dump(meta, f, ensure_ascii=False, indent=1)
    print("DONE", flush=True)


if __name__ == "__main__":
    main()
