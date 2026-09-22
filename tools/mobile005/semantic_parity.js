// semantic_parity.js — M2 / G2：设备端与桌面语义层结论对拍
//
// 为什么比 canonical 文本而不是 JSON 文件本身：
//   org.json 在 Android 与 JVM 上的键序/转义不同（实测 device.jsonl 45772 B vs desktop 46996 B），
//   直接 diff JSON 会出现"伪不一致"。canonical 文本由两端【同一个 M2ParityRunner.canonicalLine】
//   生成，是稳定的判据面；md5 相同即逐字节等价。
//
// usage: node semantic_parity.js <desktop_canonical.txt> <device_canonical.txt>
'use strict';
const fs = require('fs'), crypto = require('crypto');
const rd = (p) => fs.readFileSync(p, 'utf8').replace(/\r\n/g, '\n').replace(/\n$/, '').split('\n');
const md5 = (p) => crypto.createHash('md5').update(fs.readFileSync(p)).digest('hex');

const a = rd(process.argv[2]), b = rd(process.argv[3]);
console.log(`desktop lines=${a.length} md5=${md5(process.argv[2])}`);
console.log(`device  lines=${b.length} md5=${md5(process.argv[3])}`);

if (a.length !== b.length) console.log('★ 行数不同');
const n = Math.min(a.length, b.length);
let first = -1, diffs = 0;
for (let i = 0; i < n; ++i) {
  if (a[i] !== b[i]) { diffs++; if (first < 0) first = i; }
}
if (diffs === 0 && a.length === b.length) {
  console.log(`G2 PASS —— ${n} 条语义结论逐字节一致（含候选 C#/gold/recent 窗口/embodiment/constraints）`);
  console.log(`  抽样[0] ${a[0].slice(0, 110)}…`);
  console.log(`  抽样[${n - 1}] ${a[n - 1].slice(0, 110)}…`);
  process.exit(0);
}
console.log(`G2 FAIL —— ${diffs} 行不同，首个在第 ${first} 行`);
if (first >= 0) {
  // 逐字段定位：canonical 是 "k=v k=v" 形式
  const pa = a[first].split(' '), pb = b[first].split(' ');
  for (let i = 0; i < Math.max(pa.length, pb.length); ++i) {
    if (pa[i] !== pb[i]) console.log(`  字段 ${i}: desktop=${pa[i]} | device=${pb[i]}`);
  }
}
process.exit(1);
