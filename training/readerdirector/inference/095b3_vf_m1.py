# -*- coding: utf-8 -*-
"""M1: Voice Identity Preservation Test — VoiceFactory Pipeline v0.1 (env-fixed).
环境约束：transformers==4.51.3 + tokenizers==0.21.4（4.57.3 会导致 LLM 只生成 1 token）。
"""
import hashlib
import json
import os
import random
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

ROOT = r"E:\AndroidStudioProjects\ReaderVoiceMobile\training\readerdirector\runs\voice_state\voice_factory"
YAOLAO = os.path.join(ROOT, "yaolao")
MODEL_DIR = r"E:\AndroidStudioProjects\cosyvoice3-distill-lab\Fun-CosyVoice3-0.5B-2512-RL"
MODEL_ID = "Fun-CosyVoice3-0.5B-2512-RL-distilled-v1"
SYS = "You are a helpful assistant.<|endofprompt|>"
REF_TEXT = "小娃娃，这世间的事，哪有你想的那么简单。"
PROMPT_TEXT = SYS + REF_TEXT
MAX_TOKENS = 125
TOKEN_RATE = 25.0  # tokens per second (12Hz*2)


def camp_emb(wav_path):
    import torchaudio
    import torchaudio.compliance.kaldi as kaldi
    import torchaudio.functional as taf
    import soundfile as sf
    data, sr = sf.read(wav_path, dtype="float32")
    wav = torch.from_numpy(data).unsqueeze(0)
    if sr != 16000:
        wav = taf.resample(wav, sr, 16000)
    feat = kaldi.fbank(wav, num_mel_bins=80, dither=0, sample_frequency=16000)
    feat = feat - feat.mean(dim=0, keepdim=True)
    return sess.run(None, {sess.get_inputs()[0].name: feat.unsqueeze(0).numpy()})[0].flatten()


def cos(a, b):
    return float(np.dot(a, b) / (np.linalg.norm(a) * np.linalg.norm(b)))


def enroll(cosy, wav_path, profile_dir, profile_id, display_name):
    """lab build_voice_profile.py 同款 enrollment（内联，含 ≤125 截断）。"""
    os.makedirs(profile_dir, exist_ok=True)
    mi = cosy.frontend.frontend_zero_shot("这是新音色注册校验。", PROMPT_TEXT, wav_path, cosy.sample_rate, "")
    tokens = mi["llm_prompt_speech_token"].detach().cpu().reshape(-1).numpy().astype("<i4")
    truncated = False
    if tokens.size > MAX_TOKENS:
        tokens = tokens[:MAX_TOKENS]
        truncated = True
    feat = mi["prompt_speech_feat"].detach().float().cpu()
    frames = int(feat.shape[1])
    if frames > tokens.size * 2:
        feat = feat[:, :tokens.size * 2, :]
        frames = tokens.size * 2
    embedding = mi["flow_embedding"].to(cosy.model.device)
    with torch.inference_mode():
        flow_speaker = cosy.model.flow.spk_embed_affine_layer(
            torch.nn.functional.normalize(embedding, dim=1)).detach().float().cpu()
    assert tuple(flow_speaker.shape) == (1, 80)
    token_file = os.path.join(profile_dir, "prompt-speech-tokens.csv")
    cond_file = os.path.join(profile_dir, "prompt-cond.bin")
    spks_file = os.path.join(profile_dir, "spks.bin")
    with open(token_file, "w", encoding="ascii") as f:
        f.write(",".join(str(int(t)) for t in tokens))
    feat.transpose(1, 2).contiguous().numpy().astype("<f4").tofile(cond_file)
    flow_speaker.contiguous().numpy().astype("<f4").tofile(spks_file)
    profile_hash = hashlib.sha256(b"".join([
        MODEL_ID.encode(), PROMPT_TEXT.encode(), token_file_read(token_file),
        open(cond_file, "rb").read(), open(spks_file, "rb").read()])).hexdigest()
    meta = {"schemaVersion": 1, "id": profile_id, "displayName": display_name, "modelId": MODEL_ID,
            "promptPrefix": PROMPT_TEXT, "promptTokenCount": int(tokens.size), "promptFrameCount": int(frames),
            "profileHash": profile_hash, "builtIn": False, "truncated": truncated,
            "createdAt": int(time.time() * 1000),
            "source": {"promptText": REF_TEXT, "generator": "Qwen3-TTS-12Hz-1.7B-VoiceDesign"}}
    with open(os.path.join(profile_dir, "profile.json"), "w", encoding="utf-8") as f:
        json.dump(meta, f, ensure_ascii=False, indent=2)
    return meta


def token_file_read(p):
    with open(p, "rb") as f:
        return f.read()



def direct_synth(cosy, mi, tts_text=""):
    """无线程直连合成：llm.inference → token2wav（规避 tts() 线程死锁）。"""
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
    for t in cosy.model.llm.inference(
            text=mi["text"].to(dev), text_len=mi["text_len"].to(dev),
            prompt_text=mi["prompt_text"].to(dev), prompt_text_len=mi["prompt_text_len"].to(dev),
            prompt_speech_token=mi["llm_prompt_speech_token"].to(dev),
            prompt_speech_token_len=mi["llm_prompt_speech_token_len"].to(dev),
            embedding=mi["llm_embedding"].to(dev)):
        toks.append(int(t))
    tok = torch.tensor([toks], dtype=torch.int32).to(dev)
    speech = cosy.model.token2wav(
        token=tok, prompt_token=mi["flow_prompt_speech_token"].to(dev),
        prompt_feat=mi["prompt_speech_feat"].to(dev), embedding=mi["flow_embedding"].to(dev),
        token_offset=0, uuid=uuid, finalize=True)
    if speech.numel() == 0:
        print("EMPTY speech: toks=%d prompt_toks=%d" % (len(toks), int(mi["llm_prompt_speech_token"].numel())), flush=True)
    return speech

def main():
    global sess
    import onnxruntime as ort
    from cosyvoice.cli.cosyvoice import CosyVoice3

    sess = ort.InferenceSession(os.path.join(MODEL_DIR, "campplus.onnx"), providers=["CPUExecutionProvider"])
    for d in ("candidates", "enrollment", "validation", "generated"):
        os.makedirs(os.path.join(YAOLAO, d), exist_ok=True)

    print("loading CosyVoice3...", flush=True)
    cosy = CosyVoice3(MODEL_DIR, load_trt=False, load_vllm=False, fp16=False)
    print("loaded", flush=True)

    cands = [os.path.join(YAOLAO, "candidate_%02d.wav" % i) for i in (1, 2, 3)]
    ref_embs = {os.path.basename(c): camp_emb(c) for c in cands}

    probes = ["药老，您可算回来了。", "这丹药，是老夫为你炼的。", "哈哈哈，你这小子，倒是有趣。"]
    gen_sim = {}
    for ci, cp in enumerate(cands):
        sims = []
        for pi, pt in enumerate(probes):
            mi = cosy.frontend.frontend_zero_shot(pt, PROMPT_TEXT, cp, cosy.sample_rate, "")
            wav = direct_synth(cosy, mi)[0].cpu().numpy()
            if wav.size == 0:
                print("cand%d probe%d EMPTY, skip" % (ci + 1, pi + 1), flush=True)
                sims.append(0.0)
                continue
            import soundfile as sf
            gp = os.path.join(YAOLAO, "validation", "probe_c%d_%d.wav" % (ci + 1, pi + 1))
            sf.write(gp, wav, cosy.sample_rate)
            sims.append(cos(camp_emb(gp), ref_embs[os.path.basename(cp)]))
        gen_sim[ci + 1] = sims
        print("cand%d generability: %s" % (ci + 1, ["%.3f" % s for s in sims]), flush=True)

    names = sorted(ref_embs)
    stab = {n: float(np.mean([cos(ref_embs[n], ref_embs[m]) for m in names if m != n])) for n in names}
    scores = {ci: 0.5 * stab["candidate_%02d.wav" % ci] + 0.5 * float(np.mean(gen_sim[ci])) for ci in (1, 2, 3)}
    for ci in (1, 2, 3):
        print("cand%d stability=%.3f generability=%.3f score=%.3f" % (
            ci, stab["candidate_%02d.wav" % ci], np.mean(gen_sim[ci]), scores[ci]), flush=True)
    winner = max(scores, key=scores.get)
    print("WINNER candidate_%02d" % winner, flush=True)

    # enrollment（winner）
    win_path = cands[winner - 1]
    prof_id = "vp_yaolao_qwen3tts_c%d" % winner
    prof_dir = os.path.join(YAOLAO, "enrollment", prof_id)
    meta = enroll(cosy, win_path, prof_dir, prof_id, "药老")
    print("enrolled:", json.dumps({k: meta[k] for k in ("promptTokenCount", "promptFrameCount", "truncated", "profileHash")}, ensure_ascii=False), flush=True)

    # 100 句生成 + CAMPPlus
    rng = random.Random(42)
    sentences = []
    bases = ["老夫有话对你说。", "这件事，说来话长。", "这丹药你拿去，好生收着。", "想当年，老夫一人一剑闯过万魔窟。",
             "你可知这火候几分？信则有，不信则无。", "哈哈哈哈，妙哉妙哉！", "此事万万不可！", "好孩子，辛苦你了。",
             "哼，就凭你也配？", "走吧。", "坐下。", "别急。", "有点意思。", "原来如此。", "为何你非要执着于那段往事？",
             "你的伤，真的好了吗？", "那本书，你还留着吗？", "若你愿意，老夫可以收你为徒。", "修行之路，宁慢勿快。",
             "这天地灵火，老夫寻了三十年。"]
    sentences = [rng.choice(bases) for _ in range(100)]
    rows = []
    t0 = time.time()
    for k, t in enumerate(sentences):
        mi = cosy.frontend.frontend_zero_shot(t, PROMPT_TEXT, win_path, cosy.sample_rate, "")
        wav = direct_synth(cosy, mi)[0].cpu().numpy()
        import soundfile as sf
        gp = os.path.join(YAOLAO, "generated", "s%03d.wav" % k)
        sf.write(gp, wav, cosy.sample_rate)
        sim = cos(camp_emb(gp), ref_embs["candidate_%02d.wav" % winner])
        rows.append({"k": k, "text": t, "sim": round(sim, 4), "dur_s": round(len(wav) / cosy.sample_rate, 2)})
        if k % 10 == 0:
            print("  s%03d sim=%.3f (%.0fs)" % (k, sim, time.time() - t0), flush=True)
    sims = [r["sim"] for r in rows]
    print("\n==== M1 RESULT (winner candidate_%02d) ====" % winner, flush=True)
    print("A self-consistency: mean=%.4f min=%.4f std=%.4f pass>0.8: %d/%d" % (
        np.mean(sims), np.min(sims), np.std(sims), sum(1 for s in sims if s > 0.8), len(sims)), flush=True)
    print("C drift: first10=%.4f last10=%.4f delta=%.4f" % (
        np.mean(sims[:10]), np.mean(sims[-10:]), np.mean(sims[-10:]) - np.mean(sims[:10])), flush=True)

    # B 异角色：内置音色
    builtin_wav = r"E:\AndroidStudioProjects\CosyVoice-main\asset\zero_shot_prompt.wav"
    builtin_text = SYS + "希望你以后能够做的比我还好呦。"
    bsims = []
    for bt in ["这是一句用来做对照的普通句子。", "天气不错，出去走走吧。"]:
        mi = cosy.frontend.frontend_zero_shot(bt, builtin_text, builtin_wav, cosy.sample_rate, "")
        wav = direct_synth(cosy, mi)[0].cpu().numpy()
        import soundfile as sf
        gp = os.path.join(YAOLAO, "validation", "builtin_other.wav")
        sf.write(gp, wav, cosy.sample_rate)
        bsims.append(cos(camp_emb(gp), ref_embs["candidate_%02d.wav" % winner]))
    print("B cross-role vs builtin: %s pass<0.6: %s" % (["%.3f" % b for b in bsims], [b < 0.6 for b in bsims]), flush=True)

    res = {"winner": "candidate_%02d" % winner,
           "scores": {str(k): round(v, 4) for k, v in scores.items()},
           "stability": {k: round(v, 4) for k, v in stab.items()},
           "generability": {str(k): [round(s, 4) for s in v] for k, v in gen_sim.items()},
           "A_self": {"mean": round(float(np.mean(sims)), 4), "min": round(float(np.min(sims)), 4),
                      "std": round(float(np.std(sims)), 4), "pass_gt_08": int(sum(1 for s in sims if s > 0.8)), "n": len(sims)},
           "C_drift": {"first10": round(float(np.mean(sims[:10])), 4), "last10": round(float(np.mean(sims[-10:])), 4)},
           "B_cross": bsims, "profile": meta, "rows": rows}
    with open(os.path.join(YAOLAO, "m1_result.json"), "w", encoding="utf-8") as f:
        json.dump(res, f, ensure_ascii=False, indent=1)
    print("m1_result.json saved", flush=True)
    print("DONE", flush=True)


if __name__ == "__main__":
    main()
