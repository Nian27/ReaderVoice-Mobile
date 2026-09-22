#!/usr/bin/env node
/**
 * speaker_gold.js — CH-0 / G1–G3：**说话人准确率的测量工具**（抽取 + 判分）
 *
 * 为什么必须做：项目一直在报 coverage / anchor / 协议合规率，**从未测过"说话人是否正确"**。
 * 于是"效果差"既无法证实也无法改进。本工具把主观印象变成分布。
 *
 * 用法：
 *   ① 抽取待标注样本（从某章剧本）
 *      node speaker_gold.js extract <script_lines.jsonl> <out_gold.jsonl> [limit]
 *      → 生成 {segment_id, text, director_speaker, outcome, gold_speaker:""}
 *   ② 人工标注：给每行填 gold_speaker
 *      取值：NARRATOR / UNKNOWN / <人物名>（与 director_speaker 同口径）
 *   ③ 判分
 *      node speaker_gold.js judge <gold.jsonl>
 *      → 真实准确率 + 混淆 + 错误分类 + 候选覆盖提示
 *   ④ **非人说话人计数**（不需要 MANUAL_GOLD，是"闸门是否生效"的机械指标）
 *      node speaker_gold.js nonperson <script_lines.jsonl> <name_gate_audit.txt>
 *      → 统计说话人落在【闸门拦下的表面】上的行数（修复前真机 = 9/20）
 *
 * 口径纪律（ADR-055）：
 *   · **只有 MANUAL_GOLD 能进准确率**；规则输出/模型输出一律不得当 gold
 *   · gold 为空的行**不计入**准确率，而是报告"未标注 N 行"
 *   · `nonperson` 报的**不是准确率**，只是"哪些说话人是被判为非人名的表面"
 */
const fs = require('fs');

const speakerOf = (ref) => {
  if (ref === 'NARRATOR') return 'NARRATOR';
  if (ref === 'UNKNOWN') return 'UNKNOWN';
  if (typeof ref === 'string' && ref.startsWith('CHARACTER:')) {
    return ref.slice('CHARACTER:'.length).replace(/^boot-/, '');
  }
  return `?${ref}`;
};

function extract(scriptPath, outPath, limit) {
  const rows = fs.readFileSync(scriptPath, 'utf8').trim().split(/\n/).filter(Boolean).map(JSON.parse);
  const picked = rows.slice(0, limit || rows.length);
  const out = picked.map((r) => ({
    segment_id: r.segment_id,
    text: r.text,
    director_speaker: speakerOf(r.speakerRef),
    director_type: r.type,
    outcome: r.outcome,
    gold_speaker: '',
  }));
  fs.writeFileSync(outPath, out.map((o) => JSON.stringify(o)).join('\n') + '\n', 'utf8');
  const types = out.reduce((m, o) => (m[o.director_type] = (m[o.director_type] || 0) + 1, m), {});
  console.log(`[G1] 抽取 ${out.length} 段 → ${outPath}`);
  console.log(`[G1] 类型分布 ${JSON.stringify(types)}`);
  console.log('[G1] 下一步：人工填 gold_speaker（NARRATOR / UNKNOWN / 人名），然后 judge');
  console.log('[G1] 建议优先标注这几类（最容易错）：');
  console.log('     · 连续无 cue 对白（谁在说最不确定）');
  console.log('     · 一段里有多人出现');
  console.log('     · 代词指代（他/她/对方）');
  console.log('     · 旁白里夹引号');
}

function judge(goldPath) {
  const rows = fs.readFileSync(goldPath, 'utf8').trim().split(/\n/).filter(Boolean).map(JSON.parse);
  const labeled = rows.filter((r) => (r.gold_speaker || '').trim() !== '');
  const unlabeled = rows.length - labeled.length;
  if (labeled.length === 0) {
    console.log(`[G2] ★ 没有任何人工标注（${rows.length} 行待标）⇒ 按 ADR-055 **拒绝报告准确率**。`);
    console.log('[G2] 请先填 gold_speaker 再跑 judge。');
    process.exit(2);
  }

  let hit = 0;
  const confusion = {};           // gold -> { pred -> n }
  const errors = [];
  const cls = { missing: 0, wrong_person: 0, narrator_as_dialogue: 0, dialogue_as_narrator: 0, unknown_abuse: 0, unknown_missing: 0 };

  for (const r of labeled) {
    const g = r.gold_speaker.trim();
    const p = (r.director_speaker || '').trim();
    const key = `${g} → ${p}`;
    confusion[key] = (confusion[key] || 0) + 1;
    if (g === p) { hit++; continue; }
    errors.push(r);
    if (g === 'NARRATOR' && p !== 'NARRATOR') cls.narrator_as_dialogue++;
    else if (g !== 'NARRATOR' && p === 'NARRATOR') cls.dialogue_as_narrator++;
    else if (g === 'UNKNOWN') cls.unknown_missing++;
    else if (p === 'UNKNOWN') cls.unknown_abuse++;
    else if (g.startsWith('未知') || g === '') cls.missing++;
    else cls.wrong_person++;
  }

  const acc = hit / labeled.length;
  console.log('=== G2 说话人准确率（MANUAL_GOLD）===');
  console.log(`标注 ${labeled.length} 段（未标注 ${unlabeled} 段，未计入）`);
  console.log(`★ accuracy = ${(acc * 100).toFixed(1)}%   (${hit}/${labeled.length})`);
  console.log('\n=== G3 错误分类 ===');
  const total = errors.length || 1;
  const label = {
    narrator_as_dialogue: '旁白被当对白',
    dialogue_as_narrator: '对白被当旁白',
    unknown_abuse: '本该有人却被判 UNKNOWN',
    unknown_missing: '本该 UNKNOWN 却硬判了人',
    wrong_person: '判成了另一个人',
    missing: '漏判',
  };
  Object.entries(cls).sort((a, b) => b[1] - a[1]).forEach(([k, v]) => {
    if (v > 0) console.log(`  ${label[k] || k}: ${v} (${((v / total) * 100).toFixed(0)}% of errors)`);
  });
  console.log('\n=== 混淆（gold → pred，Top 10）===');
  Object.entries(confusion).filter(([k]) => { const [g, p] = k.split(' → '); return g !== p; })
    .sort((a, b) => b[1] - a[1]).slice(0, 10)
    .forEach(([k, v]) => console.log(`  ${k}: ${v}`));

  const out = goldPath.replace(/\.jsonl$/, '') + '.score.json';
  fs.writeFileSync(out, JSON.stringify({
    labeled: labeled.length, unlabeled, accuracy: acc, errors: cls,
    confusion, errorSamples: errors.slice(0, 20).map((e) => ({ seg: e.segment_id, text: e.text.slice(0, 40), gold: e.gold_speaker, pred: e.director_speaker })),
  }, null, 2), 'utf8');
  console.log(`\n[G2] 明细 → ${out}`);
  console.log('[G2] 注意：accuracy 只回答"选的对不对"；**候选里是否有正确角色**需要单独统计');
  console.log('     （下一步：把 DirectorContext 的候选列表也落盘，才能算出 candidate recall，');
  console.log('       从而区分"模型选错"与"候选里压根没有"）');
}

/**
 * 非人说话人计数（④）：说话人落在【闸门拦下的表面】上的行数。
 *
 * 用法：node speaker_gold.js nonperson <script_lines.jsonl> <name_gate_audit.txt>
 * audit 文件是设备侧 `files/chapter_director/name_gate_<bookId>.txt`（CharacterDiscovery.audit()）。
 * 退出码：0 = 0 行非人说话人；1 = 存在（Gate FAIL）。
 */
function nonperson(scriptPath, auditPath) {
  const audit = fs.readFileSync(auditPath, 'utf8');
  const head = audit.split('\n').find((l) => l.startsWith('harvested=')) || '';
  const rejected = new Set();
  let inSection = false;
  for (const line of audit.split('\n')) {
    if (line.startsWith('--- rejected ---')) { inSection = true; continue; }
    if (!inSection) continue;
    const cols = line.split('\t');
    if (cols.length >= 2 && cols[0].trim()) rejected.add(cols[0].trim());
  }
  const rows = fs.readFileSync(scriptPath, 'utf8').trim().split(/\n/).filter(Boolean).map(JSON.parse);
  const dist = {};
  const bad = [];
  for (const r of rows) {
    const sp = speakerOf(r.speakerRef);
    dist[sp] = (dist[sp] || 0) + 1;
    if (rejected.has(sp)) bad.push({ segment_id: r.segment_id, speaker: sp, text: (r.text || '').slice(0, 40) });
  }
  const chars = rows.filter((r) => typeof r.speakerRef === 'string' && r.speakerRef.startsWith('CHARACTER:'));
  console.log('=== 非人说话人计数（机械指标，不是准确率）===');
  console.log(`闸门审计：${auditPath}`);
  console.log(`  ${head}`);
  console.log(`  被拦表面 ${rejected.size} 个`);
  console.log(`脚本：${scriptPath}`);
  console.log(`  行数 ${rows.length}，角色行 ${chars.length}`);
  console.log(`★ 说话人落在被拦表面上的行数 = ${bad.length}  (${((bad.length / Math.max(1, rows.length)) * 100).toFixed(1)}%)`);
  console.log('\n=== 说话人分布 ===');
  Object.entries(dist).sort((x, y) => y[1] - x[1]).forEach(([k, v]) => console.log(`  ${k}: ${v}`));
  if (bad.length) {
    console.log('\n=== 非人说话人明细 ===');
    for (const b of bad) console.log(`  ${b.segment_id}  ${b.speaker}  ${b.text}`);
  }
  process.exit(bad.length > 0 ? 1 : 0);
}

const [mode, a, b, c] = process.argv.slice(2);
if (mode === 'extract') extract(a, b, c ? Number(c) : undefined);
else if (mode === 'judge') judge(a);
else if (mode === 'nonperson') nonperson(a, b);
else {
  console.log('用法: node speaker_gold.js extract <script_lines.jsonl> <out_gold.jsonl> [limit]');
  console.log('      node speaker_gold.js judge <gold.jsonl>');
  console.log('      node speaker_gold.js nonperson <script_lines.jsonl> <name_gate_audit.txt>');
  process.exit(1);
}
