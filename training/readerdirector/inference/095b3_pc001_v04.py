# -*- coding: utf-8 -*-
"""PC-001 v0.4: 重建 run 记录 + INSTRUCT2 段重合成（其余复用已有 wav）。"""
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

PROFILES = {
    "narrator": {"wav": os.path.join(FACTORY, "narrator", "candidate_02.wav")},
    "hero_tanyue": {"wav": os.path.join(FACTORY, "hero_tanyue", "candidate_02.wav")},
    "hero_conan": {"wav": os.path.join(FACTORY, "hero_conan", "candidate_03.wav")},
    "hero_yeyang": {"wav": os.path.join(FACTORY, "hero_yeyang", "candidate_02.wav")},
    "fallback_male": {"wav": os.path.join(FACTORY, "role_c", "candidate_01.wav")},
}
CHARACTER_MAP = {
    "谭越": "hero_tanyue", "老谭": "hero_tanyue", "小谭": "hero_tanyue",
    "柯南": "hero_conan", "工藤": "hero_conan", "小侦探": "hero_conan",
    "叶洋": "hero_yeyang", "叶师兄": "hero_yeyang", "叶师弟": "hero_yeyang",
}
RVIC = {"LOW_VOLUME": "轻声", "WHISPER": "耳语般", "LOUD": "大声", "TREMBLING": "声音发颤",
        "COLD": "语气冰冷", "SEDUCTIVE": "带着诱惑的语气", "MUMBLED": "含糊地",
        "CHEERFUL": "开心地", "SAD": "伤心地", "ANGRY": "生气地", "FAST": "语速快", "SLOW": "语速慢"}


def compile_instruction(d):
    ev = d.get("voice_event") or {}
    if ev.get("type") != "PERF" or not ev.get("evidence"):
        return None
    txt = ev["evidence"]
    kw = {"轻声": "LOW_VOLUME", "低": "LOW_VOLUME", "耳语": "WHISPER", "小声": "LOW_VOLUME",
          "吼": "LOUD", "大声": "LOUD", "喊": "LOUD", "颤": "TREMBLING", "冷": "COLD",
          "笑": "CHEERFUL", "哭": "SAD", "怒": "ANGRY", "急": "FAST", "慢": "SLOW"}
    frags = []
    for w, st in kw.items():
        if w in txt and st not in frags:
            frags.append(st)
    if not frags:
        return None
    return "请用" + "、".join(RVIC[f] for f in frags[:3]) + "的方式说这句话。"


def synth_instruct2(cosy, text, instruction, wav_path):
    mi = cosy.frontend.frontend_instruct2(text, SYS + instruction, wav_path, cosy.sample_rate, "")
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
    empty_tok = torch.zeros(1, 0, dtype=torch.int32).to(dev)
    empty_len = torch.tensor([0], dtype=torch.int32).to(dev)
    toks = []
    for t in cosy.model.llm.inference(
            text=mi["text"].to(dev), text_len=mi["text_len"].to(dev),
            prompt_text=mi["prompt_text"].to(dev), prompt_text_len=mi["prompt_text_len"].to(dev),
            prompt_speech_token=empty_tok, prompt_speech_token_len=empty_len,
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
    for k in PROFILES:
        sub = "narrator" if k == "narrator" else ("hero_tanyue" if k == "hero_tanyue" else ("hero_conan" if k == "hero_conan" else ("hero_yeyang" if k == "hero_yeyang" else "role_c")))
        meta = json.load(open(os.path.join(FACTORY, sub, "meta.json"), encoding="utf-8"))
        PROFILES[k]["ref"] = meta["ref_text"]

    dirs = [json.loads(l) for l in open(os.path.join(PC001, "director_v3.jsonl"), encoding="utf-8")]
    cosy = None
    out = []
    for i, d in enumerate(dirs):
        wav_path = os.path.join(PC001, "audio", d["paragraph_id"] + ".wav")
        instruction = compile_instruction(d) if d["segment_type"] == "DIALOGUE" else None
        # INSTRUCT2 段需要重合成；其余复用已有 wav
        if instruction and d["route"] == "character":
            if cosy is None:
                print("loading CosyVoice3...", flush=True)
                cosy = CosyVoice3(MODEL_DIR, load_trt=False, load_vllm=False, fp16=False)
                print("loaded", flush=True)
            prof = CHARACTER_MAP.get(d.get("speaker_v3") or d.get("speaker_v2"), "fallback_male")
            t1 = time.time()
            try:
                wav = synth_instruct2(cosy, d["text"], instruction, PROFILES[prof]["wav"])
                sf.write(wav_path, wav, cosy.sample_rate)
                rt = round(time.time() - t1, 2)
                print("INSTRUCT2 %s: %.1fs audio (%.1fs)" % (d["paragraph_id"], len(wav) / cosy.sample_rate, rt), flush=True)
            except Exception as e:
                print("INSTRUCT2 FAIL %s: %s" % (d["paragraph_id"], str(e)[:120]), flush=True)
                rt = round(time.time() - t1, 2)
                instruction = None
        else:
            rt = None
        # 记录（复用已有 wav 或 INSTRUCT2 重合成）
        if os.path.exists(wav_path) and os.path.getsize(wav_path) > 1000:
            import numpy as np
            data, sr = sf.read(wav_path, dtype="float32")
            audio_sec = round(len(data) / sr, 2)
            error = None
        else:
            audio_sec = 0
            error = "no audio"
        out.append({
            "paragraph_id": d["paragraph_id"], "book": d["book"],
            "segment_type": d["segment_type"], "route": d["route"],
            "speaker": d.get("speaker_v3") or d.get("speaker_v2") or d.get("speaker"),
            "character_id": d.get("character_id"),
            "mentions": d.get("mentions", []), "voice_event": d.get("voice_event"),
            "instruction": instruction, "profile": (CHARACTER_MAP.get(d.get("speaker_v3") or d.get("speaker_v2"), "fallback_male") if d["route"] == "character" else "narrator"),
            "profile_miss": d["route"] == "character" and (d.get("speaker_v3") or d.get("speaker_v2")) not in CHARACTER_MAP,
            "render_time_s": rt, "audio_seconds": audio_sec, "wav_path": wav_path, "error": error,
        })
    with open(os.path.join(PC001, "pc001_run_v04.jsonl"), "w", encoding="utf-8") as f:
        for r in out:
            f.write(json.dumps(r, ensure_ascii=False) + "\n")
    print("pc001_run_v04.jsonl:", len(out), flush=True)
    print("DONE", flush=True)


if __name__ == "__main__":
    main()
