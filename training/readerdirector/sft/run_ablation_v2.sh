#!/bin/bash
# Step 4 v2: rank 8/16/32（SFT v1 最终版：id-echo + voice 均衡）
set -e
cd E:/AndroidStudioProjects/ReaderVoiceMobile
PY=training/envs/task080/.venv/Scripts/swift.exe
for spec in "8 16" "16 32" "32 64"; do
  set -- $spec
  R=$1; A=$2
  echo "=== rank=$R alpha=$A (v2) ==="
  $PY sft --model training/models/qwen3.5-0.8b-base --model_type qwen3_5 \
    --dataset training/readerdirector/dataset/sft_v1.jsonl \
    --tuner_type lora --lora_rank $R --lora_alpha $A --target_modules all-linear --lora_dtype bfloat16 \
    --torch_dtype bfloat16 --learning_rate 1e-4 --max_length 1024 --packing false \
    --per_device_train_batch_size 2 --gradient_accumulation_steps 8 --gradient_checkpointing \
    --warmup_ratio 0.03 --num_train_epochs 1 --seed 42 \
    --output_dir training/readerdirector/runs/task090/ablation_v2_r$R --logging_steps 20 2>&1 | tail -5
done
echo "ABLATION_V2_ALL_DONE"
