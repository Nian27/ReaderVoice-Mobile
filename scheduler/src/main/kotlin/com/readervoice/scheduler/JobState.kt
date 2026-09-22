package com.readervoice.scheduler

/**
 * GenerationJob 生命周期状态（MOBILE-000C 冻结，2026-08-21）。
 *
 * UNPLANNED  — RenderUnit 存在但尚无 job（初始态，未持久化）
 * PLANNED    — job 已创建（cache miss 或 STALE 重规划）
 * QUEUED     — 等待 worker 认领（含退避到期）
 * GENERATING — 渲染中（协作式取消）
 * READY      — AudioAsset 可用
 * FAILED     — 重试耗尽，终态（需显式 re-plan）
 * STALE      — 输入失效（文本/档案/指令/速度/模型版本），随 plan() 以新 cache_key 重建
 */
enum class JobState { UNPLANNED, PLANNED, QUEUED, GENERATING, READY, FAILED, STALE }
