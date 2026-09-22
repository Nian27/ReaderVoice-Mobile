#!/bin/bash
# TASK-095B.3 训练 watchdog：进程死亡/重启后自动 resume（ms-swift --resume_from_checkpoint）。
set -u
cd "$(dirname "$0")/.."
PY=../envs/task080/.venv/Scripts/python.exe
SFT_PY=../envs/task080/.venv/Lib/site-packages/swift/cli/sft.py
CKPT="runs/task090/cardinality_r8_v2/v1-20260813-114010/checkpoint-394"
OUT="runs/task095/mention_r8_v2"
LOG="runs/task095/mention_r8_v2_train.log"

fail=0
# 最新 checkpoint（resume 点）：ms-swift 会在 $OUT 下创建 vN-<timestamp>/ 版本子目录，
# 必须递归查找（$OUT/*/checkpoint-* + $OUT/checkpoint-*），并按 step 数字取最新。
find_latest_ckpt() {
  "$PY" -c "
import glob, os
cands = [p for p in glob.glob('$OUT/*/checkpoint-*') + glob.glob('$OUT/checkpoint-*')
         if os.path.isfile(os.path.join(p, 'trainer_state.json'))]
def step(p):
    try:
        return int(p.rstrip('/').split('-')[-1])
    except Exception:
        return -1
best = max(cands, key=step) if cands else ''
# Windows glob 返回反斜杠路径，统一转正斜杠（否则后续 python open() 会转义 \v/\0 等）
print(best.replace('\\', '/') if best else '')
" 2>/dev/null
}
while :; do
  RESUME="$(find_latest_ckpt)"
  # 完成判断：trainer_state 的 epoch == 1.0
  done=0
  if [ -n "$RESUME" ]; then
    done=$("$PY" -c "
import json
try:
    s = json.load(open('$RESUME/trainer_state.json'))
    print(1 if s.get('epoch', 0) >= 1.0 else 0)
except Exception:
    print(0)" 2>/dev/null)
    if [ "$done" = "1" ]; then
      echo "[watchdog] $(date) TRAINING DONE at $RESUME" >> "$LOG"
      break
    fi
  fi
  echo "[watchdog] $(date) start/resume training (resume=$RESUME fail=$fail)" >> "$LOG"
  "$PY" "$SFT_PY" \
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
    ${RESUME:+--resume_from_checkpoint "$RESUME"} \
    --save_steps 100 --output_dir "$OUT" --logging_steps 20 >> "$LOG" 2>&1
  rc=$?
  fail=$((fail + 1))
  echo "[watchdog] $(date) training exited rc=$rc" >> "$LOG"
  if [ "$fail" -ge 5 ]; then
    echo "[watchdog] $(date) giving up after 5 failures" >> "$LOG"
    break
  fi
  sleep 30
done