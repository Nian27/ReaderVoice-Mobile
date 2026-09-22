# TXT_SOURCE_SCHEMA.md — 不可变 TXT 导入层 Schema（TASK-010 冻结）

**日期：2026-08-12** | 对应 v5 Appendix L.1-L.3；Room 落地在 TASK-040，本文件为字段级冻结。

## 1. 导入状态机（等价原子语义）

```text
NEW → COPYING → HASHING → DECODING → INDEXING_LINES → IMPORTED
任何失败 → FAILED_IMPORT（error_code + error_message）
```

进程中断恢复规则：`incoming/<bookId>.tmp` 无完成状态 → 清理重来；已完成 IMPORTED 的 sha256 → EXACT_DUPLICATE。**禁止 DB=IMPORTED 但文件半截**。

## 2. Book（v5 L.1 对齐）

```text
book_id            UUID/独立生成（book-<sha256前12>）
source_file_id
title?             （TASK-020 填充）
series_id?
source_format      = TXT
source_hash        sha256（重复书检测的唯一身份，不用文件名）
source_size
charset            detected_charset
charset_confidence
charset_user_locked
imported_at
last_opened_at?
lifecycle_state    v5 §5（TASK-150 落地）
```

## 3. SourceFile（v5 L.2 对齐）

```text
source_file_id
book_id
original_uri       用户来源（SAF URI/路径，仅记录）
local_path         不可变副本（books/<bookId>.txt，原子 rename 进入）
sha256
byte_size
import_time
parent_source_file_id?   revised edition 预留（本任务只记录关系，不做 diff）
```

## 4. EncodingResult（TASK-010 §10）

```text
detected_charset     UTF-8 / UTF-8 BOM / GBK / GB18030 / UTF-16LE / UTF-16BE
confidence           0..1
detection_method     BOM / ascii-subset / strict-utf8 / scoring / user-override
bom_bytes            源文件前 N 字节（属于源字节，不属于第一行正文）
user_locked
user_override_charset?
alternatives[]       低置信候选项（供 UI 预览选择）
```

声称规则（冻结）：**GBK 是 GB18030 子集**——字节流含 GB18030 特有 4 字节序列才声称 GB18030，否则声称 GBK；纯 ASCII（全字节 <0x80）声称 UTF-8 confidence 1.0（编码交集，GBK/UTF-16 声称无意义）。

## 5. PhysicalLine（v5 L.3 对齐，字段级冻结）

```text
line_id            （TASK-040 Room 分配；本任务为 0 占位）
book_id
line_no            1-based
byte_start          行文本首字节（不含 BOM/换行）
byte_end            行文本末字节后（不含换行）
char_start          code point 区间起点（不含 BOM，换行不占位）
char_end            code point 区间终点
raw_text            存储策略见 §6（Option B：不落库，lazy read）
leading_ascii_space
leading_fullwidth_space
leading_tab
trailing_space      行尾 ASCII 空格数（U+3000 属 leading 统计，不算 trailing）
char_count          code point 数
han_count           UnicodeScript.HAN
is_blank
newline_kind        LF / CRLF / CR / NONE
```

## 6. Raw-text 存储策略（冻结：Option B）

benchmark（3.3M 字符 / 116,472 行，SQLite 实测）：

| 指标 | A：raw_text 全存 | B：offsets + lazy read |
|---|---|---|
| DB size | 12.29 MB | **2.70 MB**（-78%） |
| import | 229 ms | **99 ms**（-57%） |
| query 1000 行 | 60.0 ms | 61.3 ms（+2%，可忽略） |

**决策：Option B**——DB 只存 offsets + metadata，文本从不可变源 lazy read（RandomAccessFile + 按需解码）。实现抽象保留切换能力（TASK-040 Room 落地时沿用；可加短行内存缓存）。

## 7. 错误枚举（结构化，禁止只存 Exception.toString()）

```text
IO_ERROR / PERMISSION_ERROR / UNSUPPORTED_ENCODING / AMBIGUOUS_ENCODING
/ CORRUPT_TEXT / OUT_OF_SPACE / HASH_ERROR / INDEX_ERROR / UNKNOWN
```

- 非文本拒收：NUL/control 比例 > 1/16 → CORRUPT_TEXT（扩展名 .txt ≠ 文本）。
- 低置信（<0.55）非锁定 → AMBIGUOUS_ENCODING（backend API 支持预览/选择/重解析）。
- 用户 override → 新 decode revision，**raw 文件永不修改**。

## 8. 边界语义（冻结）

- EOF 无换行：最后一行仍是有效 PhysicalLine（物理行数 = 解码逻辑行记录数，不是 wc -l）。
- BOM 属于源文件字节、不属于第一行正文字符；byte 映射从 BOM 后开始，char 映射 BOM 不占位。
- 换行支持 LF/CRLF/CR/mixed；CRLF 作为一个 newline span，\r 不进入行文本。
- 空文件 = 0 行（允许导入）。
- 超长单行不拆（TASK-030 处理），只记录。
