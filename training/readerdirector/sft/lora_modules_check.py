# G6 Gate：打印 LoRA trainable modules，验证仅语言模型路径
import sys
sys.path.insert(0, "E:/AndroidStudioProjects/ReaderVoiceMobile/training/envs/task080/.venv/Lib/site-packages")
from transformers import AutoModelForCausalLM, AutoTokenizer
from peft import LoraConfig, get_peft_model
import torch

model_path = "E:/AndroidStudioProjects/ReaderVoiceMobile/training/models/qwen3.5-0.8b-base"
tok = AutoTokenizer.from_pretrained(model_path, trust_remote_code=True)
model = AutoModelForCausalLM.from_pretrained(model_path, trust_remote_code=True, torch_dtype=torch.bfloat16)

for tm in ("all-linear",):
    cfg = LoraConfig(r=16, lora_alpha=32, target_modules=tm)
    m = get_peft_model(model, cfg)
    names = [n for n, p in m.named_parameters() if p.requires_grad]
    bad = [n for n in names if any(k in n for k in ("visual", "vision", "aligner", "image", "patch"))]
    print(f"target_modules={tm}: trainable={len(names)}")
    for n in names[:8]:
        print("  ", n)
    print("  visual/vision/aligner in LoRA:", bad[:5] if bad else "NONE")
    if bad:
        print("  -> G6 FAIL: 视觉路径进入 LoRA")
    else:
        print("  -> G6 PASS: 仅语言模型路径")
