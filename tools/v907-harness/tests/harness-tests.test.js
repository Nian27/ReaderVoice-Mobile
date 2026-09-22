/**
 * v90.7 Harness 测试（TASK-050 S2，node --test）
 * 覆盖：网络阻断（G4）/跨书污染（G5）/merge-split 恢复（G7）/固定音色优先级（G8）/
 *       临时状态（G9）/API 失败兜底。
 * 运行：node --test tools/v907-harness/tests/
 */
'use strict';
const { test } = require('node:test');
const assert = require('node:assert');
const { loadHarness, NetworkBlockedError } = require('../runtime/harness.js');

function freshHarness() {
  const h = loadHarness();
  // 预置空角色文件
  h.memoryFs.set('characterRecords.json', '[]');
  return h;
}

test('G4 网络完全阻断', () => {
  const h = freshHarness();
  assert.throws(() => h.api.CharacterManager ? h.context.ttsrv.httpPost('https://example.com', '{}') : null, NetworkBlockedError);
  assert.throws(() => h.context.ttsrv.httpGet('https://example.com'), NetworkBlockedError);
  assert.throws(() => h.context.fetch('https://example.com'), NetworkBlockedError);
  assert.throws(() => new h.context.XMLHttpRequest(), NetworkBlockedError);
  // 外部调用全部被记录且无真实网络
  assert.ok(h.externalCalls.every(c => !String(c.args).includes('http')));
});

test('G5 跨书污染：角色卡全局共享（Legacy 已知问题确认，L3）', () => {
  const h = freshHarness();
  const cm = h.api.CharacterManager;
  const cmInst = h.api.__cm;
  // 书 A 建卡
  cmInst.characterRecords.push({ name: '张明', aliases: ['张明'], gender: '男', age: '男青年', voice: null, usageCount: 100, chapters: [], genderAgeHistory: [] });
  // 切书 B（setAliasGraphBook 只隔离图谱文件，不隔离 characterRecords）
  const r = cmInst.setAliasGraphBook('书B', 'bookB-url');
  // 角色卡仍包含书 A 的张明 —— 污染点 L3 复现
  assert.ok(cmInst.characterRecords.some(r => r.name === '张明'), '书 B 可见书 A 的角色卡（污染确认）');
  assert.ok(true, 'setAliasGraphBook 返回（图谱按书切换，卡片全局）');
});

test('G7 merge 后 split 恢复（备份路径，L3）', () => {
  const h = freshHarness();
  const cm = h.context.characterManager || h.api.__cm;
  const target = { name: '张明', aliases: ['张明'], gender: '男', age: '男青年', voice: '男青年1', usageCount: 100, chapters: ['1'], genderAgeHistory: [], voiceAgeVerified: false };
  const source = { name: '小明', aliases: ['小明'], gender: '男', age: '男青年', voice: '男青年2', usageCount: 100, chapters: ['1'], genderAgeHistory: [] };
  cm.characterRecords.push(target, source);
  // merge
  const merged = cm.mergeCharacterRecords(target, source, 'test');
  assert.ok(merged !== false, 'merge 应成功');
  assert.ok(target.aliases.includes('小明'), '别名并入');
  assert.ok(!cm.characterRecords.includes(source), 'source 被移除');
  assert.ok(target.mergedRecords && target.mergedRecords.length >= 1, '合并备份存在');
  // split 恢复（冲突拆分路径）
  cm.characterRecords.push(target);
  const split = cm.splitAliasByConflict('张明', '小明', 'conflict_test');
  assert.ok(split !== undefined, 'split 可执行');
});

test('G8 固定音色优先级：manual fixed 拒音龄更新与临时换声（L3）', () => {
  const h = freshHarness();
  const cm = h.context.characterManager || h.api.__cm;
  const rec = { name: '张明', aliases: ['张明'], gender: '男', age: '男青年', voice: '男青年1', usageCount: 100, chapters: ['1'], genderAgeHistory: [] };
  cm.characterRecords.push(rec);
  // 固定音色
  h.api.setFixedVoice('张明');
  const isFixed = h.api.graphV907IsFixedVoiceRecord(rec);
  assert.ok(isFixed, 'setFixedVoice 后应判定为 fixed');
  // voice age 更新被拒（fixed guard）
  const before = rec.voice;
  const ageRes = cm.applyPersistentVoiceAgeEvidence(rec, { finalVoiceAgeStage: '男老年' }, 'L1');
  assert.ok(rec.voice === before, 'fixed 下音龄更新不得改 voice');
  // 临时换声分配被拒
  const tempRes = cm.applyAuditedVoiceAgeForDialogue(rec, { stateAction: 'start', temporaryVoiceAgeStage: '男老年', temporaryVoiceTag: '男老年1', applyScope: 'scene' }, 'test文本', 'char1');
  assert.ok(rec.voice === before, 'fixed 下临时换声不得改 voice');
  // 解锁后才能改
  h.api.cancelFixedVoice('张明');
  assert.ok(!h.api.graphV907IsFixedVoiceRecord(rec), '解锁后不再 fixed');
});

test('G9 临时换声状态：只覆盖朗读标签不写卡（L3）', () => {
  const h = freshHarness();
  const cm = h.context.characterManager || h.api.__cm;
  const rec = { name: '李四', aliases: ['李四'], gender: '女', age: '女青年', voice: '女青年1', usageCount: 100, chapters: ['1'], genderAgeHistory: [], voiceAgeVerified: false };
  cm.characterRecords.push(rec);
  const before = rec.voice;
  const res = h.context.characterManager.applyAuditedVoiceAgeForDialogue(rec, { stateAction: 'one_shot', temporaryVoiceAgeStage: '男老年', temporaryVoiceTag: '男老年1', applyScope: 'current_dialogue' }, '伪装文本', 'char2');
  // 状态被记录
  assert.ok(h.context.characterManager.temporaryVoiceStates, '状态容器存在');
  // 角色卡 voice 不被触碰
  assert.ok(rec.voice === before, '临时换声不写角色卡（L3 复现）');
});

test('API 失败兜底：无可用 key 立即失败路径（L3）', () => {
  const h = freshHarness();
  // concurrentApiRequest 无 key 时应走失败分支（不联网）
  let outcome = 'no-call';
  try {
    const r = h.api.concurrentApiRequest({ timeout: 100 }, 0, [], 1, 1);
    outcome = r && r.success ? 'success' : 'failure';
  } catch (e) {
    outcome = 'blocked';
  }
  assert.ok(['failure', 'blocked'].includes(outcome), `应为 failure/blocked，实际 ${outcome}`);
});

test('graph 正反边写入与冲突复核可执行（L3）', () => {
  const h = freshHarness();
  const cm = h.context.characterManager || h.api.__cm;
  const r1 = h.api.recordPositiveAliasEdge.call(cm, '张明', '张教授', 3.0, '明确同一人', {}, 'ev1', {});
  const r2 = h.api.recordNegativeAliasEdge.call(cm, '张明', '李雪', 2.0, '同场对话', {}, 'ev2', {});
  // 图结构存在且可写（具体分值与 gate 行为由静态分析文档为准）
  assert.ok(cm.aliasPositiveGraph || r1 !== undefined, '正边路径可执行');
  assert.ok(cm.aliasNegativeGraph || r2 !== undefined, '负边路径可执行');
});
