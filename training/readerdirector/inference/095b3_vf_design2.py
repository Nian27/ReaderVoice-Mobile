# -*- coding: utf-8 -*-
"""VF-003: 三角色 VoiceDesign 候选生成（Role B 年轻女性 / Role C 西幻 elf）。"""
import argparse
import json
import os
import time

OUT_ROOT = r"E:\AndroidStudioProjects\ReaderVoiceMobile\training\readerdirector\runs\voice_state\voice_factory"

ROLES = {
    "role_b": {
        "character": "苏晚晴", "age": "young", "gender": "female",
        "ref_text": "今天天气真好，我们一起去河边走走吧。",
        "instructs": [
            "年轻女性的声音，温柔清澈，语速轻快，像二十岁的邻家女孩。",
            "清亮甜美的女声，声音柔和不尖细，带着温暖的笑意。",
            "二十岁年轻女子的声音，明亮通透，语气温和亲切。",
        ],
    },
    "role_c": {
        "character": "Aurelius·Valenor", "age": "ancient", "gender": "male",
        "ref_text": "The forest remembers what the kingdoms forget, young one.",
        "instructs": [
            "An ancient elf scholar's voice, deep and resonant, five centuries old, calm and wise.",
            "A timeless elven voice, mellow and dignified, with a faint trace of melancholy wisdom.",
            "The voice of an ageless elven lorekeeper, smooth and grave, patient and knowing.",
        ],
    },
}


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--roles", default="role_b,role_c")
    ap.add_argument("--local", required=True, help="VoiceDesign 模型目录")
    args = ap.parse_args()

    import numpy as np
    import torch
    import soundfile as sf
    from qwen_tts import Qwen3TTSModel

    attn = "flash_attention_2"
    try:
        import flash_attn  # noqa
    except Exception:
        attn = "sdpa"
    model = Qwen3TTSModel.from_pretrained(args.local, device_map="cuda:0", dtype=torch.bfloat16, attn_implementation=attn)
    print("model loaded", flush=True)

    for rid in [r.strip() for r in args.roles.split(",")]:
        spec = ROLES[rid]
        outdir = os.path.join(OUT_ROOT, rid)
        os.makedirs(outdir, exist_ok=True)
        meta = {"character": spec["character"], "ref_text": spec["ref_text"], "instructs": spec["instructs"],
                "generated_at": time.strftime("%Y-%m-%dT%H:%M:%S")}
        for i, inst in enumerate(spec["instructs"]):
            wavs, sr = model.generate_voice_design(text=spec["ref_text"], language="Auto", instruct=inst)
            wav = wavs[0]
            path = os.path.join(outdir, "candidate_%02d.wav" % (i + 1))
            sf.write(path, wav, sr)
            dur = len(wav) / sr
            peak = float(np.max(np.abs(wav)))
            print("%s candidate_%02d: sr=%d dur=%.2fs peak=%.3f" % (rid, i + 1, sr, dur, peak), flush=True)
            meta["candidate_%02d" % (i + 1)] = {"instruct": inst, "sr": sr, "dur_s": round(dur, 2), "peak": round(peak, 3)}
        with open(os.path.join(outdir, "meta.json"), "w", encoding="utf-8") as f:
            json.dump(meta, f, ensure_ascii=False, indent=1)
    print("DONE", flush=True)


if __name__ == "__main__":
    main()
