package com.readervoice.app

internal fun structureLabel(state: String): String = when (state) {
    "PENDING_STRUCTURE" -> "待结构编译"
    "STRUCTURE_READY" -> "目录已生成"
    "STRUCTURE_EMPTY" -> "未识别到章节"
    "CONFIRMED" -> "目录已生成"
    else -> state.replace('_', ' ')
}

internal fun chapterTitle(entry: ChapterIndexEntry): String =
    if (entry.ordinal == 0 && entry.state == "PENDING_STRUCTURE") "全文" else entry.title

internal fun chapterSubtitle(entry: ChapterIndexEntry): String =
    if (entry.state == "PENDING_STRUCTURE") "结构编译完成后将生成章节目录" else "已确认章节锚点"
