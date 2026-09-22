#!/usr/bin/env node
/**
 * v90.7 Static Analyzer（TASK-050 S1）
 * - acorn 仅 parse 不 execute（§8）
 * - 输出 V907_SYMBOL_INDEX.json + V907_STATE_INVENTORY.json + V907_CALL_GRAPH.json
 * - Evidence level：L1（AST 发现）/ L2（完整路径，由人工/Harness 确认后升级）
 * 用法：node tools/v907-analyzer/analyze.js
 */
'use strict';
const fs = require('fs');
const path = require('path');
const acorn = require('acorn');

const SRC = path.resolve(__dirname, '../../research/third_party/legado-v907/v90.7.code.redacted.js');
const OUT = path.resolve(__dirname, '../../docs/baseline/v907');

const DOMAIN_HINTS = [
  [/config|threshold|timeout|WAIT_|ENABLE_|MAX_|MIN_|LIMIT|ratio/i, 'CONFIG'],
  [/speaker|speak|role|character|duihua|localSpeaker/i, 'SPEAKER'],
  [/alias|mainName|identity|graph|evidence|cooccur|relation/i, 'IDENTITY'],
  [/merge|split|backup|restore/i, 'MERGE'],
  [/voice|age|gender|pitch|tone/i, 'VOICE'],
  [/temp|temporary|stateAction|applyScope|disguise/i, 'TEMP_VOICE'],
  [/cache|dialog|chapter|context|xiawen|shouci/i, 'CACHE'],
  [/api|request|vote|http|key|model/i, 'API'],
  [/graph|edge|score|block/i, 'GRAPH'],
  [/tag|label|route|pool|bucket/i, 'VOICE_POOL'],
  [/save|load|read|write|json|file|persist/i, 'PERSISTENCE'],
  [/error|fail|retry|fallback|timeout/i, 'ERROR'],
  [/report|log|rizhi/i, 'LOG'],
];

function domainOf(name) {
  for (const [re, d] of DOMAIN_HINTS) if (re.test(name)) return d;
  return 'OTHER';
}

function analyze() {
  const code = fs.readFileSync(SRC, 'utf8');
  let ast;
  try {
    ast = acorn.parse(code, { ecmaVersion: 2020, locations: true, allowReturnOutsideFunction: true });
  } catch (e) {
    console.error('AST parse failed:', e.message);
    process.exit(1);
  }

  const symbols = [];       // functions + vars
  const states = [];        // state-like variables
  const callGraph = {};     // caller -> callees
  const storageKeys = new Set();
  const funcByLine = new Map(); // line -> function name

  const stateHints = /records|map|graph|stats|state|cache|snapshot|backup|pool|evidence|history|usage|queue/i;

  function walk(node, scope) {
    if (!node || typeof node !== 'object') return;
    switch (node.type) {
      case 'FunctionDeclaration':
        addSymbol(node.id.name, 'FUNCTION', node.loc.start.line, node.loc.end.line, node);
        funcByLine.set(node.loc.start.line, node.id.name);
        collectCalls(node, node.id.name);
        walk(node.body, node.id.name);
        return;
      case 'FunctionExpression':
      case 'ArrowFunctionExpression': {
        const name = node.id ? node.id.name : (node.parentKey === 'value' && node.parentProp ? String(node.parentProp) : null);
        if (name) {
          addSymbol(name, 'FUNCTION', node.loc.start.line, node.loc.end.line, node);
          collectCalls(node, name);
        }
        walk(node.body);
        return;
      }
      case 'VariableDeclaration':
        for (const d of node.declarations) {
          if (d.id.type === 'Identifier') {
            const name = d.id.name;
            const isState = stateHints.test(name);
            addSymbol(name, isState ? 'STATE' : 'VARIABLE', node.loc.start.line, (d.init && d.init.loc) ? d.init.loc.end.line : node.loc.end.line, node, isState);
            if (isState) states.push({ name, line: node.loc.start.line, declaredType: d.init ? d.init.type : 'declared' });
            if (d.init) {
              collectCalls(d.init, name);
              walk(d.init);
            }
          }
        }
        return;
      case 'CallExpression': {
        if (node.callee.type === 'Identifier') {
          const fname = node.callee.name;
          const caller = funcByLine.get(node.loc.start.line) || scope || 'GLOBAL';
          if (!callGraph[caller]) callGraph[caller] = new Set();
          callGraph[caller].add(fname);
        }
        break;
      }
      case 'MemberExpression': {
        if (node.property.type === 'Identifier') {
          const prop = node.property.name;
          // this.X 对象属性态（CharacterManager 等——真实 state 多在对象属性上）
          if (node.object.type === 'ThisExpression' && stateHints.test(prop)) {
            const isWrite = node.parent && node.parent.type === 'AssignmentExpression' && node.parent.left === node;
            addSymbol('this.' + prop, 'STATE', node.loc.start.line, node.loc.end.line, node, true);
            states.push({ name: 'this.' + prop, line: node.loc.start.line, declaredType: isWrite ? 'assigned' : 'accessed' });
          }
        }
        break;
      }
      case 'Literal':
        if (typeof node.value === 'string' && node.value.length < 120) {
          if (/\.json$|\.txt$|cache|graph|records|voice|alias|cooccur|dialog/i.test(node.value)) {
            storageKeys.add(node.value);
          }
        }
        break;
    }
    // 通用子节点遍历（记录 parent 上下文）
    for (const key of Object.keys(node)) {
      if (key === 'loc' || key === 'start' || key === 'end' || key === 'range') continue;
      const child = node[key];
      if (Array.isArray(child)) {
        for (const c of child) {
          if (c && typeof c === 'object') {
            c.parentKey = key;
            c.parentProp = key === 'properties' ? (c.key && c.key.name) : null;
            walk(c, scope);
          }
        }
      } else if (child && typeof child === 'object') {
        child.parentKey = key;
        child.parentProp = key === 'value' ? (node.key && node.key.name) : null;
        walk(child, scope);
      }
    }
  }

  function addSymbol(name, type, startLine, endLine, node, isState = false) {
    symbols.push({
      symbol_name: name,
      symbol_type: type,
      start_line: startLine,
      end_line: endLine,
      domain: domainOf(name),
      evidence_level: 'L1',
      risk: /eval|exec|Function\(|http|fetch|XMLHttpRequest/.test(name) ? 'HIGH' : 'LOW',
    });
  }

  function collectCalls(fnNode, fnName) {
    // 遍历函数体找调用，记录 callee（简化：收集直接 Identifier callee）
    const callees = new Set();
    (function collect(n) {
      if (!n || typeof n !== 'object') return;
      if (n.type === 'CallExpression' && n.callee.type === 'Identifier') {
        callees.add(n.callee.name);
        if (!callGraph[fnName]) callGraph[fnName] = new Set();
      }
      for (const k of Object.keys(n)) {
        if (['loc', 'start', 'end', 'range'].includes(k)) continue;
        const c = n[k];
        if (Array.isArray(c)) c.forEach(collect);
        else if (c && typeof c === 'object') collect(c);
      }
    })(fnNode.body);
    for (const c of callees) {
      if (!callGraph[fnName]) callGraph[fnName] = new Set();
      callGraph[fnName].add(c);
    }
  }

  ast.body.forEach((n, i) => {
    n.parentKey = 'body';
    n.parentProp = null;
    walk(n, 'GLOBAL');
  });

  fs.mkdirSync(OUT, { recursive: true });

  const symbolIndex = {
    meta: {
      source: 'v90.7.code.redacted.js',
      v907_sha256: '35c7d654274fa0f0ad8bda0d6498e23789a2f2f053cdbd8ec8eb48f02759842e',
      ast_parser: 'acorn@8',
      ecma_version: 2020,
      created_at: new Date().toISOString(),
      evidence_level: 'L1 (AST discovery; L2 requires control-flow audit)',
      function_count: symbols.filter(s => s.symbol_type === 'FUNCTION').length,
      state_count: symbols.filter(s => s.symbol_type === 'STATE').length,
      variable_count: symbols.filter(s => s.symbol_type === 'VARIABLE').length,
      total_symbols: symbols.length,
    },
    domains: [...new Set(symbols.map(s => s.domain))].sort().map(d => ({
      domain: d,
      count: symbols.filter(s => s.domain === d).length,
      functions: symbols.filter(s => s.domain === d && s.symbol_type === 'FUNCTION').map(s => s.symbol_name).slice(0, 50),
    })),
    symbols,
  };

  const stateInventory = {
    meta: { note: 'state-like globals (L1 discovery; persistence scope requires L2/L3 audit)' },
    states: states.map(s => ({
      name: s.name,
      line: s.line,
      declared_type: s.declaredType,
      scope: 'UNKNOWN',       // BOOK/CHARACTER/CHAPTER/SESSION/GLOBAL/CACHE 待 L2 确认
      persistence: 'UNKNOWN', // MEMORY_ONLY/JSON_PERSISTED/CACHE_PERSISTED/REMOTE_OPTIONAL
      owner: 'UNKNOWN',
      lifetime: 'UNKNOWN',
      mutated_by: [],
      mapping: 'INVESTIGATE',
    })),
  };

  const callGraphOut = {};
  for (const [caller, callees] of Object.entries(callGraph)) {
    callGraphOut[caller] = [...callees].sort();
  }

  fs.writeFileSync(path.join(OUT, 'V907_SYMBOL_INDEX.json'), JSON.stringify(symbolIndex, null, 1));
  fs.writeFileSync(path.join(OUT, 'V907_STATE_INVENTORY.json'), JSON.stringify(stateInventory, null, 1));
  fs.writeFileSync(path.join(OUT, 'V907_CALL_GRAPH.json'), JSON.stringify(callGraphOut, null, 1));

  console.log(JSON.stringify({
    functions: symbolIndex.meta.function_count,
    states: symbolIndex.meta.state_count,
    variables: symbolIndex.meta.variable_count,
    domains: symbolIndex.domains.map(d => `${d.domain}:${d.count}`).join(' '),
    storage_keys: storageKeys.size,
    callers: Object.keys(callGraphOut).length,
  }, null, 1));
}

analyze();
