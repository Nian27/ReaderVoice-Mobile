#!/bin/bash
# TASK-095B.3-TRAIN — 0.8B MENTION task（continual SFT）
# checkpoint-394 继续（One ReaderDirector Multi-task LoRA），lr 5e-5 / 1 epoch（防灾难性遗忘）
# MENTION 占 ~45%，旧任务 replay 55%（sft_v2_mention.jsonl 已混合）
set -e
cd "$(dirname "$0")/.."
PY=../envs/task080/.venv/Scripts/python.exe
SFT_PY=../envs/task080/.venv/Lib/site-packages/swift/cli/sft.py
CKPT="runs/task090/cardinality_r8_v2/v1-20260813-114010/checkpoint-394"
OUT="runs/task095/mention_r8_v2"

if [ ! -f "dataset/sft_v2_mention_v2.jsonl" ]; then
  echo "sft_v2_mention_v2.jsonl missing — run 095b3_to_swift.py first"
  exit 1
fi

$PY "$SFT_PY" \
  --model "E:/AndroidStudioProjects/ReaderVoiceMobile/training/models/qwen3.5-0.8b-base" \
  --adapters "$CKPT" \
  --model_type qwen3_5 \
  --dataset "dataset/sft_v2_mention_v2.jsonl" \
  --tuner_type lora --lora_rank 8 --lora_alpha 16 --target_modules all-linear \
  --lora_dtype bfloat16 --torch_dtype bfloat16 \
  --learning_rate 5e-5 --max_length 1024 --packing false \
  --per_device_train_batch_size 2 --gradient_accumulation_steps 8 \
  --gradient_checkpointing --warmup_ratio 0.03 \
  --num_train_epochs 1 --seed 42 \
  --save_steps 100 --output_dir "$OUT" --logging_steps 20 2>&1 | tee "runs/task095/mention_r8_v2_train.log"