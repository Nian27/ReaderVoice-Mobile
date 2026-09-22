# -*- coding: utf-8 -*-
"""PC-001 v0.3-C2: 4 个失败段（谭越+引号内短对白）→ 并入前段文本重试。"""
import json
import os
import sys
import time
import uuid as _uuid

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

MODEL_DIR = r"E:\AndroidStudioProjects\cosyvoice3-distill-lab\Fun-CosyVoice3-0.5B-2512-RL"
FACTORY = r"E:\AndroidStudioProjects\ReaderVoiceMobile\training\readerdirector\runs\voice_state\voice_factory"
PC001 = os.path.join(FACTORY, "pc001")
SYS = "You are a helpful assistant.<|endofprompt|>"
FIX = ["urban_p002", "urban_p008", "urban_p050", "urban_p064"]


def synth(cosy, text, prompt_text, wav_path):
    mi = cosy.frontend.frontend_zero_shot(text, prompt_text, wav_path, cosy.sample_rate, "")
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
    speech = cosy.model.token2wav(
        token=torch.tensor([toks], dtype=torch.int32).to(dev),
        prompt_token=mi["flow_prompt_speech_token"].to(dev),
        prompt_feat=mi["prompt_speech_feat"].to(dev),
        embedding=mi["flow_embedding"].to(dev),
        token_offset=0, uuid=uuid, finalize=True)
    return speech[0].cpu().numpy()


def main():
    from cosyvoice.cli.cosyvoice import CosyVoice3
    meta = json.load(open(os.path.join(FACTORY, "hero_tanyue", "meta.json"), encoding="utf-8"))
    ref_text = meta["ref_text"]
    cosy = CosyVoice3(MODEL_DIR, load_trt=False, load_vllm=False, fp16=False)
    print("loaded", flush=True)
    dirs = [json.loads(l) for l in open(os.path.join(PC001, "director_v2.jsonl"), encoding="utf-8")]
    by_id = {d["paragraph_id"]: d for d in dirs}
    for pid in FIX:
        d = by_id[pid]
        prev = dirs[dirs.index(d) - 1]
        tts_text = prev["text"] + d["text"]
        wav = synth(cosy, tts_text, SYS + ref_text, os.path.join(FACTORY, "hero_tanyue", "candidate_02.wav"))
        out = os.path.join(PC001, "audio", pid + ".wav")
        sf.write(out, wav, cosy.sample_rate)
        print("FIXED %s (merged with %s): %.1fs audio" % (pid, prev["paragraph_id"], len(wav) / cosy.sample_rate), flush=True)
    print("DONE", flush=True)


if __name__ == "__main__":
    main()
