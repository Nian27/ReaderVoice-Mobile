#!/usr/bin/env node
/**
 * name_evidence.js —— 从【原文语料】给每个候选表面算证据特征。
 *
 * 目的：候选人名合法性闸门必须建立在**语料证据**上，而不是再堆一张词表。
 * 核心洞察：真正的名字会在**非 cue 位置**反复出现（"李寄舟的""李寄舟和""李寄舟，"
 *   "看着李寄舟"…）；而污染碎片（"李寄舟询""张三丰知""郭襄再度"）**只**出现在
 *   cue 动词紧邻处 —— 它们是从 cue 子句里切出来的残留，一旦离开 cue 就不存在。
 *
 * 用法：
 *   node tools/mobile005/name_evidence.js <source.txt> <characters_cache.json> [--json out.json]
 *
 * 输出 TSV：surface total cueAfter free rightKinds leftPunct quoteLeft ...
 */
'use strict'
const fs = require('fs')

/** 说话 cue 动词的**首字**（表面紧邻其后即为 cue 位置）。 */
const CUE_HEADS = new Set('说道问答喊叫嚷喝斥诉询诘'.split(''))
/** 位置类别判定用：句读/引号（左侧"独立短语"证据）。 */
const PUNCT = new Set('，。！？；：、\n“”"『』「」（）() \t'.split(''))
/** 名字后常见的"非 cue"续接（名字作为普通名词使用的证据）。 */
const NAME_RIGHT = new Set('的是和与跟对向把被在也又则便就都还，。！？；：、“”'.split(''))
/** 可以带"人"作宾语的介词/动词（紧邻表面【之前】⇒ 该表面被当成人在用）。副词/动词短语不会出现在这里。 */
const PERSON_TAKERS_1 = new Set('对向朝跟和与替给为被把叫让请问找看望等邀迎劝告救扶拉推拦阻骂夸赞'.split(''))
const PERSON_TAKERS_2 = ['指着', '对着', '朝着', '望着', '看着', '向着', '冲着', '拉着', '扶着', '邀请',
  '请求', '告诉', '询问', '回答', '吩咐', '命令', '嘱咐', '拜访', '拜见', '迎接', '拦住', '阻止', '呵斥']

function main() {
  const [src, cache] = process.argv.slice(2)
  if (!src || !cache) {
    console.error('usage: node name_evidence.js <source.txt> <characters_cache.json> [--json out]')
    process.exit(2)
  }
  const text = fs.readFileSync(src, 'utf8')
  const names = JSON.parse(fs.readFileSync(cache, 'utf8')).names || []
  const rows = []
  for (const s of names) {
    let idx = 0
    let total = 0
    let cueAfter = 0
    let quoteRight = 0
    let punctLeft = 0
    let personObject = 0
    const rightKinds = new Map()
    while ((idx = text.indexOf(s, idx)) !== -1) {
      total++
      const r = text[idx + s.length]
      const l = idx > 0 ? text[idx - 1] : '\n'
      if (r !== undefined) {
        if (CUE_HEADS.has(r)) cueAfter++
        rightKinds.set(r, (rightKinds.get(r) || 0) + 1)
        if (r === '：' || r === ':' || r === '“' || r === '"') quoteRight++
      }
      if (PUNCT.has(l)) punctLeft++
      // "人"作宾语证据：紧邻之前是介词/带人动词
      if (PERSON_TAKERS_1.has(l)) personObject++
      else if (idx >= 2 && PERSON_TAKERS_2.includes(text.slice(idx - 2, idx))) personObject++
      idx += s.length
    }
    rows.push({
      surface: s,
      len: s.length,
      total,
      cueAfter,
      free: total - cueAfter,
      rightKinds: rightKinds.size,
      quoteRight,
      punctLeft,
      personObject,
      cueBoundRatio: total === 0 ? 0 : +(cueAfter / total).toFixed(3),
    })
  }
  rows.sort((a, b) => a.total - b.total || a.surface.localeCompare(b.surface))
  const jsonIdx = process.argv.indexOf('--json')
  if (jsonIdx >= 0 && process.argv[jsonIdx + 1]) {
    fs.writeFileSync(process.argv[jsonIdx + 1], JSON.stringify(rows, null, 1))
    console.log(`wrote ${process.argv[jsonIdx + 1]}`)
  }
  console.log('surface\tlen\ttotal\tcueAfter\tfree\trightKinds\tquoteRight\tpunctLeft\tcueBoundRatio')
  for (const r of rows) {
    console.log([r.surface, r.len, r.total, r.cueAfter, r.free, r.rightKinds, r.quoteRight, r.punctLeft, r.cueBoundRatio].join('\t'))
  }
}

main()
