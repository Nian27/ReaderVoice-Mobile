#!/bin/bash
# TASK-100 Teacher 标注 watchdog：进程死亡/系统重启后自动 resume 续跑，输出满 target 行即停。
# 用法: bash inference/teacher_watchdog.sh <pool> <target_lines> <model_name> [--now]
#   pool        dataset/ 下 pool 文件名（不带 .jsonl），或 --pool_path 绝对路径
#   target     输出文件满多少行算完成
#   model_name 模型名（决定 provenance 与输出文件名；qwen3.5-4b / qwen3.5-9b）
#   --now      立即开始（否则先 sleep 45 等系统稳定）
set -u
cd "$(dirname "$0")/.."
POOL="${1:?pool required}"
TARGET="${2:?target lines required}"
MODEL_NAME="${3:?model name required}"
PY="../envs/task080/.venv/Scripts/python.exe"
OUT="../readerdirector/runs/task100/teacher_${MODEL_NAME}_${POOL}.jsonl"
LOG="../readerdirector/runs/task090/teacher_${MODEL_NAME}_${POOL}_watchdog.log"
MODEL_DIR="E:/AndroidStudioProjects/ReaderVoiceMobile/training/models/${MODEL_NAME}"
CKPT="E:/AndroidStudioProjects/ReaderVoiceMobile/training/readerdirector/runs/task090/cardinality_r8_v2/v1-20260813-114010/checkpoint-394"

if [ "${4:-}" != "--now" ]; then
  echo "[watchdog] $(date) boot grace 45s..." >> "$LOG"
  sleep 45
fi

fail=0
while :; do
  n=$(wc -l < "$OUT" 2>/dev/null || echo 0)
  if [ "$n" -ge "$TARGET" ]; then
    echo "[watchdog] $(date) DONE n=$n" >> "$LOG"; break
  fi
  echo "[watchdog] $(date) start resume from n=$n (fail=$fail)" >> "$LOG"
  "$PY" inference/teacher_label.py --model_dir "$MODEL_DIR" --model_name "$MODEL_NAME" \
    --pool "$POOL" --perm \
    --student_checkpoint "$CKPT" --prompt_version v2 --resume >> "$LOG" 2>&1
  rc=$?
  n=$(wc -l < "$OUT" 2>/dev/null || echo 0)
  if [ "$n" -ge "$TARGET" ]; then
    echo "[watchdog] $(date) DONE n=$n" >> "$LOG"; break
  fi
  fail=$((fail + 1))
  echo "[watchdog] $(date) exited rc=$rc at n=$n" >> "$LOG"
  if [ "$fail" -ge 5 ]; then
    echo "[watchdog] $(date) giving up after 5 failures" >> "$LOG"; break
  fi
  sleep 30
done
