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
from cosyvoice.cli.cosyvoice import CosyVoice3

MODEL_DIR = r"E:\AndroidStudioProjects\cosyvoice3-distill-lab\Fun-CosyVoice3-0.5B-2512-RL"
REF = r"E:\AndroidStudioProjects\ReaderVoiceMobile\training\readerdirector\runs\voice_state\voice_factory\yaolao\candidate_02.wav"
print("loading...", flush=True)
cosy = CosyVoice3(MODEL_DIR, load_trt=False, load_vllm=False, fp16=False)
mi = cosy.frontend.frontend_zero_shot(
    "药老，您可算回来了。", "You are a helpful assistant.<|endofprompt|>小娃娃，这世间的事，哪有你想的那么简单。", REF, cosy.sample_rate, "")
print("inputs ready", flush=True)
llm = cosy.model.llm
try:
    gen = llm.inference(
        text=mi["text"].to("cuda"),
        text_len=mi["text_len"].to("cuda"),
        prompt_text=mi["prompt_text"].to("cuda"),
        prompt_text_len=mi["prompt_text_len"].to("cuda"),
        prompt_speech_token=mi["llm_prompt_speech_token"].to("cuda"),
        prompt_speech_token_len=mi["llm_prompt_speech_token_len"].to("cuda"),
        embedding=mi["llm_embedding"].to("cuda"))
    toks = []
    for i, t in enumerate(gen):
        toks.append(int(t))
        if i >= 200:
            break
    print("LLM tokens generated:", len(toks), flush=True)
    print("first 30:", toks[:30], flush=True)
    print("eos_token id:", llm.eos_token, flush=True)
except Exception as e:
    import traceback
    traceback.print_exc()
    print("LLM FAIL:", type(e).__name__, str(e)[:400], flush=True)
