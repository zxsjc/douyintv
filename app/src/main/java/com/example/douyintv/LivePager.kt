package com.example.douyintv

import android.util.Log

/**
 * 直播分页器：首页「直播」频道 与 直播播放页 共用同一份翻页状态。
 *
 * <h3>为什么需要共用</h3>
 * 直播接口按 offset 翻页，一页只回 6 个房间，而且池子里热门房间跨页大量重复。
 * 原来这份状态只活在 [MainActivity] 里：网格里按 ↓ 到底能自动续拉，但点进直播
 * 播放页之后只有「进页那一刻」的那批房间 —— 按 ↓ 到最后一个只能提示「已经是最后
 * 一个直播间」，因为播放页根本不知道还能往哪拉。
 * 现在 offset / 已见房间 id / hasMore 全收进这个单例，两处都从这里取：网格续拉和
 * 播放页续拉走同一条逻辑，去重、连换 3 批、页数上限都一样。
 *
 * <h3>线程</h3>
 * [next] 会做网络请求，必须在后台线程调用；返回后由调用方自己 post 回主线程。
 */
object LivePager {

    private const val TAG = "LivePager"
    /** 单次拉几个房间（接口上限就是 6） */
    private const val BATCH = 6
    /** 页数上限（只防死循环，正常早就到底了） */
    private const val MAX_PAGES = 30
    /** 连着几批全是老房间就收手 */
    private const val MAX_DUP_RETRY = 3

    /** 直播卡片 id 前缀（首页直播频道与播放页共用） */
    const val ID_PREFIX = "live_"

    private var offset = 0
    private var pages = 0
    private var dupBatches = 0

    /** 还有没有下一批；false = 确实到底了 */
    var hasMore = true
        private set

    /** 已经出现过的房间 id（跨批次去重，网格与播放页共用） */
    private val seen = LinkedHashSet<String>()

    /** 本次加载到的全部房间（按取到顺序）——首页从播放页回来时靠它补齐网格 */
    val collected = ArrayList<DouyinApi.VideoItem>()

    /** 一批结果：items 为空时看 error / exhausted 区分「出错」还是「到底了」 */
    data class Batch(
        val items: List<DouyinApi.VideoItem> = emptyList(),
        val error: String? = null,
        val exhausted: Boolean = false
    )

    /** 重新从第一批开始（切换频道 / 重新加载直播时调用） */
    fun reset() {
        offset = 0
        pages = 0
        dupBatches = 0
        hasMore = true
        seen.clear()
        collected.clear()
        Log.d(TAG, "直播分页已重置")
    }

    /** 把已经拿到的房间登记进去（避免后面续拉又拉回同一批） */
    fun markSeen(items: List<DouyinApi.VideoItem>) {
        for (it in items) if (it.awemeId.startsWith(ID_PREFIX)) seen.add(it.awemeId)
    }

    /**
     * 拉下一批新房间。后台线程调用；返回时 [hasMore] 已经更新。
     * 这一批全是老房间会自己换下一批（最多连换 [MAX_DUP_RETRY] 次）。
     */
    fun next(cookie: String): Batch {
        var attempt = 0
        while (true) {
            if (!hasMore) return Batch(exhausted = true)
            val res: DouyinApi.LiveFeedResult? = try {
                DouyinApi.fetchLiveRooms(cookie, offset = offset, count = BATCH)
            } catch (e: Exception) {
                Log.e(TAG, "拉直播列表异常", e)
                null
            }
            if (res == null) {
                return Batch(error = DouyinApi.lastError.ifBlank { "网络请求失败" })
            }
            if (res.rooms.isEmpty()) {
                hasMore = false
                Log.d(TAG, "直播第 ${pages + 1} 批没有房间 → 到底了")
                return Batch(exhausted = true)
            }
            pages++
            offset = res.nextOffset
            hasMore = res.hasMore && pages < MAX_PAGES

            val fresh = ArrayList<DouyinApi.VideoItem>()
            for (room in res.rooms) {
                val id = ID_PREFIX + room.roomId
                if (!seen.add(id)) continue
                fresh.add(
                    DouyinApi.VideoItem(
                        awemeId = id,
                        desc = room.title.ifEmpty { "直播中" },
                        author = room.nickname.ifEmpty { "主播" },
                        videoUrl = room.flvUrl,
                        coverUrl = room.coverUrl,
                        altUrls = room.altUrls,
                        isPortrait = room.isPortrait
                    )
                )
            }
            if (fresh.isEmpty()) {
                dupBatches++
                if (hasMore && attempt < MAX_DUP_RETRY) {
                    attempt++
                    Log.d(TAG, "直播第 $pages 批全是重复房间 → 自动换下一批（第 $dupBatches 次）")
                    continue
                }
                hasMore = false
                Log.d(TAG, "直播连换 $attempt 批都重复 → 判定到底")
                return Batch(exhausted = true)
            }
            dupBatches = 0
            collected.addAll(fresh)
            Log.d(TAG, "直播 +${fresh.size} 个（第 $pages 批，累计 ${seen.size}）")
            return Batch(fresh)
        }
    }
}
