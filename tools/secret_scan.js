#!/usr/bin/env node
/**
 * ReaderVoice secret scanner
 *
 * 用法:
 *   node tools/secret_scan.js [path]          扫描指定路径（默认仓库根）
 *   node tools/secret_scan.js --self-test     对 synthetic fixture 自测（必须全模式命中）
 *   node tools/secret_scan.js --include-private 连 research/private/ 隔离区一起扫（预期发现 1 个凭据）
 *
 * 规则:
 *   - 不依赖外网；只读文件。
 *   - 默认跳过 .git / node_modules / 大二进制；research/private/ 是"已知隔离区"（故意含凭据、不入 git），默认跳过。
 *   - 任何隔离区外的命中 → exit 1（Gate FAIL）。
 *
 * 设计教训（2026-08-12）:
 *   首轮扫描只匹配双引号长串 + "KEY=" 命名，漏掉单引号 `key: '32hex.suffix'` 智谱格式。
 *   因此本扫描同时覆盖: 单/双引号、供应商特定格式、key 字段赋值、Authorization、URL 内嵌凭据、私钥块。
 */
'use strict';
const fs = require('fs');
const path = require('path');

const ROOT = path.resolve(__dirname, '..');
const ISOLATION_ZONE = path.join(ROOT, 'research', 'private');
const SKIP_DIRS = new Set(['.git', 'node_modules', '.gradle', '.idea', 'build', 'test-fixtures', '.venv', // python 虚拟环境（第三方库自带示例串）
  'training/models', 'training/readerdirector/dataset', 'training/readerdirector/runs']); // TASK-080 起 gitignored 产物目录（真实书文本/模型权重/推理输出，不入库；含合法哈希与随机串）
// 已知分析产物：**精确文件白名单**（禁止扩展为目录通配 tools/v907-*——AST/trace/exporter 可能把 literal 带进产物）。
// 仅允许跳过已证明只含 symbol 名称的具体文件；其余分析产物必须过 Secret Gate。
const KNOWN_ANALYSIS_OUTPUTS = new Set([
  path.join(ROOT, 'docs', 'baseline', 'v907', 'V907_SYMBOL_INDEX.json'),
  path.join(ROOT, 'docs', 'baseline', 'v907', 'V907_STATE_INVENTORY.json'),
  path.join(ROOT, 'docs', 'baseline', 'v907', 'V907_CALL_GRAPH.json'),
  path.join(ROOT, 'tools', 'v907-harness', 'runtime', 'harness.js'), // exportNames 引用 Legacy 函数名
  path.join(ROOT, 'training', 'readerdirector', 'manifests', 'models.json'), // TASK-080 模型 revision（可复现性哈希，非凭据）
  path.join(ROOT, 'training', 'readerdirector', 'manifests', 'dataset.json'), // TASK-080 数据集 book hash（可复现性哈希，非凭据）
  path.join(ROOT, 'training', 'readerdirector', 'manifests', 'prompts.json'), // TASK-080 prompt 内容哈希（可复现性哈希，非凭据）
  path.join(ROOT, 'training', 'readerdirector', 'dataset', 'TASK080_DATASET_MANIFEST.json'), // gitignored（含真实书哈希）；扫描器不随 gitignore，故显式白名单
]);
const SKIP_EXTS = new Set(['.png', '.jpg', '.jpeg', '.gif', '.wav', '.mp3', '.bin', '.so', '.zip', '.apk', '.mnn', '.pt', '.safetensors', '.onnx', '.mtok', '.csv', '.ttf', '.otf']);
const MAX_FILE_BYTES = 8 * 1024 * 1024;

const PATTERNS = [
  { name: 'zhipu-api-key-32hex-dot-suffix', re: /[0-9a-f]{32}\.[A-Za-z0-9_-]{8,}/g, severity: 'CRITICAL' },
  { name: 'private-key-block', re: /-----BEGIN [A-Z ]*PRIVATE KEY-----/g, severity: 'CRITICAL' },
  { name: 'authorization-header', re: /authorization\s*[:=]\s*['"]?\s*(bearer|basic)\s+/gi, severity: 'HIGH' },
  { name: 'url-embedded-credentials', re: /https?:\/\/[^\s\/@'"]{3,}:[^\s\/@'"]{3,}@/g, severity: 'HIGH' },
  { name: 'key-field-assignment', re: /(api[_-]?key|apikey|access[_-]?token|secret|password)\s*[:=]\s*['"][^'"]{8,}['"]/gi, severity: 'HIGH' },
  { name: 'long-hex-string-single-or-double-quoted', re: /['"][0-9a-fA-F]{32,}['"]/g, severity: 'MEDIUM' },
  { name: 'long-random-string-single-or-double-quoted', re: /['"](?=[^'"]*[A-Z])(?=[^'"]*[a-z])(?=[^'"]*[0-9])[A-Za-z0-9_\-\.]{32,}['"]/g, severity: 'MEDIUM' },
];

function walk(dir, out) {
  for (const entry of fs.readdirSync(dir, { withFileTypes: true })) {
    const full = path.join(dir, entry.name);
    if (entry.isDirectory()) {
      const rel = path.relative(ROOT, full).replace(/\\/g, '/');
      if (SKIP_DIRS.has(entry.name) || SKIP_DIRS.has(rel)) continue;
      walk(full, out);
    } else if (entry.isFile()) {
      const ext = path.extname(entry.name).toLowerCase();
      if (SKIP_EXTS.has(ext)) continue;
      try {
        if (fs.statSync(full).size > MAX_FILE_BYTES) continue;
        out.push(full);
      } catch (_) { /* ignore */ }
    }
  }
}

function scanFile(file, includePrivate) {
  if (KNOWN_ANALYSIS_OUTPUTS.has(file)) return [];
  const zone = isInside(file, ISOLATION_ZONE) ? 'ISOLATION' : 'WORKTREE';
  if (zone === 'ISOLATION' && !includePrivate) return [];
  const text = fs.readFileSync(file, 'utf8');
  const findings = [];
  for (const p of PATTERNS) {
    p.re.lastIndex = 0;
    let m;
    while ((m = p.re.exec(text)) !== null) {
      const pre = text.slice(Math.max(0, m.index - 24), m.index);
      // 校验和/哈希值不是凭据
      if (/sha[-_]?256|hash|checksum|digest|md5/i.test(pre)) { p.re.lastIndex = m.index + 1; continue; }
      // 包名/类名引用（com./org./io. 等前缀，可能被引号包裹）不是凭据
      const pkgCore = m[0].replace(/^["']|["']$/g, '');
      if (/^(com|org|io|kotlin|java|android|okhttp|org\.xerial)\.[A-Za-z0-9_.]+$/.test(pkgCore)) { p.re.lastIndex = m.index + 1; continue; }
      findings.push({ file, zone, pattern: p.name, severity: p.severity, match: mask(m[0]) });
      if (m[0].length === 0) p.re.lastIndex++; // 空匹配防死循环
    }
  }
  return findings;
}

function isInside(file, dir) {
  const rel = path.relative(dir, file);
  return rel === '' || (!rel.startsWith('..') && !path.isAbsolute(rel));
}

function mask(s) {
  if (s.length <= 8) return '<redacted>';
  return s.slice(0, 4) + '…' + s.slice(-4) + ` (len=${s.length})`;
}

function main() {
  const args = process.argv.slice(2);
  const selfTest = args.includes('--self-test');
  const includePrivate = args.includes('--include-private');
  const target = args.find((a) => !a.startsWith('--')) || ROOT;

  if (selfTest) {
    const fixture = path.join(__dirname, 'test-fixtures', 'fixture.secrets.js');
    const findings = scanFile(fixture, true);
    const names = new Set(findings.map((f) => f.pattern));
    const required = new Set(PATTERNS.map((p) => p.name));
    const missing = [...required].filter((n) => !names.has(n));
    console.log(`[self-test] fixture findings: ${[...names].sort().join(', ') || '(none)'}`);
    console.log(`[self-test] required patterns: ${[...required].sort().join(', ')}`);
    if (missing.length) {
      console.error(`[self-test] FAIL — 漏检模式: ${missing.join(', ')}`);
      process.exit(1);
    }
    console.log('[self-test] PASS — 所有模式可检出（含单引号 provider-style key）');
    process.exit(0);
  }

  const files = [];
  walk(target, files);
  let findings = [];
  for (const f of files) findings = findings.concat(scanFile(f, includePrivate));

  const worktree = findings.filter((f) => f.zone === 'WORKTREE');
  const isolated = findings.filter((f) => f.zone === 'ISOLATION');

  if (isolated.length) {
    console.log(`[secret_scan] 隔离区命中 ${isolated.length} 个（预期，不入 git）:`);
    for (const f of isolated.slice(0, 5)) console.log(`  [${f.severity}] ${f.pattern} — ${path.relative(ROOT, f.file)} — ${f.match}`);
  }
  if (worktree.length) {
    console.error(`[secret_scan] FAIL — 工作区 ${worktree.length} 个命中:`);
    for (const f of worktree) console.error(`  [${f.severity}] ${f.pattern} — ${path.relative(ROOT, f.file)} — ${f.match}`);
    process.exit(1);
  }
  console.log(`[secret_scan] PASS — 工作区扫描 ${files.length} 个文件，无凭据命中`);
}

main();
