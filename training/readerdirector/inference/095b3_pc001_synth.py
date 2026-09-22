# -*- coding: utf-8 -*-
"""PC-001 Step B: 合成（cosyvoice venv）——旁白=narrator 档案；角色 v0.1=男声 fallback + 记录。
输出 pc001_run.jsonl：paragraph_id/speaker/profile/route/emotion/render_time/wav_path。
"""
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

# 档案 → 参考素材（v0.2：narrator + 三主角；其余 fallback_male）
PROFILES = {
    "narrator": {"wav": os.path.join(FACTORY, "narrator", "candidate_02.wav"), "ref": None},
    "hero_tanyue": {"wav": os.path.join(FACTORY, "hero_tanyue", "candidate_02.wav"), "ref": None},
    "hero_conan": {"wav": os.path.join(FACTORY, "hero_conan", "candidate_03.wav"), "ref": None},
    "hero_yeyang": {"wav": os.path.join(FACTORY, "hero_yeyang", "candidate_02.wav"), "ref": None},
    "fallback_male": {"wav": os.path.join(FACTORY, "role_c", "candidate_01.wav"), "ref": None},
}
CHARACTER_MAP = {
    "谭越": "hero_tanyue", "老谭": "hero_tanyue", "小谭": "hero_tanyue",
    "柯南": "hero_conan", "工藤": "hero_conan", "小侦探": "hero_conan",
    "叶洋": "hero_yeyang", "叶师兄": "hero_yeyang", "叶师弟": "hero_yeyang",
}


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


def synth_instruct2(cosy, text, instruction, wav_path):
    """INSTRUCT2：LLM 只收 instruction+文本（不带参考 token），Flow 仍用档案。"""
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
    # load ref texts
    for k in PROFILES:
        meta = json.load(open(os.path.join(FACTORY, "narrator" if k == "narrator" else "role_c", "meta.json"), encoding="utf-8"))
        PROFILES[k]["ref"] = meta["ref_text"]
    print("loading CosyVoice3...", flush=True)
    cosy = CosyVoice3(MODEL_DIR, load_trt=False, load_vllm=False, fp16=False)
    print("loaded", flush=True)

    dirs = [json.loads(l) for l in open(os.path.join(PC001, "director_v3.jsonl"), encoding="utf-8")]
    # rvic-v1 表演编译表（v0.3）
    RVIC = {"LOW_VOLUME": "轻声", "WHISPER": "耳语般", "LOUD": "大声", "TREMBLING": "声音发颤",
            "COLD": "语气冰冷", "SEDUCTIVE": "带着诱惑的语气", "MUMBLED": "含糊地",
            "CHEERFUL": "开心地", "SAD": "伤心地", "ANGRY": "生气地", "FAST": "语速快", "SLOW": "语速慢"}
    EV2S = {"VOICE_OVERRIDE_SET": "SET", "VOICE_OVERRIDE_CLEAR": "CLEAR", "PERFORMANCE": "PERF", "NONE": "NONE"}

    def compile_instruction(d):
        ev = d.get("voice_event") or {}
        if ev.get("type") != "PERF" or not ev.get("evidence"):
            return None
        txt = ev["evidence"]
        frags = []
        for k, v in RVIC.items():
            pass  # evidence 是自由文本，v0.3 用简单关键词规则
        kw = {"轻声": "LOW_VOLUME", "低": "LOW_VOLUME", "耳语": "WHISPER", "小声": "LOW_VOLUME",
              "吼": "LOUD", "大声": "LOUD", "喊": "LOUD", "颤": "TREMBLING", "冷": "COLD",
              "笑": "CHEERFUL", "哭": "SAD", "怒": "ANGRY", "急": "FAST", "慢": "SLOW"}
        for w, st in kw.items():
            if w in txt and st not in frags:
                frags.append(st)
        if not frags:
            return None
        inst = "请用" + "、".join(RVIC[f] for f in frags[:3]) + "的方式说这句话。"
        return inst
    os.makedirs(os.path.join(PC001, "audio"), exist_ok=True)
    # 短句合并（SegmentGroup v2）：DIALOGUE 整段 <5 字，或 引号内对白 <6 字且整段 <26 字 -> 并入前段（仅 TTS 用）
    import re as _re
    for i in range(1, len(dirs)):
        d = dirs[i]
        short = len(d["text"]) < 5
        if not short and d["segment_type"] == "DIALOGUE":
            _LQ, _RQ = chr(0x201c), chr(0x201d)
            q1 = d["text"].find(_LQ)
            q2 = d["text"].find(_RQ)
            if q1 >= 0 and q2 > q1 and (q2 - q1 - 1) < 6 and len(d["text"]) < 26:
                short = True
        if short and d["segment_type"] == "DIALOGUE":
            prev = dirs[i - 1]
            if not prev.get("merged_into"):
                d["tts_text"] = prev["text"] + d["text"]
                d["merged_into"] = prev["paragraph_id"]
            else:
                d["tts_text"] = d["text"]
    out_rows = []
    t0 = time.time()
    for i, d in enumerate(dirs):
        t1 = time.time()
        if d["route"] == "narrator":
            prof = "narrator"
            wav_ref = PROFILES["narrator"]["wav"]
            ref_text = PROFILES["narrator"]["ref"]
        else:
            prof = CHARACTER_MAP.get(d.get("speaker_v3") or d.get("speaker_v2"), "fallback_male")
            wav_ref = PROFILES[prof]["wav"]
            ref_text = PROFILES[prof]["ref"]
        try:
            tts_text = d.get("tts_text") or d["text"]
            instruction = compile_instruction(d)
            if instruction and d["route"] == "character":
                wav = synth_instruct2(cosy, tts_text, instruction, wav_ref)
                inst_used = instruction
            else:
                wav = synth(cosy, tts_text, SYS + ref_text, wav_ref)
                inst_used = None
        except Exception as e:
            print("FAIL %s: %s" % (d["paragraph_id"], str(e)[:120]), flush=True)
            out_rows.append({"paragraph_id": d["paragraph_id"], "book": d["book"],
                             "segment_type": d["segment_type"], "route": d["route"],
                             "speaker": d.get("speaker_v2") or d.get("speaker"), "voice_event": d.get("voice_event"),
                             "instruction": inst_used,
                             "profile": prof, "profile_miss": prof == "fallback_male",
                             "render_time_s": round(time.time() - t1, 2), "audio_seconds": 0,
                             "wav_path": None, "error": str(e)[:200]})
            continue
        wav_path = os.path.join(PC001, "audio", d["paragraph_id"] + ".wav")
        sf.write(wav_path, wav, cosy.sample_rate)
        out_rows.append({
            "paragraph_id": d["paragraph_id"], "book": d["book"],
            "segment_type": d["segment_type"], "route": d["route"],
            "speaker": d.get("speaker_v3") or d.get("speaker_v2") or d.get("speaker"), "mentions": d.get("mentions", []),
            "voice_event": d.get("voice_event"),
            "instruction": inst_used,
            "profile": prof, "profile_miss": d["route"] == "character" and prof == "fallback_male",
            "render_time_s": round(time.time() - t1, 2),
            "audio_seconds": round(len(wav) / cosy.sample_rate, 2),
            "wav_path": wav_path,
        })
        if i % 30 == 0:
            print("  %d/%d (%.0fs)" % (i, len(dirs), time.time() - t0), flush=True)
    with open(os.path.join(PC001, "pc001_run.jsonl"), "w", encoding="utf-8") as f:
        for r in out_rows:
            f.write(json.dumps(r, ensure_ascii=False) + "\n")
    print("pc001_run.jsonl:", len(out_rows), "rows | total %.0fs" % (time.time() - t0), flush=True)
    print("DONE", flush=True)


if __name__ == "__main__":
    main()
