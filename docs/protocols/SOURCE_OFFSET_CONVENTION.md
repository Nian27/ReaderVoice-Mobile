# SOURCE_OFFSET_CONVENTION.md — Source Offset 口径冻结（TASK-010 §18）

**日期：2026-08-12** | 本项目所有派生数据（高亮/纠错/SemanticSegment/训练定位/书签/TTS 对齐）统一遵循本约定。

## 1. 三种 offset 概念（禁止混用）

| 口径 | 单位 | 语义 | 存储 |
|---|---|---|---|
| `byte_offset` | 字节 | 原始文件内绝对字节位置（0-based，**含 BOM 字节**） | ✅ 存储 |
| `unicode_codepoint_offset` | 字符 | 解码字符流中 Unicode code point 序号（0-based；**BOM 不占位**，换行不占位） | ✅ 存储（主口径） |
| `android_utf16_offset` | UTF-16 code unit | Kotlin String index（surrogate pair 占 2） | ❌ 不存储，按需派生 |

**冻结决策（ADR-013）**：存储双轨（byte + codepoint）；UTF-16 一律派生计算，不落库——避免三份冗余且与 Python 训练工具天然对齐。

## 2. 派生公式

```text
codepoint → utf16:
    utf16 = codepoints.take(cp).sumOf { if (it > 0xFFFF) 2 else 1 }   // 或 String.codePointCount 反推
    JVM 便捷实现: str.substring(0, cp).length 不可靠（O(n) 且语义易错）——
    推荐: 对行文本用 codePoints() 前 cp 个求和 charCount

byte ↔ codepoint:
    精确 char→byte 表由 PhysicalLineScanner(collectCharOffsets=true) 在扫描时产出
    （IntArray[totalChars]，第 i 项 = 第 i 个 code point 的起始字节位置）
```

## 3. 陷阱清单（测试覆盖）

1. **emoji / 扩展字符**：U+1F600 = 1 codepoint = 2 UTF-16 units = 4 UTF-8 bytes；三数各不相同。
2. **VS16 等组合字符**：U+FE0F 是独立 codepoint（🀄️ = 2 cp / 3+3 bytes）——"一个 emoji"≠"一个 codepoint"。
3. **GB18030 4 字节序列**：扩展区字符（如 U+20000）1 codepoint = 4 bytes。
4. **BOM**：字节映射含 BOM（文件前 2-3 字节），字符映射不含——`charToByte(0)` 是正文首字符，其 byte = BOM 后偏移。
5. **CRLF**：换行 span 2 字节但 0 字符；行间 byte 区间不连续（差 2），char 区间连续。
6. **EOF 无换行**：最后一行 byteEnd = 文件长度；`wc -l` 少 1 不是 bug。
7. **行号**：line_no 1-based（= 解码逻辑行记录序号）；API 参数统一 1-based。

## 4. 不变性（I4）

- 任何后续处理（normalization/reflow/join/split）不得修改本基准：行 byte/char 区间永久锚定原始文件。
- 用户修正/段落 join-split 只产生新派生层（LogicalParagraph revision），PhysicalLine 索引不变。

## 5. Round-trip 契约

```text
stored byte range → 原始文件切片 → 按行 charset 解码 = 行 raw_text（100% 一致）
stored char range → lineAtCharOffset → 行内第 k 个 code point = 对应文本
charToByte(cp) = 该 code point 起始字节（扫描时精确记录）
```

Gate：随机抽样 round-trip 100% 正确（G5）。
