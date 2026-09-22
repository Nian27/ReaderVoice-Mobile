# -*- coding: utf-8 -*-
import sys, os
HERE = os.path.dirname(os.path.abspath(__file__))
sys.path.insert(0, HERE)
import _vf_shim  # noqa: F401
sys.path.insert(0, r"E:\AndroidStudioProjects\CosyVoice-main")
sys.path.insert(0, r"E:\AndroidStudioProjects\CosyVoice-main\third_party\Matcha-TTS")
import torch
import torch.serialization as _ts
_orig = torch.load
torch.load = lambda *a, **k: _orig(*a, **{**k, "weights_only": False})
import soundfile as sf
from cosyvoice.cli.cosyvoice import CosyVoice3

MODEL_DIR = r"E:\AndroidStudioProjects\cosyvoice3-distill-lab\Fun-CosyVoice3-0.5B-2512-RL"
SYS = "You are a helpful assistant.<|endofprompt|>"
PROMPT_TEXT = SYS + "小娃娃，这世间的事，哪有你想的那么简单。"
REF = r"E:\AndroidStudioProjects\ReaderVoiceMobile\training\readerdirector\runs\voice_state\voice_factory\yaolao\candidate_02.wav"
OUT_WAV = r"E:\AndroidStudioProjects\ReaderVoiceMobile\training\readerdirector\runs\voice_state\voice_factory\yaolao\smoke_01.wav"

print("loading...", flush=True)
cosy = CosyVoice3(MODEL_DIR, load_trt=False, load_vllm=False, fp16=False)
print("loaded ok, sr=", cosy.sample_rate, flush=True)
out = list(cosy.inference_zero_shot(
    tts_text="药老，您可算回来了。", prompt_text=PROMPT_TEXT, prompt_wav=REF))
wav = torch.cat([o["tts_speech"] for o in out], dim=1)[0].cpu().numpy()
sf.write(OUT_WAV, wav, cosy.sample_rate)
print("synth ok, dur=%.2fs peak=%.3f" % (len(wav) / cosy.sample_rate, float(abs(wav).max())), flush=True)
