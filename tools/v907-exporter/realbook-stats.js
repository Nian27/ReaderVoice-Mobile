#!/usr/bin/env node
/**
 * PRIVATE_REALBOOK_001 私有分析（TASK-050 S8）
 * 纯文本统计（candidate，非 ground truth，§115）：
 * 引号对白段 / 名字候选 / 关系称谓 / 临时换声触发上下文 / 对话密度。
 * 真实书 gitignored；报告只输出聚合统计（§72）。
 * 用法：node tools/v907-exporter/realbook-stats.js
 */
'use strict';
const fs = require('fs');
const path = require('path');
const crypto = require('crypto');

const BOOK_PATH = path.resolve(__dirname, '../../娱乐：从1990年开始 作者：咖啡香草.txt');
const BOOK_ID = 'PRIVATE_REALBOOK_001';

const KINSHIP = /师父|徒弟|母亲|父亲|妈妈|爸爸|老婆|丈夫|媳妇|哥哥|弟弟|姐姐|妹妹|爷爷|奶奶|老师|同学|朋友|兄弟/;
const TEMP_VOICE_TRIGGER = /压低声音|压着嗓子|变声|伪装|模仿|粗着嗓子|尖着嗓子|故意用.*声音|换了个声音|捏着嗓子/;

function analyze() {
  if (!fs.existsSync(BOOK_PATH)) {
    console.log('SKIP_PRIVATE_FIXTURE: real book not present (gitignored)');
    return;
  }
  const text = fs.readFileSync(BOOK_PATH, 'utf8');
  const lines = text.split('\n');
  const sha = crypto.createHash('sha256').update(text).digest('hex').slice(0, 16);

  // 引号对白段（“...”）——candidate
  const quoteBlocks = (text.match(/“[^”]{1,200}”/g) || []).length;
  // 名字候选（简化：引号前 6 字内的人名样 token 由规则抓“XX说/道”）
  const speechCues = (text.match(/[^，。！？\n]{1,12}(?:说道|说|问|答|喊|叫|道)[：:]?/g) || []).length;
  // 关系称谓行
  const kinshipHits = lines.filter(l => KINSHIP.test(l) && l.length < 80).length;
  // 临时换声触发上下文
  const tempHits = lines.filter(l => TEMP_VOICE_TRIGGER.test(l)).length;
  // 对话密度
  const blankish = lines.filter(l => l.trim().length === 0).length;

  const report = {
    book_test_id: BOOK_ID,
    title: '《娱乐：从1990年开始》',
    author_candidate: '咖啡香草',
    source_hash_prefix: sha,
    size_bytes: Buffer.byteLength(text, 'utf8'),
    line_count: lines.length,
    quote_dialogue_candidates: quoteBlocks,
    speech_cue_candidates: speechCues,
    kinship_line_candidates: kinshipHits,
    temp_voice_trigger_candidates: tempHits,
    blank_line_count: blankish,
    note: '全部为 candidate 统计，非 ground truth（§115）；仅供 Hard Case 提取与 TASK-060/070 数据准备',
    status: 'REALBOOK_OBSERVED_PENDING_AUDIT',
  };
  const out = path.resolve(__dirname, '../../docs/experiments/TASK050_PRIVATE_REALBOOK_REPORT.json');
  fs.mkdirSync(path.dirname(out), { recursive: true });
  fs.writeFileSync(out, JSON.stringify(report, null, 1));
  console.log(JSON.stringify(report, null, 1));
}

analyze();
