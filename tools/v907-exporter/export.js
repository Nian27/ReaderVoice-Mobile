#!/usr/bin/env node
/**
 * v90.7 Legacy Behavior Exporter（TASK-050 S4）
 * - Harness trace → LegacyBehaviorRecord JSONL（canonical schema，§79-§82）
 * - deterministic record_id = hash(book_uid, paragraph_uid, behavior_type, legacy_version, evidence)
 * - metadata export 可 commit；private export（含 context text）写 research/private/（gitignored）
 * - ReaderVoice DB 只读（node:sqlite readOnly，§124）
 * 用法：node tools/v907-exporter/export.js <trace.jsonl> <out.ndjson>
 */
'use strict';
const fs = require('fs');
const path = require('path');
const crypto = require('crypto');

const LEGACY_VERSION = 'tts-rule-2.85-v907';
const SCHEMA_VERSION = 'legacy-behavior-record-v1';
const EXTRACTOR_VERSION = 'v907-exporter-1.0';
const V907_SHA256 = '35c7d654274fa0f0ad8bda0d6498e23789a2f2f053cdbd8ec8eb48f02759842e';

const BEHAVIOR_TYPES = new Set([
  'UTTERANCE_DECISION', 'SPEAKER_DECISION', 'CHARACTER_CREATE', 'ALIAS_CANDIDATE',
  'IDENTITY_SAME', 'IDENTITY_DIFFERENT', 'RELATION_EVIDENCE', 'MERGE', 'SPLIT',
  'VOICE_AGE_UPDATE', 'TEMP_VOICE_START', 'TEMP_VOICE_CONTINUE', 'TEMP_VOICE_REPLACE', 'TEMP_VOICE_END',
  'VOICE_BIND', 'VOICE_LOCK', 'VOICE_UNLOCK', 'CACHE_FALLBACK', 'ERROR_FALLBACK',
]);

function recordId(bookUid, paragraphUid, behaviorType, evidence) {
  return crypto.createHash('sha256')
    .update([bookUid, paragraphUid, behaviorType, LEGACY_VERSION, JSON.stringify(evidence || null)].join('|'))
    .digest('hex').slice(0, 32);
}

function makeRecord(trace) {
  const behaviorType = trace.behavior_type;
  if (!BEHAVIOR_TYPES.has(behaviorType)) return null;
  const evidence = trace.evidence || [];
  return {
    record_id: recordId(trace.book_uid, trace.paragraph_uid, behaviorType, evidence),
    book_uid_hash: trace.book_uid,
    source_revision_hash: trace.source_revision_hash || null,
    chapter_index: trace.chapter_index ?? null,
    paragraph_uid: trace.paragraph_uid,
    paragraph_revision_uid: trace.paragraph_revision_uid || null,
    narrative_position: trace.narrative_position || null, // {chapter_index, paragraph_index, source_codepoint_start}
    behavior_type: behaviorType,
    input_refs: trace.input_refs || [],
    legacy_output: trace.legacy_output,
    legacy_state_before: trace.legacy_state_before || null,
    legacy_state_after: trace.legacy_state_after || null,
    evidence,
    provenance: trace.provenance || 'LEGACY_HARNESS_REPRODUCED', // LEGACY_STATIC_INFERRED/HARNESS_REPRODUCED/PRIVATE_BOOK_OBSERVED/API_STUBBED
    confidence_level: trace.confidence_level || 'HARNESS_VERIFIED', // STATIC_ONLY/HARNESS_VERIFIED/REALBOOK_OBSERVED/HUMAN_AUDITED
    quality: trace.quality || 'LEGACY_LABEL', // LEGACY_LABEL（仅 HUMAN_AUDITED 才可能 Gold）
    legacy_version: LEGACY_VERSION,
    extractor_version: EXTRACTOR_VERSION,
    schema_version: SCHEMA_VERSION,
    context_text: trace.context_text || null, // 仅 private export 保留；metadata export 为 null
  };
}

function exportTrace(tracePath, outPath, opts = { private: false }) {
  const lines = fs.readFileSync(tracePath, 'utf8').split('\n').filter(Boolean);
  const out = [];
  const byType = {};
  for (const line of lines) {
    const trace = JSON.parse(line);
    if (!opts.private) trace.context_text = null; // metadata export 不保留正文（§127）
    const rec = makeRecord(trace);
    if (!rec) continue;
    out.push(rec);
    byType[rec.behavior_type] = (byType[rec.behavior_type] || 0) + 1;
  }
  fs.mkdirSync(path.dirname(outPath), { recursive: true });
  fs.writeFileSync(outPath, out.map(r => JSON.stringify(r)).join('\n') + '\n');
  const manifest = {
    v907_sha256: V907_SHA256,
    extractor_version: EXTRACTOR_VERSION,
    schema_version: SCHEMA_VERSION,
    created_at: new Date().toISOString(),
    record_count: out.length,
    record_count_by_type: byType,
    book_count: new Set(out.map(r => r.book_uid_hash)).size,
    private_export: !!opts.private,
    determinism: 'record_id = sha256(book_uid|paragraph_uid|behavior_type|legacy_version|evidence)',
  };
  fs.writeFileSync(outPath.replace(/\.ndjson$/, '.manifest.json'), JSON.stringify(manifest, null, 1));
  return manifest;
}

/** ReaderVoice DB 只读验证（§124/§155 G13）：node:sqlite readOnly 打开 + SELECT */
function verifyDbReadOnly(dbPath) {
  const { DatabaseSync } = require('node:sqlite');
  const db = new DatabaseSync(dbPath, { readOnly: true });
  const rows = db.prepare('SELECT count(*) AS c FROM book').get();
  db.close();
  return rows.c;
}

module.exports = { exportTrace, makeRecord, recordId, verifyDbReadOnly };

if (require.main === module) {
  const [tracePath, outPath] = process.argv.slice(2);
  if (!tracePath || !outPath) { console.error('usage: export.js <trace.jsonl> <out.ndjson> [--private]'); process.exit(1); }
  const m = exportTrace(tracePath, outPath, { private: process.argv.includes('--private') });
  console.log(JSON.stringify(m, null, 1));
}
