package com.readervoice.data.m3

import java.io.File
import kotlin.test.Test
import kotlin.test.assertTrue

/**
 * R1 产品门（ADR-056 / PLAN-20260918-062）：**canonical chapters 是唯一生产口径**。
 *
 * ## 为什么要一条静态门，而不是只靠单测
 *
 * `StructureRevision.chapters` 是**候选集合**（CONFIRMED / PROVISIONAL / REJECTED / TOC_ENTRY 混在一起）。
 * 真机实测 4.33MB 的书在里面放 16,060 个"章节"（含 `====` 与 `《…》`）。一旦生产代码按它的**下标**
 * 当章节序，得到的就是假章节 + 错范围 —— 而这个错误**不会让任何单测变红**，只会让产品表现变怪
 * （取文过大 → 段落恢复扫全文 → Director "看起来特别慢"）。所以要用编译期可见的静态约束把它钉住。
 *
 * ## 规则
 *
 * 生产源码（`app-android/src/main`、`data-room/src/main`、`parser/src/main`）里：
 * ```
 * 禁止：revision.chapters / .revision.chapters 作为章节序列使用
 * 允许：① 文件内显式标注 `CANONICAL-JOIN` 的按 chapterId 回连（如 Persister 取元数据）
 *       ② *ParityActivity（桌面/设备一致性探针，按设计要 raw 集合）
 *       ③ ConfirmedChapterView 自身（它是唯一被允许读 raw 并做筛选的地方）
 * 要求：所有其它读取都必须走 ConfirmedChapterView / confirmedChapters()
 * ```
 */
class CanonicalChapterDisciplineGateTest {

    private val roots = listOf(
        "../app-android/src/main",
        "../data-room/src/main",
        "../parser/src/main",
    )

    @Test
    fun `production code never treats revision chapters as the chapter sequence`() {
        val offenders = mutableListOf<String>()
        var scanned = 0
        for (rootPath in roots) {
            val root = File(rootPath)
            if (!root.isDirectory) continue
            root.walkTopDown().filter { it.isFile && it.extension == "kt" }.forEach { f ->
                scanned++
                val rel = f.path.replace('\\', '/')
                if (rel.endsWith("ConfirmedChapterView.kt")) return@forEach      // 唯一允许读 raw 的地方
                if (rel.contains("ParityActivity")) return@forEach              // 一致性探针：按设计要 raw
                val inCanonicalJoin = f.readText().contains("CANONICAL-JOIN")
                stripComments(f.readText()).lines().forEachIndexed { i, line ->
                    if (!line.contains("revision.chapters")) return@forEachIndexed
                    if (inCanonicalJoin) return@forEachIndexed
                    offenders += "$rel:${i + 1}: ${line.trim()}"
                }
            }
        }
        assertTrue(scanned > 40, "扫描到的 Kotlin 文件过少（$scanned），路径可能不对")
        assertTrue(
            offenders.isEmpty(),
            "生产代码把 revision.chapters 当章节序使用（应改为 ConfirmedChapterView / confirmedChapters）：\n" +
                offenders.joinToString("\n"),
        )
    }

    /** 章节列表/阅读必须走 canonical 视图：抽查关键生产文件确实引用了 ConfirmedChapterView。 */
    @Test
    fun `reader and director consume the canonical view`() {
        val mustUseCanonical = listOf(
            "../app-android/src/main/java/com/readervoice/app/ChapterDirectorService.kt",
            "../app-android/src/main/java/com/readervoice/app/ReaderActivity.kt",
        )
        for (p in mustUseCanonical) {
            val f = File(p)
            if (!f.isFile) continue
            val t = f.readText()
            assertTrue(
                t.contains("ConfirmedChapterView"),
                "$p 必须消费 ConfirmedChapterView（canonical chapters），否则会拿到候选集合",
            )
        }
    }

    /** 去掉 `/* … */` 与 `// …` 注释：文档里提到 `revision.chapters` 不该触发代码门。 */
    private fun stripComments(src: String): String {
        val noBlock = Regex("/\\*.*?\\*/", RegexOption.DOT_MATCHES_ALL).replace(src) { m ->
            m.value.replace(Regex("[^\\n]"), " ")
        }
        return noBlock.lineSequence().joinToString("\n") { it.substringBefore("//") }
    }
}
