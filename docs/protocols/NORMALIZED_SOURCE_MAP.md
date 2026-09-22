# NORMALIZED_SOURCE_MAP.md — 归一化文本↔原文映射（TASK-030 §45-§48/§84/§85）

**日期：2026-08-12**

## 原则

- **构建时同步生成，禁止事后 substring 反推**（§46：TASK-010 已证明 rawText 无法可靠反推 byte offset）。
- 表示：**piecewise spans**（§47），不逐字符存 Long。

## NormalizedSpan（§47 冻结）

```text
normalized_start / normalized_end     归一化文本区间 [start,end)
source_line_no / source_codepoint_start / source_codepoint_end   原文定位
transform_type                        IDENTITY / REMOVED_NEWLINE / INSERTED_SPACE / REMOVED_INDENT
synthetic                             仅 INSERTED_SPACE 为 true（§48：不伪造 byte offset）
```

## 变换语义（§48/§84/§85）

```text
IDENTITY          行内字符原样保留
REMOVED_NEWLINE   hard-wrap 行间连接（源 = 当前行 body 区间）
INSERTED_SPACE    JoinPolicy 插入的英文/混合连接空格——source 定位为 null（synthetic=true）
REMOVED_INDENT    段首全角缩进（仅段首行；记录 transform 不丢映射）
```

## JoinPolicy（§22-§27）

```text
HAN+HAN     → ""（"张三看着"+"李雪。"→"张三看着李雪。"）
ASCII+ASCII → " "（"This is a long"+"sentence."）
HAN+ASCII   → " "（"使用 Transformer"、"Python 语言"）
前行尾已有空格 → 不重复加
```

## Round-trip 契约（G7/G8）

- 非合成 normalized 字符 → source (line, codepoint) 100% 可定位；
- synthetic 空格 source 定位必须为 null——禁止伪造 offset（G8）；
- 原始 whitespace 永远保留在 source（用户可查看原文，§27/§85）。

## 兼容性

- 段落 SourceMap 随 ParagraphRevision 版本化；SourceRevision 更换 → 新段落 namespace（§96/§97，跨版本对齐留未来）。
