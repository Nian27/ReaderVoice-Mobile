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
import uuid as _uuid

MODEL_DIR = r"E:\AndroidStudioProjects\cosyvoice3-distill-lab\Fun-CosyVoice3-0.5B-2512-RL"
SYS = "You are a helpful assistant.<|endofprompt|>"
PROMPT_TEXT = SYS + "小娃娃，这世间的事，哪有你想的那么简单。"
REF = r"E:\AndroidStudioProjects\ReaderVoiceMobile\training\readerdirector\runs\voice_state\voice_factory\yaolao\candidate_03.wav"
print("loading...", flush=True)
cosy = CosyVoice3(MODEL_DIR, load_trt=False, load_vllm=False, fp16=False)
mi = cosy.frontend.frontend_zero_shot("药老，您可算回来了。", PROMPT_TEXT, REF, cosy.sample_rate, "")
dev = cosy.model.device
uuid = str(_uuid.uuid1())
for _k in ("tts_speech_token_dict", "llm_end_dict", "hift_cache_dict", "mel_overlap_dict", "flow_cache_dict"):
    if not hasattr(cosy.model, _k):
        setattr(cosy.model, _k, {})
with cosy.model.lock:
    cosy.model.tts_speech_token_dict[uuid] = []
    cosy.model.hift_cache_dict[uuid] = None
    cosy.model.mel_overlap_dict[uuid] = torch.zeros(1, 80, 0, device=dev)
    cosy.model.flow_cache_dict[uuid] = torch.zeros(1, 80, 0, 2, device=dev)
toks = []
for t in cosy.model.llm.inference(
        text=mi["text"].to(dev), text_len=mi["text_len"].to(dev),
        prompt_text=mi["prompt_text"].to(dev), prompt_text_len=mi["prompt_text_len"].to(dev),
        prompt_speech_token=mi["llm_prompt_speech_token"].to(dev),
        prompt_speech_token_len=mi["llm_prompt_speech_token_len"].to(dev),
        embedding=mi["llm_embedding"].to(dev)):
    toks.append(int(t))
print("llm tokens:", len(toks), flush=True)
tok = torch.tensor([toks], dtype=torch.int32).to(dev)
speech = cosy.model.token2wav(
    token=tok, prompt_token=mi["flow_prompt_speech_token"].to(dev),
    prompt_feat=mi["prompt_speech_feat"].to(dev), embedding=mi["flow_embedding"].to(dev),
    token_offset=0, uuid=uuid, finalize=True)
print("speech shape:", tuple(speech.shape), speech.dtype, "finite:", bool(torch.isfinite(speech).all()), flush=True)
if speech.numel():
    w = speech[0].cpu().numpy()
    import soundfile as sf
    sf.write(r"E:\AndroidStudioProjects\ReaderVoiceMobile\training\readerdirector\runs\voice_state\voice_factory\yaolao\debug_03.wav", w, cosy.sample_rate)
    print("wav written, dur=%.2fs peak=%.3f" % (len(w)/cosy.sample_rate, float(abs(w).max())), flush=True)
