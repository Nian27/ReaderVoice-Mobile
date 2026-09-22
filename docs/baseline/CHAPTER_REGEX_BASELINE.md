# CHAPTER_REGEX_BASELINE.md — 章节 Regex 基线冻结

**更新：2026-08-12（TASK-000）**

## 源资产

- 规则数据：`E:\AndroidStudioProjects\FUN-legado\app\src\main\assets\defaultData\txtTocRule.json`（26 条默认规则）
- 执行实现（参考）：`E:\AndroidStudioProjects\FUN-legado\app\src\main\java\io\legado\app\model\localBook\TextFile.kt`
  - `getChapterList` 89-110 行：按序选规则；
  - 501-528 行：`Pattern.MULTILINE` 匹配 + 标题替换处理 + 正则语法错误捕获。

## 规则 family（txtTocRule.json 的 name 字段）

```text
目录(去空白) / 目录 / 目录(匹配简介) / 目录(古典、轻小说备用)
数字(纯数字标题) / 大写数字(纯数字标题) / 数字混合(纯数字标题)
数字 分隔符 标题名称 / 大写数字 分隔符 标题名称 / 数字混合 分隔符 标题名称
正文 标题/序号 / Chapter/Section/Part/Episode 序号 标题 / Chapter(去简介)
特殊符号 序号 标题 / 特殊符号 标题(成对/单个) / 章/卷 序号 标题
顶格标题 / 双标题(前向/后向) / 书名 括号 序号 / 书名 序号
特定字符 标题 特定符号 / 字数分割 分节阅读 / 通用规则 / 默认分章规则
```

## 迁移约定（TASK-020）

1. 26 条规则整体导入为 `LegacyChapterRulePack`（Kotlin 数据类 + family 标注 + 每规则带正/负示例位），**不重写超级 Regex**；
2. 旧规则只负责 Candidate Generation（v5 §12 Pass A）；最终判断 = 全局 Resolver（序号连续性/规则族稳定性/卷重置/TOC/密度，Pass B）；
3. 中文数字解析独立模块（ChineseNumeralParser：一→1、一百零八→108、壹佰贰拾→120、0012→12），解析失败 serial=null 不强猜；
4. 卷/章层级必须支持（章号按卷重置是正常现象，禁止 sequence checker 误判）；
5. 纯数字规则（PURE_NUMBER）风险最高：不得仅靠 Regex 判真；
6. 网络书源目录解析（Rhino JS 引擎）**不迁移**——只迁移本地 TXT 规则。

## 与 v5 手册对齐

- family 对应 v5 §10 规则族（STANDARD_ZH/ZH_CHAPTER_WORD/ARABIC_PREFIX/HASH_NUMBER/PURE_NUMBER/SPECIAL_TITLE/VOLUME/ENGLISH_CHAPTER/CUSTOM）；
- 指标：Chapter Boundary F1 / TOC False Positive（v5 §25）。
