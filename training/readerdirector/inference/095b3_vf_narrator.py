# -*- coding: utf-8 -*-
"""NarratorProvider v0.1 冒烟：Edge TTS 默认旁白（NarratorProfile: warm_clear female adult）。"""
import argparse
import hashlib
import os
import time

import edge_tts

NARRATOR_PROFILE = {
    "voice_id": "narrator_default_001", "type": "narrator", "engine": "edge_tts",
    "style": {"gender": "female", "age": "adult", "tone": "warm_clear", "speed": 1.0},
    "provider": {"default": True, "fallback": ["cosyvoice_narrator", "system"]},
}
# Edge 中文女声 warm_clear 候选
EDGE_VOICES = ["zh-CN-XiaoxiaoNeural", "zh-CN-XiaoyiNeural"]


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--text", default="夜色渐深，小镇的灯火一盏一盏地亮了起来。他站在窗前，望着远处连绵的山影，久久没有说话。")
    ap.add_argument("--voice", default="zh-CN-XiaoxiaoNeural")
    ap.add_argument("--speed", default="+0%")
    ap.add_argument("--out", default=None)
    args = ap.parse_args()

    out_root = r"E:\AndroidStudioProjects\ReaderVoiceMobile\training\readerdirector\runs\voice_state\voice_factory\narrator"
    os.makedirs(out_root, exist_ok=True)
    key = hashlib.sha256((args.voice + "|" + args.speed + "|" + args.text).encode()).hexdigest()[:16]
    out = args.out or os.path.join(out_root, "narrator_smoke_%s.mp3" % key)
    t0 = time.time()
    communicate = edge_tts.Communicate(args.text, args.voice, rate=args.speed)
    communicate.save_sync(out)
    dt = time.time() - t0
    print("narrator ok: %s (%.1fs) voice=%s speed=%s" % (out, dt, args.voice, args.speed), flush=True)
    print("cache key:", key, flush=True)


if __name__ == "__main__":
    main()
