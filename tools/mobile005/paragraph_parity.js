// paragraph_parity.js — M1 / G1：设备端与桌面端段落化逐段对拍
// 判据：记录数相同 + 每条 {pid,rid,idx,ch,block,policy,conf,len,sha} 全等
// usage: node paragraph_parity.js <desktop.jsonl> <device.jsonl>
'use strict';
const fs = require('fs');
const rd = (p) => fs.readFileSync(p, 'utf8').trim().split(/\r?\n/).map((l) => JSON.parse(l));
const A = rd(process.argv[2]), B = rd(process.argv[3]);
const FIELDS = ['pid', 'rid', 'idx', 'ch', 'block', 'policy', 'conf', 'len', 'sha'];

console.log(`desktop records=${A.length}  device records=${B.length}`);
if (A.length !== B.length) console.log(`★ 记录数不同`);

const n = Math.min(A.length, B.length);
let mismatch = 0, firstAt = -1, shaOnly = 0;
const diffFields = {};
for (let i = 0; i < n; ++i) {
  for (const f of FIELDS) {
    const a = A[i][f], b = B[i][f];
    if (a !== b) {
      diffFields[f] = (diffFields[f] || 0) + 1;
      if (firstAt < 0) firstAt = i;
      mismatch++;
      if (f === 'sha' && FIELDS.every((x) => x === 'sha' || A[i][x] === B[i][x])) shaOnly++;
      break;
    }
  }
}
if (mismatch === 0 && A.length === B.length) {
  console.log('G1 PASS —— 逐段完全一致（含 normalizedText 的 sha256）');
  console.log(`  抽样：i=0 pid=${A[0].pid} len=${A[0].len} sha=${A[0].sha.slice(0, 16)}…`);
  console.log(`        i=${n - 1} pid=${A[n - 1].pid} len=${A[n - 1].len} sha=${A[n - 1].sha.slice(0, 16)}…`);
} else {
  console.log(`G1 FAIL —— 首个不一致在第 ${firstAt} 段`);
  console.log('  字段差异分布:', diffFields);
  if (firstAt >= 0) {
    console.log('  desktop:', JSON.stringify(A[firstAt]));
    console.log('  device :', JSON.stringify(B[firstAt]));
  }
}
process.exit(mismatch === 0 && A.length === B.length ? 0 : 1);
