# app-android/ AGENTS.md

Android 应用层：播放/调度/存储/UI。**播放连续性第一**（Playback P0）。

## 不变量

```text
1. 当前播放窗口永远优先：Playback Frontier <= Acoustic Frontier <= Semantic Frontier，调度目标是维持安全距离，不是后台多算
2. 已经开始播放的 Paragraph revision = COMMITTED，后台热结果不得替换；新分析只作用未播段
3. Job 幂等持久化：job_id/type/target_id/input_hash/generation_epoch/state/attempt/lease_until；被杀后 lease 超时恢复 PENDING；音频 .tmp → checksum → atomic rename → DB DONE
4. 跳章/切书：generation_epoch++，旧 queued 取消、旧 in-flight 标 stale，结果即使算完也 discard
5. Room 非破坏迁移：正式版禁止 fallbackToDestructiveMigration；db_schema/narration_ir/voicepack/director_model/tts_model/frontend 版本分开管理
6. 书签存 segment_id + intra_segment_offset，不存 position_ms（重生成会变时长）
7. 模型切换 staging→smoke→active，旧模型在新模型验证前不删，保留 rollback 窗口；同一章尽量固定 synthesis epoch（不半章换声）
8. warmup 用代表性小输入，不 touch 全部权重页；UI 不冻结，已有音频时播放器继续工作
9. 8GB 模式：Director batch → 释放/降驻留 → TTS batch；ASR 仅 Enrollment/QC 按需，不常驻
10. 多书：Current Book=1 + Prewarm≤1，其余 idle；未激活书无 LLM/TTS；切书立即旧 speculative stale
```

## 运行时策略

- 音频水位：Critical <60s（停 Director、全资源 TTS）/ Low <180s / Healthy >600s（降 TTS 频率防发热）。
- ThermalController：高温+buffer 足 → 停后台 AI；高温+buffer 低 → 只保 P0；暂停播放 → 停重推理。
- 锁屏承诺：只保证"当前听书窗口可靠"，远期预生成 best-effort、checkpointable，不承诺锁屏无限跑全库。
- TTFA 为一级 KPI（p50/p95 真机测）；Bootstrap Render Path：Fast Paragraph Recovery → Rule-only Semantic → provisional voice → 短 RenderUnit → TTS → PLAY，后台再跑完整 Director。

## 验收

v5 Appendix J 场景（25 步）+ Final Gate：无 OOM / 无 buffer underrun / 无 native crash / 角色声音不乱跳 / 60min 连续播放 / 飞行模式。
