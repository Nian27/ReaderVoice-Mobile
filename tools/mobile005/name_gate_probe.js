#!/usr/bin/env node
/**
 * name_gate_probe.js —— 候选人名合法性闸门的**规则搜索与评估**（离线，只读）。
 *
 * 为什么要有这个脚本：闸门不能靠"再堆一张词表"拍脑袋。它必须建立在
 * ① 语料证据：真名字会在 cue 之外**多种右邻上下文**里反复出现；cue 切片只在 cue 里存在
 * ② 手工标注的真名清单（本文件内 MANUAL_GOLD，73 人 + 7 称谓）
 * 之上，并且**量化**：丢了多少真名（召回损失）／拦掉多少污染（精确率）。
 *
 * 用法：
 *   node tools/mobile005/name_gate_probe.js <source.txt> <characters_cache.json> [labels.json]
 */
'use strict'
const fs = require('fs')

/** 说话 cue 动词首字：表面紧邻其后 ⇒ 该次出现是 cue 位置。 */
const CUE_HEADS = new Set('说道问答喊叫嚷喝斥诉询诘'.split(''))
const PUNCT = new Set('，。！？；：、\n“”"『』「」（）() \t'.split(''))

/** 叹词/拟声：绝不可能是人名。 */
const INTERJECTION = new Set(['哈哈', '呵呵', '嘿嘿', '嘻嘻', '哎呀', '哎哟', '咦', '唉', '嗯', '哦', '噢', '嘿', '喂', '呸', '哇', '喔', '呦', '哼', '嘁', '切', '啊', '呀', '哈', '嘻', '嘎', '噗'])

/** 虚词/副词/连词：封闭类，几乎不可能作为人名（整词）。 */
const FUNCTION_WORDS = new Set(('倘若 如实 因为 所以 如果 那么 故此 继而 要知 倒不如 然而 虽然 唯有 只是 而是 并且 同时 可以 应该 任何 如此 这份 当年 如今 最终 率先 立马 立时 登时 陡然 好似 再度 兀自 主动 平淡 畅快 突兀 艰难 欣慰 果断 肆意 豪言 开怀 喜好 保证 打断 反驳 求情 安慰 破功 怒斥 娓娓 应和 低语 释然 肃然 傲然 昂然 娇嗔 一字一句 毛遂自荐 指点江山 声音沙哑 神色僵硬 意兴阑珊 这些 这种 那种 这样 那样 什么 怎么 就是 还是 但是 而且 然后 于是 因此 已经 曾经 正在 立刻 马上 终于 忽然 突然 当然 显然 其实 确实 简直 几乎 大概 也许 或者 不过 只有 还要 一边 一面 一声 一句 一手 一眼 一步 一阵 一番 一场 一时 一头 一起 一同 一直 一定 一样 一般 一切 一些 一点 一下' +
  // 语料实测补：残留的谓词/副词短语（均为封闭类或高频动词短语，不含姓名用字）
  ' 不知 不屑 就连 忍不住 颇有些 招呼 当先 打发 打听 打算 打量 招呼 应酬 寒暄 见礼 抱拳 点头 摇头 挥手 抬手 伸手 转身 起身 抬头 低头 回首 回头 沉吟 迟疑 犹豫 叹息 叹气 苦笑 冷笑 微笑 大笑 冷哼' +
  ' 这两个字 这位 那位 这位婶娘 完全可以 好似是在 生怕自己 将两 见一 还有一 只有一 并且一 然而一 可这一 这样一 那样一').split(' '))

/** 常见动作/身体短语：作为整体不是人名。 */
const ACTION_WORDS = new Set('拱手抱拳 躬身抱拳 躬身一礼 躬身拜 拱手执礼 拱手鞠躬 抱拳 鞠躬 拍掌 拍手称赞 拜见 叩首 诚心叩首 摇头 点头 颔首 皱眉 闭目 咬 咬着牙 冷着脸 满不在意 直接了当 轻松写意 毛遂自荐 娓娓道来 喜极而泣 故作不知 故作可惜 故作调 连连保证 立马出列 开口道歉 开口询 上前询 接着询 仔细询 立即询 转而询 随即询 当即询'.split(' '))

/** 称谓后缀（1–2 字残留若是称谓，则不得当作"残留"剥掉）。 */
const TITLE_SUFFIX = new Set(['大师', '禅师', '师太', '仙子', '娘娘', '先生', '前辈', '长老', '掌门', '真人', '道人', '首座', '圣姑', '教主', '姑娘', '夫人', '公子', '小姐', '丫头', '大爷', '婆婆', '姑姑', '哥哥', '姐姐', '妹妹', '兄弟', '阁下', '陛下', '殿下', '师兄', '师弟', '师姐', '师妹', '师父', '师傅', '老人', '老者'])

/** 结尾虚词/动词字（表面以这些字收尾 ⇒ 不是人名；称谓后缀另有豁免）。 */
// 注意：不得含"心/力/生"等**可在真名末字出现**的字（实测 "弘心首座" 曾被 "心" 误杀）。
const BAD_FINAL = new Set('的了着过但是而如果那么就还也都很太更最把被使让给对向从与或则乃依旧竟却已未不没非常更加话事人时后前中里外上下起身手眼口头声气意思情语笑掌拳躬拜别答一动完好象样点面边间处算加'.split(''))
/** 程度/范围副词起首（"很好听""最要紧"）：整词不是人名。 */
const DEGREE_PREFIX = new Set('很挺更最极特超蛮'.split(''))
/** 数词/量词起首（"一脸""两人"）：整词不是人名（中文人名极少以"一/两"起首）。 */
const NUMERAL_PREFIX = new Set('一两'.split(''))
/** 身体/方位/心理部位短语（"仰脖子""心头一动""脸上"）：描述动作，不是人名。 */
const BODY_CONTEXT = ['脖子', '脑袋', '眼睛', '嘴巴', '手指', '手臂', '肩膀', '胳膊', '脸颊', '额头', '眉毛', '鼻子', '耳朵', '心头', '心中', '心里', '身上', '脸上', '手里', '眼中', '眼里', '嘴里', '脚下', '眼下', '背后', '身边', '面前', '眼前']

/** 介词/使役/被动起首：整词不是人名（"为李寄舟""被张三"）。 */
const PREP_PREFIX = new Set('为被把让使给替由'.split(''))
/** 指示/疑问/代词起首：整词不是人名（"这位婶娘""这两个字"）。 */
const DEMON_PREFIX = new Set('这那其此该某怎多么'.split(''))
/** 虚词前缀（连词/副词）："虽然不知"（=虽然+不知）这类双虚词切片。 */
const FUNCTION_PREFIXES = new Set(('虽然 因为 所以 倘若 如果 那么 于是 因此 然而 但是 而且 并且 同时 可以 应该 任何 如此 只是 只有 无论 不管 即使 即便 仍然 依然 忽然 突然 果然 当然 显然 其实 确实 简直 几乎 大概 也许 或者 不过 一旦 直到 随即 立刻 马上 终于 一直 再度 再次 十分 非常 尤其 渐渐 逐渐 勉强 不禁 顿时 登时 陡然 索性 干脆 偏偏 恰好 幸亏 幸好 好在 难道 不妨 除非 唯有 只要 万一 若是 假如 就算 哪怕 纵使 纵然 除了 关于 由于 为了 反而 反倒 甚至 不仅 不但 况且 何况 至于 从而 进而').split(' '))

function stats(text, s) {
  let idx = 0, total = 0, cueAfter = 0, punctLeft = 0, punctLeftNonCue = 0
  const rightKinds = new Set()
  while ((idx = text.indexOf(s, idx)) !== -1) {
    total++
    const r = text[idx + s.length]
    const l = idx > 0 ? text[idx - 1] : '\n'
    if (r !== undefined) {
      if (CUE_HEADS.has(r)) cueAfter++
      rightKinds.add(r)
    }
    if (PUNCT.has(l)) { punctLeft++; if (r === undefined || !CUE_HEADS.has(r)) punctLeftNonCue++ }
    idx += s.length
  }
  return { surface: s, len: s.length, total, cueAfter, free: total - cueAfter, rightKinds: rightKinds.size, punctLeft, punctLeftNonCue }
}

/** 闸门（顺序即优先级，返回第一个命中的拒因；null = 通过）。 */
function gate(f, legitNames) {
  const s = f.surface
  if (INTERJECTION.has(s)) return 'INTERJECTION'
  if (FUNCTION_WORDS.has(s)) return 'FUNCTION_WORD'
  if (ACTION_WORDS.has(s)) return 'ACTION_WORD'
  if (PREP_PREFIX.has(s[0])) return 'PREP_PREFIX'
  if (DEMON_PREFIX.has(s[0])) return 'DEMON_PREFIX'
  if (DEGREE_PREFIX.has(s[0]) || NUMERAL_PREFIX.has(s[0])) return 'FUNCTION_WORD'
  if (BODY_CONTEXT.some((b) => s.includes(b))) return 'ACTION_WORD'
  for (let len = 2; len <= 3; len++) {
    if (s.length - len < 2) break
    if (FUNCTION_PREFIXES.has(s.slice(0, len))) return 'FUNCTION_WORD'
  }
  // 真名 + 1~2 字残留 ⇒ 归并到真名（放在语料判据前：拒因更可审计）
  for (const p of legitNames) {
    if (p.length >= 2 && s.startsWith(p) && s.length > p.length) {
      const rest = s.slice(p.length)
      if (rest.length <= 2 && !TITLE_SUFFIX.has(rest)) return `NAME_PLUS_RESIDUE:${p}`
    }
  }
  if (f.rightKinds < 3) return 'CUE_ONLY'                 // 语料证据：只在 cue 位置出现
  if (f.punctLeftNonCue < 1) return 'NO_INDEPENDENT'      // 语料证据：从未以独立短语出现
  // 结尾检查：先剥称谓后缀（"风陵师太"的"太"是称谓，不是虚词）
  let stem = s
  for (const t of TITLE_SUFFIX) if (stem.endsWith(t) && stem.length > t.length) { stem = stem.slice(0, -t.length); break }
  if (BAD_FINAL.has(stem[stem.length - 1])) return 'BAD_FINAL'
  return null
}

function main() {
  const [src, cache, labelsPath] = process.argv.slice(2)
  const text = fs.readFileSync(src, 'utf8')
  const names = JSON.parse(fs.readFileSync(cache, 'utf8')).names || []
  const labels = labelsPath ? JSON.parse(fs.readFileSync(labelsPath, 'utf8')) : {}
  const feats = names.map((s) => ({ ...stats(text, s), label: labels[s] || 'fragment' }))
  const real = feats.filter((r) => r.label === 'name')
  const frag = feats.filter((r) => r.label !== 'name')
  console.log(`candidates=${feats.length} labeled_name=${real.length} other=${frag.length}`)

  // 逐条累积通过（legitNames 随通过集合增长，模拟"先定干净集合、再剥残留"）
  const legit = []
  const reasonCount = {}
  const rejected = []
  for (const f of feats) {
    const r = gate(f, legit)
    if (r == null) legit.push(f.surface)
    else { reasonCount[r.split(':')[0]] = (reasonCount[r.split(':')[0]] || 0) + 1; rejected.push({ ...f, reason: r }) }
  }
  const lostNames = real.filter((r) => !legit.includes(r.surface))
  const keptFrag = frag.filter((r) => legit.includes(r.surface))
  console.log(`survive=${legit.length} rejected=${rejected.length}`)
  console.log(`byReason=${JSON.stringify(reasonCount)}`)
  console.log(`name_recall_kept=${real.length - lostNames.length}/${real.length}`)
  console.log(`pollution_killed=${frag.length - keptFrag.length}/${frag.length}`)
  if (lostNames.length) console.log(`LOST_NAMES=[${lostNames.map((r) => r.surface).join(' ')}]`)
  console.log(`\n--- 残留污染 ${keptFrag.length} ---`)
  console.log(keptFrag.sort((a, b) => b.total - a.total).map((r) => `${r.surface}(${r.total},k${r.rightKinds})`).join(' '))
}

main()
