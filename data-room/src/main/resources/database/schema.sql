-- ReaderVoice Database v1 — canonical schema（设计源，§123）
-- 实现约束：核心持久化表禁止 INSERT OR REPLACE（ADR-025，G11）；
-- FK：核心用户数据 RESTRICT，派生子表 CASCADE（§42/§43）；
-- ID：Hybrid——低数量跨系统对象用 UID，高数量热表用 INTEGER PRIMARY KEY（ADR-022，§45-§48）。
-- 本文件是 Room Entities 必须验证一致的设计源；ROOM_DEVICE_GATE=OPEN（本机无 Android SDK）。

PRAGMA foreign_keys = ON;

-- ============ Book / Source（§49/§50/§51） ============

CREATE TABLE book (
    book_pk              INTEGER PRIMARY KEY,        -- 无 AUTOINCREMENT（§48）
    book_uid             TEXT NOT NULL UNIQUE,       -- stable UID（跨系统）
    title                TEXT,
    author               TEXT,
    series_uid           TEXT,
    created_at           TEXT NOT NULL,
    last_opened_at       TEXT,
    deleted_at           TEXT,                        -- 软删除（§41）
    active_source_revision_pk INTEGER REFERENCES source_revision(source_revision_pk)
);

CREATE TABLE source_revision (
    source_revision_pk   INTEGER PRIMARY KEY,
    source_revision_uid  TEXT NOT NULL UNIQUE,
    book_pk              INTEGER NOT NULL REFERENCES book(book_pk) ON DELETE RESTRICT,
    source_uri           TEXT,
    local_path           TEXT NOT NULL,
    sha256               TEXT NOT NULL,
    byte_size            INTEGER NOT NULL,
    charset              TEXT,
    charset_confidence   REAL,
    charset_user_locked  INTEGER NOT NULL DEFAULT 0,
    imported_at          TEXT NOT NULL,
    parent_source_revision_pk INTEGER REFERENCES source_revision(source_revision_pk), -- 仅显式 SOURCE_REVISION
    revision_reason      TEXT,
    UNIQUE (book_pk, sha256)
);

CREATE TABLE physical_line (
    line_pk              INTEGER PRIMARY KEY,
    source_revision_pk   INTEGER NOT NULL REFERENCES source_revision(source_revision_pk) ON DELETE CASCADE,
    line_no              INTEGER NOT NULL,
    byte_start           INTEGER NOT NULL,
    byte_end             INTEGER NOT NULL,
    codepoint_start      INTEGER NOT NULL,
    codepoint_end        INTEGER NOT NULL,
    leading_ascii_spaces INTEGER NOT NULL DEFAULT 0,
    leading_fullwidth_spaces INTEGER NOT NULL DEFAULT 0,
    leading_tabs        INTEGER NOT NULL DEFAULT 0,
    trailing_spaces      INTEGER NOT NULL DEFAULT 0,
    char_count           INTEGER NOT NULL,
    han_count            INTEGER NOT NULL,
    is_blank             INTEGER NOT NULL DEFAULT 0,
    UNIQUE (source_revision_pk, line_no)               -- §75
);
CREATE INDEX idx_physical_line_src ON physical_line(source_revision_pk, line_no);

-- ============ Revision 核心（§5-§10/§86-§89） ============

CREATE TABLE artifact_revision (
    revision_id          INTEGER PRIMARY KEY,
    artifact_type        TEXT NOT NULL,               -- SOURCE/STRUCTURE/PARAGRAPH_RECOVERY（预留 CHARACTER/SEMANTIC/NARRATION/RENDER/AUDIO/SEARCH_INDEX）
    owner_scope          TEXT NOT NULL,               -- 如 book:<uid>、source:<uid>
    book_id              TEXT,
    source_revision_id   TEXT,
    parent_revision_id   INTEGER REFERENCES artifact_revision(revision_id),
    input_hash           TEXT NOT NULL,
    algorithm_version    TEXT NOT NULL,               -- 如 chapter_resolver_v3 / paragraph_boundary_v2（§88）
    status               TEXT NOT NULL,               -- BUILDING/ACTIVE/SUPERSEDED/FAILED/STALE（§6）
    created_at           TEXT NOT NULL,
    activated_at         TEXT,
    superseded_at        TEXT,
    reason               TEXT
);
CREATE INDEX idx_artifact_rev_owner ON artifact_revision(owner_scope, artifact_type, status);

CREATE TABLE artifact_head (                           -- §8：owner+type → 当前 active revision（不许 ORDER BY 猜）
    head_pk              INTEGER PRIMARY KEY,
    owner_scope          TEXT NOT NULL,
    artifact_type        TEXT NOT NULL,
    active_revision_id   INTEGER NOT NULL REFERENCES artifact_revision(revision_id),
    UNIQUE (owner_scope, artifact_type)
);

CREATE TABLE artifact_dependency (                     -- §9：stage-level DAG（§10 原则：不管 item 级）
    dependency_pk        INTEGER PRIMARY KEY,
    owner_scope          TEXT NOT NULL,
    dependent_revision_id   INTEGER NOT NULL REFERENCES artifact_revision(revision_id),
    dependency_revision_id  INTEGER NOT NULL REFERENCES artifact_revision(revision_id),
    UNIQUE (dependent_revision_id, dependency_revision_id)
);

CREATE TABLE build_session (                           -- §113/§114：crash 恢复
    session_id           TEXT PRIMARY KEY,
    artifact_type        TEXT NOT NULL,
    revision_id          INTEGER NOT NULL REFERENCES artifact_revision(revision_id),
    state                TEXT NOT NULL,               -- RUNNING/COMPLETED/FAILED_INTERRUPTED
    started_at           TEXT NOT NULL,
    heartbeat_at         TEXT,
    completed_at         TEXT,
    error                TEXT
);

-- ============ Structure（§52-§57） ============

CREATE TABLE structure_revision (
    structure_revision_pk INTEGER PRIMARY KEY,
    artifact_revision_id   INTEGER NOT NULL REFERENCES artifact_revision(revision_id),
    source_revision_pk     INTEGER NOT NULL REFERENCES source_revision(source_revision_pk),
    rule_pack_version      TEXT NOT NULL,
    resolver_version       TEXT NOT NULL,
    input_hash             TEXT NOT NULL,
    created_at             TEXT NOT NULL
);

CREATE TABLE chapter_candidate (
    candidate_pk         INTEGER PRIMARY KEY,
    structure_revision_pk INTEGER NOT NULL REFERENCES structure_revision(structure_revision_pk) ON DELETE CASCADE,
    line_pk              INTEGER NOT NULL REFERENCES physical_line(line_pk),
    rule_id              TEXT NOT NULL,
    family               TEXT NOT NULL,
    target_type          TEXT NOT NULL,
    serial_raw           TEXT,
    serial_value         INTEGER,
    regex_score          REAL,
    priority_score       REAL,
    length_score         REAL,
    spacing_score        REAL,
    sequence_score       REAL,
    style_score          REAL,
    final_score          REAL,
    evidence_mask        INTEGER,                      -- compact（§53/§54）
    decision             TEXT NOT NULL                -- CONFIRMED/PROVISIONAL/REJECTED/TOC_ENTRY
);

CREATE TABLE volume (
    volume_pk            INTEGER PRIMARY KEY,
    structure_revision_pk INTEGER NOT NULL REFERENCES structure_revision(structure_revision_pk) ON DELETE CASCADE,
    ordinal              INTEGER NOT NULL,
    serial_value         INTEGER,
    title                TEXT NOT NULL,
    anchor_line_pk       INTEGER NOT NULL REFERENCES physical_line(line_pk),
    confidence           REAL
);

CREATE TABLE chapter (
    chapter_pk           INTEGER PRIMARY KEY,
    structure_revision_pk INTEGER NOT NULL REFERENCES structure_revision(structure_revision_pk) ON DELETE CASCADE,
    volume_pk            INTEGER REFERENCES volume(volume_pk),
    chapter_index        INTEGER NOT NULL,             -- 书内顺序（§56）
    serial_value         INTEGER,
    serial_part          TEXT,
    title_raw            TEXT NOT NULL,
    title_clean          TEXT NOT NULL,
    chapter_type         TEXT NOT NULL,
    anchor_line_pk       INTEGER NOT NULL REFERENCES physical_line(line_pk),
    content_start_line_pk INTEGER REFERENCES physical_line(line_pk),
    content_end_line_pk  INTEGER REFERENCES physical_line(line_pk),
    confidence           REAL,
    user_locked          INTEGER NOT NULL DEFAULT 0
);
CREATE INDEX idx_chapter_struct ON chapter(structure_revision_pk, chapter_index);   -- §75

CREATE TABLE toc_block (
    toc_block_pk         INTEGER PRIMARY KEY,
    structure_revision_pk INTEGER NOT NULL REFERENCES structure_revision(structure_revision_pk) ON DELETE CASCADE,
    start_line_pk        INTEGER NOT NULL REFERENCES physical_line(line_pk),
    end_line_pk          INTEGER NOT NULL REFERENCES physical_line(line_pk),
    entry_count          INTEGER NOT NULL
);

CREATE TABLE toc_entry (
    toc_entry_pk         INTEGER PRIMARY KEY,
    toc_block_pk         INTEGER NOT NULL REFERENCES toc_block(toc_block_pk) ON DELETE CASCADE,
    line_pk              INTEGER NOT NULL REFERENCES physical_line(line_pk),
    serial_raw           TEXT,
    serial_value         INTEGER,
    title                TEXT NOT NULL
);

CREATE TABLE toc_body_link (
    link_pk              INTEGER PRIMARY KEY,
    toc_block_pk         INTEGER NOT NULL REFERENCES toc_block(toc_block_pk) ON DELETE CASCADE,
    toc_line_pk          INTEGER NOT NULL,
    body_line_pk         INTEGER,
    serial_value         INTEGER,
    title_similarity     REAL
);

-- ============ Paragraph（§58-§67） ============

CREATE TABLE paragraph_recovery_revision (
    pr_revision_pk       INTEGER PRIMARY KEY,
    artifact_revision_id  INTEGER NOT NULL REFERENCES artifact_revision(revision_id),
    source_revision_pk    INTEGER NOT NULL REFERENCES source_revision(source_revision_pk),
    structure_revision_pk INTEGER NOT NULL REFERENCES structure_revision(structure_revision_pk),
    layout_profiler_version  TEXT NOT NULL,
    boundary_classifier_version TEXT NOT NULL,
    join_policy_version   TEXT NOT NULL,
    block_detector_version TEXT NOT NULL,
    input_hash            TEXT NOT NULL,
    created_at            TEXT NOT NULL
);

CREATE TABLE layout_profile (
    profile_pk           INTEGER PRIMARY KEY,
    pr_revision_pk       INTEGER NOT NULL REFERENCES paragraph_recovery_revision(pr_revision_pk) ON DELETE CASCADE,
    scope_type           TEXT NOT NULL,               -- BOOK/CHAPTER/LOCAL
    profile_type         TEXT NOT NULL,
    stats_blob           TEXT,                        -- compact stats（§59）
    confidence           REAL
);

CREATE TABLE layout_region (
    region_pk            INTEGER PRIMARY KEY,
    pr_revision_pk       INTEGER NOT NULL REFERENCES paragraph_recovery_revision(pr_revision_pk) ON DELETE CASCADE,
    profile_pk           INTEGER REFERENCES layout_profile(profile_pk),
    start_line_pk        INTEGER NOT NULL REFERENCES physical_line(line_pk),
    end_line_pk          INTEGER NOT NULL REFERENCES physical_line(line_pk),
    profile_type         TEXT NOT NULL,
    confidence           REAL
);

CREATE TABLE line_boundary (                          -- 高数量表，字段紧凑（§60）
    boundary_pk          INTEGER PRIMARY KEY,
    pr_revision_pk       INTEGER NOT NULL REFERENCES paragraph_recovery_revision(pr_revision_pk) ON DELETE CASCADE,
    left_line_pk         INTEGER NOT NULL REFERENCES physical_line(line_pk),
    right_line_pk        INTEGER NOT NULL REFERENCES physical_line(line_pk),
    boundary_type        TEXT NOT NULL,
    confidence           REAL NOT NULL,
    evidence_mask        INTEGER NOT NULL DEFAULT 0,
    profile_pk           INTEGER REFERENCES layout_profile(profile_pk),
    user_locked          INTEGER NOT NULL DEFAULT 0
);
CREATE INDEX idx_boundary_pr_left ON line_boundary(pr_revision_pk, left_line_pk);   -- §75

CREATE TABLE logical_paragraph (                      -- 逻辑头（§61/§28）
    paragraph_pk         INTEGER PRIMARY KEY,
    paragraph_uid        TEXT NOT NULL UNIQUE,
    book_pk              INTEGER NOT NULL REFERENCES book(book_pk) ON DELETE RESTRICT,
    source_revision_pk   INTEGER NOT NULL REFERENCES source_revision(source_revision_pk),
    created_at           TEXT NOT NULL
);

CREATE TABLE paragraph_revision (                     -- §62
    paragraph_revision_pk INTEGER PRIMARY KEY,
    paragraph_pk         INTEGER NOT NULL REFERENCES logical_paragraph(paragraph_pk) ON DELETE RESTRICT,
    pr_revision_pk       INTEGER NOT NULL REFERENCES paragraph_recovery_revision(pr_revision_pk),
    chapter_pk           INTEGER REFERENCES chapter(chapter_pk),
    paragraph_index      INTEGER NOT NULL,
    block_type           TEXT NOT NULL,
    read_policy          TEXT NOT NULL,
    confidence           REAL NOT NULL,
    fingerprint          TEXT NOT NULL,               -- §30：source_revision+spans+role
    parent_revision_pk   INTEGER REFERENCES paragraph_revision(paragraph_revision_pk),
    revision_reason      TEXT NOT NULL,
    active               INTEGER NOT NULL DEFAULT 1
);
CREATE INDEX idx_para_rev_pr_chapter ON paragraph_revision(pr_revision_pk, chapter_pk, paragraph_index);  -- §75

CREATE TABLE paragraph_source_span (                  -- §63（Option B，ADR-019）
    span_pk              INTEGER PRIMARY KEY,
    paragraph_revision_pk INTEGER NOT NULL REFERENCES paragraph_revision(paragraph_revision_pk) ON DELETE CASCADE,
    order_index          INTEGER NOT NULL,
    line_pk              INTEGER NOT NULL REFERENCES physical_line(line_pk),
    source_codepoint_start INTEGER NOT NULL,
    source_codepoint_end INTEGER NOT NULL
);
CREATE INDEX idx_span_para ON paragraph_source_span(paragraph_revision_pk, order_index);  -- §75

CREATE TABLE normalized_transform (                   -- §64/§65
    transform_pk         INTEGER PRIMARY KEY,
    paragraph_revision_pk INTEGER NOT NULL REFERENCES paragraph_revision(paragraph_revision_pk) ON DELETE CASCADE,
    order_index          INTEGER NOT NULL,
    transform_type       TEXT NOT NULL,               -- IDENTITY/REMOVED_NEWLINE/INSERTED_SPACE/REMOVED_INDENT/REMOVED_TRAILING_LAYOUT
    source_span_pk       INTEGER REFERENCES paragraph_source_span(span_pk),
    synthetic_text       TEXT                          -- synthetic 空格：source_span=NULL + synthetic_text=" "（§65）
);

CREATE TABLE cross_paragraph_link (                   -- §66
    link_pk              INTEGER PRIMARY KEY,
    pr_revision_pk       INTEGER NOT NULL REFERENCES paragraph_recovery_revision(pr_revision_pk) ON DELETE CASCADE,
    left_paragraph_revision_pk  INTEGER NOT NULL REFERENCES paragraph_revision(paragraph_revision_pk),
    right_paragraph_revision_pk INTEGER NOT NULL REFERENCES paragraph_revision(paragraph_revision_pk),
    link_type            TEXT NOT NULL,               -- SPEECH_CUE/QUOTE_CONTINUATION/CONTINUATION/SCENE_CONTINUATION
    confidence           REAL NOT NULL,
    evidence_mask        INTEGER NOT NULL DEFAULT 0
);

CREATE TABLE paragraph_lineage (                      -- §31/§32
    lineage_pk           INTEGER PRIMARY KEY,
    from_paragraph_pk    INTEGER NOT NULL REFERENCES logical_paragraph(paragraph_pk) ON DELETE RESTRICT,
    to_paragraph_pk      INTEGER NOT NULL REFERENCES logical_paragraph(paragraph_pk) ON DELETE RESTRICT,
    lineage_type         TEXT NOT NULL,               -- MERGED_INTO/SPLIT_FROM/SUPERSEDES
    created_at           TEXT NOT NULL
);

-- ============ Correction / Override（§34-§40） ============

CREATE TABLE correction_event (                       -- 不可变日志（§34/§35，禁止 UPDATE）
    correction_id        INTEGER PRIMARY KEY,
    book_id              TEXT NOT NULL,
    source_revision_id   TEXT,
    type                 TEXT NOT NULL,               -- PARAGRAPH_JOIN/PARAGRAPH_SPLIT/CHAPTER_OVERRIDE/...
    target_type          TEXT NOT NULL,
    target_id            TEXT NOT NULL,
    old_payload          TEXT,
    new_payload          TEXT,
    user_locked          INTEGER NOT NULL DEFAULT 1,
    created_at           TEXT NOT NULL,
    compiled_override_id INTEGER REFERENCES override_rule(rule_id)
);

CREATE TABLE override_rule (                          -- 当前生效约束（§36/§37）
    rule_id              INTEGER PRIMARY KEY,
    book_id              TEXT,
    scope                TEXT NOT NULL,               -- GLOBAL/BOOK/SERIES
    rule_type            TEXT NOT NULL,               -- PARAGRAPH_JOIN/PARAGRAPH_SPLIT/SPEAKER_ASSIGN/...
    match_payload        TEXT NOT NULL,
    action_payload       TEXT NOT NULL,
    priority             TEXT NOT NULL,               -- USER_LOCKED > USER_OVERRIDE > SYSTEM_CONFIRMED > AUTO（§39）
    status               TEXT NOT NULL,               -- ACTIVE/SUPERSEDED/DISABLED/NEEDS_REBIND（§38）
    user_locked          INTEGER NOT NULL DEFAULT 1,
    valid_from           TEXT,
    valid_to             TEXT
);

-- ============ Invalidation（§12-§15） ============

CREATE TABLE invalidation_event (
    event_id             INTEGER PRIMARY KEY,
    book_id              TEXT NOT NULL,
    reason_type          TEXT NOT NULL,               -- SOURCE_CHANGED/ENCODING_OVERRIDE/CHAPTER_METADATA/CHAPTER_ANCHOR/PARAGRAPH_JOIN/...
    origin_type          TEXT NOT NULL,
    origin_id            TEXT NOT NULL,
    scope_type           TEXT NOT NULL,               -- WHOLE_BOOK/WHOLE_SOURCE_REVISION/LINE_RANGE/CHAPTER_RANGE/PARAGRAPH_SET（§13）
    scope_payload        TEXT,
    created_at           TEXT NOT NULL,
    processed            INTEGER NOT NULL DEFAULT 0
);
