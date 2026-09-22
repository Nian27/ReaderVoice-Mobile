# -*- coding: utf-8 -*-
"""旁白档案候选生成：云健感（沉稳清晰中年男声，播音/有声书旁白风格）。"""
import json
import os
import time

import numpy as np
import torch
import soundfile as sf
from qwen_tts import Qwen3TTSModel

OUT = r"E:\AndroidStudioProjects\ReaderVoiceMobile\training\readerdirector\runs\voice_state\voice_factory\narrator"
REF_TEXT = "夜色渐深，小镇的灯火一盏一盏地亮了起来。他站在窗前，望着远处连绵的山影，久久没有说话。"
INSTRUCTS = [
    "a steady, clear middle-aged male voice, professional broadcaster tone, calm and measured, suitable for audiobook narration",
    "a deep yet warm adult male narrator voice, unhurried and composed, crisp enunciation, classic audiobook style",
    "a mature male voice with balanced resonance, steady rhythm, clear diction, gentle but authoritative, ideal for long narration",
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
    meta = {"character": "旁白", "type": "narrator", "ref_text": REF_TEXT, "instructs": INSTRUCTS,
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
