package com.readervoice.scheduler

/**
 * 调度优先级（2026-08-21 冻结）：
 * 当前播放 > 下一句 > 当前章 > 下一章 > 其他。
 * 值越小越优先；持久化为整数。
 */
enum class Priority(val rank: Int) {
    CURRENT_PLAYBACK(0),
    NEXT_UP(1),
    CURRENT_CHAPTER(2),
    NEXT_CHAPTER(3),
    BACKGROUND(4);

    companion object {
        fun fromRank(rank: Int): Priority = entries.firstOrNull { it.rank == rank } ?: BACKGROUND
    }
}
