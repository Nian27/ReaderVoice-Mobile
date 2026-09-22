#!/usr/bin/env node
/**
 * candidate_audit.js —— 角色候选（实体集）污染审计。
 *
 * 动机：真机 discovery 缓存 characters_<bookId>.json 是「角色发现」的两轮规则输出，
 * 它同时决定 **候选 C#** 与 **delivery cue 安全阀**（knownEntities 命中即不许判为 cue）。
 * 一旦碎片混入，模型就在被污染的候选里作答——协议全绿、人是错的。
 *
 * 用法：
 *   node tools/mobile005/candidate_audit.js <characters_cache.json> [--json out.json]
 *
 * 退出码：0 = 无"疑似非人名"命中；1 = 存在命中（供 Gate 使用）。
 * 注意：本脚本只做**形态启发式**分类，不宣称是准确率——准确率必须靠 MANUAL_GOLD（ADR-055）。
 */
'use strict'
const fs = require('fs')

const LEXICON = {
  // 叹词/拟声：绝不可能是人名（逐字比对）
  interjection: ['哈哈', '呵呵', '嘿嘿', '嘻嘻', '哈哈', '哎呀', '哎哟', '咦', '唉', '嗯', '哦', '噢', '嘿', '喂', '呸', '哇', '喔', '呦', '哼', '嘁', '切', '啊', '呀'],
  // cue 动词（说话动作）：出现在表面里即高度可疑
  cueVerb: ['说道', '问道', '答道', '喊道', '叫道', '笑道', '怒道', '回道', '应道', '叹道', '道', '说', '问', '答', '喊', '叫', '嚷', '喝'],
  // 动作/身体部位结尾（实现里的 ACTION_SUFFIX 同源）
  actionSuffix: ['了', '着', '过', '门', '头', '身', '手', '眼', '步', '口', '声', '气', '里', '前', '后', '来', '去', '上', '下', '开', '起', '进', '出', '看', '想', '顿', '劝', '拦', '骂', '拍', '打', '即', '续', '板', '街', '常', '反', '没', '的', '是', '对', '案', '还', '直', '接', '继', '能', '要', '会', '只'],
  // 连词/副词/虚词（整词）
  functionWord: ['倘若', '因为', '故此', '继而', '如实', '倒不如', '要知', '显然', '虽然', '然而', '如果', '所以', '并且', '同时', '不禁', '甚至', '唯有', '故作', '得到', '作为', '应当', '可以', '不管', '只是', '然而一', '只有一', '由此', '于是', '最终', '方才', '直到'],
}

function classify(name) {
  const hits = []
  if (LEXICON.interjection.includes(name)) hits.push('INTERJECTION')
  for (const v of LEXICON.cueVerb) if (name.includes(v)) { hits.push(`CUE_VERB:${v}`); break }
  for (const s of LEXICON.actionSuffix) if (name.endsWith(s)) { hits.push(`ACTION_SUFFIX:${s}`); break }
  if (LEXICON.functionWord.includes(name)) hits.push('FUNCTION_WORD')
  if (/[他她你我咱们]/.test(name)) hits.push('PRONOUN_CHAR')
  return hits
}

function main() {
  const args = process.argv.slice(2)
  if (args.length === 0) {
    console.error('usage: node candidate_audit.js <characters_cache.json> [--json out.json]')
    process.exit(2)
  }
  const src = args[0]
  const outIdx = args.indexOf('--json')
  const o = JSON.parse(fs.readFileSync(src, 'utf8'))
  const names = o.names || []
  const rows = names.map((n) => ({ name: n, reasons: classify(n) }))
  const suspect = rows.filter((r) => r.reasons.length > 0)
  const byReason = {}
  for (const r of suspect) for (const x of r.reasons) byReason[x] = (byReason[x] || 0) + 1

  console.log(`source   : ${src}`)
  console.log(`bookId   : ${o.bookId}  sourceLength=${o.sourceLength}`)
  console.log(`names    : ${names.length}`)
  console.log(`suspect  : ${suspect.length}  (${(suspect.length * 100 / Math.max(1, names.length)).toFixed(1)}%)`)
  console.log(`reasons  : ${JSON.stringify(byReason, null, 0)}`)
  console.log('--- suspect list (first 80) ---')
  for (const r of suspect.slice(0, 80)) console.log(`  ${r.name}\t${r.reasons.join(',')}`)

  if (outIdx >= 0 && args[outIdx + 1]) {
    fs.writeFileSync(args[outIdx + 1], JSON.stringify({ source: src, total: names.length, suspect: suspect.length, byReason, rows }, null, 2))
    console.log(`wrote ${args[outIdx + 1]}`)
  }
  process.exit(suspect.length > 0 ? 1 : 0)
}

main()
