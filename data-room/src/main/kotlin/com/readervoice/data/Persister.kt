package com.readervoice.data

import com.readervoice.parser.chapters.ChapterStructureCompiler
import com.readervoice.parser.chapters.ConfirmedChapterView
import com.readervoice.parser.chapters.ResolveState
import com.readervoice.parser.paragraph.ParagraphRecoveryPipeline
import com.readervoice.parser.source.PhysicalLine
import java.sql.Connection

/**
 * Persister：parser domain（TASK-010/020/030 内存产物）→ DB（TASK-040）。
 * - 批量 + 事务插入（§107）
 * - Stage revision 化 + head promotion + 依赖边注册
 * - fingerprint（§30）：source_revision + ordered spans + role
 */
class Persister(private val db: Db, private val revisions: RevisionStore) {

    private val conn: Connection get() = db.connection()

    // ---------- Book / SourceRevision（§49/§50） ----------

    fun persistBook(bookUid: String, title: String? = null): Long {
        conn.prepareStatement(
            "INSERT OR IGNORE INTO book(book_uid,title,created_at) VALUES(?,?,?)"
        ).use { ps ->
            ps.setString(1, bookUid); ps.setString(2, title)
            ps.setString(3, java.time.Instant.now().toString())
            ps.executeUpdate()
        }
        return conn.prepareStatement("SELECT book_pk FROM book WHERE book_uid=?").use { ps ->
            ps.setString(1, bookUid)
            ps.executeQuery().use { rs -> rs.next(); rs.getLong(1) }
        }
    }

    /** 持久化 SourceRevision（不可变，§3）；返回 (source_revision_pk, artifact_revision_id)。 */
    fun persistSourceRevision(
        bookPk: Long, sourceUid: String, localPath: String, sha256: String, byteSize: Long,
        charset: String?, charsetConfidence: Double?, parentSourceRevisionPk: Long? = null,
        revisionReason: String? = null,
    ): Pair<Long, Long> {
        val srcPk = conn.prepareStatement(
            "INSERT INTO source_revision(source_revision_uid,book_pk,local_path,sha256,byte_size,charset,charset_confidence,imported_at,parent_source_revision_pk,revision_reason) VALUES(?,?,?,?,?,?,?,?,?,?)",
            java.sql.Statement.RETURN_GENERATED_KEYS,
        ).use { ps ->
            ps.setString(1, sourceUid); ps.setLong(2, bookPk); ps.setString(3, localPath)
            ps.setString(4, sha256); ps.setLong(5, byteSize)
            ps.setString(6, charset)
            if (charsetConfidence != null) ps.setDouble(7, charsetConfidence) else ps.setNull(7, java.sql.Types.DOUBLE)
            ps.setString(8, java.time.Instant.now().toString())
            if (parentSourceRevisionPk != null) ps.setLong(9, parentSourceRevisionPk) else ps.setNull(9, java.sql.Types.BIGINT)
            ps.setString(10, revisionReason)
            ps.executeUpdate()
            ps.generatedKeys.use { ks -> ks.next(); ks.getLong(1) }
        }
        // Source ArtifactRevision（幂等）
        val rev = revisions.create(
            artifactType = "SOURCE", ownerScope = "book:$bookPk", bookId = "$bookPk",
            sourceRevisionId = sourceUid, parentRevisionId = null,
            inputHash = sha256, algorithmVersion = "txt_import_v1",
        )
        val head = revisions.head("book:$bookPk", "SOURCE")
        if (head == null || head.activeRevisionId != rev.revisionId) {
            if (rev.status == RevisionStore.Status.BUILDING) revisions.promote("book:$bookPk", "SOURCE", rev.revisionId)
        }
        return srcPk to rev.revisionId
    }

    /** PhysicalLine 批量插入（§51/§107）。 */
    fun persistPhysicalLines(sourceRevisionPk: Long, lines: List<PhysicalLine>): Map<Int, Long> {
        val linePkByNo = mutableMapOf<Int, Long>()
        conn.autoCommit = false
        try {
            val ps = conn.prepareStatement(
                "INSERT INTO physical_line(source_revision_pk,line_no,byte_start,byte_end,codepoint_start,codepoint_end," +
                    "leading_ascii_spaces,leading_fullwidth_spaces,leading_tabs,trailing_spaces,char_count,han_count,is_blank) " +
                    "VALUES(?,?,?,?,?,?,?,?,?,?,?,?,?)"
            )
            for (line in lines) {
                ps.setLong(1, sourceRevisionPk); ps.setInt(2, line.lineNo)
                ps.setInt(3, line.byteStart); ps.setInt(4, line.byteEnd)
                ps.setInt(5, line.charStart); ps.setInt(6, line.charEnd)
                ps.setInt(7, line.leadingAsciiSpace); ps.setInt(8, line.leadingFullwidthSpace)
                ps.setInt(9, line.leadingTab); ps.setInt(10, line.trailingSpace)
                ps.setInt(11, line.charCount); ps.setInt(12, line.hanCount)
                ps.setInt(13, if (line.isBlank) 1 else 0)
                ps.addBatch()
            }
            ps.executeBatch()
            ps.close()
            conn.createStatement().use { st ->
                st.executeQuery("SELECT line_pk,line_no FROM physical_line WHERE source_revision_pk=$sourceRevisionPk").use { rs ->
                    while (rs.next()) linePkByNo[rs.getInt("line_no")] = rs.getLong("line_pk")
                }
            }
            conn.commit()
        } catch (e: Exception) {
            conn.rollback()
            throw e
        } finally {
            conn.autoCommit = true
        }
        return linePkByNo
    }

    // ---------- Structure（§52-§57） ----------

    data class StructureInput(
        val result: ChapterStructureCompiler.PipelineResult,
        val sourceRevisionPk: Long,
        val linePkByNo: Map<Int, Long>,
    )

    fun persistStructure(input: StructureInput): Long {
        val r = input.result
        val owner = "book:${input.sourceRevisionPk}"
        val inputHash = hashOf(
            input.sourceRevisionPk.toString(), "rulepack=legacy-26", "resolver=task020-v1",
            r.groups.size.toString(),
        )
        val rev = revisions.create(
            artifactType = "STRUCTURE", ownerScope = owner,
            bookId = null, sourceRevisionId = "$input.sourceRevisionPk",
            parentRevisionId = revisions.head(owner, "SOURCE")?.activeRevisionId,
            inputHash = inputHash, algorithmVersion = "chapter_resolver_v1",
        )
        revisions.head(owner, "SOURCE")?.let { src ->
            revisions.addDependency(owner, rev.revisionId, src.activeRevisionId)
        }

        conn.autoCommit = false
        try {
            val structPk = conn.prepareStatement(
                "INSERT INTO structure_revision(artifact_revision_id,source_revision_pk,rule_pack_version,resolver_version,input_hash,created_at) VALUES(?,?,?,?,?,?)",
                java.sql.Statement.RETURN_GENERATED_KEYS,
            ).use { ps ->
                ps.setLong(1, rev.revisionId); ps.setLong(2, input.sourceRevisionPk)
                ps.setString(3, "legacy-26"); ps.setString(4, "task020-v1")
                ps.setString(5, inputHash); ps.setString(6, java.time.Instant.now().toString())
                ps.executeUpdate()
                ps.generatedKeys.use { ks -> ks.next(); ks.getLong(1) }
            }
            // candidates
            val candPs = conn.prepareStatement(
                "INSERT INTO chapter_candidate(structure_revision_pk,line_pk,rule_id,family,target_type,serial_raw,serial_value," +
                    "regex_score,priority_score,length_score,spacing_score,final_score,evidence_mask,decision) VALUES(?,?,?,?,?,?,?,?,?,?,?,?,?,?)"
            )
            for (c in r.candidates) {
                val linePk = input.linePkByNo[c.lineNo] ?: continue
                candPs.setLong(1, structPk); candPs.setLong(2, linePk)
                candPs.setString(3, c.ruleId); candPs.setString(4, c.family.name); candPs.setString(5, c.target.name)
                candPs.setString(6, c.serialRaw)
                val sv = c.serialValue
                if (sv != null) candPs.setInt(7, sv) else candPs.setNull(7, java.sql.Types.INTEGER)
                candPs.setDouble(8, c.regexScore); candPs.setDouble(9, c.rulePriorityScore)
                candPs.setDouble(10, c.lengthScore); candPs.setDouble(11, c.spacingScore)
                candPs.setDouble(12, c.finalScore); candPs.setLong(13, 0)
                candPs.setString(14, "CONFIRMED")
                candPs.addBatch()
            }
            candPs.executeBatch()
            candPs.close()
            // volumes
            val volPs = conn.prepareStatement(
                "INSERT INTO volume(structure_revision_pk,ordinal,serial_value,title,anchor_line_pk,confidence) VALUES(?,?,?,?,?,?)"
            )
            for (v in r.volumes) {
                val linePk = input.linePkByNo[v.startLine] ?: continue
                volPs.setLong(1, structPk); volPs.setInt(2, v.volumeIndex)
                val vsv = v.serialValue
                if (vsv != null) volPs.setInt(3, vsv) else volPs.setNull(3, java.sql.Types.INTEGER)
                volPs.setString(4, v.title); volPs.setLong(5, linePk); volPs.setDouble(6, v.confidence)
                volPs.addBatch()
            }
            volPs.executeBatch()
            volPs.close()
            // chapters
            //
            // ★ R1（ADR-056 产品模型）：**只持久化 canonical chapters**（`ConfirmedChapterView`）。
            //   旧实现写的是 `CONFIRMED || PROVISIONAL` —— 等于把候选状态泄漏进产品数据，
            //   而产品 UI（目录/阅读/进度/导演）根本不应该知道 PROVISIONAL/REJECTED。
            //   这里用 canonical 视图**定序**，并通过 `chapterId` 回连原始 Chapter 取元数据
            //   （serialValue/serialPart/titleRaw/titleDisplay/finalScore/anchorLine），
            //   正文区间则用视图**重算**过的 contentStartLine/contentEndLine。
            val chapterPs = conn.prepareStatement(
                "INSERT INTO chapter(structure_revision_pk,chapter_index,serial_value,serial_part,title_raw,title_clean,chapter_type," +
                    "anchor_line_pk,content_start_line_pk,content_end_line_pk,confidence) VALUES(?,?,?,?,?,?,?,?,?,?,?)"
            )
            val eofLine = input.linePkByNo.keys.maxOrNull() ?: 0
            val canonical = ConfirmedChapterView.of(r.revision, eofLine)
            // CANONICAL-JOIN: 读 revision.chapters 只为按 chapterId 取元数据，不按下标当章节序（见 R1 门）
            val rawByChapterId = r.revision.chapters.associateBy { it.chapterId }
            for (c in canonical) {
                val ch = rawByChapterId[c.chapterId] ?: continue
                val anchorPk = input.linePkByNo[ch.anchorLine] ?: continue
                chapterPs.setLong(1, structPk); chapterPs.setInt(2, c.ordinal)
                val csv = ch.serialValue
                if (csv != null) chapterPs.setInt(3, csv) else chapterPs.setNull(3, java.sql.Types.INTEGER)
                chapterPs.setString(4, ch.serialPart); chapterPs.setString(5, ch.titleRaw); chapterPs.setString(6, ch.titleDisplay)
                chapterPs.setString(7, ResolveState.CONFIRMED.name)
                chapterPs.setLong(8, anchorPk)
                input.linePkByNo[c.contentStartLine]?.let { chapterPs.setLong(9, it) } ?: chapterPs.setNull(9, java.sql.Types.BIGINT)
                input.linePkByNo[c.contentEndLine]?.let { chapterPs.setLong(10, it) } ?: chapterPs.setNull(10, java.sql.Types.BIGINT)
                chapterPs.setDouble(11, ch.finalScore)
                chapterPs.addBatch()
            }
            chapterPs.executeBatch()
            chapterPs.close()
            // TOC
            for (block in r.toc.blocks) {
                conn.prepareStatement(
                    "INSERT INTO toc_block(structure_revision_pk,start_line_pk,end_line_pk,entry_count) VALUES(?,?,?,?)"
                ).use { ps ->
                    ps.setLong(1, structPk)
                    ps.setLong(2, input.linePkByNo[block.startLine] ?: 0)
                    ps.setLong(3, input.linePkByNo[block.endLine] ?: 0)
                    ps.setInt(4, block.entryCount)
                    ps.executeUpdate()
                }
            }
            conn.commit()
            revisions.promote(owner, "STRUCTURE", rev.revisionId)
            return structPk
        } catch (e: Exception) {
            conn.rollback()
            revisions.markFailed(rev.revisionId, e.message ?: "structure persist failed")
            throw e
        } finally {
            conn.autoCommit = true
        }
    }

    // ---------- Paragraph（§58-§67） ----------

    data class ParagraphInput(
        val result: ParagraphRecoveryPipeline.PipelineResult,
        val sourceRevisionPk: Long,
        val structureRevisionPk: Long,
        val linePkByNo: Map<Int, Long>,
    )

    fun persistParagraphs(input: ParagraphInput): Long {
        val r = input.result
        val owner = "book:${input.sourceRevisionPk}"
        val inputHash = hashOf(
            input.sourceRevisionPk.toString(), input.structureRevisionPk.toString(),
            "layout=task030-v1", "boundary=task030-v1", "join=task030-v1", "block=task030-v1",
            r.paragraphs.size.toString(),
        )
        val rev = revisions.create(
            artifactType = "PARAGRAPH_RECOVERY", ownerScope = owner,
            bookId = null, sourceRevisionId = "$input.sourceRevisionPk",
            parentRevisionId = revisions.head(owner, "STRUCTURE")?.activeRevisionId,
            inputHash = inputHash, algorithmVersion = "paragraph_recovery_v1",
        )
        revisions.head(owner, "STRUCTURE")?.let { st ->
            revisions.addDependency(owner, rev.revisionId, st.activeRevisionId)
        }

        conn.autoCommit = false
        try {
            val prPk = conn.prepareStatement(
                "INSERT INTO paragraph_recovery_revision(artifact_revision_id,source_revision_pk,structure_revision_pk," +
                    "layout_profiler_version,boundary_classifier_version,join_policy_version,block_detector_version,input_hash,created_at) VALUES(?,?,?,?,?,?,?,?,?)",
                java.sql.Statement.RETURN_GENERATED_KEYS,
            ).use { ps ->
                ps.setLong(1, rev.revisionId); ps.setLong(2, input.sourceRevisionPk); ps.setLong(3, input.structureRevisionPk)
                ps.setString(4, "task030-v1"); ps.setString(5, "task030-v1")
                ps.setString(6, "task030-v1"); ps.setString(7, "task030-v1")
                ps.setString(8, inputHash); ps.setString(9, java.time.Instant.now().toString())
                ps.executeUpdate()
                ps.generatedKeys.use { ks -> ks.next(); ks.getLong(1) }
            }
            // layout profile
            conn.prepareStatement(
                "INSERT INTO layout_profile(pr_revision_pk,scope_type,profile_type,stats_blob,confidence) VALUES(?,?,?,?,?)"
            ).use { ps ->
                ps.setLong(1, prPk); ps.setString(2, "BOOK")
                ps.setString(3, r.bookProfile.profileType.name)
                ps.setString(4, "{\"median\":${r.bookProfile.medianLineLength},\"blank\":${r.bookProfile.blankLineRatio}}")
                ps.setDouble(5, r.bookProfile.confidence)
                ps.executeUpdate()
            }
            val profilePk = conn.createStatement().use { st ->
                st.executeQuery("SELECT profile_pk FROM layout_profile WHERE pr_revision_pk=$prPk").use { rs -> rs.next(); rs.getLong(1) }
            }
            // regions
            val regPs = conn.prepareStatement(
                "INSERT INTO layout_region(pr_revision_pk,profile_pk,start_line_pk,end_line_pk,profile_type,confidence) VALUES(?,?,?,?,?,?)"
            )
            for (region in r.regions) {
                val s = input.linePkByNo[region.startLine] ?: continue
                val e = input.linePkByNo[region.endLine] ?: continue
                regPs.setLong(1, prPk); regPs.setLong(2, profilePk)
                regPs.setLong(3, s); regPs.setLong(4, e)
                regPs.setString(5, region.profileType.name); regPs.setDouble(6, region.confidence)
                regPs.addBatch()
            }
            regPs.executeBatch()
            regPs.close()
            // boundaries
            val bPs = conn.prepareStatement(
                "INSERT INTO line_boundary(pr_revision_pk,left_line_pk,right_line_pk,boundary_type,confidence,evidence_mask,profile_pk) VALUES(?,?,?,?,?,?,?)"
            )
            for (b in r.boundaries) {
                val l = input.linePkByNo[b.leftLineNo] ?: continue
                val rr = input.linePkByNo[b.rightLineNo] ?: continue
                bPs.setLong(1, prPk); bPs.setLong(2, l); bPs.setLong(3, rr)
                bPs.setString(4, b.type.name); bPs.setDouble(5, b.confidence)
                bPs.setLong(6, b.evidenceMask); bPs.setLong(7, profilePk)
                bPs.addBatch()
            }
            bPs.executeBatch()
            bPs.close()

            // paragraphs：logical head + revision（批量 + 回填 pk）
            val paraPs = conn.prepareStatement(
                "INSERT INTO logical_paragraph(paragraph_uid,book_pk,source_revision_pk,created_at) VALUES(?,?,?,?)",
                java.sql.Statement.RETURN_GENERATED_KEYS,
            )
            val revPs = conn.prepareStatement(
                "INSERT INTO paragraph_revision(paragraph_pk,pr_revision_pk,paragraph_index,block_type,read_policy,confidence,fingerprint,revision_reason) VALUES(?,?,?,?,?,?,?,?)",
                java.sql.Statement.RETURN_GENERATED_KEYS,
            )
            var uidSeq = 0
            val bookPk = bookPkOf(input.sourceRevisionPk)
            for (bp in r.paragraphs) {
                val uid = "para-${input.sourceRevisionPk}-${++uidSeq}"
                paraPs.setString(1, uid); paraPs.setLong(2, bookPk)
                paraPs.setLong(3, input.sourceRevisionPk); paraPs.setString(4, java.time.Instant.now().toString())
                paraPs.addBatch()
            }
            paraPs.executeBatch()
            paraPs.close()
            val paraPks = conn.createStatement().use { st ->
                st.executeQuery(
                    "SELECT paragraph_pk FROM logical_paragraph WHERE source_revision_pk=${input.sourceRevisionPk} ORDER BY paragraph_pk"
                ).use { rs ->
                    val out = mutableListOf<Long>()
                    while (rs.next()) out += rs.getLong(1)
                    out
                }
            }
            val chapterByLine = chapterOfLine(input.structureRevisionPk)
            for ((i, bp) in r.paragraphs.withIndex()) {
                val p = bp.paragraph
                val fingerprint = fingerprintOf(p, input.sourceRevisionPk)
                val chapterPk = p.sourceSpans.firstOrNull()?.lineNo?.let { chapterByLine[it] }
                revPs.setLong(1, paraPks[i]); revPs.setLong(2, prPk)
                revPs.setInt(3, p.paragraphIndex); revPs.setString(4, p.blockType.name)
                revPs.setString(5, p.readPolicy.name); revPs.setDouble(6, p.boundaryConfidence)
                revPs.setString(7, fingerprint); revPs.setString(8, p.revisionReason.name)
                revPs.addBatch()
                chapterPk
            }
            revPs.executeBatch()
            revPs.close()
            val revPks = conn.createStatement().use { st ->
                st.executeQuery("SELECT paragraph_revision_pk FROM paragraph_revision WHERE pr_revision_pk=$prPk ORDER BY paragraph_revision_pk").use { rs ->
                    val out = mutableListOf<Long>()
                    while (rs.next()) out += rs.getLong(1)
                    out
                }
            }

            // spans + transforms
            val spanPs = conn.prepareStatement(
                "INSERT INTO paragraph_source_span(paragraph_revision_pk,order_index,line_pk,source_codepoint_start,source_codepoint_end) VALUES(?,?,?,?,?)"
            )
            val tfPs = conn.prepareStatement(
                "INSERT INTO normalized_transform(paragraph_revision_pk,order_index,transform_type,source_span_pk,synthetic_text) VALUES(?,?,?,?,?)"
            )
            for ((i, bp) in r.paragraphs.withIndex()) {
                val revPk = revPks[i]
                for ((si, span) in bp.paragraph.sourceSpans.withIndex()) {
                    val linePk = input.linePkByNo[span.lineNo] ?: continue
                    spanPs.setLong(1, revPk); spanPs.setInt(2, si); spanPs.setLong(3, linePk)
                    spanPs.setInt(4, span.charStart); spanPs.setInt(5, span.charEnd)
                    spanPs.addBatch()
                }
            }
            spanPs.executeBatch()
            spanPs.close()
            // 批量回填 span_pk（避免 N+1——3M 规模下超长事务触发 SQLITE_BUSY）
            val spanPkByKey = mutableMapOf<Pair<Long, Int>, Long>()
            conn.createStatement().use { st ->
                st.executeQuery(
                    "SELECT ps.span_pk, ps.paragraph_revision_pk, ps.order_index FROM paragraph_source_span ps " +
                        "JOIN paragraph_revision pr ON pr.paragraph_revision_pk = ps.paragraph_revision_pk WHERE pr.pr_revision_pk=$prPk"
                ).use { rs ->
                    while (rs.next()) {
                        spanPkByKey[rs.getLong("paragraph_revision_pk") to rs.getInt("order_index")] = rs.getLong("span_pk")
                    }
                }
            }
            // 分段提交（§107：一个事务塞 40 万行会触发 SQLITE_BUSY；幂等保证重跑安全）
            conn.commit()
            for ((i, bp) in r.paragraphs.withIndex()) {
                val revPk = revPks[i]
                for ((ti, tf) in r.normalizedSpans.getValue(bp.paragraph.paragraphId).withIndex()) {
                    tfPs.setLong(1, revPk); tfPs.setInt(2, ti); tfPs.setString(3, tf.transformType.name)
                    if (tf.sourceLineNo != null) {
                        val sp = bp.paragraph.sourceSpans.firstOrNull { it.lineNo == tf.sourceLineNo }
                        val spanPk = sp?.let { spanPkByKey[revPk to bp.paragraph.sourceSpans.indexOf(it)] }
                        if (spanPk != null) tfPs.setLong(4, spanPk) else tfPs.setNull(4, java.sql.Types.BIGINT)
                    } else {
                        tfPs.setNull(4, java.sql.Types.BIGINT)
                        tfPs.setString(5, if (tf.synthetic) " " else null)
                    }
                    tfPs.addBatch()
                }
            }
            tfPs.executeBatch()
            tfPs.close()
            conn.commit()

            // links
            val linkPs = conn.prepareStatement(
                "INSERT INTO cross_paragraph_link(pr_revision_pk,left_paragraph_revision_pk,right_paragraph_revision_pk,link_type,confidence,evidence_mask) VALUES(?,?,?,?,?,?)"
            )
            for (link in r.links) {
                val leftIdx = r.paragraphs.indexOfFirst { it.paragraph.paragraphId == link.leftParagraphId }
                val rightIdx = r.paragraphs.indexOfFirst { it.paragraph.paragraphId == link.rightParagraphId }
                if (leftIdx < 0 || rightIdx < 0) continue
                linkPs.setLong(1, prPk); linkPs.setLong(2, revPks[leftIdx]); linkPs.setLong(3, revPks[rightIdx])
                linkPs.setString(4, link.type.name); linkPs.setDouble(5, link.confidence); linkPs.setLong(6, 0)
                linkPs.addBatch()
            }
            linkPs.executeBatch()
            linkPs.close()

            conn.commit()
            revisions.promote(owner, "PARAGRAPH_RECOVERY", rev.revisionId)
            return prPk
        } catch (e: Exception) {
            conn.rollback()
            revisions.markFailed(rev.revisionId, e.message ?: "paragraph persist failed")
            throw e
        } finally {
            conn.autoCommit = true
        }
    }

    // ---------- helpers ----------

    private fun bookPkOf(sourceRevisionPk: Long): Long = conn.createStatement().use { st ->
        st.executeQuery("SELECT book_pk FROM source_revision WHERE source_revision_pk=$sourceRevisionPk").use { rs -> rs.next(); rs.getLong(1) }
    }

    private fun chapterOfLine(structureRevisionPk: Long): Map<Int, Long> {
        val out = mutableMapOf<Int, Long>()
        conn.prepareStatement(
            "SELECT c.chapter_pk, c.anchor_line_pk, pl.line_no FROM chapter c " +
                "JOIN physical_line pl ON pl.line_pk=c.anchor_line_pk WHERE c.structure_revision_pk=? ORDER BY c.chapter_index"
        ).use { ps ->
            ps.setLong(1, structureRevisionPk)
            ps.executeQuery().use { rs ->
                while (rs.next()) out[rs.getInt("line_no")] = rs.getLong("chapter_pk")
            }
        }
        return out
    }

    /** fingerprint（§30）：source_revision + ordered spans + role。 */
    private fun fingerprintOf(p: com.readervoice.parser.paragraph.LogicalParagraph, sourceRevisionPk: Long): String {
        val spans = p.sourceSpans.joinToString("|") { "${it.lineNo}:${it.charStart}-${it.charEnd}" }
        return hashOf(sourceRevisionPk.toString(), spans, p.blockType.name)
    }

    private fun hashOf(vararg parts: String): String =
        java.security.MessageDigest.getInstance("SHA-256")
            .digest(parts.joinToString("|").toByteArray(Charsets.UTF_8))
            .joinToString("") { "%02x".format(it) }
            .take(32)
}
