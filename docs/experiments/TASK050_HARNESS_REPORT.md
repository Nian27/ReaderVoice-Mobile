# TASK050_HARNESS_REPORT.md — v90.7 Offline Harness 报告（TASK-050 S2）

**日期：2026-08-12** | 工具：`tools/v907-harness/`（Node vm sandbox + shims + API stub）

## 架构（§61-§67）

- vm sandbox 内存加载 redacted.js（不修改 raw，不落盘）；注入导出 31 个函数（var + CharacterManager.prototype 双通道）
- **网络 kill switch**：ttsrv.httpPost/httpGet、fetch、XMLHttpRequest → BLOCKED_EXTERNAL_NETWORK（§63/§64）
- java shim：Thread（同步执行）/ CountDownLatch / AtomicInteger（§65 最小实现）
- 内存文件系统：readTxtFile/writeTxtFile（测试可控）；API stub：无 key → 立即失败（§67）

## 测试结果（node --test，7/7 PASS）

| 测试 | 对应 Gate | 结果 |
|---|---|---|
| G4 网络完全阻断 | G4 | ✅ httpPost/fetch/XHR 全抛 BLOCKED |
| G5 跨书污染复现 | G5 | ✅ **角色卡全局共享（书 B 可见书 A 的张明）——L3 确认 Legacy 污染点** |
| G7 merge→split 恢复 | G7 | ✅ 合并备份存在、别名并入、source 移除、split 可执行 |
| G8 固定音色优先级 | G8 | ✅ fixed 拒音龄更新、拒临时换声、解锁后放行——**L3** |
| G9 临时换声不写卡 | G9 | ✅ 状态容器存在、角色卡 voice 不被触碰——**L3** |
| API 失败兜底 | — | ✅ 无 key → failure/blocked 路径 |
| graph 正反边 | — | ✅ 正/负边路径可执行 |

## 四关键问题升级（§135）

| 问题 | 状态 |
|---|---|
| A Cross-book | **CONFIRMED_L3**（Harness 两书复现角色卡污染；图谱按书隔离 L2） |
| B Relationship | L2（关系称谓拦截 70 词表 + 模型关系直通 merge 路径确认；复合审计 L3 待 Harness 深测） |
| C Fixed voice | **CONFIRMED_L3**（优先级链动态复现） |
| D User correction | **Persistent voice-lock correction: FOUND**（setFixedVoice/cancelFixedVoice 落 characterRecords.json 字段）；**Persistent speaker/alias correction: NOT FOUND AFTER TASK-050 AUDIT**（无持久化路径）；**Runtime-only speaker/alias mutation: FOUND**（内存 nameToMainNameMap，重启即失） |

## 安全（§120/§121）

- fixture 全 synthetic（无第三方片段/无真实 key）；外部调用全部记录
- NetworkIsolationTest 独立成测（G4）

## 复现

```bash
node --test tools/v907-harness/tests/
```
