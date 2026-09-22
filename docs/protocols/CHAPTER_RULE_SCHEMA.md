# CHAPTER_RULE_SCHEMA.md — 章节规则模型（TASK-020 §6/§7/§8/§9）

**日期：2026-08-12** | 由 LegacyChapterRuleAdapter 产出；用户自定义规则走同一模型。

## ChapterRule（冻结字段）

```text
rule_id            新系统规则 ID（legacy-N 或用户自定义）
legacy_rule_id     原 txtTocRule.json 的 id（-1..-100，可追溯）
name
regex              原样保留（Legacy 不改写）
family             STANDARD_ZH / ZH_CHAPTER_WORD / ARABIC_PREFIX / HASH_NUMBER /
                    PURE_NUMBER / SPECIAL_TITLE / VOLUME / ENGLISH_CHAPTER / CUSTOM
target             CHAPTER / VOLUME / PREFACE / AFTERWORD / EXTRA / INTERLUDE /
                    TOC_ENTRY / UNKNOWN_STRUCTURAL
priority           legacy serialNumber（排序键 0..99；越大越后）
enabled
scope              GLOBAL / BOOK / SERIES
confidence_base    0.2(EXTREME) / 0.4(HIGH) / 0.6(MEDIUM) / 0.7(LOW)
serial_parser / title_parser
legacy_replacement / legacy_serial_number
positive_examples / negative_examples
regex_engine       TRUSTED_LEGACY / AUDITED_JAVA_PATTERN / REJECTED
regex_risk         LOW / MEDIUM / HIGH / EXTREME
user_locked
```

## 优先级（仅影响候选/冲突评分，不允许危险 Regex 绕过安全检查）

```text
USER_BOOK_RULE > USER_GLOBAL_RULE > LEGACY_ENABLED_RULE > AUTO_DISCOVERED_RULE
```

## 特殊标题词表（target 归类，不粗暴转 numbered chapter）

```text
序章/楔子/正文/终章/尾声/后记/番外(一..)/附录/后日谈/前传/幕间 → PREFACE/AFTERWORD/EXTRA/INTERLUDE
```

## 规则来源与许可

- 26 条 legacy 规则：gedoor/Legado（GPL-3.0），`parser/src/main/resources/legacy/` + NOTICE；
- 用户自定义规则：必须过 RegexSafetyAnalyzer + RegexCompatibilityAnalyzer + 行长度上限（REGEX_SAFETY_POLICY）。
