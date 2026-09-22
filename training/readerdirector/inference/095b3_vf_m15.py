# -*- coding: utf-8 -*-
"""M1.5 (VF-001 concat + VF-002 emotion): 身份保持严格验证。"""
import json
import os
import sys
import time

HERE = os.path.dirname(os.path.abspath(__file__))
sys.path.insert(0, HERE)
import _vf_shim  # noqa: F401
sys.path.insert(0, r"E:\AndroidStudioProjects\CosyVoice-main")
sys.path.insert(0, r"E:\AndroidStudioProjects\CosyVoice-main\third_party\Matcha-TTS")

import numpy as np
import torch
import torch.serialization as _ts
_orig = torch.load
torch.load = lambda *a, **k: _orig(*a, **{**k, "weights_only": False})
import soundfile as sf

MODEL_DIR = r"E:\AndroidStudioProjects\cosyvoice3-distill-lab\Fun-CosyVoice3-0.5B-2512-RL"
YAOLAO = r"E:\AndroidStudioProjects\ReaderVoiceMobile\training\readerdirector\runs\voice_state\voice_factory\yaolao"
SYS = "You are a helpful assistant.<|endofprompt|>"
PROMPT_TEXT = SYS + "小娃娃，这世间的事，哪有你想的那么简单。"
WIN = os.path.join(YAOLAO, "candidate_03.wav")


def camp_emb(wav_path):
    import torchaudio
    import torchaudio.compliance.kaldi as kaldi
    import torchaudio.functional as taf
    data, sr = sf.read(wav_path, dtype="float32")
    wav = torch.from_numpy(data).unsqueeze(0)
    if sr != 16000:
        wav = taf.resample(wav, sr, 16000)
    feat = kaldi.fbank(wav, num_mel_bins=80, dither=0, sample_frequency=16000)
    feat = feat - feat.mean(dim=0, keepdim=True)
    return sess.run(None, {sess.get_inputs()[0].name: feat.unsqueeze(0).numpy()})[0].flatten()


def cos(a, b):
    return float(np.dot(a, b) / (np.linalg.norm(a) * np.linalg.norm(b)))


def main():
    global sess
    import onnxruntime as ort
    from cosyvoice.cli.cosyvoice import CosyVoice3
    sess = ort.InferenceSession(os.path.join(MODEL_DIR, "campplus.onnx"), providers=["CPUExecutionProvider"])
    ref = camp_emb(WIN)
    out = {}

    # ---- VF-001 concat speaker test（复用 s000-s099）----
    gen_dir = os.path.join(YAOLAO, "generated")
    files = [os.path.join(gen_dir, "s%03d.wav" % k) for k in range(100)]
    sims = []
    for g in range(10):
        parts = [sf.read(f, dtype="float32")[0] for f in files[g * 10:(g + 1) * 10]]
        gap = np.zeros(int(0.05 * 24000), dtype="float32")
        concat = np.concatenate([x for p in parts for x in (p, gap)])
        gp = os.path.join(YAOLAO, "validation", "concat_%02d.wav" % g)
        sf.write(gp, concat, 24000)
        sims.append(cos(camp_emb(gp), ref))
    out["VF001_concat"] = {"mean": round(float(np.mean(sims)), 4), "std": round(float(np.std(sims)), 4),
                           "all": [round(s, 4) for s in sims]}
    print("VF001 concat: mean=%.4f std=%.4f (target >0.85 / <0.05)" % (np.mean(sims), np.std(sims)), flush=True)

    # ---- VF-002 emotion stability ----
    print("loading CosyVoice3 for emotion test...", flush=True)
    cosy = CosyVoice3(MODEL_DIR, load_trt=False, load_vllm=False, fp16=False)
    emo = {"neutral": "你好。", "anger": "你竟然敢骗我！", "whisper": "不要告诉别人。",
           "laugh": "哈哈哈哈，妙哉妙哉！", "sorrow": "唉，物是人非啊。", "stern": "此事万万不可！"}
    esims = {}
    for name, t in emo.items():
        mi = cosy.frontend.frontend_zero_shot(t, PROMPT_TEXT, WIN, cosy.sample_rate, "")
        import uuid as _uuid
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
        for tk in cosy.model.llm.inference(
                text=mi["text"].to(dev), text_len=mi["text_len"].to(dev),
                prompt_text=mi["prompt_text"].to(dev), prompt_text_len=mi["prompt_text_len"].to(dev),
                prompt_speech_token=mi["llm_prompt_speech_token"].to(dev),
                prompt_speech_token_len=mi["llm_prompt_speech_token_len"].to(dev),
                embedding=mi["llm_embedding"].to(dev)):
            toks.append(int(tk))
        speech = cosy.model.token2wav(
            token=torch.tensor([toks], dtype=torch.int32).to(dev),
            prompt_token=mi["flow_prompt_speech_token"].to(dev),
            prompt_feat=mi["prompt_speech_feat"].to(dev),
            embedding=mi["flow_embedding"].to(dev),
            token_offset=0, uuid=uuid, finalize=True)
        wav = speech[0].cpu().numpy()
        gp = os.path.join(YAOLAO, "validation", "emotion_%s.wav" % name)
        sf.write(gp, wav, cosy.sample_rate)
        s = cos(camp_emb(gp), ref)
        esims[name] = round(s, 4)
        print("VF002 %s: sim=%.4f" % (name, s), flush=True)
    out["VF002_emotion"] = esims
    print("VF002 emotion: %s (target same speaker >0.8)" % json.dumps(esims), flush=True)

    with open(os.path.join(YAOLAO, "m15_result.json"), "w", encoding="utf-8") as f:
        json.dump(out, f, ensure_ascii=False, indent=1)
    print("m15_result.json saved", flush=True)
    print("DONE", flush=True)


if __name__ == "__main__":
    main()
