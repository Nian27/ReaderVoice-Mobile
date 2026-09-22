# -*- coding: utf-8 -*-
"""生成 manifests/models.json + manifests/dataset.json + manifests/prompts.json（G1/G2/G15）。"""
import hashlib
import json
import os

ROOT = os.path.abspath(os.path.join(os.path.dirname(__file__), "..", "..", ".."))
RD = os.path.join(ROOT, "training", "readerdirector")
OUT = os.path.join(RD, "manifests")


def sha(path):
    h = hashlib.sha256()
    with open(path, "rb") as f:
        for chunk in iter(lambda: f.read(1 << 16), b""):
            h.update(chunk)
    return h.hexdigest()


os.makedirs(OUT, exist_ok=True)

# dataset.json：从冻结 manifest 转发
dm = json.load(open(os.path.join(RD, "dataset", "TASK080_DATASET_MANIFEST.json"), encoding="utf-8"))
dataset = {
    "dataset_sha256": dm["dataset_sha256"],
    "test_sha256": dm["test_sha256"],
    "locked_test": dm["locked_test"],
    "counts": dm["counts"],
    "by_provenance": dm["by_provenance"],
    "by_task": dm["by_task"],
    "split": dm["split"],
    "note": dm["note"],
}
json.dump(dataset, open(os.path.join(OUT, "dataset.json"), "w", encoding="utf-8"), indent=1, ensure_ascii=False)

# prompts.json：版本 + 内容 hash
prompts = {}
for name in ("speaker", "identity", "voice_state"):
    p = os.path.join(RD, "prompts", f"{name}_v1.txt")
    prompts[f"{name}_v1"] = {"file": f"prompts/{name}_v1.txt", "sha256": sha(p)}
json.dump(prompts, open(os.path.join(OUT, "prompts.json"), "w", encoding="utf-8"), indent=1, ensure_ascii=False)

# models.json：revision 锁（来自 model_downloads.json，由 download_models.py 写）
dl = json.load(open(os.path.join(ROOT, "training", "models", "model_downloads.json"), encoding="utf-8"))
env = {}
for k, v in dl.items():
    env[k] = {"repo": v["repo"], "revision": v["revision"], "local_dir": v["local_dir"]}
json.dump(env, open(os.path.join(OUT, "models.json"), "w", encoding="utf-8"), indent=1, ensure_ascii=False)

print("manifests written:", os.listdir(OUT))
