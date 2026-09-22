# TASK040_CRASH_RECOVERY_REPORT.md — Crash 恢复报告（TASK-040 §111-§114）

**日期：2026-08-12**

## 恢复模型（§113/§114）

```text
BuildSession{session_id, artifact_type, revision_id, state(RUNNING/COMPLETED/FAILED_INTERRUPTED), heartbeat, error}
启动恢复：BUILDING revision 且无 RUNNING session → FAILED_INTERRUPTED；旧 ACTIVE 不变
```

## 测试结果（G13）

| 场景 | 结果 |
|---|---|
| R1 ACTIVE、R2 BUILDING 无 session → 恢复 | R2=FAILED_INTERRUPTED，R1 仍 ACTIVE ✅ |
| R3 BUILDING + RUNNING session → 不恢复 | R3 保持 BUILDING（可继续/超时处理留 Job Scheduler）✅ |
| Promotion 中途异常（模拟 50% 失败） | 回滚，R1 ACTIVE，R2=FAILED，head 不变 ✅ |
| 进程重启 reopen | 数据一致 + integrity PASS ✅ |

## 说明

- TASK-040 只负责 **unfinished revision build 恢复**，不实现完整 Job Scheduler（§113）；
- 事务边界：每个 stage（source/lines/structure/paragraphs）独立事务 + promotion 事务——失败不会出现新旧各半（§7）；
- 幂等（§86）：重跑同 input_hash → 复用已有 revision，不会产生重复版本。
