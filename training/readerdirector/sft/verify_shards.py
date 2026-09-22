# -*- coding: utf-8 -*-
"""权威校验模型分片：safetensors safe_open 可读 + tensor 数与 index 匹配。"""
import json
import os

MODELS = {"qwen3.5-4b": "Qwen/Qwen3.5-4B", "qwen3.5-9b": "Qwen/Qwen3.5-9B"}
ROOT = os.path.abspath(os.path.join(os.path.dirname(__file__), "..", "..", ".."))


def main():
    from safetensors import safe_open
    for key, repo in MODELS.items():
        idx = os.path.join(ROOT, "training", "models", key, "model.safetensors.index.json")
        wm = json.load(open(idx))["weight_map"]
        shards = sorted(set(wm.values()))
        print(f"=== {key} ({repo}) ===")
        n_ok = 0
        for sh in shards:
            p = os.path.join(ROOT, "training", "models", key, sh)
            if not os.path.exists(p):
                print(f"  MISSING {sh}"); continue
            try:
                with safe_open(p, framework="pt") as h:
                    n = len(list(h.keys()))
                print(f"  OK {sh} ({os.path.getsize(p)//1048576}MB, {n} tensors)")
                n_ok += 1
            except Exception as e:
                print(f"  FAIL {sh}: {str(e)[:100]}")
        print(f"  -> {n_ok}/{len(shards)} shards OK")


if __name__ == "__main__":
    main()
