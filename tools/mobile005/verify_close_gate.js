// verify_close_gate.js — MOBILE-005 Close Gate 证据校验
// 用法: node verify_close_gate.js <close_gate 目录>
const fs = require('fs');
const path = require('path');

const root = process.argv[2];
const runs = fs.readdirSync(root).filter(d => fs.statSync(path.join(root, d)).isDirectory()).sort();

const LOCAL_ID = /^C\d+$/;
let allOk = true;
const summary = [];

function pct(sorted, p) {
  if (!sorted.length) return 0;
  const i = Math.min(sorted.length - 1, Math.floor((p / 100) * sorted.length));
  return sorted[i];
}

for (const dir of runs) {
  const d = path.join(root, dir);
  const statsPath = path.join(d, 'stats.json');
  const linesPath = path.join(d, 'script_lines.jsonl');
  if (!fs.existsSync(statsPath) || !fs.existsSync(linesPath)) {
    summary.push(`[${dir}] ★ 缺产物（stats=${fs.existsSync(statsPath)} lines=${fs.existsSync(linesPath)}）`);
    allOk = false;
    continue;
  }
  const stats = JSON.parse(fs.readFileSync(statsPath, 'utf8'));
  const rows = fs.readFileSync(linesPath, 'utf8').trim().split(/\n/).filter(Boolean).map(l => JSON.parse(l));

  const badSchema = rows.filter(r => r.schemaVersion !== 2).length;
  const legacyField = rows.filter(r => Object.prototype.hasOwnProperty.call(r, 'speaker')).length;
  const refs = rows.map(r => r.speakerRef);
  const localLeaks = refs.filter(r => typeof r !== 'string' || LOCAL_ID.test(r)).length;
  const badRef = refs.filter(r => !(r === 'NARRATOR' || r === 'UNKNOWN' || (typeof r === 'string' && r.startsWith('CHARACTER:')))).length;
  const spanOk = rows.filter(r => r.text.length === (r.source_end - r.source_start)).length;
  const jsonInText = rows.filter(r => /segment_id|speakerRef|"speaker"/.test(r.text)).length;
  let orderOk = true;
  for (let i = 1; i < rows.length; i++) {
    const p = rows[i - 1], c = rows[i];
    if (!(c.paragraph_id > p.paragraph_id || (c.paragraph_id === p.paragraph_id && c.segment_index >= p.segment_index))) orderOk = false;
  }
  const routeCount = rows.reduce((m, r) => (m[r.route] = (m[r.route] || 0) + 1, m), {});
  const kindCount = refs.reduce((m, r) => {
    const k = r === 'NARRATOR' ? 'NARRATOR' : r === 'UNKNOWN' ? 'UNKNOWN' : 'CHARACTER';
    m[k] = (m[k] || 0) + 1; return m;
  }, {});
  const ids = [...new Set(refs.filter(r => typeof r === 'string' && r.startsWith('CHARACTER:')))];

  // P50/P95：单次模型调用 prefill+decode 延迟（来自 JNI metrics 日志）
  let lat = [];
  const mf = path.join(d, 'qwen_metrics.log');
  if (fs.existsSync(mf)) {
    for (const line of fs.readFileSync(mf, 'utf8').split(/\n/)) {
      const m = /metrics backend=(\S+) load_us=(\d+) prefill_us=(\d+) decode_us=(\d+) gen_seq_len=(\d+)/.exec(line);
      if (m) lat.push((Number(m[3]) + Number(m[4])) / 1000);
    }
  }
  lat.sort((a, b) => a - b);

  const ok = badSchema === 0 && localLeaks === 0 && badRef === 0 && spanOk === rows.length && jsonInText === 0 && orderOk;
  if (!ok) allOk = false;

  summary.push([
    `[${dir}] ${ok ? 'PASS' : '★FAIL'}`,
    `  stats: segments=${stats.segments} lines=${stats.lines} coverage=${(stats.coverage * 100).toFixed(1)}% ` +
      `acc=${stats.accepted} ver=${stats.verify} down=${stats.downgrade} rej=${stats.reject} ` +
      `shortcut=${stats.shortcut} calls=${stats.model_calls} noOutput=${stats.no_output} canceled=${stats.canceled}`,
    `  watchdog: ${stats.watchdog}`,
    `  C18: schemaVersion!=2 的行=${badSchema} 旧 speaker 字段=${legacyField} 局部 ID 泄漏=${localLeaks} 非法 ref=${badRef}`,
    `  refs: ${JSON.stringify(kindCount)}  distinctCharacters=${ids.length}`,
    `  anchor: text.length==span ${spanOk}/${rows.length}  文本含 JSON=${jsonInText}  顺序单调=${orderOk}`,
    `  routes: ${JSON.stringify(routeCount)}  failureCodes=${JSON.stringify(stats.failure_codes || {})}`,
    lat.length ? `  latency(P50/P95, ms, n=${lat.length}): ${pct(lat, 50).toFixed(0)} / ${pct(lat, 95).toFixed(0)}` : '  latency: 无 metrics 日志',
  ].join('\n'));
}

console.log('=== MOBILE-005 Close Gate 证据校验 ===');
console.log(summary.join('\n'));
console.log(`\n总体: ${allOk ? 'ALL PASS' : '★ 存在失败项'}`);
process.exit(allOk ? 0 : 1);
