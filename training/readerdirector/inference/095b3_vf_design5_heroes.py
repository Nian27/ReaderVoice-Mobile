# -*- coding: utf-8 -*-
"""PC-001 v0.2: 三书主角自动建档（谭越/柯南/叶洋）——VoiceDesign 3 候选。"""
import json
import os
import time

import numpy as np
import torch
import soundfile as sf
from qwen_tts import Qwen3TTSModel

FACTORY = r"E:\AndroidStudioProjects\ReaderVoiceMobile\training\readerdirector\runs\voice_state\voice_factory"

HEROES = {
    "hero_tanyue": {
        "name": "谭越", "book": "urban",
        "ref_text": "那好，我在家里等你。前世的那些事，就让它过去吧。",
        "instructs": [
            "a calm steady male voice around thirty, urban professional, slightly low and composed",
            "a composed adult male voice, measured and warm, like a city man who has seen life",
            "a low steady male voice in his thirties, restrained and thoughtful, clear diction",
        ],
    },
    "hero_conan": {
        "name": "柯南", "book": "fantasy",
        "ref_text": "真相永远只有一个。这件事，果然没有这么简单。",
        "instructs": [
            "a bright clear young boy's voice around ten, quick and intelligent, energetic but not shrill",
            "a clever young boy's voice, crisp and confident, with youthful sharpness",
            "a young boy's voice with a detective's calm, clear and agile, not childish",
        ],
    },
    "hero_yeyang": {
        "name": "叶洋", "book": "xianxia",
        "ref_text": "修行之路，宁慢勿快。这飞天门，终究要靠实力说话。",
        "instructs": [
            "a determined young adult male voice, steady and earnest, a disciple cultivating immortality",
            "a firm young male voice, unhurried and resolute, with quiet confidence",
            "a young cultivator's voice, calm and grounded, earnest and disciplined",
        ],
    },
}


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
    for rid, spec in HEROES.items():
        outdir = os.path.join(FACTORY, rid)
        os.makedirs(outdir, exist_ok=True)
        meta = {"character": spec["name"], "book": spec["book"], "ref_text": spec["ref_text"],
                "instructs": spec["instructs"], "generated_at": time.strftime("%Y-%m-%dT%H:%M:%S")}
        for i, inst in enumerate(spec["instructs"]):
            wavs, sr = model.generate_voice_design(text=spec["ref_text"], language="Auto", instruct=inst)
            wav = wavs[0]
            path = os.path.join(outdir, "candidate_%02d.wav" % (i + 1))
            sf.write(path, wav, sr)
            dur = len(wav) / sr
            peak = float(np.max(np.abs(wav)))
            print("%s c%d: %.2fs peak=%.3f" % (rid, i + 1, dur, peak), flush=True)
            meta["candidate_%02d" % (i + 1)] = {"instruct": inst, "sr": sr, "dur_s": round(dur, 2), "peak": round(peak, 3)}
        with open(os.path.join(outdir, "meta.json"), "w", encoding="utf-8") as f:
            json.dump(meta, f, ensure_ascii=False, indent=1)
    print("DONE", flush=True)


if __name__ == "__main__":
    main()
