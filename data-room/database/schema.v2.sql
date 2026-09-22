-- ReaderVoice Database v2 — character domain migration（TASK-060）
-- v1 → v2：仅新增 character 表族（SQLite 无 DROP COLUMN 需求；v1 表 100% 保留）
-- 约束：book_pk NOT NULL（cross-book isolation 是 DB constraint，§19）；
--       核心表禁止 INSERT OR REPLACE（ADR-025）；确定性 uid。

-- ============ v2: Character / Identity / Embodiment / VoiceState ============

CREATE TABLE character_observation (
    observation_id INTEGER PRIMARY KEY,
    character_revision_id INTEGER NOT NULL REFERENCES artifact_revision(revision_id),
    book_pk INTEGER NOT NULL REFERENCES book(book_pk),
    paragraph_revision_pk INTEGER REFERENCES paragraph_revision(paragraph_revision_pk),
    observation_type TEXT NOT NULL,          -- MENTION/SAME_IDENTITY_EVIDENCE/DIFFERENT_IDENTITY_EVIDENCE/
                                             -- RELATIONSHIP/CO_PRESENCE/CONTROL/POSSESSION/EMBODIMENT/IMITATION/
                                             -- VOICE_AGE_EVIDENCE/TEMP_VOICE_EVENT
    source_start INTEGER,
    source_end INTEGER,
    payload_json TEXT,                       -- compact（§46：不存重复长文本）
    narrative_position INTEGER,
    provenance TEXT NOT NULL,                -- EXPLICIT_RULE/LEGACY_*/HUMAN_GOLD/...
    created_at TEXT NOT NULL
);
CREATE INDEX idx_char_obs_rev ON character_observation(character_revision_id, book_pk);

CREATE TABLE narrative_entity (
    entity_pk INTEGER PRIMARY KEY,
    entity_uid TEXT NOT NULL UNIQUE,         -- deterministic（§17）
    book_pk INTEGER NOT NULL REFERENCES book(book_pk),
    entity_type TEXT NOT NULL,               -- PERSON/GROUP/NARRATOR/SYSTEM/NON_PERSON_AGENT/UNKNOWN
    canonical_name TEXT,
    status TEXT NOT NULL DEFAULT 'CANDIDATE',-- CANDIDATE/PROVISIONAL/CONFIRMED（§11）
    importance REAL NOT NULL DEFAULT 0,
    created_at TEXT NOT NULL,
    UNIQUE (book_pk, canonical_name)         -- cross-book 复合约束（§19）
);
CREATE INDEX idx_entity_book ON narrative_entity(book_pk, entity_type);

CREATE TABLE mention (
    mention_id INTEGER PRIMARY KEY,
    character_revision_id INTEGER NOT NULL REFERENCES artifact_revision(revision_id),
    book_pk INTEGER NOT NULL REFERENCES book(book_pk),
    paragraph_revision_pk INTEGER NOT NULL REFERENCES paragraph_revision(paragraph_revision_pk),
    source_start INTEGER NOT NULL,
    source_end INTEGER NOT NULL,
    surface_hash TEXT NOT NULL,
    mention_type TEXT NOT NULL,
    resolved_entity_pk INTEGER REFERENCES narrative_entity(entity_pk),
    candidate_entity_pks TEXT,               -- JSON array（§5）
    confidence REAL,
    provenance TEXT NOT NULL,
    created_at TEXT NOT NULL
);
CREATE INDEX idx_mention_rev ON mention(character_revision_id, book_pk, paragraph_revision_pk);

CREATE TABLE identity_evidence (
    evidence_id INTEGER PRIMARY KEY,
    character_revision_id INTEGER NOT NULL REFERENCES artifact_revision(revision_id),
    book_pk INTEGER NOT NULL REFERENCES book(book_pk),
    entity_a_pk INTEGER NOT NULL REFERENCES narrative_entity(entity_pk),
    entity_b_pk INTEGER NOT NULL REFERENCES narrative_entity(entity_pk),
    relation_type TEXT NOT NULL,             -- SAME_PERSON/DIFFERENT_PERSON/ALIAS/TITLE/KINSHIP/
                                             -- SOCIAL_RELATION/CO_PRESENCE/POSSESSION/CONTROL/EMBODIMENT/IMITATION
    sign TEXT NOT NULL,                      -- POSITIVE/NEGATIVE/NEUTRAL
    strength REAL NOT NULL DEFAULT 0,
    hard_block INTEGER NOT NULL DEFAULT 0,   -- DIFFERENT_PERSON hard（§9）
    source_paragraph_pk INTEGER REFERENCES paragraph_revision(paragraph_revision_pk),
    narrative_position INTEGER,
    valid_from INTEGER,
    valid_to INTEGER,
    provenance TEXT NOT NULL,
    confidence REAL,
    legacy_weight REAL,                      -- v90.7 证据分映射（§7）
    created_at TEXT NOT NULL,
    CHECK (entity_a_pk != entity_b_pk)
);
CREATE INDEX idx_evidence_book_pair ON identity_evidence(book_pk, entity_a_pk, entity_b_pk);
CREATE INDEX idx_evidence_hard ON identity_evidence(book_pk, hard_block);

CREATE TABLE identity_cluster_membership (   -- non-destructive merge（§12）
    membership_pk INTEGER PRIMARY KEY,
    character_revision_id INTEGER NOT NULL REFERENCES artifact_revision(revision_id),
    book_pk INTEGER NOT NULL REFERENCES book(book_pk),
    entity_pk INTEGER NOT NULL REFERENCES narrative_entity(entity_pk),
    cluster_id TEXT NOT NULL,
    joined_at TEXT NOT NULL,
    UNIQUE (book_pk, entity_pk)
);
CREATE INDEX idx_cluster_book ON identity_cluster_membership(book_pk, cluster_id);

CREATE TABLE identity_lineage (
    lineage_pk INTEGER PRIMARY KEY,
    book_pk INTEGER NOT NULL REFERENCES book(book_pk),
    from_entity_pk INTEGER NOT NULL REFERENCES narrative_entity(entity_pk),
    to_entity_pk INTEGER NOT NULL REFERENCES narrative_entity(entity_pk),
    lineage_type TEXT NOT NULL,               -- MERGED_INTO/SPLIT_FROM/SUPERSEDES（§16）
    created_at TEXT NOT NULL
);

CREATE TABLE embodiment_interval (           -- §24/§26
    interval_id INTEGER PRIMARY KEY,
    character_revision_id INTEGER NOT NULL REFERENCES artifact_revision(revision_id),
    book_pk INTEGER NOT NULL REFERENCES book(book_pk),
    identity_entity_pk INTEGER NOT NULL REFERENCES narrative_entity(entity_pk),
    body_entity_pk INTEGER NOT NULL REFERENCES narrative_entity(entity_pk),
    state_type TEXT NOT NULL,                -- CONTROL/EMBODIMENT/IMITATION/POSSESSION
    start_position INTEGER NOT NULL,
    end_position INTEGER,
    evidence_id INTEGER REFERENCES identity_evidence(evidence_id),
    confidence REAL,
    created_at TEXT NOT NULL,
    CHECK (identity_entity_pk != body_entity_pk)   -- §25：CONTROL != SAME_PERSON
);
CREATE INDEX idx_embodiment_body ON embodiment_interval(book_pk, body_entity_pk, start_position);

CREATE TABLE voice_phase_interval (          -- §30：长期变化
    interval_id INTEGER PRIMARY KEY,
    character_revision_id INTEGER NOT NULL REFERENCES artifact_revision(revision_id),
    book_pk INTEGER NOT NULL REFERENCES book(book_pk),
    entity_pk INTEGER NOT NULL REFERENCES narrative_entity(entity_pk),
    phase TEXT NOT NULL,                     -- CHILD/TEEN/YOUNG/MIDDLE/OLD
    start_position INTEGER NOT NULL,
    end_position INTEGER,
    confidence REAL,
    evidence_id INTEGER,
    created_at TEXT NOT NULL
);
CREATE INDEX idx_phase_pos ON voice_phase_interval(book_pk, entity_pk, start_position);

CREATE TABLE temporary_voice_event (         -- §31/§32：事实来源
    event_id INTEGER PRIMARY KEY,
    character_revision_id INTEGER NOT NULL REFERENCES artifact_revision(revision_id),
    book_pk INTEGER NOT NULL REFERENCES book(book_pk),
    identity_entity_pk INTEGER NOT NULL REFERENCES narrative_entity(entity_pk),
    action TEXT NOT NULL,                    -- START/CONTINUE/REPLACE/END
    state_type TEXT NOT NULL,
    payload_json TEXT,
    narrative_position INTEGER NOT NULL,
    scope TEXT NOT NULL,                     -- current_dialogue/scene/persistent/uncertain
    evidence_id INTEGER,
    provenance TEXT NOT NULL,
    created_at TEXT NOT NULL
);
CREATE INDEX idx_temp_event_pos ON temporary_voice_event(book_pk, identity_entity_pk, narrative_position);

CREATE TABLE character_attribute (           -- §28/§29：base attributes with evidence
    attribute_id INTEGER PRIMARY KEY,
    character_revision_id INTEGER NOT NULL REFERENCES artifact_revision(revision_id),
    book_pk INTEGER NOT NULL REFERENCES book(book_pk),
    entity_pk INTEGER NOT NULL REFERENCES narrative_entity(entity_pk),
    attribute TEXT NOT NULL,                 -- gender_style/chronological_age/appearance_age/voice_age
    value TEXT NOT NULL,
    confidence REAL,
    source TEXT NOT NULL,                    -- EXPLICIT_RULE/INFERRED/MANUAL/CACHED
    narrative_position INTEGER,
    user_override INTEGER NOT NULL DEFAULT 0,
    created_at TEXT NOT NULL,
    UNIQUE (book_pk, entity_pk, attribute)
);
