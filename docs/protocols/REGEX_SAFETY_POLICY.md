# REGEX_SAFETY_POLICY.md — 章节正则安全策略（TASK-020 §5）

**日期：2026-08-12** | 依据 ADR-017（Hybrid 策略，兼容优先）。

## 1. 威胁模型

- **ReDoS / catastrophic backtracking**：嵌套量词 `(a+)+`、`(.+)+`、`(.*)+`、歧义重复交替可在病态输入上指数回溯。Java/Kotlin `Pattern` 无内建 timeout；`Future.cancel()` 不能可靠终止正在回溯的 match。
- **空/过宽 regex**：-100 兜底规则 `rule=""` 匹配任意位置（已审计发现，禁止启用）。
- **超长行**：Chapter title 正常不会超过一页；病态超长行放大回溯成本。

## 2. Hybrid 策略（冻结）

```text
Legacy 26 条（已静态审计，CHAPTER_RULE_AUDIT.md）
  → TRUSTED_LEGACY：
      静态审计 ✓（feature/风险逐条记录）
      + 回归测试（26 条 example → fixture，G1）
      + 输入长度上限（>1024 字符行仅跑白名单规则）
用户自定义规则（未来 UI 导入）
  → compile validation（PatternSyntaxException 拒绝）
  → feature audit（RegexCompatibilityAnalyzer）
  → danger pattern check（RegexSafetyAnalyzer，危险 → 拒绝/隔离）
  → line length policy（>1024 字符行默认不执行用户复杂规则）
```

## 3. Danger Pattern 检测（RegexSafetyAnalyzer）

至少检测并拒绝/隔离：

```text
(a+)+         嵌套量词
(.+)+         （捕获组+量词）嵌套
(.*)+
(?:...)+)+    任意嵌套 quantified group（深度 ≥2）
[a-z]*[a-z]*  歧义重复（等价类重叠且无锚定，启发式）
(?:ab|a)+     歧义重复交替
```

检测方法：对 regex 做括号/量词栈扫描，统计"组内直接嵌套量词且外层也带量词"的结构；不尝试证明无回溯（不可判定），只拦截明显模式 + 长度上限兜底。

## 4. RE2/J 兼容性（实测结论）

- 26 条 Legacy：**19/26 含 lookaround**（lookbehind ×12 / lookahead ×13）→ RE2/J 不支持，**不得直接替换**（会破坏用户原规则）。
- 7 条纯锚定（-8/-11/-13/-18/-21/-22/-100）RE2/J 可移植。
- 未来：RE2-compatible 规则 → safe engine（RE2/J）；Legacy incompatible → audited Java Pattern。TASK-020 V1 全走 audited Java Pattern + 长度上限。

## 5. 行长度上限（§5.4）

- 默认阈值：**1024 字符**（Gold 校准后可调）。
- >1024 行：只运行 TRUSTED_LEGACY 白名单中的"明确安全、锚定、低复杂度"规则（-2/-8/-9/-11/-12/-17/-21/-22/-14 等锚定明确者），或直接降低候选概率。
- 白名单由静态审计 + 回归决定，不随用户规则扩张。

## 6. 测试要求（G9）

- pathological fixture：synthetic 病态 regex（`(a+)+` 等）+ 超长重复输入，验证 validation **拒绝**（不实际长时间运行挂起证明）。
- 空 regex 拒绝测试。
- 全部 26 条 Legacy 在 3M+ 结构 fixture 上运行无卡死（G10 兜底）。
