# -*- coding: utf-8 -*-
"""M0.6-P2 self-consistency v2: CAM++ embeddings, no frontend import."""
import json
import os
import sys

import numpy as np
import torch
import torchaudio
import torchaudio.compliance.kaldi as kaldi
import torchaudio.functional as taf
import soundfile as sf
import onnxruntime as ort

CAND = r"E:\AndroidStudioProjects\ReaderVoiceMobile\training\readerdirector\runs\voice_state\voice_factory\yaolao"
CAMPP = r"E:\AndroidStudioProjects\cosyvoice3-distill-lab\Fun-CosyVoice3-0.5B-2512-RL\campplus.onnx"


def load_wav16k(path):
    data, sr = sf.read(path, dtype="float32")
    wav = torch.from_numpy(data).unsqueeze(0)
    if sr != 16000:
        wav = taf.resample(wav, sr, 16000)
    return wav  # (1, n)


def extract(wav_path):
    speech = load_wav16k(wav_path)
    feat = kaldi.fbank(speech, num_mel_bins=80, dither=0, sample_frequency=16000)
    feat = feat - feat.mean(dim=0, keepdim=True)
    emb = sess.run(None, {sess.get_inputs()[0].name: feat.unsqueeze(dim=0).cpu().numpy()})[0].flatten()
    return emb


sess = ort.InferenceSession(CAMPP, providers=["CPUExecutionProvider"])
paths = [os.path.join(CAND, "candidate_%02d.wav" % i) for i in (1, 2, 3)]
embs = [extract(p) for p in paths]
print("embedding dims:", [e.shape for e in embs])
cos = {}
for i in range(3):
    for j in range(i + 1, 3):
        c = float(np.dot(embs[i], embs[j]) / (np.linalg.norm(embs[i]) * np.linalg.norm(embs[j])))
        cos["%d%d" % (i + 1, j + 1)] = round(c, 4)
        print("cos(cand_%d, cand_%d) = %.4f" % (i + 1, j + 1, c))
with open(os.path.join(CAND, "campp_embeddings.json"), "w", encoding="utf-8") as f:
    json.dump({"cosine": cos, "dims": [int(e.shape[0]) for e in embs]}, f, ensure_ascii=False, indent=1)
print("saved")
