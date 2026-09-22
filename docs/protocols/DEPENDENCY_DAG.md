# DEPENDENCY_DAG.md — 依赖 DAG 与失效传播契约（TASK-040 §117/§9-§25）

**日期：2026-08-12**

## Stage 依赖图（§9）

```text
SOURCE
  ↓
STRUCTURE
  ↓
PARAGRAPH_RECOVERY
  ↓
future: SEMANTIC → NARRATION → RENDER → AUDIO
```

- **Generic DAG 只管 stage-level revision**（§10：不把 PhysicalLine→Paragraph→Segment→Render 每个 item 都建成 DAG 行——会爆炸）；item 级依赖用 domain 表 FK 表达（§11：paragraph 保存 pr_revision_id、span→physical_line_id）。
- Cycle 拒绝：application-level BFS（SQLite 不保证 DAG，§81）+ 测试（G6）。

## InvalidationEvent（§12-§14）

```text
event_id / book_id / reason_type（SOURCE_CHANGED/ENCODING_OVERRIDE/CHAPTER_METADATA/CHAPTER_ANCHOR/PARAGRAPH_JOIN/...）
origin_type / origin_id / scope_type / scope_payload / created_at / processed
Scope：WHOLE_BOOK / WHOLE_SOURCE_REVISION / LINE_RANGE / CHAPTER_RANGE / PARAGRAPH_SET
（预留 SEGMENT_SET / ROLE_SET / RENDER_SET）
```

## 失效传播矩阵（§16-§25 冻结）

| 变更 | 影响 | 不失效 |
|---|---|---|
| SourceRevision 变化（含 encoding override，§17） | SOURCE→STRUCTURE→PARAGRAPH→未来全链 STALE | 无 |
| Chapter 标题元数据（anchor 不变，§18） | 仅结构 metadata revision | **Paragraph 不 stale** |
| Chapter anchor 增删（§19） | 前一 anchor → 后一 anchor 范围 | 其余书 |
| Paragraph Join/Split（§20） | 局部段落 + 下游 semantic/narration/render/audio | PhysicalLine/Chapter detection |
| Layout 算法版本更新（§21） | 整 SourceRevision 的 PARAGRAPH_RECOVERY STALE（V1 保守） | — |
| 未来 Speaker 修正（§22） | Semantic/Narration/Render/Audio | Chapter/Paragraph（不向上污染） |
| 未来 VoiceBinding（§23） | Render/Audio | Speaker/Character/Paragraph |
| 未来发音修正（§24） | Narration/Render/Audio | Character Identity |
| 未来 TTS 模型升级（§25） | Audio | ReaderDirector/NarrationIR（除非 frontend 语义变） |

## 测试（G7/G8/G9）

- Whole source invalidation → 链上全 STALE（§82）
- Scoped（Join P100/P101）→ 局部，STRUCTURE 不变（§83）
- Metadata-only chapter change → Paragraph 仍 VALID（§84）
- Anchor change → 相邻 chapter range 传播（§85）
