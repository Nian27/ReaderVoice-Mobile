# TASK010_ENCODING_REPORT.md — 编码检测实验报告（E1）

**日期：2026-08-12** | 代码：`parser/src/main/kotlin/.../EncodingDetector.kt` | fixtures：`tests/fixtures/txt/`（15 个，全部自制/合成）

## 1. 检测管线（冻结）

```text
BOM（EF BB BF / FF FE / FE FF）
→ 空文件（UTF-8 0.5 置信）
→ 纯 ASCII（全字节 <0x80 → UTF-8 1.0，"ascii-subset"）   ← 编码交集，防 AMBIGUOUS 误报
→ strict UTF-8 试解（手工解码器：拒绝 overlong/surrogate/>U+10FFFF/截断）
→ UTF-16 heuristics（NUL 奇偶位置偏斜 >30% → 严格解码验证）
→ GB18030 试解（1/2/4 字节序列边界自判 + JDK 单序列解码）
→ 评分：UTF8 = 0.5+hanRatio；UTF16 = 0.5+han×0.4+(1-control)×0.1；
        GB18030 = 0.4+han×0.5+(1-control)×0.1
→ confidence = best×0.6 + margin×0.4；margin<0.12 或 conf<0.55 → 低置信（AMBIGUOUS）
```

## 2. 声称规则（GBK vs GB18030）

- 字节流含 GB18030 特有 4 字节序列（`0x81-0xFE 0x30-0x39 0x81-0xFE 0x30-0x39`）→ 声称 **GB18030**；
- 否则 → 声称 **GBK**（更具体；GBK 是 GB18030 子集，避免 decoder 命名造成错误声称）。

## 3. Fixture 检测结果（36 测试全绿）

| fixture | 期望 | 实际 | 方法 |
|---|---|---|---|
| utf8_lf.txt | UTF-8 | UTF-8 conf≥0.8 | strict-utf8 |
| utf8_bom_crlf.txt | UTF-8 BOM | UTF-8 BOM 1.0（bom=3） | BOM |
| gb18030.txt（含 4 字节序列） | GB18030 | GB18030 | scoring |
| 纯 GBK（无双字节 4 序列，测试内构造） | GBK | GBK | scoring |
| utf16le.txt / utf16be.txt | UTF-16LE/BE | ✓ | BOM |
| eof_no_newline / mixed / fullwidth / very_long / emoji / one_line | UTF-8 | ✓ | strict-utf8 |
| empty.txt | UTF-8 低置信 | UTF-8 0.5 | empty |
| malformed_utf8.bin | 无候选 | conf 0.0 → UNSUPPORTED_ENCODING | 全失败 |
| binary_nul.bin（NUL 密集） | 合法 ASCII + 拒收层 | detector=UTF-8；pipeline=CORRUPT_TEXT | ascii-subset + non-text 拒收 |

## 4. 实验发现（E1 教训）

1. **纯 ASCII 是编码交集**：UTF-8 与 GBK 都能"完美解码"纯 ASCII——只靠"decode 不报错"会误报 AMBIGUOUS（实测 mixed_newlines 曾 conf=0.355）。解法：全字节 <0x80 直接 UTF-8 1.0（ascii-subset 分支）。
2. **"decode 不报错"≠可靠判据**：strict UTF-8 必须拒绝 overlong（C0/C1 前缀）与 surrogate 编码——Java 默认 UTF-8 decoder（REPLACE 模式）会静默吞掉，本项目手工解码器保证严格。
3. **非文本拒收必须在置信度判定之前**：binary 文件可能被"勉强解码"出低分候选 → 先解码 + control/NUL 统计（>1/16 → CORRUPT_TEXT），再判 AMBIGUOUS。
4. **GB18030 序列边界自判 + JDK 内容映射**：偏移精度由边界判定保证，内容正确性由 JDK 保证，两者解耦。

## 5. 遗留

- 评分权重（0.5/0.4/0.1）为初始工程值，未在更大样本集校准——TASK-010 后如遇真实误检，用 Gold Set 校准并更新本报告。
- UTF-16 无 BOM 的检测依赖 NUL 偏斜启发式（≥30% 阈值），纯 BMP 中文 UTF-16 无 BOM 文件（NUL 少）可能漏检 → 记为 Open Issue。
