# -*- coding: utf-8 -*-
"""VF-003: 三角色泛化（Role B 年轻女性 / Role C 西幻 elf）— 复用 M1 pipeline。
B 组 cross：三角色 ref 与彼此生成句互比（目标 <0.6），药老资产来自 M1。
"""
import json
import os
import random
import sys
import time
import uuid as _uuid

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
FACTORY = r"E:\AndroidStudioProjects\ReaderVoiceMobile\training\readerdirector\runs\voice_state\voice_factory"
SYS = "You are a helpful assistant.<|endofprompt|>"

ROLES = {
    "role_b": {
        "ref_text": "今天天气真好，我们一起去河边走走吧。",
        "lang": "zh",
        "sentences": ["你好呀。", "我们走吧。", "真的吗？", "太棒了！", "你尝尝这个。", "别担心啦。", "明天见。", "我喜欢这里。",
                      "他什么时候回来？", "这个故事真有趣。", "我想去海边看看。", "你听，鸟在唱歌。", "路上小心。", "生日快乐！",
                      "我们一起努力吧。", "天冷，多穿点。", "你在想什么？", "没关系，慢慢来。", "这个给你。", "晚安，做个好梦。"],
    },
    "role_c": {
        "ref_text": "The forest remembers what the kingdoms forget, young one.",
        "lang": "en",
        "sentences": ["The old songs speak of such days.", "Patience, my friend, patience.", "This knowledge was not earned lightly.",
                      "The stars have shifted since then.", "We do not rush what the ages have woven.", "You carry your father's calm.",
                      "The gates of the city will open at dawn.", "Magic is a language, not a weapon.", "I have seen empires rise and fall.",
                      "Your question deserves an honest answer.", "The library holds three thousand years of memory.", "Speak, and the stones will listen.",
                      "There is wisdom in stillness.", "The river remembers every stone.", "We shall meet again under the oak.",
                      "That spell was written before your grandfather's grandfather.", "The crown weighs heavier than the sword.", "Learn first, judge later.",
                      "Even elves forget, but rarely.", "Come, the fire is warm."],
    },
}


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


def enroll_profile(cosy, wav_path, outdir):
    import hashlib
    mi = cosy.frontend.frontend_zero_shot("这是新音色注册校验。", PROMPT_TEXT, wav_path, cosy.sample_rate, "")
    tokens = mi["llm_prompt_speech_token"].detach().cpu().reshape(-1).numpy().astype("<i4")
    if tokens.size > 125:
        tokens = tokens[:125]
    feat = mi["prompt_speech_feat"].detach().float().cpu()
    frames = min(int(feat.shape[1]), tokens.size * 2)
    feat = feat[:, :frames, :]
    emb = mi["flow_embedding"].to(cosy.model.device)
    with torch.inference_mode():
        spk = cosy.model.flow.spk_embed_affine_layer(torch.nn.functional.normalize(emb, dim=1)).detach().float().cpu()
    with open(os.path.join(outdir, "prompt-speech-tokens.csv"), "w", encoding="ascii") as f:
        f.write(",".join(str(int(t)) for t in tokens))
    feat.transpose(1, 2).contiguous().numpy().astype("<f4").tofile(os.path.join(outdir, "prompt-cond.bin"))
    spk.contiguous().numpy().astype("<f4").tofile(os.path.join(outdir, "spks.bin"))
    return {"tokens": int(tokens.size), "frames": int(frames)}


def main():
    global sess, PROMPT_TEXT
    import argparse
    ap = argparse.ArgumentParser()
    ap.add_argument("--role", default=None, help="单角色模式：从 FACTORY/<role>/meta.json 读 ref_text")
    ap.add_argument("--sentences", default=None, help="句子池 JSON 文件（可选）")
    ap.add_argument("--quick", action="store_true", help="只 rank+enroll，不跑 100 句")
    args = ap.parse_args()
    import onnxruntime as ort
    from cosyvoice.cli.cosyvoice import CosyVoice3
    if args.role:
        meta = json.load(open(os.path.join(FACTORY, args.role, "meta.json"), encoding="utf-8"))
        ROLES[args.role] = {"ref_text": meta["ref_text"], "lang": "zh",
                            "sentences": (json.load(open(args.sentences, encoding="utf-8")) if args.sentences
                                          else ROLES["role_b"]["sentences"])}
    sess = ort.InferenceSession(os.path.join(MODEL_DIR, "campplus.onnx"), providers=["CPUExecutionProvider"])
    print("loading CosyVoice3...", flush=True)
    cosy = CosyVoice3(MODEL_DIR, load_trt=False, load_vllm=False, fp16=False)
    print("loaded", flush=True)

    result = {}
    refs = {}  # role -> ref embedding
    for rid, spec in ROLES.items():
        outdir = os.path.join(FACTORY, rid)
        PROMPT_TEXT = SYS + spec["ref_text"]
        cands = [os.path.join(outdir, "candidate_%02d.wav" % i) for i in (1, 2, 3)]
        ref_embs = {os.path.basename(c): camp_emb(c) for c in cands}
        names = sorted(ref_embs)
        stab = {n: float(np.mean([cos(ref_embs[n], ref_embs[m]) for m in names if m != n])) for n in names}
        probes = spec["sentences"][:3]
        gen_sim = {}
        for ci, cp in enumerate(cands):
            sims = []
            for pt in probes:
                wav = synth(cosy, pt, PROMPT_TEXT, cp)
                gp = os.path.join(outdir, "validation_probe_c%d_%d.wav" % (ci + 1, probes.index(pt) + 1))
                sf.write(gp, wav, cosy.sample_rate)
                sims.append(cos(camp_emb(gp), ref_embs[os.path.basename(cp)]))
            gen_sim[ci + 1] = sims
            print("%s cand%d gen: %s" % (rid, ci + 1, ["%.3f" % s for s in sims]), flush=True)
        scores = {ci: 0.5 * stab["candidate_%02d.wav" % ci] + 0.5 * float(np.mean(gen_sim[ci])) for ci in (1, 2, 3)}
        winner = max(scores, key=scores.get)
        print("%s WINNER candidate_%02d (scores %s)" % (rid, winner, {k: round(v, 3) for k, v in scores.items()}), flush=True)
        win_path = cands[winner - 1]
        prof_dir = os.path.join(outdir, "enrollment", "vp_%s_c%d" % (rid, winner))
        os.makedirs(prof_dir, exist_ok=True)
        en = enroll_profile(cosy, win_path, prof_dir)
        print("%s enrolled: %s" % (rid, en), flush=True)

        if args.quick:
            refs[rid] = ref_embs["candidate_%02d.wav" % winner]
            result[rid] = {"winner": "candidate_%02d" % winner,
                           "scores": {str(k): round(v, 4) for k, v in scores.items()},
                           "enroll": en}
            print("%s QUICK enrolled (skip 100-sentence check)" % rid, flush=True)
            continue
        rng = random.Random(7)
        sents = [rng.choice(spec["sentences"]) for _ in range(100)]
        os.makedirs(os.path.join(outdir, "generated"), exist_ok=True)
        sims = []
        t0 = time.time()
        for k, t in enumerate(sents):
            wav = synth(cosy, t, PROMPT_TEXT, win_path)
            gp = os.path.join(outdir, "generated", "s%03d.wav" % k)
            sf.write(gp, wav, cosy.sample_rate)
            sims.append(cos(camp_emb(gp), ref_embs["candidate_%02d.wav" % winner]))
            if k % 25 == 0:
                print("  %s s%03d sim=%.3f (%.0fs)" % (rid, k, sims[-1], time.time() - t0), flush=True)
        refs[rid] = ref_embs["candidate_%02d.wav" % winner]
        result[rid] = {"winner": "candidate_%02d" % winner, "scores": {str(k): round(v, 4) for k, v in scores.items()},
                       "A_same": {"mean": round(float(np.mean(sims)), 4), "min": round(float(np.min(sims)), 4),
                                  "std": round(float(np.std(sims)), 4), "pass_gt_08": int(sum(1 for s in sims if s > 0.8))},
                       "C_drift": {"first10": round(float(np.mean(sims[:10])), 4), "last10": round(float(np.mean(sims[-10:])), 4)},
                       "enroll": en}
        print("%s A: mean=%.4f min=%.4f pass>0.8: %d/100 | C: %.4f->%.4f" % (
            rid, np.mean(sims), np.min(sims), sum(1 for s in sims if s > 0.8),
            np.mean(sims[:10]), np.mean(sims[-10:])), flush=True)

    # B cross：三角色 ref 互比 + 药老（M1 candidate_03）
    yaolao_ref = camp_emb(os.path.join(FACTORY, "yaolao", "candidate_03.wav"))
    refs["yaolao"] = yaolao_ref
    cross = {}
    for a in refs:
        for b in refs:
            if a < b:
                cross["%s_vs_%s" % (a, b)] = round(cos(refs[a], refs[b]), 4)
    print("B cross refs:", json.dumps(cross), flush=True)
    result["cross_refs"] = cross
    with open(os.path.join(FACTORY, "vf003_result.json"), "w", encoding="utf-8") as f:
        json.dump(result, f, ensure_ascii=False, indent=1)
    print("vf003_result.json saved", flush=True)
    print("DONE", flush=True)


if __name__ == "__main__":
    main()
