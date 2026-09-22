#!/usr/bin/env node
/**
 * v90.7 Offline Legacy Harness（TASK-050 S2）
 * - 在 Node vm sandbox 中加载 redacted.js（仅分析副本，内存加载不落盘，不修改 raw）
 * - 网络 kill switch：ttsrv.httpPost / fetch / XMLHttpRequest → BLOCKED_EXTERNAL_NETWORK
 * - java shim：并发 API 依赖的最小桩（同步执行）
 * - 内存文件系统：readTxtFile/writeTxtFile（book 隔离由测试控制）
 * 用法：node tools/v907-harness/runtime/harness.js
 */
'use strict';
const fs = require('fs');
const path = require('path');
const vm = require('vm');

const SRC = path.resolve(__dirname, '../../../research/third_party/legado-v907/v90.7.code.redacted.js');

class NetworkBlockedError extends Error {
  constructor(target) { super(`BLOCKED_EXTERNAL_NETWORK: ${target}`); this.name = 'NetworkBlockedError'; }
}

/** 最小 CountDownLatch（同步语义：count 归零即放行） */
class CountDownLatch {
  constructor(count) { this.count = count; }
  countDown() { if (this.count > 0) this.count--; }
  await() { if (this.count !== 0) throw new Error('latch not zero (async unsupported in harness)'); }
  getCount() { return this.count; }
}

function buildSandbox(files = {}) {
  const memoryFs = new Map(Object.entries(files));
  const externalCalls = [];
  const fsApi = {
    readTxtFile(name) { externalCalls.push({ fn: 'readTxtFile', args: name }); return memoryFs.get(name) ?? null; },
    writeTxtFile(name, content) { externalCalls.push({ fn: 'writeTxtFile', args: name }); memoryFs.set(name, content); return true; },
    readFile: fsApiRead,
    writeFile: fsApiWrite,
    existsFile(name) { return memoryFs.has(name); },
    deleteFile(name) { memoryFs.delete(name); return true; },
  };
  function fsApiRead(name) { externalCalls.push({ fn: 'readFile', args: name }); return memoryFs.get(name) ?? null; }
  function fsApiWrite(name, content) { externalCalls.push({ fn: 'writeFile', args: name }); memoryFs.set(name, content); return true; }

  const sandbox = {
    console,
    ttsrv: {
      readTxtFile: fsApi.readTxtFile,
      writeTxtFile: fsApi.writeTxtFile,
      existsFile: fsApi.existsFile,
      deleteFile: fsApi.deleteFile,
      httpPost: () => { throw new NetworkBlockedError('ttsrv.httpPost'); },
      httpGet: () => { throw new NetworkBlockedError('ttsrv.httpGet'); },
      readFile: fsApiRead,
      writeFile: fsApiWrite,
    },
    java: {
      lang: {
        Thread: class {
          constructor(r) { this.runnable = r; }
          start() { this.runnable.run(); }        // 同步执行（并发语义由 Harness 测试控制）
          join() {}
          interrupt() {}
        },
        Math: Math,
        System: { currentTimeMillis: () => Date.now() },
      },
      util: {
        concurrent: {
          CountDownLatch,
          AtomicInteger: class { constructor(v = 0) { this.v = v; } get() { return this.v; } set(v) { this.v = v; } incrementAndGet() { return ++this.v; } addAndGet(n) { this.v += n; return this.v; } },
        },
      },
    },
    // 网络 kill switch（§63）
    fetch: () => { throw new NetworkBlockedError('fetch'); },
    XMLHttpRequest: class { constructor() { throw new NetworkBlockedError('XMLHttpRequest'); } },
    // 常见缺失全局 → undefined（避免 ReferenceError 中断初始化）
    setTimeout, clearTimeout, setInterval, clearInterval,
    Promise, Math, Date, JSON, Object, Array, String, Number, Boolean, RegExp, Error, parseInt, parseFloat, isNaN,
    // Legado 引擎环境（最小占位）
    AppLog: { put: () => {} },
    ttsServerPlugins: {},
    localSoundOnoMap: {},
    yinxiao: {},
    gengxin: {},
    cunfang: {},
    liebiao: {},
    fayinren: {},
    global: undefined,
  };
  sandbox.global = sandbox;
  sandbox.window = sandbox;
  sandbox.self = sandbox;
  sandbox.globalThis = sandbox;

  const context = vm.createContext(new Proxy(sandbox, {
    get(target, prop) {
      if (prop in target) return target[prop];
      return undefined; // 未知全局 → undefined（v90.7 用 typeof 防御）
    },
    set(target, prop, value) { target[prop] = value; return true; },
    has(target, prop) { return true; }, // 让 typeof x !== 'undefined' 对所有名称可用
  }));
  return { context, memoryFs, externalCalls };
}

/** 加载脚本到 sandbox 并返回句柄（内存注入导出，不落盘） */
function loadHarness(files = {}) {
  const { context, memoryFs, externalCalls } = buildSandbox(files);
  let code = fs.readFileSync(SRC, 'utf8');
  // 在脚本末尾注入导出（内存副本改造，不修改 raw；§61 允许）
  code += '\n;globalThis.__v907_export = {}\n';
  // 把顶层 var 函数挂到导出（var 在 context 全局可访问）
  const exportNames = [
    'CharacterManager', 'concurrentApiRequest', 'voteNameAnalyzeResult', 'voteAliasAnalyzeResult',
    'graphAddWeightedEdge', 'recordPositiveAliasEdge', 'recordNegativeAliasEdge', 'verifyGraphConflictAndFix',
    'mergeCharacterRecords', 'splitAliasByConflict', 'assignVoice', 'setFixedVoice', 'cancelFixedVoice',
    'graphV907IsFixedVoiceRecord', 'graphV907MarkFixedVoiceRecord', 'enforceFixedVoiceRecordV907',
    'applyAuditedVoiceAgeForDialogue', 'endTemporaryVoiceState', 'tryRestoreTemporaryVoiceSnapshot',
    'graphV907NormalizeAgeForVoice', 'graphV907NormalizeGenderForVoice', 'applyPersistentVoiceAgeEvidence',
    'updateAliasGraphsFromCache', 'setAliasGraphBook', 'saveRecords', 'loadRecords', 'v87EnsureRecordId',
    'getAllCharacterNamesAndAliases', 'rebuildNameToMainNameMap', 'analyzeCharacterFallback',
  ];
  for (const n of exportNames) {
    // var 函数（顶层）或 CharacterManager.prototype 方法
    code += `\n;if (typeof ${n} !== 'undefined') globalThis.__v907_export.${n} = ${n};\n` +
      `\n;if (typeof CharacterManager !== 'undefined' && CharacterManager.prototype && typeof CharacterManager.prototype.${n} !== 'undefined' && typeof globalThis.__v907_export.${n} === 'undefined') globalThis.__v907_export.${n} = CharacterManager.prototype.${n}.bind(globalThis.__v907_export.__cm || (globalThis.__v907_export.__cm = new CharacterManager()));\n`;
  }
  code += '\n;globalThis.__v907_export.done = true;\n';

  try {
    vm.runInContext(code, context, { filename: 'v90.7.code.redacted.js' });
  } catch (e) {
    // 初始化失败不影响导出测试（部分函数可能未定义）
    console.error('[harness] init warning:', e.message);
  }
  const api = context.__v907_export || {};
  return { api, memoryFs, externalCalls, context };
}

module.exports = { loadHarness, NetworkBlockedError };

if (require.main === module) {
  const h = loadHarness();
  const exported = Object.keys(h.api).filter(k => h.api[k]);
  console.log('导出函数数:', exported.length, ':', exported.join(', '));
  console.log('外部调用:', h.externalCalls.length);
}
