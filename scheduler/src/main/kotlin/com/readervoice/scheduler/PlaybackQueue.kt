package com.readervoice.scheduler

/**
 * 播放侧交接（M1 最小版）：按章节顺序取 READY 资产。
 * M3 正式化：seek 定位 / 播放窗口（当前章前 20 分钟）→ 优先级映射 / 断点恢复。
 */
class PlaybackQueue(private val db: SchedulerDb) {

    /** 按给定 unit 顺序返回可用资产（缺失的跳过） */
    fun available(unitIdsInPlaybackOrder: List<String>): List<AudioAsset> =
        db.assetsInOrder(unitIdsInPlaybackOrder)

    fun assetFor(unitId: String): AudioAsset? = db.getAssetByUnit(unitId)
}
