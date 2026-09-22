# CHAPTER_RULE_AUDIT.md — Legacy 26 条规则逐条审计（TASK-020 Step 0）

**日期：2026-08-12** | 源：`FUN-legado/app/src/main/assets/defaultData/txtTocRule.json`（gedoor/Legado，GPL-3.0；仓库副本 `parser/src/main/resources/legacy/` + NOTICE）
**执行语义已从 legado 源码确认**（`TextFile.kt: getTocRule 501-528 / replacement 552-573 / TxtTocRuleDao order by serialNumber`）：
- `serialNumber` = **规则排序键**（小者先试），不是章节序号；
- 标题 = **匹配文本原样**（26 条均无 replacement 字段）；
- 规则竞争 = 匹配数量（`csNum ≥ numE×3 且 > 前规则+overRuleCount`，`>70 提前停`；间隔 <100 字计 numE）。

## 1. 总览

| 维度 | 值 |
|---|---|
| 规则总数 | 26（enabled 12 / disabled 14） |
| regex 特征 | lookbehind ×12、lookahead ×13、alternation ×14、全部含 ^/$ 锚点；**0 backref / 0 named group / 0 unicode class** |
| RE2/J 兼容 | **19/26 含 lookaround（RE2/J 不兼容）**；7 条纯锚定可安全移植 |
| 高风险规则 | -5/-6/-7（纯数字）、-25（激进通用）、-100（**空 regex**） |
| 数据风险 | -100 兜底规则 `rule=""`：启用即匹配所有行（禁用状态，禁止无审计启用） |

## 2. 逐条审计表

| id | 状态 | name | regex（完整） | serial | family | target | RE2/J | 风险 |
|---|---|---|---|---|---|---|---|---|
| -1 | ON | 目录(去空白) | `(?<=[　\s])(?:序章\|楔子\|正文(?!完\|结)\|终章\|后记\|尾声\|番外\|第\s{0,4}[\d〇零一二两三四五六七八九十百千万壹贰叁肆伍陆柒捌玖拾佰仟]+?\s{0,4}(?:章\|节(?!课)\|卷\|集(?![合和]))).{0,30}$` | 0 | STANDARD_ZH | CHAPTER | ✗(lookbehind) | 低 |
| -2 | ON | 目录 | `^[ 　\t]{0,4}(?:序章\|楔子\|正文(?!完\|结)\|终章\|后记\|尾声\|番外\|第\s{0,4}[...]+?\s{0,4}(?:章\|节(?!课)\|卷\|集(?![合和])\|部(?![分赛游])\|篇(?!张))).{0,30}$` | 1 | STANDARD_ZH | CHAPTER | ✗(lookahead) | 低 |
| -3 | OFF | 目录(匹配简介) | `(?<=[　\s])(?:(?:内容\|文章)?简介\|文案\|前言\|序章\|...\|回(?![合来事去])\|场(?![和合比电是])\|篇(?!张))).{0,30}$` | 2 | SPECIAL_TITLE | CHAPTER | ✗(lookbehind) | 低 |
| -4 | OFF | 目录(古典、轻小说备用) | `^[ 　\t]{0,4}(?:序章\|...\|回(...)\|场(...)\|话\|篇(?!张))).{0,30}$` | 3 | STANDARD_ZH | CHAPTER | ✗(lookahead) | 低 |
| -5 | OFF | 数字(纯数字标题) | `(?<=[　\s])\d+\.?[ 　\t]{0,4}$` | 4 | PURE_NUMBER | CHAPTER | ✗(lookbehind) | **高**（2026/520/10086 命中） |
| -6 | OFF | 大写数字(纯数字标题) | `(?<=[　\s])[零一二两三四五六七八九十百千万壹贰叁肆伍陆柒捌玖拾佰仟]{1,12}[ 　\t]{0,4}$` | 5 | PURE_NUMBER | CHAPTER | ✗(lookbehind) | **高**（"二零二六年"命中） |
| -7 | OFF | 数字混合(纯数字标题) | `(?<=[　\s])[\d零一二两三四五六七八九十百千万壹贰叁肆伍陆柒捌玖拾佰仟]{1,12}[ 　\t]{0,4}$` | 6 | PURE_NUMBER | CHAPTER | ✗(lookbehind) | **高** |
| -8 | ON | 数字 分隔符 标题名称 | `^\d{1,6}[、. 　].{0,20}$`（实测：`^\d+[、. 　\t]{0,3}.{0,30}$`） | 7 | ARABIC_PREFIX | CHAPTER | ✓ | 低 |
| -9 | ON | 大写数字 分隔符 标题名称 | `^[零一二两三四五六七八九十百千万壹贰叁肆伍陆柒捌玖拾佰仟]{1,8}[、. 　][^。]{0,20}$`（含 `(?!...)` 防御） | 8 | ZH_CHAPTER_WORD | CHAPTER | ✗(lookahead) | 中 |
| -10 | OFF | 数字混合 分隔符 标题名称 | 同 -9 混合字符集 | 9 | ARABIC_PREFIX | CHAPTER | ✗ | 中 |
| -11 | ON | 正文 标题/序号 | `^[ 　\t]{0,4}正文.{0,20}$`（实测：`^[ 　\t]{0,4}正文[^。]{0,30}$`） | 10 | SPECIAL_TITLE | PREFACE | ✓ | 低 |
| -12 | ON | Chapter/Section/Part/Episode 序号 标题 | `^(?:Chapter\|Section\|Part\|Episode|第...章)[ 　]{0,4}\d{1,6}(?!...)[ 　\t]{0,4}.{0,30}$`（含负向断言防御） | 11 | ENGLISH_CHAPTER | CHAPTER | ✗(lookahead) | 低 |
| -13 | OFF | Chapter(去简介) | `^(?:Chapter\|Section\|Part\|Episode)[ 　\t]{0,4}\d{1,6}[ 　\t]{0,4}.{0,30}$` | 12 | ENGLISH_CHAPTER | CHAPTER | ✓ | 低 |
| -14 | ON | 特殊符号 序号 标题 | `(?<=[【［\[])...` 实测：`(?<=[【［\[])\s*第[...]+\s*(?:章\|...).{0,30}$` | 13 | SPECIAL_TITLE | CHAPTER | ✗(lookbehind) | 低 |
| -15 | OFF | 特殊符号 标题(成对) | `(?<=[『「［【])...[』」］】]` | 14 | SPECIAL_TITLE | CHAPTER | ✗ | 低 |
| -16 | ON | 特殊符号 标题(单个) | `(?<=[☆★●○◆◇])...` 等单符号前缀 | 15 | SPECIAL_TITLE | CHAPTER | ✗ | 低 |
| -17 | ON | 章/卷 序号 标题 | `^[ \t　]{0,4}(?:(?:内容\|文章)?简介\|文案\|前言\|序章\|楔子\|正文(?!完\|结)\|终章\|后记\|尾声\|番外\|[卷章][\d零一二两三四五六七八九十百千万壹贰叁肆伍陆柒捌玖拾佰仟]{1,8})[ 　]{0,4}.{0,30}$`（"卷五 开源盛世"） | 16 | STANDARD_ZH* | CHAPTER | ✗(lookahead) | 中（含"正文"词，正文行会误报——Resolver 弱化处理） |

> *审计修正（2026-08-12 实现期）：-17 是**综合词表规则**（词表+`[卷章]`序号），非纯 VOLUME——错误归 VOLUME 会导致同线冲突让 -17 赢过 -2、所有章节行变"卷"。正式 family=STANDARD_ZH；卷识别由 VolumeResolver 独立判据（rawTitle 含"第N卷/卷N"）完成。
| -18 | OFF | 顶格标题 | `^.{1,20}$`（任意 20 字内顶格行——**极宽**） | 17 | SPECIAL_TITLE | CHAPTER | ✓ | **高**（正文短行误报） |
| -19 | OFF | 双标题(前向) | `(?<=[　\s])第[...]章.{0,20}(?=第[...]章)` | 18 | SPECIAL_TITLE | CHAPTER | ✗ | 中 |
| -20 | OFF | 双标题(后向) | `(?<=第[...]章.{0,20})第[...]章` | 19 | SPECIAL_TITLE | CHAPTER | ✗ | 中 |
| -21 | ON | 书名 括号 序号 | `^.{0,10}\(.{0,10}\d+\)$`（标题后数字带括号） | 20 | ARABIC_PREFIX | CHAPTER | ✓ | 低 |
| -22 | ON | 书名 序号 | `^.{0,10}\d{1,6}$`（标题后裸数字） | 21 | ARABIC_PREFIX | CHAPTER | ✓ | 中（行尾数字正文） |
| -23 | OFF | 特定字符 标题 特定符号 | `(?<=={2,4})...(?={2,4})` | 22 | SPECIAL_TITLE | CHAPTER | ✗ | 低 |
| -24 | ON | 字数分割 分节阅读 | `(?<=[ 　\t]{0,4})(?:.{0,15}分[页节章段]阅读[-_ ]\|第\s{0,4}[...]{1,6}\s{0,4}[页节]).{0,30}$` | 23 | SPECIAL_TITLE | EXTRA | ✗(lookbehind) | 中 |
| -25 | OFF | 通用规则 | `(?im)^.{0,6}(?:[引楔]子\|正文(?!完\|结)\|[引序前]言\|[序终]章\|扉页\|[上中下][部篇卷]\|卷首语\|后记\|尾声\|番外\|={2,4}\|第\s{0,4}[...]\s{0,4}(?:章\|节(?!课)\|卷\|页[、 　]\|集(?![合和])\|部(?![分是门落])\|篇(?!张))).{0,40}$\|^.{0,6}[...a-z]{1,8}[、. 　].{0,20}$`（**inline flag (?im)**，最复杂） | 24 | CUSTOM | CHAPTER | ✗ | **高**（激进） |
| -100 | OFF | 默认分章规则 | **`""`（空 regex）** | 99 | CUSTOM | UNKNOWN | ✓ | **极高**（启用即匹配全部行；禁止无审计启用） |

> 注：-8/-9/-11/-12/-14/-17 等规则的 regex 在表中为特征摘要（完整原文见 `parser/src/main/resources/legacy/txtTocRule.json`），id 唯一可追溯。

## 3. 结论与迁移约定

1. **Legacy 原 JSON 不可修改**；迁移通过 `LegacyChapterRuleAdapter` 产出 `ChapterRule`（含 legacy_rule_id 可追溯）。
2. **危险规则默认不参与自动候选**：-5/-6/-7/-18/-25/-100 在无用户显式启用前不进 Resolver 最终确认（PURE_NUMBER 依赖全局序列证据才增强，§25）；-100 空 regex 直接拒绝。
3. **RE2/J 不适用**（19/26 含 lookaround）→ Hybrid 策略（ADR-017）：TRUSTED_LEGACY = 静态审计 + 回归测试 + 行长度上限（>1024 字符仅跑白名单规则）。
4. **标题语义**：title_raw = 匹配文本原样；clean = 去序号/空白（展示用）。
5. **serialNumber 排序键**保留到 ChapterRule.priority（0..99）。
6. 26 条 example 全部转 fixture（`tests/fixtures/chapter/legacy_examples/`），enabled 规则 candidate recall 100% 为 G1。
