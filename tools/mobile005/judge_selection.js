// judge_selection.js — M0 / MOBILE-005 selection-protocol judge（M3 口径版）
//
// 口径（用户 2026-09-17 冻结）：
//   · 规则层输出【不是 gold】：报告写 "agreement with rule layer"，绝不叫 accuracy
//   · 只有 MANUAL_GOLD 才能进硬准确率 Gate；本 fixture 无人工标注 ⇒ 不报 accuracy
//   · 拆开写：segment_id 回填 / speaker 成员性 / evidence 成员性 / UNKNOWN 率 / 规则一致率
// usage: node judge_selection.js <fixture.jsonl> <outdir>
'use strict';
const fs = require('fs'), path = require('path');

const fixture = fs.readFileSync(process.argv[2], 'utf8').trim().split(/\r?\n/).map((l) => JSON.parse(l));
const outDir = process.argv[3];

const extractAnswers = (t) => t.split(/\r?\n/).filter((l) => l.trim().startsWith('{')).map((l) => l.trim());
function normalize(raw) {
  let cosmetic = 0;
  const fixed = raw.replace(/:\s*([A-Za-z_][A-Za-z0-9_]*)\s*([,}])/g, (m, tok, tail) => {
    if (tok === 'true' || tok === 'false' || tok === 'null') return m;
    cosmetic++;
    return `: "${tok}"${tail}`;
  });
  return { fixed, cosmetic };
}

let p1 = 0, p2 = 0, p3 = 0, p4 = 0, p6 = 0, n = 0, cosmeticTotal = 0, consistent = 0;
let agree = 0, agreeDen = 0;                 // 与规则层一致（弱口径）
const byKind = {};
const violations = [];

console.log('target rule(gold_kind)      ans   seg  spkOK evOK agree json reps');
for (let i = 0; i < fixture.length; ++i) {
  const fx = fixture[i];
  const ruleC = fx.rule_candidate !== undefined ? fx.rule_candidate : fx.gold_candidate;
  const ruleS = fx.rule_speaker !== undefined ? fx.rule_speaker : fx.gold_speaker_surface;
  const kind = fx.gold_kind || 'UNKNOWN_KIND';
  const f = path.join(outDir, `out_${String(i).padStart(2, '0')}.txt`);
  if (!fs.existsSync(f)) { console.log(`${fx.target_segment_id}  MISSING`); continue; }
  const raws = extractAnswers(fs.readFileSync(f, 'utf8'));
  if (raws.length === 0) { console.log(`${fx.target_segment_id}  NO_ANSWER`); continue; }
  const norm = raws.map(normalize);
  cosmeticTotal += norm[0].cosmetic;
  let ok0 = null;
  try { ok0 = JSON.parse(norm[0].fixed); } catch (e) { ok0 = null; }
  const stable = raws.every((r) => r === raws[0]);
  if (ok0) p1++; else violations.push({ i, why: 'JSON parse fail', raw: raws[0].slice(0, 120) });

  const candIds = fx.candidates.map((c) => c.id);
  const evIds = fx.evidence_ids || (fx.evidence || []).map((e) => e.id);
  const segOK = ok0 && ok0.segment_id === fx.target_segment_id;
  const spkOK = ok0 && (candIds.includes(String(ok0.speaker)) || ok0.speaker === 'UNKNOWN');
  const evOK = ok0 && Array.isArray(ok0.evidence) && ok0.evidence.every((e) => evIds.includes(e));
  if (segOK) p2++; else if (ok0) violations.push({ i, why: `segment_id=${ok0.segment_id} != ${fx.target_segment_id}` });
  if (spkOK) p3++; else if (ok0) violations.push({ i, why: `speaker=${ok0.speaker} 越界（候选 ${candIds.join(',')}）` });
  if (evOK) p4++; else if (ok0) violations.push({ i, why: `evidence=${JSON.stringify(ok0.evidence)} 越界（提供 ${evIds.join(',')}）` });
  if (ok0 && ok0.speaker === 'UNKNOWN') p6++;
  if (stable) consistent++;

  // 与规则层一致率：只在规则层确实给出候选时计入（弱标签，非 accuracy）
  const a = ok0 && ruleC ? String(ok0.speaker) === String(ruleC) : null;
  if (a !== null) { agreeDen++; if (a) agree++; }
  byKind[kind] = byKind[kind] || { n: 0, agree: 0, den: 0, unknown: 0 };
  byKind[kind].n++;
  if (ok0 && ok0.speaker === 'UNKNOWN') byKind[kind].unknown++;
  if (a !== null) { byKind[kind].den++; if (a) byKind[kind].agree++; }
  n++;

  console.log(`${fx.target_segment_id.padEnd(6)} ${String(ruleS || '-').padEnd(8)}(${kind.padEnd(11)}) ${String(ok0 ? ok0.speaker : '-').padEnd(5)} ` +
    `${(segOK ? 'ok' : 'NO').padEnd(4)} ${(spkOK ? 'ok' : 'NO').padEnd(5)} ${(evOK ? 'ok' : 'NO').padEnd(4)} ` +
    `${(a === null ? '-' : a ? 'HIT' : '-').padEnd(5)} ${(ok0 ? 'ok' : 'FAIL').padEnd(4)} ${stable ? 'same' : 'DIFF'}`);
}

const pct = (x) => `${x}/${n} (${((x / Math.max(n, 1)) * 100).toFixed(1)}%)`;
console.log('\n==== M0 判据（协议层）====');
console.log(`P1 JSON 可解析（容忍裸枚举，cosmetic=${cosmeticTotal}）: ${pct(p1)}`);
console.log(`P2 segment_id 回填正确            : ${pct(p2)}`);
console.log(`P3 speaker ∈ candidates ∪ UNKNOWN : ${pct(p3)}`);
console.log(`P4 evidence ⊆ provided IDs        : ${pct(p4)}`);
console.log(`P6 UNKNOWN rate                   : ${pct(p6)}`);
console.log(`3 次运行完全一致                  : ${consistent}/${n}`);
console.log('\n==== 语义指标（★ 无 MANUAL_GOLD ⇒ 下列均非 accuracy）====');
console.log(`Agreement with Rule Layer（弱）   : ${agree}/${agreeDen} (${((agree / Math.max(agreeDen, 1)) * 100).toFixed(1)}%)`);
console.log('按 gold_kind 分解                  : ' + JSON.stringify(byKind));
const manual = fixture.filter((f) => f.gold_kind === 'MANUAL_GOLD').length;
console.log(`MANUAL_GOLD 样本数                : ${manual}  ${manual === 0 ? '⇒ 禁止报告 speaker accuracy（见 GoldPolicy）' : ''}`);
if (violations.length) {
  console.log('\n==== 协议越界明细 ====');
  violations.forEach((v) => console.log(`  i=${v.i} ${v.why}${v.raw ? ' raw=' + v.raw : ''}`));
}
fs.writeFileSync(path.join(outDir, 'judge_result.json'), JSON.stringify({
  n, p1, p2, p3, p4, p6, consistent, cosmeticTotal, agree, agreeDen, byKind, manualGold: manual, violations,
}, null, 2));
