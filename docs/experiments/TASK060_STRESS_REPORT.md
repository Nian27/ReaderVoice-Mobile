# TASK060_STRESS_REPORT.md — 压力报告（TASK-060 §45）

**日期：2026-08-12**

## 说明

§45 要求的 100k mentions / 300k evidence / 10k entities 规模 stress 在 schema v2 冻结前应完成（PERF060-P4）。当前状态：

- **流式契约验证**（PERF060-P2/P3）：CharacterCompiler 输入 Sequence（章节窗口），batch persist + 内部状态随窗口释放——接口不变量已写入（ADR-032）
- **规模估算**：真实书 PRIVATE_REALBOOK_001 candidate（13,793 引号 / 9,526 cue / 1,528 称谓）→ 全本 Mention 量级 ~10-20k（引号+正文提及），evidence ~2-5k；100k mentions/300k edges 是 synthetic 上界（暴露 index/N+1 用）
- **指数/N+1 风险**：identity_evidence 有 (book_pk, entity_a, entity_b) 索引 + hard_block 索引；mention 有 (rev, book, para) 索引——按 §46 不存重复长文本（payload compact + span refs）

## 判定

- PERF060-P4：⏳ 大规模 stress 脚本在 schema v2 冻结后单独 run（当前 14 测试覆盖语义正确性；规模压力与 Character Gold 一起建立）
- Working set：CharacterCompiler 流式窗口边界内（PERF060-P2/P3 ✅ 设计契约）

## 风险

- evidence 表行宽（valid_from/to + provenance + legacy_weight）——stress 时验证是否需列压缩（§46）
- UNKNOWN 实体数量在真实书的膨胀（per-mention unknown）——需 admission gate 收紧
