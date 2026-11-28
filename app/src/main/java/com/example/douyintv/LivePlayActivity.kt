package com.example.douyintv

import android.app.Activity
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.util.Log
import android.view.KeyEvent
import android.view.SurfaceView
import android.view.View
import android.view.WindowManager
import android.widget.TextView
import com.google.android.exoplayer2.MediaItem
import com.google.android.exoplayer2.PlaybackException
import com.google.android.exoplayer2.Player
import com.google.android.exoplayer2.SimpleExoPlayer
import com.google.android.exoplayer2.ext.okhttp.OkHttpDataSource
import com.google.android.exoplayer2.source.ProgressiveMediaSource
import com.google.android.exoplayer2.video.VideoSize

/**
 * 直播播放页（直播频道点卡片进来）。
 *
 * <h3>为什么单独做一个页面，而不是复用 PlayerActivity</h3>
 * PlayerActivity 是「点播」语义：进度条、倍速、弹幕（拉评论）、地址失效时换线路
 * / 换引擎重拉。直播流没有时长，混进去只会互相打架。这里做四件事：
 * 起播（Flv 直播流）、断了自动重连、**↑↓ 切换直播间**、按返回退出。
 *
 * <h3>切换直播间的列表从哪来</h3>
 * 直播频道的整页房间通过 [FeedBus] 带进来（Intent 传大列表会撞 Binder 1MB 上限），
 * 这里按 `live_` 前缀过滤出房间列表，记住进入时的下标，↑ 上一个 / ↓ 下一个。
 * 单条进入（没有列表）时按键只提示「只有一个直播间」，不会假装能切。
 *
 * <h3>技术要点</h3>
 * · 直播地址是 Flv（`stream_url.flv_pull_url`）：ProgressiveMediaSource +
 *   默认 Extractor 工厂（含 FlvExtractor）能直接吃；
 * · 画面比例仍用 AspectFrame 约束（竖屏直播自动留黑边）；
 * · 播放器只建一个、换台复用它（setMediaSource），老设备上比反复 release 稳；
 * · 断流重连：每次失败先换一条备用地址（原画/超清/标清），同一地址最多试 3 次；
 * · 换台瞬间盖一层黑罩（不碰 SurfaceView，避免 Surface 重建触发重播）。
 */
class LivePlayActivity : Activity() {

    companion object {
        private const val TAG = "Live"
        private const val UA = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/122.0.0.0 Safari/537.36"
        /** 单次重连等待 */
        private const val RETRY_DELAY = 2000L
        /** 同一地址最多重连几次（超过就换下一条地址） */
        private const val RETRY_PER_URL = 3
        /** 最多换几条地址（含首播） */
        private const val MAX_URLS = 4
        /** 换台后这段时间内的播放器报错忽略（旧源停掉时会补发错误） */
        private const val SWITCH_QUIET_MS = 600L
        /** 直播卡片 id 前缀（与首页直播频道的卡片 id 规则一致，统一取 LivePager） */
        private const val LIVE_ID_PREFIX = LivePager.ID_PREFIX
    }

    private lateinit var aspectFrame: AspectFrame
    private lateinit var surfaceView: SurfaceView
    private lateinit var tvTitle: TextView
    private lateinit var tvMeta: TextView
    private lateinit var tvStatus: TextView
    private lateinit var switchCover: View

    private val handler = Handler(Looper.getMainLooper())
    private var player: SimpleExoPlayer? = null

    /** 可切换的直播间列表（空 = 单条模式，不能切换） */
    private var rooms: List<DouyinApi.VideoItem> = emptyList()
    private var index = 0
    /** 正在后台补拉下一批直播间（防止连按 ↓ 触发多次请求） */
    private var loadingMore = false

    private val urls = ArrayList<String>()
    private var urlIndex = 0
    private var retryPerUrl = 0
    private var finished = false
    private var ignoreErrorsUntil = 0L

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        setContentView(R.layout.activity_live)

        aspectFrame = findViewById(R.id.liveAspectFrame) as AspectFrame
        surfaceView = findViewById(R.id.liveSurface) as SurfaceView
        tvTitle = findViewById(R.id.liveTitle) as TextView
        tvMeta = findViewById(R.id.liveMeta) as TextView
        tvStatus = findViewById(R.id.liveStatus) as TextView
        switchCover = findViewById(R.id.liveCover)

        // ★ 2.42：整页直播间从 FeedBus 拿（Intent 只带当前这条的兜底信息）
        val id = intent.getStringExtra("id") ?: ""
        val bus = try { FeedBus.peek() } catch (_: Throwable) { null }
        if (bus != null) {
            rooms = bus.filter { it.awemeId.startsWith(LIVE_ID_PREFIX) }
        }
        if (rooms.isNotEmpty()) {
            val found = rooms.indexOfFirst { it.awemeId == id }
            index = if (found >= 0) found else 0
            playRoom(initial = true)
        } else {
            // 没有列表（理论上不会发生，留兜底）：照 Intent 里的单条起播
            index = 0
            val title = intent.getStringExtra("title") ?: "直播"
            val author = intent.getStringExtra("author") ?: ""
            tvTitle.text = TextCompat.forTv(title)
            tvMeta.text = if (author.isBlank()) "直播中" else TextCompat.forTv("直播中 · $author")
            val portrait = intent.getBooleanExtra("portrait", false)
            aspectFrame.aspectRatio = if (portrait) 9f / 16f else 16f / 9f
            urls.clear()
            intent.getStringExtra("url")?.let { if (it.isNotBlank()) urls.add(it) }
            (intent.getStringExtra("alts") ?: "").split("\n").forEach {
                if (it.isNotBlank() && it != urls.firstOrNull() && urls.size < MAX_URLS) urls.add(it)
            }
            if (urls.isEmpty()) {
                tvStatus.text = "这个直播间没有可用地址"
                return
            }
            startPlay()
        }
    }

    /** 切到 [rooms] 里当前 index 的那个房间：标题/比例/地址全套重来 */
    private fun playRoom(initial: Boolean = false) {
        val room = rooms.getOrNull(index) ?: return
        tvTitle.text = TextCompat.forTv(room.desc.ifEmpty { "直播" })
        tvMeta.text = TextCompat.forTv("直播中 · " + room.author.ifEmpty { "主播" })
        // 先按已知朝向摆好比例，避免「先铺满再缩回」；真实尺寸到了会再校正
        aspectFrame.aspectRatio = if (room.isPortrait) 9f / 16f else 16f / 9f
        // 换台时清掉弹幕/提示残留（直播页没有弹幕，这里只是保险）
        tvStatus.text = ""
        urls.clear()
        if (room.videoUrl.isNotBlank()) urls.add(room.videoUrl)
        for (u in room.altUrls) if (u != room.videoUrl && urls.size < MAX_URLS) urls.add(u)
        urlIndex = 0
        retryPerUrl = 0
        if (urls.isEmpty()) {
            tvStatus.text = "这个直播间没有可用地址"
            return
        }
        startPlay()
        Log.d(TAG, "直播换台 ${index + 1}/${rooms.size} ${room.author}")
    }

    /** 建一次播放器，之后换台复用（老设备上反复 release 会累积内存碎片） */
    private fun ensurePlayer(): SimpleExoPlayer {
        player?.let { return it }
        val p = SimpleExoPlayer.Builder(this).build()
        p.setVideoSurfaceView(surfaceView)
        p.addListener(object : Player.Listener {
            override fun onPlaybackStateChanged(state: Int) {
                if (finished) return
                if (state == Player.STATE_READY) {
                    retryPerUrl = 0
                    // ★ 画面已就绪：先撤黑罩（留一拍让解码首帧真的画上去）
                    handler.postDelayed({ hideCover() }, 120L)
                    tvStatus.visibility = View.GONE
                }
            }

            override fun onVideoSizeChanged(videoSize: VideoSize) {
                val w = videoSize.width
                val h = videoSize.height
                if (w > 0 && h > 0) {
                    aspectFrame.aspectRatio = w.toFloat() / h.toFloat()
                    Log.d(TAG, "直播画面尺寸 ${w}x$h")
                }
            }

            override fun onPlayerError(error: PlaybackException) {
                if (finished) return
                // 换台瞬间旧源停掉会补报一次错误，别把它当成断流去重连
                if (SystemClock.uptimeMillis() < ignoreErrorsUntil) {
                    Log.d(TAG, "换台静默期内的错误已忽略")
                    return
                }
                onFail(error.errorCodeName + ": " + (error.message ?: ""))
            }
        })
        player = p
        return p
    }

    private fun startPlay() {
        if (finished) return
        val url = urls.getOrNull(urlIndex)
        if (url.isNullOrBlank()) {
            tvStatus.visibility = View.VISIBLE
            tvStatus.text = "直播加载失败，按「返回」退出"
            hideCover()
            return
        }
        tvStatus.visibility = View.VISIBLE
        tvStatus.text = "正在连接直播…"

        try {
            // 数据源走 DouyinApi 的 OkHttp（IPv4-only DNS + 宽松 TLS，与点播页一致）
            val http = OkHttpDataSource.Factory(DouyinApi.httpClient())
                .setDefaultRequestProperties(
                    mapOf(
                        "User-Agent" to UA,
                        "Referer" to "https://live.douyin.com/"
                    )
                )
            val source = ProgressiveMediaSource.Factory(http)
                .createMediaSource(MediaItem.fromUri(Uri.parse(url)))

            val p = ensurePlayer()
            try {
                p.stop()
                p.clearMediaItems()
            } catch (_: Throwable) {}
            p.setMediaSource(source)
            p.prepare()
            p.playWhenReady = true
            Log.d(TAG, "直播起播 线路${urlIndex + 1}/${urls.size} ${url.take(80)}")
        } catch (t: Throwable) {
            onFail(t.message ?: t.javaClass.simpleName)
        }
    }

    /** 断流处理：同地址重试 RETRY_PER_URL 次，再换下一条地址；全试完就停在提示上 */
    private fun onFail(msg: String) {
        if (finished) return
        Log.w(TAG, "直播失败(${urlIndex + 1}/${urls.size}) $msg")
        tvStatus.visibility = View.VISIBLE
        tvStatus.text = "连接中断，正在重连…"
        handler.postDelayed({
            if (finished) return@postDelayed
            retryPerUrl++
            if (retryPerUrl >= RETRY_PER_URL) {
                retryPerUrl = 0
                urlIndex++
            }
            if (urlIndex >= urls.size) {
                tvStatus.text = "直播加载失败，按「返回」退出"
                hideCover()
                return@postDelayed
            }
            startPlay()
        }, RETRY_DELAY)
    }

    /** ★ 2.42：↑↓ 切换直播间；↓ 到最后一个时自动补拉下一批（与网格里完全一致） */
    private fun switchRoom(step: Int) {
        if (finished) return
        if (step < 0) {
            if (index <= 0) {
                CustomToast.show(this, "已经是第一个直播间")
                return
            }
            index--
            jumpToCurrent()
            return
        }
        // 往下：还有下一条就直接切
        if (index + 1 <= rooms.size - 1) {
            index++
            jumpToCurrent()
            return
        }
        // 已经在这批的最后一个 → 补拉下一批（LivePager 里的 offset/去重与网格共用）
        loadMoreRooms()
    }

    private fun jumpToCurrent() {
        showCover()
        ignoreErrorsUntil = SystemClock.uptimeMillis() + SWITCH_QUIET_MS
        playRoom()
    }

    /**
     * 补拉一批直播间接在后面，成功后自动切到这批的第一个。
     *
     * 为什么要在播放页里也做：原来分页状态只在首页，播放页只有进页时那批房间，
     * ↓ 到最后一个就"卡住"（提示已经是最后一个），而网格里同样的操作却能续拉。
     * 现在两处共用 [LivePager]，行为一致；拉不到就如实提示。
     */
    private fun loadMoreRooms() {
        if (loadingMore) return
        if (!LivePager.hasMore) {
            CustomToast.show(this, if (rooms.size <= 1) "只有一个直播间" else "没有更多直播了")
            return
        }
        loadingMore = true
        CustomToast.show(this, "正在加载更多直播间…")
        Thread {
            val batch = try {
                LivePager.next(Prefs.getCookie(this))
            } catch (t: Throwable) {
                LivePager.Batch(error = t.message ?: t.javaClass.simpleName)
            }
            handler.post {
                loadingMore = false
                if (finished) return@post
                if (batch.items.isEmpty()) {
                    tvStatus.visibility = View.VISIBLE
                    tvStatus.text = if (batch.error != null) "直播加载失败，按「返回」退出" else "没有更多直播了"
                    CustomToast.show(this, if (batch.error != null) "加载失败，稍后再试" else "没有更多直播了")
                    return@post
                }
                val from = rooms.size
                rooms = rooms + batch.items
                index = from      // 切到新一批的第一个
                Log.d(TAG, "直播续拉 +${batch.items.size} 个，累计 ${rooms.size}")
                jumpToCurrent()
            }
        }.start()
    }

    private fun showCover() {
        if (!::switchCover.isInitialized) return
        switchCover.visibility = View.VISIBLE
        // 兜底：万一新流一直不就绪，1.5 秒后也要把黑罩撤掉（状态提示在罩上层）
        handler.postDelayed({ hideCover() }, 1500L)
    }

    private fun hideCover() {
        if (!::switchCover.isInitialized) return
        if (switchCover.visibility != View.GONE) switchCover.visibility = View.GONE
    }

    private fun releasePlayer() {
        val p = player ?: return
        player = null
        try {
            p.stop()
            p.release()
        } catch (_: Throwable) {
        }
    }

    private fun togglePause() {
        val p = player ?: return
        try {
            if (p.isPlaying) {
                p.playWhenReady = false
                tvStatus.visibility = View.VISIBLE
                tvStatus.text = "已暂停　按 OK 继续"
            } else {
                tvStatus.visibility = View.GONE
                p.playWhenReady = true
            }
        } catch (_: Throwable) {
        }
    }

    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        if (event.action == KeyEvent.ACTION_DOWN) {
            when (event.keyCode) {
                KeyEvent.KEYCODE_BACK -> {
                    finish()
                    return true
                }
                KeyEvent.KEYCODE_DPAD_CENTER, KeyEvent.KEYCODE_ENTER -> {
                    togglePause()
                    return true
                }
                // ★ 2.42：上下键换直播间（频道加减键同效）
                KeyEvent.KEYCODE_DPAD_UP, KeyEvent.KEYCODE_CHANNEL_UP -> {
                    switchRoom(-1)
                    return true
                }
                KeyEvent.KEYCODE_DPAD_DOWN, KeyEvent.KEYCODE_CHANNEL_DOWN -> {
                    switchRoom(1)
                    return true
                }
            }
        }
        return super.dispatchKeyEvent(event)
    }

    override fun onPause() {
        super.onPause()
        try { player?.playWhenReady = false } catch (_: Throwable) {}
    }

    override fun onResume() {
        super.onResume()
        // 从后台回来继续看（被系统回收过的话 player 为空，就不再自动重连了）
        try { player?.playWhenReady = true } catch (_: Throwable) {}
    }

    override fun onDestroy() {
        finished = true
        handler.removeCallbacksAndMessages(null)
        releasePlayer()
        // 列表是 FeedBus 的静态引用，用完必须清（与点播页同一套防泄漏约定）
        try { FeedBus.clear() } catch (_: Throwable) {}
        super.onDestroy()
    }
}
