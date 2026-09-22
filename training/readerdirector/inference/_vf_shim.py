# -*- coding: utf-8 -*-
"""torchaudio.load → soundfile shim（torchcodec 不可用环境的兼容层）。"""
import io

import numpy as np
import soundfile as sf
import torch
import torchaudio
import torchaudio.functional as taf


def _sf_load(wav, backend=None, **kwargs):
    if isinstance(wav, (bytes, bytearray)):
        data, sr = sf.read(io.BytesIO(bytes(wav)), dtype="float32")
    else:
        data, sr = sf.read(wav, dtype="float32")
    w = torch.from_numpy(data)
    if w.ndim == 1:
        w = w.unsqueeze(0)
    else:
        w = w.t()
    return w, sr


torchaudio.load = _sf_load
