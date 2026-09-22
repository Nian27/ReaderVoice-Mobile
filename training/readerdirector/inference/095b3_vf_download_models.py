# -*- coding: utf-8 -*-
"""M0.6-P1/P2: 下载 Qwen3-TTS VoiceDesign 模型（modelscope）。"""
import os
os.environ.setdefault("MODELSCOPE_CACHE", r"E:\AndroidStudioProjects\ReaderVoiceMobile\training\envs\qwen3tts\.mscache")
import subprocess, sys
from pathlib import Path
PY = sys.executable
VENV_BIN = str(Path(sys.executable).parent)
MS_CLI = os.path.join(VENV_BIN, "modelscope.exe")
MODELS = [
    ("Qwen/Qwen3-TTS-Tokenizer-12Hz", r"E:\AndroidStudioProjects\ReaderVoiceMobile\training\models\qwen3-tts\Qwen3-TTS-Tokenizer-12Hz"),
    ("Qwen/Qwen3-TTS-12Hz-1.7B-VoiceDesign", r"E:\AndroidStudioProjects\ReaderVoiceMobile\training\models\qwen3-tts\Qwen3-TTS-12Hz-1.7B-VoiceDesign"),
]
for mid, local in MODELS:
    if os.path.exists(os.path.join(local, "config.json")) or os.path.exists(local):
        print("skip (exists):", local)
        continue
    print("downloading:", mid, flush=True)
    r = subprocess.run([MS_CLI, "download", "--model", mid, "--local_dir", local], capture_output=True, text=True)
    print(r.stdout[-800:])
    if r.returncode != 0:
        print("ERR:", r.stderr[-800:])
        sys.exit(1)
print("DONE")
