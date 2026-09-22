# -*- coding: utf-8 -*-
"""M0.6-P2: 药老 persona → VoiceDesign 3 候选参考 wav。

验证问题 P2：VoiceDesign 生成的 5s 参考 wav 是否满足 CosyVoice enrollment 输入要求
（3-15s 单人干净语音）+ persona 可辨识（后续由 CAM++/CAMPPlus 验证）。
"""
import argparse
import json
import os
import sys
import time

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
ROOT = os.path.abspath(os.path.join(os.path.dirname(__file__), "..", "..", ".."))
OUT = os.path.join(ROOT, "training", "readerdirector", "runs", "voice_state", "voice_factory", "yaolao")

PERSONA = {
    "character": "药老",
    "gender": "male", "age": "elder", "pitch": "low",
    "timbre": "rough", "texture": "hoarse", "style": "wise and humorous",
}

# 同一参考文本（约 5s），三个候选用不同 instruct 措辞生成同一 persona 的不同表达
REF_TEXT = "小娃娃，这世间的事，哪有你想的那么简单。"
INSTRUCTS = [
    "苍老男性的声音，低沉沙哑，带着岁月感，语调平稳从容，像一位见多识广的老者。",
    "老年男性，声音苍劲有力，略带沙哑，说话不急不缓，气息沉稳，透出长者的智慧。",
    "苍老低沉的声音，气息沉稳，带着一丝调侃的笑意，像一位喜欢打趣的世外高人。",
]


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--model", default="Qwen/Qwen3-TTS-12Hz-1.7B-VoiceDesign")
    ap.add_argument("--local", default=None, help="本地模型目录（优先）")
    args = ap.parse_args()

    import torch
    import soundfile as sf
    from qwen_tts import Qwen3TTSModel

    model_id = args.local or args.model
    attn = "flash_attention_2"
    try:
        import flash_attn  # noqa
    except Exception:
        attn = "sdpa"
        print("flash-attn 不可用，使用 sdpa", flush=True)
    print("loading", model_id, "attn=", attn, flush=True)
    model = Qwen3TTSModel.from_pretrained(
        model_id, device_map="cuda:0", dtype=torch.bfloat16,
        attn_implementation=attn,
    )
    print("loaded", flush=True)
    os.makedirs(OUT, exist_ok=True)
    meta = {"character": PERSONA, "ref_text": REF_TEXT, "instructs": INSTRUCTS, "generated_at": time.strftime("%Y-%m-%dT%H:%M:%S")}
    for i, inst in enumerate(INSTRUCTS):
        wavs, sr = model.generate_voice_design(
            text=REF_TEXT, language="Chinese", instruct=inst,
        )
        wav = wavs[0]
        path = os.path.join(OUT, "candidate_%02d.wav" % (i + 1))
        sf.write(path, wav, sr)
        dur = len(wav) / sr
        peak = float(wav.abs().max()) if hasattr(wav, "abs") else float(max(abs(wav)))
        print("candidate_%02d: sr=%d dur=%.2fs peak=%.3f -> %s" % (i + 1, sr, dur, peak, path), flush=True)
        meta["candidate_%02d" % (i + 1)] = {"instruct": inst, "sr": sr, "dur_s": round(dur, 2), "peak": round(peak, 3)}
    with open(os.path.join(OUT, "meta.json"), "w", encoding="utf-8") as f:
        json.dump(meta, f, ensure_ascii=False, indent=1)
    print("meta ->", os.path.join(OUT, "meta.json"), flush=True)
    print("DONE", flush=True)


if __name__ == "__main__":
    main()
