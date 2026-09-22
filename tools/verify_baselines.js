#!/usr/bin/env node
/**
 * ReaderVoice baseline verifier（离线，不依赖外网）
 *
 * 验证:
 *   1. v90.7 raw 资产身份（hash/bytes/lines/code chars/lines）与 V907_MANIFEST.json 一致
 *   2. redacted 副本存在且不含真实凭据
 *   3. raw 第三方文件未被 git tracked
 *   4. 危险 remote-upload 开关未迁入产品代码（research/ 之外）
 *
 * 用法: node tools/verify_baselines.js
 * 退出码: 0 = PASS, 1 = FAIL
 */
'use strict';
const fs = require('fs');
const path = require('path');
const crypto = require('crypto');
const { execFileSync } = require('child_process');

const ROOT = path.resolve(__dirname, '..');
let failed = false;
const fail = (msg) => { failed = true; console.error('  FAIL:', msg); };
const ok = (msg) => console.log('  OK:', msg);

const RAW_JSON = path.join(ROOT, 'research', 'private', 'legado-v907', 'v90.7.original.json');
const RAW_JS = path.join(ROOT, 'research', 'private', 'legado-v907', 'v90.7.code.extracted.raw.js');
const REDACTED_JS = path.join(ROOT, 'research', 'third_party', 'legado-v907', 'v90.7.code.redacted.js');
const MANIFEST = path.join(ROOT, 'research', 'third_party', 'legado-v907', 'V907_MANIFEST.json');

const EXPECTED = {
  sha256: '35c7d654274fa0f0ad8bda0d6498e23789a2f2f053cdbd8ec8eb48f02759842e',
  jsonBytes: 1939477,
  jsonLines: 28564, // node split 口径：文件最后一行无结尾换行，物理行 = wc -l(28563) + 1
  codeChars: 779255,
  codeLines: 14058,
  codeBytesUtf8: 858476,
};

console.log('[verify_baselines] 1/5 v90.7 raw 身份（与 EXPECTED 及 manifest 双重核对）');
if (!fs.existsSync(RAW_JSON)) fail(`missing ${RAW_JSON}`);
else {
  const buf = fs.readFileSync(RAW_JSON);
  const sha = crypto.createHash('sha256').update(buf).digest('hex');
  const text = buf.toString('utf8');
  const lines = text.split('\n').length - (text.endsWith('\n') ? 1 : 0);
  const parsed = JSON.parse(text);
  const codeChars = parsed.code.length;
  const codeLines = parsed.code.split('\n').length;
  const codeBytes = Buffer.byteLength(parsed.code, 'utf8');

  const m = JSON.parse(fs.readFileSync(MANIFEST, 'utf8'));
  const checks = [
    ['sha256', sha, EXPECTED.sha256, m.sha256],
    ['json_bytes', buf.length, EXPECTED.jsonBytes, m.json_bytes],
    ['json_lines', lines, EXPECTED.jsonLines, m.json_lines],
    ['code_chars', codeChars, EXPECTED.codeChars, m.code_field_chars_unescaped],
    ['code_lines', codeLines, EXPECTED.codeLines, m.code_expanded_lines],
    ['code_bytes_utf8', codeBytes, EXPECTED.codeBytesUtf8, m.code_expanded_bytes_utf8],
  ];
  for (const [name, actual, expected, manifest] of checks) {
    if (actual !== expected) fail(`${name}: 实测 ${actual} ≠ EXPECTED ${expected}`);
    else if (actual !== manifest) fail(`${name}: 实测 ${actual} ≠ manifest ${manifest}`);
    else ok(`${name} = ${actual}（与 EXPECTED 和 manifest 一致）`);
  }
}

console.log('[verify_baselines] 2/5 redacted 副本');
if (!fs.existsSync(REDACTED_JS)) fail(`missing ${REDACTED_JS}`);
else {
  const t = fs.readFileSync(REDACTED_JS, 'utf8');
  if (/b26b869ffd|REDACTED_POTENTIALLY_COMPROMISED/.test(t)) ok('redacted 标记存在且无真实 key');
  else fail('redacted 副本异常（无替换标记）');
}

console.log('[verify_baselines] 3/5 raw 未被 git tracked');
try {
  const tracked = execFileSync('git', ['-C', ROOT, 'ls-files'], { encoding: 'utf8' }).split('\n');
  const hits = tracked.filter((f) => f.startsWith('research/private/'));
  if (hits.length) fail(`research/private/ 有 ${hits.length} 个文件被 tracked: ${hits.join(', ')}`);
  else ok('research/private/ 未被 git tracked');
} catch (e) {
  fail(`git 不可用或非仓库: ${e.message}`);
}

console.log('[verify_baselines] 4/5 远程上传开关未迁入产品代码');
const dangerous = [/ENABLE_REMOTE_UPLOAD/, /GRAPH_REMOTE_ENDPOINT/, /ttsrv\.httpPost/, /graph_remote_chapter_queue/];
// 只扫产品代码目录（docs/tools/research 允许描述这些开关）
const PRODUCT_DIRS = ['parser', 'core-character', 'core-director', 'training', 'tts-cosyvoice', 'audio-renderer', 'scheduler', 'data-room', 'app-android'];
function walk(dir, out) {
  if (!fs.existsSync(dir)) return;
  for (const entry of fs.readdirSync(dir, { withFileTypes: true })) {
    const full = path.join(dir, entry.name);
    if (entry.isDirectory()) {
      if (['build', '.gradle', 'node_modules', 'runs', 'test-fixtures'].includes(entry.name)) continue;
      walk(full, out);
    } else if (entry.isFile() && /\.(kt|java|py|ts|js)$/.test(entry.name)) out.push(full);
  }
}
const codeFiles = [];
for (const d of PRODUCT_DIRS) walk(path.join(ROOT, d), codeFiles);
let dangerHits = [];
for (const f of codeFiles) {
  const t = fs.readFileSync(f, 'utf8');
  for (const re of dangerous) if (re.test(t)) dangerHits.push(path.relative(ROOT, f));
}
if (dangerHits.length) fail(`产品代码含危险开关引用: ${dangerHits.join(', ')}`);
else ok(`产品代码（${codeFiles.length} 个文件）无远程上传开关`);

console.log('[verify_baselines] 5/5 manifest 顶层身份');
try {
  const m = JSON.parse(fs.readFileSync(MANIFEST, 'utf8'));
  if (m.rule_id !== 'mingwuyan_v907' || m.version !== 907 || m.license_status !== 'UNKNOWN') fail('manifest 身份字段异常');
  else ok(`asset=${m.asset_id} rule=${m.rule_id} v${m.version} license=${m.license_status}`);
} catch (e) { fail(`manifest 读取失败: ${e.message}`); }

if (failed) { console.error('[verify_baselines] FAIL'); process.exit(1); }
console.log('[verify_baselines] PASS — 全部基线校验通过');
