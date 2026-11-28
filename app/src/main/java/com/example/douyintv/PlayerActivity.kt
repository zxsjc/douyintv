package com.example.douyintv

import tv.danmaku.ijk.media.player.IjkMediaPlayer
import android.app.Activity
import android.app.AlertDialog
import android.content.Intent
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.util.Log
import android.view.GestureDetector
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.SurfaceHolder
import android.view.SurfaceView
import android.view.View
import android.view.WindowManager
import android.widget.ProgressBar
import android.widget.TextView
import okhttp3.Request

/**
 * 刷视频界面
 *
 * 遥控器：↑/↓ 换视频（快到底自动加载） | ←/→ 快退/快进 10s
 *         OK 暂停/继续 | 菜单键 打开功能菜单 | BACK 退出
 * 触  屏：上/下滑换视频 | 左/右滑快退快进 | 单击暂停
 *
 * 菜单里可进：作者主页 / 评论区 / 播放模式（自动连播·单集循环）/ 切换引擎 / 设置
 * 控制栏 3 秒自动淡出，不再遮挡画面。
 */
class PlayerActivity : Activity(), SurfaceHolder.Callback, VideoEngine.Listener, PlayerMemoryAware {

    companion object {
        private const val TAG = "Player"

        /** 等比缩放后有效宽/高小于该像素即视为「小到看不清」（1080p 屏约 1/9 屏宽） */
        private const val MIN_VISIBLE_PX = 120f
        private const val REFERER = "https://www.douyin.com/"
        private const val SWIPE_MIN = 70f
        private const val SEEK_STEP = 10000L
        private const val HIDE_DELAY = 3000L

        // ---- 画面比例 ----
        // ★ v11 方案：SurfaceView 铺满全屏，比例交给解码器原生 letterbox。
        //   这正是 v11「播放完全正常」的做法 —— 不需要任何容器尺寸约束。
        //   旧的手动档位（拉伸 / 16:9 / 4:3）靠 AspectFrame 改容器尺寸实现，
        //   而那正是在 Mali450 上产生黑方块的机制，已整体移除。

        private const val UA =
            "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/122.0.0.0 Safari/537.36"

        /** 评论小窗的 Fragment tag（用于判断是否已经开着） */
        private const val TAG_COMMENT = "frag_comment"

    /**
     * IJK 连刷节流：1.5 秒内不允许连切。
     * 早期版本不崩正是因为切换慢（native 线程有充分时间退出）；
     * 性能优化后切换变快 → ff_read 撞 OMX 析构竞态 → 第 5 个必崩。
     * 节流只影响快速连按，正常看视频节奏完全无感。
     */
    private const val IJK_SWITCH_GUARD_MS = 1500L

    // ---- C1: 内存探针触发重建的阈值（按机型内存比例，不写死）----
    // 判读依据：mem_log.txt 里 PLAY#/seq 行的 native= / fd= / rss= 曲线。
    // 老设备（647MB/96MB 堆）实测稳定值：native~25MB、fd~100 上下；此处按
    // DeviceProfile.memoryClassMB 等比放大，正常设备不会过早误触发。
    private val nativeHeapLimitMB: Int get() = DeviceProfile.memoryClassMB * 4 / 5   // 96MB→76，256MB→204
    private val fdLimit: Int = 180                                                     // fd 上限设备相关，保守通用
    private val rssLimitMB: Int get() = DeviceProfile.memoryClassMB * 15 / 4           // 96MB→360，256MB→960
    private const val MIN_PLAYED_BEFORE_RESTART = 3 // 至少播 3 个才允许探针触发，避免起播瞬间误判

    /**
     * KILLCHASE 逐次切换的调用栈落盘 —— 历史排查残留（定位「到点必死」时的炸药）。
     * 每次 playCurrent 都要生成一份 1.2KB 栈 + 整文件读改写，在 eMMC 老盒子上
     * 是实打实的主线程 IO 卡顿。默认关闭，只在专门复现该问题时才置 true。
     */
    private const val DEBUG_KILLCHASE = false
    }

    private lateinit var surface: SurfaceView

    /** ★ 图文作品显示层（无视频流时轮播图片） */
    private lateinit var imagePostView: android.widget.ImageView

    /** 黑边遮罩：非 Exo 引擎图层缩放后，四周涂黑（防某些 ROM 露白） */
    private lateinit var barMask: BlackBarMaskView
    /** 上一条视频是否已经真正出过画面（决定切条时能不能沿用旧容器比例） */
    private var hasRenderedVideo = false

    /** 横竖切换过渡遮罩：目标比例（0 = 没有待撤的遮罩） */
    private var pendingCoverRatio = 0f
    private lateinit var switchCover: View

    /**
     * ★ 2.42：切条过渡遮罩的兜底撤除计时器 ★
     *   正常路径是「新视频尺寸到达 → 比例摆好 → 撤遮罩」；
     *   但坏链 / 纯音频可能永远不报尺寸，不能让黑罩一直挂着，
     *   2.6 秒后无条件撤掉（此时状态提示已经写在遮罩上层，用户看得到）。
     */

    private lateinit var aspectFrame: AspectFrame
    private lateinit var topBar: View
    private lateinit var bottomBar: View
    private lateinit var tvAuthor: TextView
    private lateinit var tvDesc: TextView
    private lateinit var tvPos: TextView
    private lateinit var tvDur: TextView
    private lateinit var tvStatus: TextView
    private lateinit var ivPause: TextView
    private lateinit var seekBar: ProgressBar
    private lateinit var progressBar: ProgressBar
    private lateinit var tvHint: TextView

    /** 底栏功能按钮，顺序 = 遥控器 ←→ 的导航顺序 */
    private lateinit var btnViews: List<TextView>
    private var btnFocus = 0

    private var engines: List<VideoEngine> = emptyList()
    private var engineIndex = 0

    private var items: List<DouyinApi.VideoItem> = emptyList()
    private var currentIndex = 0
    private var hasMore = true
    private var loadingMore = false

    private var holderReady: SurfaceHolder? = null
    private var lastVw = 0
    /** 当前视频是否已 prepare 成功 —— 用于区分「首次起播」与「Surface 重建仅重绑」 */
    private var preparedOnce = false
    private var lastVh = 0

    private var playToken = 0
    /** 81.4: 上次已就绪的 token（onPrepared 判重防虚涨） */
    private var lastPreparedToken = -1
    /** 黑屏看门狗：当前视频是否已出过首帧（onVideoSize = 出帧信号） */
    private var firstFrameForToken = -1
    /** 黑屏看门狗（按视频 ID 记次数）：换引擎会让 playToken 自增，
     *  若继续用 token 判重会无限换引擎。这里按 awemeId 统计当前视频已换过几次。 */
    private var blackRetryId = ""
    private var blackRetryCount = 0
    /** 比例容器是否已为当前视频应用过（每视频一次，防比例抖动反复重建 Surface） */
    private var aspectAppliedToken = -1

    /** 比例自愈的节流计数 */
    private var geomSeq = 0
    /** 弹幕层 */
    private var danmaku: DanmakuView? = null

    // ---- 播放诊断（给真机排查用：卡在“网络喂不饱”还是“解码/渲染跟不上”）----
    private var perfLine = ""

    // ---------------- 播放页自我重建（治 IJK 的 native 资源累积）----------------
    /**
     * 已连播的视频数量。
     *
     * 为什么要计数：真机实测，在同一个 PlayerActivity 里连播时，
     * IJK 的 Graphics 内存从 7.6MB 一路涨到 34.6MB（约 3~4 个硬解 overlay），
     * 且退出播放页也不回落 —— 源码里 overlay 只在 SDL_Vout 销毁时才批量释放，
     * 而单次 release 并不能保证 vout 被销毁。系统 MemFree 被挤到 27MB 后
     * 进程就被杀掉，表现为「刷着刷着退回首页」。
     */
    private var playedCount = 0
    /** ★ 本位置是否已连播够久（>30s）→ 作为「这次恢复是健康的」信号，用来清零崩溃连击 */
    private var healthyMarked = false
    /** KILLCHASE：切换计数（写文件定位"到点必死"的确定性触发） */
    private var killSwitch = 0
    // ★ 内存探针：每 2 秒采样 Java/Native 内存写盘（被杀前轨迹分析用），
    //   同时兼任「泄漏驱动的主动重建」触发器（见 maybeRestartByMemory）
    private val memTick = object : Runnable {
        private var seq = 0
        /** 内存里的环形缓冲：采样先攒着，不再每 2 秒整文件读改写（主线程 IO 卡顿源） */
        private val buf = ArrayList<String>(300)
        override fun run() {
            try {
                seq++
                val jused = (Runtime.getRuntime().totalMemory() - Runtime.getRuntime().freeMemory()) / 1024 / 1024
                val jmax = Runtime.getRuntime().maxMemory() / 1024 / 1024
                val nheap = try { android.os.Debug.getNativeHeapAllocatedSize() / 1024 / 1024 } catch (_: Throwable) { -1 }
                val sb = StringBuilder()
                sb.append(java.text.SimpleDateFormat("HH:mm:ss").format(java.util.Date()))
                sb.append(" seq=").append(seq)
                sb.append(" java=").append(jused).append("/").append(jmax).append("MB")
                if (nheap >= 0) sb.append(" native=").append(nheap).append("MB")
                val img = try { ImageLoader.cacheSize() / 1024 / 1024 } catch (_: Throwable) { -1 }
                if (img >= 0) sb.append(" img=").append(img).append("MB")
                buf.add(sb.toString())
                while (buf.size > 300) buf.removeAt(0)
                // ★ 低配盒子：写盘是主线程 IO 卡顿源，节流到每 30 秒一次，且挪到后台线程。
                //   诊断用途而已，不需要每 10 秒刷一次盘。snapMem 的 PLAY# 快照行仍保留。
                val now = android.os.SystemClock.elapsedRealtime()
                if (now - lastMemFlush >= 30_000L) {
                    lastMemFlush = now
                    val snapshot = buf.toList()
                    bgExec.execute {
                        try {
                            val f = java.io.File(filesDir, "mem_log.txt")
                            val keep = if (f.exists()) {
                                f.readText().split("\n").filter { it.isNotBlank() && !it.contains(" seq=") }.takeLast(120)
                            } else emptyList()
                            f.writeText((keep + snapshot).joinToString("\n") + "\n")
                        } catch (_: Throwable) {}
                    }
                }
            } catch (_: Throwable) {}
            // ★ C1：泄漏逼近危险水位 → 在系统 LMK / 内核 OOM 之前主动重建。
            //   旧的固定「第 4 个自杀」假设是"固件第 5 次起播杀"——已被"其他引擎不死"
            //   证伪（泄漏是 IJK 特有，非固件/进程级），故改为内存驱动：健康时不打扰。
            maybeRestartByMemory()
            handler.postDelayed(this, 2000)
        }
    }

    /** 正在自我重建：onDestroy 里据此跳过 FeedBus.clear()，否则列表会被清空导致重建后无片可播 */
    private var restarting = false

    /**
     * 连播多少个视频后重建播放页（纯兜底计数）。
     *
     * ★ 82.x 起由「固定计数」改为「内存探针优先、计数兜底」：
     *   旧值 4 建立在"固件第 5 次起播杀"假设上（81.1），但"Exo/VLC/系统播放器
     *   都不死"的事实证明第 5 个死是 IJK 特有的 native 资源泄漏（OMX/MediaCodec/
     *   vout overlay 反复申请释放），不是固件计数。计数只做最后保险，
     *   真正触发交给 maybeRestartByMemory() 的阈值探针 —— 健康视频一次都不重启。
     */
    /** 连播多少视频后重建播放页（纯兜底计数；高配机器放大到 60，避免误杀） */
    private val maxPlayBeforeRestart: Int get() = if (DeviceProfile.isLegacy) 20 else 60
    /** 是否处于「缓冲中」—— IJK 没有 bufferedPercentage API，用它替代水位显示 */
    private var buffering = false

    /** 上一次进度采样位置（ms）—— 用于判定「画面确实在往前走」 */
    private var lastTickPos = 0L

    /** 「缓冲中」开始时刻（elapsedRealtime）；0 = 当前不在缓冲中 */
    private var bufferingSince = 0L

    /** 本轮 tick 里播放位置是否在前进（用来区分“真卡住”和“网络抖一下”） */
    private var posAdvancing = false
    private val perfTick = object : Runnable {
        override fun run() {
            try { samplePerf() } catch (_: Throwable) {}
            handler.postDelayed(this, 2000)
        }
    }
    private var controlsVisible = true
    /** 当前弹层菜单对话框；显示中时所有按键交给它（修「暂停后按方向键切走视频」） */
    private var menuDialog: android.app.Dialog? = null
    /** 画质切换后的重解析结果：仅对 overrideForIndex 这一条生效，切视频自动失效 */
    private var qualityOverride: DouyinApi.VideoItem? = null
    private var overrideForIndex: Int = -1

    /**
     * 数据来源（feed / author / search）。
     * 用于「数据源隔离」：滑到底时按来源决定继续拉推荐流还是该作者作品，
     * 避免在作者页滑着滑着混进首页推荐。
     */
    private var feedSource = FeedBus.SOURCE_FEED

    /**
     * 是否处于暂停态。
     *
     * ★ 控制栏自动隐藏策略完全依赖这个标志：
     *   播放中 → 显示后 3 秒自动隐藏
     *   暂停中 → 保持可见，不自动隐藏（用户要操作）
     *
     * 之前是反的（播放常驻、暂停反而消失），根因是"暂停"分支传了
     * autoHide=false 却在其它 15 处被重新 showControls() 覆盖了计时器状态。
     */
    private var paused = false

    // ---- 错误抖动抑制 ----
    private var lastErrorAt = 0L
    private var retrySameEngine = 0

    /** 播放倍速（播放器菜单可调；Exo/IJK 生效） */
    private var playSpeed = 1.0f

    /** 当前使用第几条线路（0 = 主链，其余为备用链） */
    private var urlAttempt = 0

    /**
     * 已做过「重新获取播放地址」的视频 awemeId。
     * 抖音的播放地址带时效签名，同一条隔一段时间就会 403/404。
     * 播失败时按 awemeId 重拉一次详情换新地址，每条只重试一次防死循环。
     */
    private val refreshedIds = HashSet<String>()

    // ---- 图文作品轮播状态 ----
    /** 当前图文条目的图片列表（仅图文作品非空） */
    private var imageSlides: List<String> = emptyList()
    private var imageSlideIndex = 0
    /** 图文轮播是否处于暂停态 */
    private var imageSlidePaused = false
    /**
     * 图文轮播定时器（每 4 秒切一张）。
     * ★ 2.38：不再 % size 死循环 ★
     *   旧实现取模，永远在本条的图片集里转圈 —— 用户看到的就是「图文只单条循环，
     *   不会自动播下一条」。现在放完最后一张就按播放模式决定：
     *   自动连播 → 推进下一条作品；单集循环 → 回到第一张。
     */
    private val imageSlideTick = object : Runnable {
        override fun run() {
            if (isFinishing || imageSlides.isEmpty()) return
            if (imageSlidePaused || paused) { handler.postDelayed(this, 1000L); return }
            if (imageSlideIndex < imageSlides.lastIndex) {
                imageSlideIndex++
                showCurrentSlide()
                updateInfo()
                tvPos.text = "${imageSlideIndex + 1}/${imageSlides.size}"
                try { seekBar.progress = (imageSlideIndex + 1) * 1000 / imageSlides.size } catch (_: Throwable) {}
                handler.postDelayed(this, 4000L)
            } else {
                advanceImagePost()
            }
        }
    }

    /** 本条图文的最后一张放完了：连播下一条 / 单集循环重头再来 */
    private fun advanceImagePost() {
        if (imageSlides.isEmpty()) return
        if (Prefs.isLoopOne(this)) {
            imageSlideIndex = 0
            showCurrentSlide()
            updateInfo()
            tvPos.text = "1/${imageSlides.size}"
            try { seekBar.progress = 1000 / maxOf(1, imageSlides.size) } catch (_: Throwable) {}
            handler.postDelayed(imageSlideTick, 4000L)
            return
        }
        if (currentIndex < items.size - 1) {
            currentIndex++
            urlAttempt = 0
            retrySameEngine = 0
            playCurrent()
            return
        }
        if (hasMore) {
            maybeLoadMore()
            flash("加载更多…")
            // 这一批回来可能要一两秒：稍后重试一次（仍在末尾就再等）
            handler.postDelayed({
                if (imageSlides.isNotEmpty() && imageSlideIndex >= imageSlides.lastIndex) advanceImagePost()
            }, 1600L)
            return
        }
        flash("已是最后一条")
    }

    /** 图文作品：←→ 手动翻图（到头则切下一条作品） */
    private fun stepImageSlide(delta: Int) {
        if (imageSlides.isEmpty()) return
        val target = imageSlideIndex + delta
        if (target > imageSlides.lastIndex) { nextVideo(); return }
        imageSlideIndex = target.coerceAtLeast(0)
        showCurrentSlide()
        updateInfo()
        tvPos.text = "${imageSlideIndex + 1}/${imageSlides.size}"
        try { seekBar.progress = (imageSlideIndex + 1) * 1000 / imageSlides.size } catch (_: Throwable) {}
        handler.removeCallbacks(imageSlideTick)
        handler.postDelayed(imageSlideTick, 4000L)
        flash("${imageSlideIndex + 1}/${imageSlides.size}")
        showControls()
    }

    /** 起播卡死看门狗：位置一直为 0 的起始时刻 / 是否已救过当前视频 */
    private var stallSince = 0L
    private var stallRetriedForToken = -1

    /** 中途冻住看门狗：位置不再前进的起始时刻 / 上一次位置 / 本条已自动换引擎次数 */
    private var stuckSince = 0L
    private var lastStuckPos = 0L
    private var stuckSwitches = 0

    /** 最近错误（电视上看不到 logcat，放进菜单里可查） */
    private val errHistory = ArrayList<String>()

    // ================================================================
    // ★ 低配盒子流畅度：把主线程上的重复文件 IO / 线程创建全部收敛 ★
    // ================================================================
    /**
     * 点赞状态的进程内缓存。
     * 旧代码在 progressTick（每 500ms！）里直接调 Prefs.isDigged，
     * 而后者每次都 readText() 整个 digged.json 再 JSON 解析 ——
     * 在 eMMC 老盒子上等于每半秒一次磁盘读 + JSON 解析，主线程被 IO 拖死，
     * 表现就是「播一会儿卡住」。这里改成：进入视频时读一次，结果缓存，
     * tick 只读内存；点赞切换时同步更新缓存。
     */
    private var cachedDiggedId: String = ""
    private var cachedDigged = false

    /** 复用线程：替代散落各处的 Thread{}.start()，避免连播时线程无限累积 */
    private val bgExec = java.util.concurrent.Executors.newFixedThreadPool(3)

    /** 内存日志写盘节流：至少间隔 30 秒才落一次盘（诊断用途，不值得频繁 IO） */
    private var lastMemFlush = 0L

    private val handler = Handler(Looper.getMainLooper())
    private val headers = mapOf("Referer" to REFERER, "User-Agent" to UA)

    private val hideTask = Runnable { hideControls() }

    private val progressTick = object : Runnable {
        private var aliveSeq = 0
        override fun run() {
            // ★ 图文作品不走视频进度/看门狗：位置恒为 0，会误触发换引擎。
            //   其进度与暂停由 imageSlideTick 管理；这里只保留恢复点心跳与健康信号。
            if (imageSlides.isNotEmpty()) {
                try {
                    if (++aliveSeq % 10 == 0 && items.isNotEmpty()) Prefs.touchResumeAlive(this@PlayerActivity)
                } catch (_: Throwable) {}
                handler.postDelayed(this, 500)
                return
            }
            try {
                val e = currentEngine()
                val pos = e.position()
                val dur = e.duration()
                if (dur > 0) {
                    seekBar.progress = ((pos.toFloat() / dur) * 1000f).toInt().coerceIn(0, 1000)
                    tvPos.text = fmt(pos)
                    tvDur.text = fmt(dur)
                }
                // ★「缓冲中」自愈：IJK 偶发不发 MEDIA_INFO_BUFFERING_END
                //   （实测：画面早已在正常播放，屏幕中间的「缓冲中...」却一直挂着）。
                //   两个判据，任一成立即认为已恢复：
                //     ① 位置在前进（最硬）；
                //     ② 已报缓冲超过 6 秒且播放器自称在播（兜底，防 position 不刷新）。
                posAdvancing = pos > lastTickPos + 300L
                if (buffering) {
                    val now = SystemClock.elapsedRealtime()
                    if (posAdvancing ||
                        (bufferingSince > 0L && now - bufferingSince > 6000L && e.isPlaying())) {
                        buffering = false
                        bufferingSince = 0L
                        handler.removeCallbacks(bufferNoticeTask)
                        if (tvStatus.text?.contains("缓冲") == true) tvStatus.text = ""
                    }
                }
                lastTickPos = pos
                // ★ 起播卡死看门狗 ★
                //   「恢复上次播放」命中旧直链时，可能被服务端"挂着不响应"：
                //   既不出画面也不报错，位置一直是 0 —— 用户看到的就是 App 卡死 / 像断网。
                //   这里 8 秒还没动就开始自救：重新解析地址重播。
                if (!paused && pos < 500L && !isFinishing) {
                    if (stallSince == 0L) stallSince = SystemClock.elapsedRealtime()
                    else {
                        if (SystemClock.elapsedRealtime() - stallSince > 8000L &&
                            stallRetriedForToken != playToken) {
                            stallRetriedForToken = playToken
                            stallSince = 0L
                            Log.w(TAG, "起播卡死：换引擎重播")
                            switchToNextEngineAndReplay("起播卡死")
                        }
                    }
                } else stallSince = 0L
                // 中途冻住的判定放在独立定时器 freezeWatch 里
                //   （不能用进度回调：真冻住时引擎根本不再回调，这里永远收不到 tick）
                // ★ 比例自愈 ★
                //   「有时正常、有时变形」的根子是回调乱序：上一条视频的尺寸回调可能迟到，
                //   把新视频的比例盖成上一条的。这里每 1 秒按已知真实尺寸比对一次，
                //   只要容器/图层跟视频比例对不上就重新应用（幂等，比例正确时零动作）。
                if (++geomSeq % 2 == 0) resyncGeometryIfNeeded()
                // ★ 恢复点心跳 ★
                //   恢复窗口的过期判定必须基于「进程此刻还活着」，而不是
                //   「上一个视频的起播时刻」—— 否则看一个视频超过窗口再往下滑，
                //   崩溃时恢复点会被误判过期清掉 → 用户被丢回推荐页（还回不去）。
                //   每 5 秒只写一个 long，开销可忽略。
                if (items.isNotEmpty() && ++aliveSeq % 10 == 0) {
                    try { Prefs.touchResumeAlive(this@PlayerActivity) } catch (_: Throwable) {}
                }
                // ★ 健康信号 ★
                //   用「本位置连播超过 30 秒」判定这次恢复是健康的，而不是
                //   「起播成功」—— 崩溃恰恰发生在起播成功之后的下一次切换，
                //   用起播成功清零的话，连崩计数永远累加不起来（熔断形同虚设）。
                if (!healthyMarked && pos > 30_000L) {
                    healthyMarked = true
                    try { Prefs.resetCrashStreak(this@PlayerActivity) } catch (_: Throwable) {}
                }
                // ★ 黑屏看门狗 ★
                //   起播后位置正常往前走但首帧迟迟不到（onVideoSize 未触发）= 黑屏。
                //   自动重载一次（每视频最多一次），把「黑屏卡死」变成「黑屏后自动恢复」。
                //   · buffering 时不触发（那只是缓冲中，画面本来就没出来）
                //   · VLC 不回调视频尺寸（源一直报 0x0），拿它判首帧必然误判成黑屏 →
                //     明明在正常播也会每隔几秒重载一次。VLC 直接跳过这条判据。
                val engNow = try { currentEngine() } catch (_: Throwable) { null }
                val blackId = cur()?.awemeId ?: ""
                // 换了视频就重置该视频的黑屏重试计数
                if (blackId != blackRetryId) { blackRetryId = blackId; blackRetryCount = 0 }
                // 重试上限 = 线路数 × 引擎数：先换遍所有线路，再逐个引擎重来，
                // 组合用尽后停下（防 TRIN 这类极端作品无限自救）。
                val maxBlackRetries = maxOf(1, engines.size) * maxOf(1, candidateUrls().size)
                if (engNow != null &&
                    preparedOnce && !paused && !buffering &&
                    firstFrameForToken != playToken && blackRetryCount < maxBlackRetries &&
                    pos > 5000L) {
                    // ★★★「有声音无画面」修复（第 4 版，正确解法）★★★
                    //   教训链：
                    //     · 换引擎没用 —— 三个引擎拿到同一条 bit_rate 坏档；
                    //     · 把裸 /aweme/v1/play/ 塞进线路也没用 —— 播放器带不齐
                    //       Cookie/Referer、302 跨域丢 headers → 风控页 → 一直缓冲。
                    //   正解：无首帧时用 DouyinApi.refreshViaPlayApi（OkHttp 带完整
                   //   headers + Cookie）请求 play 接口，拿它 302 后的【官方 CDN 直链】，
                   //   再喂给播放器 —— 这正是官方抖音能播的那条流。
                    //   position 前进不能当「有画面」——音频解码时照样前进。
                    blackRetryCount++
                    val it = cur()
                    val key = "bf_" + (it?.awemeId ?: "")
                    if (it != null && it.awemeId.isNotBlank() && refreshedIds.add(key)) {
                        // ① 先试带 Cookie 解析出的官方新鲜直链（对 TRIN 这类最有效）
                        Log.w(TAG, "黑屏看门狗：位置 ${pos}ms 无首帧 → 解析官方主链 ${it.awemeId}")
                        refreshUrlAndReplay(it)
                    } else {
                        val urls = candidateUrls()
                        if (urls.size > 1 && urlAttempt < urls.size - 1) {
                            urlAttempt++
                            Log.w(TAG, "黑屏看门狗：无首帧 → 换线路 ${urlAttempt + 1}/${urls.size}")
                            playCurrent()
                        } else {
                            Log.w(TAG, "黑屏看门狗：仍无首帧 → 换引擎 ($blackRetryCount/$maxBlackRetries)")
                            switchToNextEngineAndReplay("无视频首帧")
                        }
                    }
                }
            } catch (_: Exception) {
            }
            handler.postDelayed(this, 500)
        }
    }

    private val gestureDetector: GestureDetector by lazy {
        GestureDetector(this, object : GestureDetector.SimpleOnGestureListener() {
            override fun onDown(e: MotionEvent): Boolean = true
            override fun onSingleTapConfirmed(e: MotionEvent): Boolean {
                if (!controlsVisible) { showControls(); return true }
                togglePlayPause(); return true
            }
            override fun onDoubleTap(e: MotionEvent): Boolean { togglePlayPause(); return true }
            override fun onFling(e1: MotionEvent?, e2: MotionEvent, vx: Float, vy: Float): Boolean {
                if (e1 == null) return false
                val dx = e2.x - e1.x
                val dy = e2.y - e1.y
                if (Math.abs(dy) > Math.abs(dx)) {
                    if (dy < -SWIPE_MIN) nextVideo() else if (dy > SWIPE_MIN) prevVideo()
                } else {
                    if (dx < -SWIPE_MIN) doSeek(SEEK_STEP) else if (dx > SWIPE_MIN) doSeek(-SEEK_STEP)
                }
                return true
            }
        })
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        try {
            window.addFlags(
                WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON
                        or WindowManager.LayoutParams.FLAG_FULLSCREEN
            )
            setContentView(R.layout.activity_player)

            surface = findViewById(R.id.surface) as SurfaceView
            switchCover = findViewById(R.id.switchCover)
            imagePostView = findViewById(R.id.imagePostView) as android.widget.ImageView
        // ★ 播放页用不到任何封面图：把首页积累的图片缓存压到 2MB ★
        //   这台电视空闲内存常年只有 30MB 左右，让出这十几 MB
        //   能给解码器/缓冲留出余量，显著降低被系统杀掉(退回首页)的概率。
        try { ImageLoader.trimTo(2 * 1024 * 1024) } catch (_: Throwable) {}
            aspectFrame = findViewById(R.id.aspectFrame) as AspectFrame
            barMask = findViewById(R.id.barMask) as BlackBarMaskView
            topBar = findViewById(R.id.topBar)
            bottomBar = findViewById(R.id.bottomBar)
            tvAuthor = findViewById(R.id.tvAuthor) as TextView
            tvDesc = findViewById(R.id.tvDesc) as TextView
            tvPos = findViewById(R.id.tvPos) as TextView
            tvDur = findViewById(R.id.tvDur) as TextView
            tvStatus = findViewById(R.id.tvStatus) as TextView
            ivPause = findViewById(R.id.ivPause) as TextView
            seekBar = findViewById(R.id.seekBar) as ProgressBar
            progressBar = findViewById(R.id.progressBar) as ProgressBar
            tvHint = findViewById(R.id.tvHint) as TextView
            danmaku = try { findViewById(R.id.danmaku) as DanmakuView } catch (_: Throwable) { null }
            if (Prefs.getDanmaku()) applyDanmaku()
            playSpeed = Prefs.getPlaySpeed(this)      // ★ 2.38 记住上次的倍速

            // ★ 功能按钮直接摆在底栏：←→ 就能选中、OK 进入
            //   （不再让用户去猜「菜单键」在哪 —— 电视遥控器还可能压根没有 MENU 键）
            btnViews = listOf(
                findViewById(R.id.btnPlay) as TextView,
                findViewById(R.id.btnAuthor) as TextView,
                findViewById(R.id.btnTopic) as TextView,
                findViewById(R.id.btnComment) as TextView,
                findViewById(R.id.btnSearch) as TextView,
                findViewById(R.id.btnDigg) as TextView,
                findViewById(R.id.btnMenu) as TextView
            )
            btnViews.forEachIndexed { i, v ->
                v.setOnClickListener { FocusAnim.press(v); triggerBtn(i) }
                v.setOnFocusChangeListener { view, has -> FocusAnim.scale(view, has) }
                // ★ 2.38：底栏按钮不再接受遥控器焦点 ★
                //   以前播放页一进来焦点就落在按钮上，←→ 全被它们吃掉，
                //   进度条永远拖不动。功能入口统一收到「菜单键」里，
                //   ←→ 就只干一件事：快退 / 快进。触屏点按仍然有效。
                v.isFocusable = false
                v.isFocusableInTouchMode = false
            }
            tvHint.text = "←→ 选择　OK 进入"

            // ★ 播放列表改从内存总线取（零拷贝），不再解析 Intent 里的 JSON。
            //   原实现用 gson 反序列化大列表：既违反"禁 Gson"红线，
            //   又可能因 Binder 事务上限（~1MB）抛 TransactionTooLargeException
            //   → 用户看到的就是「作者页点进去播不了」。
            items = FeedBus.peek() ?: emptyList()
            currentIndex = FeedBus.startIndex()
                .coerceIn(0, maxOf(0, items.size - 1))
            feedSource = FeedBus.source()
            // 关注流的续拉游标随列表一起带进来（否则播放页会从第 0 页重拉，全被去重掉）
            followCursor = FeedBus.cursor()
            // 推荐流 / 关注流 / 作者页能继续加载；搜索结果是固定集合，不再翻页
            hasMore = feedSource == FeedBus.SOURCE_FEED ||
                feedSource == FeedBus.SOURCE_FOLLOW ||
                feedSource == FeedBus.SOURCE_AUTHOR

            // IJK native 库：软解兜底引擎必需。★ 用户实测三个引擎里 IJK 最流畅 ★
            // （Mali450 那代硬解驱动不稳，软解反而更顺）
            try {
                IjkMediaPlayer.loadLibrariesOnce(null)
                IjkMediaPlayer.native_profileBegin("libijkplayer.so")
            } catch (e: Throwable) {
                Log.w(TAG, "IJK 库加载失败: ${e.message}")
            }

            val list = mutableListOf<VideoEngine>()
            // ★ 引擎顺序与 v11 一致：ExoPlayer 第一 ★
            //   （v11 反编译确认：Exo → IJK → 系统；默认 0 = ExoPlayer）
            //   ExoEngine 实现已逐行对齐 v11（setVideoSurfaceHolder +
            //   ProgressiveMediaSource），黑方块/比例问题随之一并消除。
            try { list.add(ExoEngine(this, headers)); Log.i(TAG, "✓ ExoPlayer") }
            catch (t: Throwable) { Log.e(TAG, "✗ ExoPlayer: ${t.javaClass.simpleName}: ${t.message}") }
            try { list.add(IjkEngine(headers)); Log.i(TAG, "✓ IJKPlayer") }
            catch (t: Throwable) { Log.e(TAG, "✗ IJK: ${t.javaClass.simpleName}: ${t.message}") }
            try { list.add(SystemEngine(this, headers)); Log.i(TAG, "✓ 系统播放器") }
            catch (t: Throwable) { Log.e(TAG, "✗ 系统播放器: ${t.message}") }
            // ★ VLC 已移除 ★
            //   VLC 引擎在这台盒子上音画不同步、播放中途丢音（它自己的音频时钟和
            //   系统 AudioTrack 对不上，盒子 ROM 没有可用参数可调）。实测无法稳定修复，
            //   按用户要求彻底删除：引擎列表只保留 Exo / IJK / 系统播放器。
            engines = list
            Log.i(TAG, "共 ${engines.size} 个引擎: ${engines.joinToString { it.name }}")
            if (engines.isEmpty()) tvStatus.text = "没有可用的播放引擎"
            // 把可用引擎直接显示在底栏 —— 用户在电视上就能确认「到底有几个能用的」，
            // 不用再靠猜（这一步就是为了排查「只剩系统播放器可用」那类问题）
            tvHint.text = if (engines.isEmpty()) "无可用引擎"
            else "引擎 ${engines.joinToString("/") { it.name }}　←→快进快退 · 菜单键功能"

            engineIndex = if (engines.isEmpty()) 0
            else {
                // ★ 记忆优先级：用户手动选过的引擎 > 最近一次起播成功的引擎
                val userRaw = Prefs.getUserEngine(this)
                val savedRaw = if (userRaw >= 0) userRaw else Prefs.getEngine(this)
                // 旧版本的 3 = VLC（已删除），自动回落到默认，别把用户"记住"到系统播放器
                val saved = if (savedRaw == 3) -1 else savedRaw
                // ★ 用户没手动选过时默认 IJK ★
                //   实测这台盒子（Cortex-A7 / Mali450 / 256MB 上报内存）上 ExoPlayer
                //   播一会儿会停住（位置不走、控制栏自动变成暂停态），IJK 稳定得多。
                //   Exo 仍然保留，用户想用可以在菜单里手动切（手动选择会被记住）。
                if (userRaw < 0 && engines.size > 1) 1 else saved.coerceIn(0, engines.size - 1)
            }

            surface.holder.addCallback(this)
            // 不再 setType(SURFACE_TYPE_PUSH_BUFFERS)：API16+ 已废弃，
            // 部分老 ROM 上反而干扰渲染；也不让 SurfaceView 抢焦点。
            // （SurfaceView 尺寸保持 match_parent —— 任何尺寸调整都会引起
            //   Surface 重建，是黑方块/卡顿的来源）

            Log.d(TAG, "视频=${items.size} 引擎=${engines.getOrNull(engineIndex)?.name}")
            updateInfo()
            showControls()
            try { (application as App).activePlayer = this } catch (_: Throwable) {}
            // 前台服务提升进程优先级（对齐 B 站 TV 存活策略）
            try {
                val si = Intent(this, PlayService::class.java)
                if (android.os.Build.VERSION.SDK_INT >= 26) startForegroundService(si) else startService(si)
            } catch (_: Throwable) {}
            // 音频焦点：让系统识别为"正在播放的媒体 App"
            try {
                val am = getSystemService(AUDIO_SERVICE) as android.media.AudioManager
                am.requestAudioFocus(null, android.media.AudioManager.STREAM_MUSIC,
                    android.media.AudioManager.AUDIOFOCUS_GAIN)
            } catch (_: Throwable) {}
            handler.removeCallbacks(memTick)
            handler.postDelayed(memTick, 2000)
        } catch (e: Exception) {
            Log.e(TAG, "onCreate crash", e)
            throw e
        }
    }

    private fun currentEngine(): VideoEngine = engines[engineIndex]
private fun cur(): DouyinApi.VideoItem? {
        // 画质切换后：当前这条用重解析结果（1080P 主链），换视频自动失效
        if (overrideForIndex == currentIndex) {
            qualityOverride?.let { return it }
        }
        return items.getOrNull(currentIndex)
    }

    // ---------------- 控制栏 ----------------
    // ★ 用 visibility 直接切换，不用 ViewPropertyAnimator：
    //   老电视 ROM 上 animate().alpha() 经常不生效或停在中间态，
    //   表现就是「控制栏死活不隐藏」——用户反馈过这个。
    /**
     * 显示控制栏，并按当前播放状态决定是否启动自动隐藏倒计时。
     *
     * ★ 这是显示控制栏的【唯一入口】★
     *   不要在别处直接改 topBar/bottomBar 的 visibility，也不要另建一套计时器。
     *   之前 16 处散落调用互相 removeCallbacks + postDelayed，
     *   正是「播放时控制栏常驻不消失」「翻页不重置计时」的根因。
     */
    private fun showControls() {
        controlsVisible = true
        topBar.visibility = View.VISIBLE
        bottomBar.visibility = View.VISIBLE
        topBar.alpha = 1f
        bottomBar.alpha = 1f
        restartHideTimer()
    }

    /**
     * 重置自动隐藏倒计时。
     *
     * 切视频、翻页、任意按键后都必须走到这里 —— 否则会出现
     * 「刚翻页就被上一次留下的计时器隐藏掉」。
     * 暂停态下不启动倒计时（控制栏要留在屏幕上供操作）。
     */
    private fun restartHideTimer() {
        handler.removeCallbacks(hideTask)
        if (!paused) {
            handler.postDelayed(hideTask, HIDE_DELAY)
        }
    }

    private fun hideControls() {
        controlsVisible = false
        topBar.visibility = View.GONE
        bottomBar.visibility = View.GONE
    }

    /**
     * 采样播放状态，拼成一行诊断显示。
     *
     * 判读方法（给真机排查用）：
     *   · 缓冲领先很小(pos 紧贴 buf) 且卡  → 网络喂不饱（降码率/加缓冲才有效）
     *   · 缓冲领先很大却还卡               → 解码或渲染跟不上（换引擎/换渲染路径才有用）
     */
    private fun samplePerf() {
        // ★ 图文作品没有视频引擎在跑，采样只会显示「ExoPlayer 无实例」
        if (imageSlides.isNotEmpty()) { perfLine = ""; return }
        val eng = try { currentEngine() } catch (_: Throwable) { return }
        val pos = try { eng.position() } catch (_: Throwable) { 0L }
        val buf = try { eng.bufferedMs() } catch (_: Throwable) { pos }
        val lead = ((buf - pos).coerceAtLeast(0L)) / 1000f
        val kbps = DouyinApi.Diag.lastBitrate / 1000
        val gear = DouyinApi.Diag.lastGear
        // Surface 尺寸也要显示：视频层尺寸 != 屏幕尺寸时，老 ROM 的合成器可能
        // 回退到软件缩放，那是另一条卡顿路径（和缺硬解是两回事）
        val sw = try { surface.width } catch (_: Throwable) { 0 }
        val sh = try { surface.height } catch (_: Throwable) { 0 }
        perfLine = "%s %s %s · 源%dx%d · 画%dx%d · %dkbps%s · 领先%.1fs".format(
            eng.name,
            eng.decoderInfo(),
            if (eng.isPlaying()) "▶" else "‖",
            lastVw, lastVh, sw, sh, kbps,
            if (gear.isEmpty()) "" else "·" + gear,
            lead
        )
        if (buffering) perfLine += "　⟳缓冲中"
        updateInfo()
    }

    /**
     * C1：内存/资源逼近危险水位 → 主动重建播放页。
     *
     * 为什么必要：这台 647MB / 无 swap 的老电视上，IJK 每切一个视频，
     * native 侧（OMX/MediaCodec/vout overlay）会漏一点不可回收的内存，
     * 大约第 5 个触顶被系统 LMK / 内核 OOM 杀掉 → 无 tombstone、无崩溃日志，
     * 用户看到的就是「刷着刷着直接退回推荐页」。Exo（复用实例）/VLC（自管
     * MediaCodec 生命周期）没有这条泄漏路径，所以从来不死。
     *
     * 本方法在系统动手之前主动离场（killProcess + 恢复点回原位），
     * 把「被杀丢回推荐页」变成「受控重建、回到同一位置」。健康视频不触发。
     *
     * ★ 阈值需真机校准：对照 mem_log.txt 的 native=/fd=/rss= 曲线，
     *   取「系统杀之前」的稳定值再留 20% 余量。默认值偏保守（宁早勿晚）。
     */
    private fun maybeRestartByMemory() {
        // ★ 内存自杀探针只对老设备生效 ★
        //   阈值是按 IJK 泄漏的老电视调的（native/FD/RSS 逼近触顶）。
        //   高配机器 RSS/FD 正常就高，照旧阈值会误触发 → 播着播着整个 App 重载，
        //   用户看到的就是「黑屏 → 自动重新加载」。高配只靠 count 兜底（值更大）。
        if (!DeviceProfile.isLegacy) return
        if (restarting || isFinishing || playedCount < MIN_PLAYED_BEFORE_RESTART) return
        try {
            val nheap = try { (android.os.Debug.getNativeHeapAllocatedSize() / 1024 / 1024).toInt() } catch (_: Throwable) { -1 }
            val fds = try { java.io.File("/proc/self/fd").list()?.size ?: 0 } catch (_: Throwable) { -1 }
            val rss = try {
                val st = java.io.File("/proc/self/status").readText()
                st.lineSequence().firstOrNull { it.startsWith("VmRSS:") }
                    ?.substring(6)?.trim()?.split(" ")[0]?.toLongOrNull()?.div(1024)?.toInt() ?: -1
            } catch (_: Throwable) { -1 }
            val hit = (nheap > nativeHeapLimitMB) || (fds > fdLimit) || (rss > rssLimitMB)
            if (hit) {
                Prefs.trace(this, "MEM_TRIGGER nheap=${nheap}MB fd=$fds rss=${rss}MB played=$playedCount -> restart")
                Log.w(TAG, "内存逼近危险水位(native=${nheap}MB fd=$fds rss=${rss}MB) → 主动重建")
                restartSelf()
            }
        } catch (_: Throwable) {}
    }

    /**
     * 自我重建播放页 —— 用来把 IJK 累积的 native 资源（vout / 硬解 overlay）
     * 彻底放掉。实测重建一次能回收约 9MB Graphics。
     *
     * 列表不重新传（走 FeedBus 零拷贝），只把「当前播到第几条」写回去，
     * 新实例会从同一条继续，用户感知就是「当前这个视频重播了一下」。
     */
    private fun restartSelf() {
        if (isFinishing || restarting) return
        try {
            restarting = true
            // ★ 必须落盘恢复点，而且要用【当前要播的那条】的 index ★
            //   原来这里只调 FeedBus.put —— 那是纯内存总线，killProcess 之后
            //   跟着进程一起没了。真正让用户回到原位的只有 Prefs 里的恢复 JSON，
            //   而它的 idx 还停在上一个视频（saveResumeState 只在 onPrepared 时写）
            //   → 重启后总是回到上一条重播。这里先补一次保存，把目标条写死。
            saveResumeState()
            // ★ 标记「计划内重启」★
            //   killProcess 在冷启动侧看起来和崩溃一模一样。不标记的话，
            //   「探针/计数触发的重启」会被当成连续崩溃累积 → 误把用户强制切到别的引擎
            //   （IJK 明明是用户选的、也更流畅）。
            Prefs.markPlannedRestart(this)
            Prefs.trace(this, "SELF_KILL idx=$currentIndex played=$playedCount resume+planned 已同步落盘")
            Log.i(TAG, "自我重启（切换点）：已连播 $playedCount 个，绕泄漏触顶")
            android.os.Process.killProcess(android.os.Process.myPid())
        } catch (t: Throwable) {
            restarting = false
            Log.w(TAG, "重启失败，继续原样播放: " + t.message)
        }
    }

    private fun flash(msg: String) {
        tvStatus.text = msg
        handler.postDelayed({ if (tvStatus.text == msg) tvStatus.text = "" }, 1700)
    }

    private fun fmt(ms: Long): String {
        val s = (ms / 1000).coerceAtLeast(0)
        return String.format("%02d:%02d", s / 60, s % 60)
    }

    /** 读赞状态：同视频直接用缓存，换视频才真正读一次文件 */
    private fun isDiggedCached(id: String): Boolean {
        if (id != cachedDiggedId) {
            cachedDiggedId = id
            cachedDigged = try { Prefs.isDigged(this, id) } catch (_: Exception) { false }
        }
        return cachedDigged
    }

    /** 作品发布日期：完整「年-月-日」格式 */
    private fun formatPublishDate(createTimeSec: Long): String {
        return try {
            val ms = if (createTimeSec < 100000000000L) createTimeSec * 1000L else createTimeSec
            val d = java.util.Date(ms)
            "发布于 " + java.text.SimpleDateFormat("yyyy-MM-dd", java.util.Locale.getDefault()).format(d)
        } catch (_: Throwable) { "" }
    }

    private fun updateInfo() {
        val it = cur()
        if (it == null) { tvAuthor.text = ""; tvDesc.text = "无视频"; return }
        tvAuthor.text = "@${it.author}"
        val flags = buildString {
            append("${currentIndex + 1}/${items.size}")
            append("　赞 ${it.diggCount}")
            if (it.commentCount > 0) append("　评论 ${it.commentCount}")
            // ★ 作品发布日期（紧跟赞/评论之后）；create_time 是 Unix 秒
            if (it.createTime > 0L) append("　${formatPublishDate(it.createTime)}")
        }
        tvDesc.text = if (perfLine.isEmpty()) "$flags\n${it.desc}"
        else "$flags\n$perfLine\n${it.desc}"

        // 底栏按钮随视频内容刷新
        if (::btnViews.isInitialized) {
            btnViews[1].text = "@${it.author}"
            val topic = it.topics.firstOrNull()
            btnViews[2].text = if (topic != null) "#$topic" else "# 无话题"
            btnViews[2].alpha = if (topic != null) 1f else 0.45f
            btnViews[3].text = "评论 ${it.commentCount}"
            // ★ 赞状态回显（本地记忆；已赞用品牌红）——走缓存，不读盘
            val digged = isDiggedCached(it.awemeId)
            btnViews[5].text = if (digged) "赞 ✓" else "赞"
            btnViews[5].setTextColor(if (digged) 0xFFFE2C55.toInt() else 0xFFFFFFFF.toInt())
        }
    }

    // ---------------- 图文/图集作品轮播 ----------------
    /**
     * 显示当前这一张图。
     * ★ 2.36：改用 loadLarge（原图级）—— 旧版走封面用的 480px 采样，
     *   图文作品全屏后等于把缩略图放大 3 倍，满屏都是马赛克（用户反馈的正是这个）。
     */
    private fun showCurrentSlide() {
        if (imageSlides.isEmpty()) return
        val idx = imageSlideIndex.coerceIn(0, imageSlides.lastIndex)
        ImageLoader.loadLarge(imageSlides[idx], imagePostView)
        // 预热下一张：4 秒后切换时不再等网络
        val next = imageSlides[(idx + 1) % imageSlides.size]
        if (imageSlides.size > 1) ImageLoader.prefetchLarge(next)
    }

    /** 启动图文作品：隐藏视频画面、显示图片层、开始轮播 */
    private fun playImagePost(it: DouyinApi.VideoItem) {
        playToken++                 // 让可能还在飞的视频 prepare 回调失效
        imageSlides = it.imageUrls
        imageSlideIndex = 0
        imageSlidePaused = false
        paused = false
        try { currentEngine().pause() } catch (_: Throwable) {}  // 停掉上一条视频
        try { barMask.visibility = View.GONE } catch (_: Throwable) {}
        try { resetVideoGeometry() } catch (_: Throwable) {}
        // ★ 2.38：把视频层整层藏起来 ★
        //   旧实现只 pause() 引擎，Surface 里最后一帧还留在屏幕上；
        //   图文层又只有图片本身不透明（FIT_CENTER 两侧透明），于是
        //   「上一段视频的静止画面铺满屏幕、图文叠在上面」。现在：
        //   ① SurfaceView 设为 INVISIBLE（不销毁 Surface，切回视频不用重建）
        //   ② 图文层自带 #FF000000 黑底（布局里已加），双保险
        try { surface.visibility = View.INVISIBLE } catch (_: Throwable) {}
        try { imagePostView.setBackgroundColor(android.graphics.Color.BLACK) } catch (_: Throwable) {}
        try { clearBufferNotice() } catch (_: Throwable) {}
        imagePostView.visibility = View.VISIBLE
        Log.d(TAG, "图文切层完成：surface=${surface.visibility} 图片层=${imagePostView.visibility}")
        imagePostView.scaleType = android.widget.ImageView.ScaleType.FIT_CENTER
        progressBar.visibility = View.GONE
        ivPause.visibility = View.GONE
        showCurrentSlide()
        updateInfo()
        showControls()
        maybeLoadMore()
        handler.removeCallbacks(imageSlideTick)
        handler.postDelayed(imageSlideTick, 4000L)
        // 进度/时长：图文按「第几张/共几张」显示
        tvPos.text = "1/${imageSlides.size}"
        tvDur.text = "图文"
        try { seekBar.progress = 1000 / maxOf(1, imageSlides.size) } catch (_: Throwable) {}
        Log.d(TAG, "图文作品轮播：${it.awemeId} 共 ${imageSlides.size} 张")
    }

    /** 从图文切回普通视频：停轮播、隐藏图片层、恢复黑边遮罩 */
    private fun stopImagePost() {
        handler.removeCallbacks(imageSlideTick)
        imageSlides = emptyList()
        imageSlideIndex = 0
        imageSlidePaused = false
        try { imagePostView.setImageBitmap(null) } catch (_: Throwable) {}
        imagePostView.visibility = View.GONE
        try { surface.visibility = View.VISIBLE } catch (_: Throwable) {}   // ★ 2.38 恢复视频层
        try { barMask.visibility = View.VISIBLE } catch (_: Throwable) {}
    }

    // ---------------- 播放 ----------------
    /** 该视频的全部可选线路：主链 + 备用链（url_list 里的其余地址） */
    private fun candidateUrls(): List<String> {
        val it = cur() ?: return emptyList()
        val list = mutableListOf<String>()
        if (it.videoUrl.isNotBlank() && it.videoUrl !in list) list.add(it.videoUrl)
        it.altUrls.forEach { u -> if (u.isNotBlank() && u !in list) list.add(u) }
        return list
    }

    private fun playCurrent() {
        // ★ KILLCHASE：切换计数 + 线程 + 调用栈写文件（绕开 ROM logcat 过滤）
        killSwitch++
        // ★ 82.x: 计数只是兜底（真触发是 maybeRestartByMemory 的内存探针）。
        //   播放页用不到任何封面图：每次切视频把图片缓存全清掉，
        //   把内存让给解码器/缓冲（1GB 老电视上这是被系统杀的关键推手之一）。
        try { ImageLoader.trimTo(0) } catch (_: Throwable) {}
        if (playedCount >= maxPlayBeforeRestart && !restarting) {
            Prefs.trace(this, "RESTART_GUARD hit played=$playedCount idx=$currentIndex")
            restartSelf()
            return
        }
        if (DEBUG_KILLCHASE) try {
            val sb = StringBuilder()
            sb.append("switch=").append(killSwitch)
            sb.append(" idx=").append(currentIndex)
            sb.append(" t=").append(SystemClock.elapsedRealtime())
            sb.append(" thread=").append(Thread.currentThread().name)
            sb.append("\n").append(android.util.Log.getStackTraceString(Throwable()).take(1200)).append("\n")
            val f = java.io.File(filesDir, "killchase.txt")
            val prev = if (f.exists()) f.readText() else ""
            val lines = (prev.split("\n") + sb.toString().split("\n")).filter { it.isNotBlank() }.takeLast(399)
            f.writeText(lines.joinToString("\n") + "\n")
        } catch (_: Throwable) {}

        var it = cur() ?: return

        // ★★★ 图文/图集作品：无视频流，走图片轮播，不碰播放器 ★★★
        if (it.isImagePost) {
            hideSwitchCover()
            playImagePost(it)
            return
        }
        // 从图文切回普通视频：收起图片层、停掉轮播
        if (imageSlides.isNotEmpty()) stopImagePost()

        if (holderReady == null) return

        // ★ 按「线路序号」取地址：出错时先换线路，而不是盲目换引擎
        //   （URL 失效时换引擎毫无意义 —— 三个引擎拿同一条死链都放不出来）
        val urls = candidateUrls()
        if (urls.isEmpty()) {
            tvStatus.text = "这条没有可用播放地址"
            return
        }
        val idx = urlAttempt.coerceIn(0, urls.size - 1)
        val rawUrl = urls[idx]

        maybeLoadMore()
        playToken++
        val token = playToken
        // ★ 2.42-fix：切条时不要复位画面几何 ★
        //   旧行为（被用户吐槽的「先放大、再缩小、才换掉」）：
        //     ① resetVideoGeometry() 把容器比例清成「铺满」；
        //     ② 上一条视频的最后一帧还挂在 Surface 上 → 被拉满全屏（放大）；
        //     ③ 新视频尺寸到了再缩回正常比例（缩小）；
        //   修法：只要上一条视频已经出过画面，容器比例就原样保留 —— 旧画面不动、
        //   不变形；等新视频 onVideoSize 到达时（新帧同一时刻接管）一次性改成新比例，
        //   肉眼看不到被拉变形的画面。
        //   （试过用一层纯黑 View 盖住切条瞬间，但在这台 ROM 上 SurfaceView 合成的
        //     视频层压在这层之上，遮罩根本盖不住画面 —— 截图实测切换全程仍是旧画面。）
        if (hasRenderedVideo) {
            try { resetSurfaceScale() } catch (_: Throwable) {}
        } else {
            // 首次起播：Surface 里还没有任何画面，先把容器比例摆好，避免首帧被拉满
            try { resetVideoGeometry() } catch (_: Throwable) {}
            presetAspect(it.isPortrait)
        }
        lastTickPos = 0L   // 新视频的进度基线清零，避免沿用上一条的位置判「已恢复」
        bufferingSince = 0L
        stallSince = 0L
        stuckSince = 0L
        lastStuckPos = -1L
        stuckSwitches = 0
        handler.removeCallbacks(freezeWatch)
        handler.postDelayed(freezeWatch, 2000L)
        val engine = currentEngine()

        // ★ 不再 engine.release() ★
        //   以前这里每次都 release，导致 prepare() 里的「复用实例」分支永远走不到，
        //   等于每次切视频都重建一个播放器：ExoPlayer 的 release 是异步的 →
        //   新旧实例并存 → 连播几十个视频后内存碎片累积 → 被系统杀掉退回首页。
        //   现在改由各引擎的 prepare() 内部复用（Exo stop+换源 / IJK reset+换源）。
        //   真正需要释放的场景是「换引擎」——那里已经显式 release 旧引擎了。
        preparedOnce = false    // 新视频 → 未就绪
        healthyMarked = false    // 换了视频 → 健康计时重新开始
        engine.listener = this
        engine.holder = holderReady

        paused = false          // ★ 切新视频 → 回到播放态
        ivPause.visibility = View.GONE
        progressBar.visibility = View.VISIBLE
        tvPos.text = "00:00"
        tvDur.text = "00:00"
        seekBar.progress = 0
        updateInfo()
        // ★ 这里同时做两件事：显示控制栏 + 重置自动隐藏倒计时。
        //   「翻页不重置计时」就是因为以前缺少这个统一入口。
        showControls()

        Thread {
            val url = urlForEngine(engine, rawUrl)
            if (token != playToken) return@Thread
            handler.post {
                if (token == playToken && !isFinishing) {
                    Log.d(TAG, "[${engine.name}] 线路${idx + 1}/${urls.size} ${url.take(80)}")
                    engine.prepare(url)
                }
            }
        }.start()
    }

    /**
     * 中途冻住看门狗（独立定时器）。
     * 有些视频在 ExoPlayer 上会停在 0.8~1 秒不动：位置不变、也不报错，
     * 换 IJK/系统就正常。真冻住时引擎不再回调进度，所以这里自己每 2 秒
     * 主动读位置；10 秒不动就自动换下一个引擎重播（每条最多换 2 次）。
     */
    private val freezeWatch = object : Runnable {
        override fun run() {
            if (isFinishing) return
            var again = true
            try {
                if (!paused && preparedOnce && engines.isNotEmpty()) {
                    val p = try { currentEngine().position() } catch (_: Throwable) { -1L }
                    val d = try { currentEngine().duration() } catch (_: Throwable) { 0L }
                    val nearEnd = d > 0 && p >= d - 500
                    if (p >= 0 && p == lastStuckPos && p > 300 && !nearEnd) {
                        if (stuckSince == 0L) stuckSince = SystemClock.elapsedRealtime()
                        else if (SystemClock.elapsedRealtime() - stuckSince > 10000L &&
                            stuckSwitches < 2) {
                            stuckSince = 0L
                            stuckSwitches++
                            Log.w(TAG, "播放冻住 ${p}ms → 自动换引擎")
                            again = false
                            switchToNextEngineAndReplay("播放冻住")
                        }
                    } else {
                        stuckSince = 0L
                        lastStuckPos = p
                    }
                }
            } catch (_: Throwable) {}
            if (again && !isFinishing) handler.postDelayed(this, 2000L)
        }
    }

    /** 自动换到下一个引擎并重播当前视频（用户无感的兜底，不改用户记住的引擎） */
    private fun switchToNextEngineAndReplay(reason: String) {
        if (engines.size <= 1) { playCurrent(); return }
        try { engines[engineIndex].release() } catch (_: Throwable) {}
        engineIndex = (engineIndex + 1) % engines.size
        Log.w(TAG, "自动换引擎（$reason）→ ${engines[engineIndex].name}")
        // ★ 不写 Prefs.setEngine ★
        //   这是「自动救场」，不是用户选择；写 setEngine 会覆盖「最近一次成功起播引擎」，
        //   下次冷启动就把用户记忆带偏。用户的显式选择只由 setUserEngine 记。
        //   同时重置线路/重试计数，换引擎后从主线路干净起播，而不是接着上一条死链序号。
        urlAttempt = 0
        retrySameEngine = 0
        playCurrent()
    }

    /** 手动「换线路」统一入口（播放器菜单）：在候选线路里循环。 */
    private fun switchLineManually() {
        val n = candidateUrls().size
        if (n <= 1) flash("这条只有一条线路")
        else {
            urlAttempt = (urlAttempt + 1) % n
            flash("线路 ${urlAttempt + 1}/$n")
            playCurrent()
        }
    }

    private fun isPlayingSafe(): Boolean = try { currentEngine().isPlaying() } catch (_: Throwable) { false }

    /** 作者页续拉用的 cursor（数据源隔离：作者页滑到底继续拉该作者作品） */
    private var authorCursor = 0L

    /** 关注流续拉用的 cursor（首页「关注」频道进来时） */
    private var followCursor = 0L

    private fun maybeLoadMore() {
        if (loadingMore || !hasMore || items.isEmpty()) return
        if (currentIndex < items.size - 2) return

        loadingMore = true
        val cookie = Prefs.getCookie(this)
        Thread {
            val r = try {
                when (feedSource) {
                    // ★ 数据源隔离：作者页滑到底拉【该作者的作品】，绝不混入首页推荐流
                    FeedBus.SOURCE_AUTHOR -> {
                        val sec = items.firstOrNull()?.secUid
                        if (sec.isNullOrBlank()) null
                        else DouyinApi.fetchUserPosts(cookie, sec, authorCursor, 20)
                            .also { if (it != null) authorCursor = it.maxCursor }
                    }
                    FeedBus.SOURCE_FOLLOW -> {
                        DouyinApi.fetchFollowFeed(cookie, followCursor, 12)
                            .also { if (it != null) { followCursor = it.maxCursor; if (it.videos.isEmpty()) hasMore = false } }
                    }
                    else -> DouyinApi.fetchFeed(cookie)
                }
            } catch (e: Exception) { null }
            handler.post {
                loadingMore = false
                if (r == null || r.videos.isEmpty()) { Log.d(TAG, "拉取失败"); return@post }
                val known = items.map { it.awemeId }.toHashSet()
                val fresh = r.videos.filter { it.awemeId !in known }
                if (fresh.isEmpty()) { Log.d(TAG, "本批全重复"); return@post }
                items = items + fresh
                Log.d(TAG, "追加 ${fresh.size} 条 → 共 ${items.size}")
                updateInfo()
                // ★ 81.4: 加载更多后若播放已结束（onComplete 触发的加载）→ 自动续播新内容
                try {
                    if (!isPlayingSafe()) {
                        currentIndex++
                        playCurrent()
                    }
                } catch (_: Throwable) {}
            }
        }.start()
    }

    private fun urlForEngine(engine: VideoEngine, raw: String): String {
        // 系统 MediaPlayer 与 IJK 都走【系统自带 HTTP 栈】——
        // 不经全局宽松 TLS 的 HttpsURLConnection，Android 4.2 的 TLS/CA 太老
        // 直接播 https 会握手失败。统一先解 302 拿新鲜 CDN 地址，再降级 http。
        if (engine !is IjkEngine && engine !is SystemEngine) return raw
        var u = raw
        if (u.contains("/aweme/v1/play/")) u = resolve302(u)
        if (u.startsWith("https://")) u = "http://" + u.substring("https://".length)
        return u
    }

    private fun resolve302(url: String): String = try {
        val req = Request.Builder().url(url)
            .header("User-Agent", UA).header("Referer", REFERER).build()
        // ★ 用宽松 client：电视 CA 库过期时严格 client 握手失败 →
        //   catch 后返回原 URL → IJK 又把它降级成 http → 这条线路直接废掉
        val nr = DouyinApi.relaxedHttpClient().newBuilder()
            .followRedirects(false).followSslRedirects(false).build()
        val resp = nr.newCall(req).execute()
        val code = resp.code(); val loc = resp.header("Location"); resp.close()
        if (code in 300..399 && !loc.isNullOrBlank()) loc else url
    } catch (e: Exception) { url }

    // ---------------- 底栏功能按钮 ----------------
    /** ←→ 在底栏按钮间移动焦点（仅在控件可见时） */
    private fun moveBtnFocus(delta: Int) {
        if (!::btnViews.isInitialized || btnViews.isEmpty()) return
        val n = btnViews.size
        btnFocus = ((btnFocus + delta) % n + n) % n
        btnViews[btnFocus].requestFocus()
        showControls()
        tvStatus.text = ""
    }

    private fun triggerBtn(i: Int) {
        when (i) {
            0 -> togglePlayPause()
            1 -> openAuthor(cur())
            2 -> openTopic(cur())
            3 -> openComments(cur())
            4 -> openSearch()
            5 -> doDigg()      // ★ 点赞直达
            6 -> showMenu()    // 触屏菜单入口
        }
    }

    /**
     * 话题作品 —— 直接走搜索接口。
     *
     * 改前这里是死入口：challenge/aweme 对 X-Bogus 回空 body、challenge/detail
     * 的 rec_list 全 null，所以只弹一句"暂无法打开"。那是搜索还没打通时的降级。
     *
     * 现在搜索已实测可用（登录 Cookie + 干净参数、不带任何签名即可出结果），
     * 点话题就直接拿话题名去搜 —— 话题词本身是强相关关键词，搜出来就是该话题
     * 下的内容，与抖音话题页效果基本一致。
     */
    private fun openTopic(it: DouyinApi.VideoItem?) {
        val name = it?.topics?.firstOrNull()
        if (name.isNullOrBlank()) { flash("这条视频没有话题"); return }
        val i = Intent(this, SearchActivity::class.java)
        i.putExtra("kw", name)
        startActivity(i)
    }

    /**
     * 搜索 —— 直接打开搜索页。
     *
     * 改前这里也是死入口（"搜索接口受风控限制，暂无法使用"），同样是搜索没打通
     * 时的降级文案。现在搜索实测可用，这里只负责跳转。
     */
    private fun openSearch() {
        startActivity(Intent(this, SearchActivity::class.java))
    }

    /** 错误历史 —— 电视没有 logcat，只能这样回看 */
    private fun showErrorHistory() {
        val msg = if (errHistory.isEmpty()) "本轮没有播放错误 ✓"
        else errHistory.joinToString("\n\n")
        try {
            val dlg = AlertDialog.Builder(this)
                .setTitle("最近播放错误（${errHistory.size}）")
                .setMessage(msg)
                .setPositiveButton("知道了", null)
                .setOnDismissListener { showControls() }
                .show()
        } catch (e: Exception) {
            Log.e(TAG, "显示错误历史失败", e)
        }
    }

    // ---------------- 播放器内菜单 ----------------
    private fun showMenu() {
        val it = cur()
        val loopMode = if (Prefs.isLoopOne(this)) "单集循环" else "自动连播"
        val engineName = engines.getOrNull(engineIndex)?.name ?: "无"
        val n = candidateUrls().size
        val landscapeOn = Prefs.isLandscapeOnly(this)
        // ★ 点赞状态回显（本地记忆）——走缓存，不读盘
        val digged = it?.awemeId?.let { isDiggedCached(it) } == true
        val labels = arrayOf(
            "作者主页 · @${it?.author ?: "?"}",
            "查看评论 · ${it?.commentCount ?: 0} 条",
            if (digged) "👍 已赞 · 取消点赞" else "👍 点赞",
            "换线路 · 当前 ${urlAttempt + 1}/${maxOf(1, n)}",
            "切换引擎 · 当前 $engineName",
            "播放模式 · $loopMode",
            "竖屏过滤 · ${if (landscapeOn) "仅横屏" else "全部"}",
            "弹幕 · ${if (Prefs.getDanmaku()) "开" else "关"}（评论区文案）",
            "最近错误（${errHistory.size} 条）",
            "打开设置 / 重新配置 Cookie",
            "画质档位 · 当前 " + Prefs.getQualityGear(),
            "播放倍速 · 当前 " + fmtSpeed(playSpeed)
        )
        showControls()
        try {
            val dlg = AlertDialog.Builder(this)
                .setTitle(it?.desc?.take(30)?.ifBlank { "菜单" } ?: "菜单")
                .setItems(labels) { _, which ->
                    when (which) {
                        0 -> openAuthor(it)
                        1 -> openComments(it)
                        2 -> doDigg()
                        3 -> switchLineManually()
                        4 -> {
                            // 手动换引擎：必须 release 掉旧实例，
                            // 否则它会继续持有 Surface（画面残留/黑块）
                            try { engines[engineIndex].release() } catch (_: Exception) {}
                            engineIndex = (engineIndex + 1) % engines.size
                            Prefs.setUserEngine(this, engineIndex)
                            urlAttempt = 0
                            resetVideoGeometry()
                            flash("引擎：${engines[engineIndex].name}（已记住）")
                            playCurrent()
                        }
                        5 -> {
                            val v = !Prefs.isLoopOne(this)
                            Prefs.setLoopOne(this, v)
                            flash(if (v) "播放模式：单集循环" else "播放模式：自动连播")
                        }
                        6 -> {
                            // ★ 竖屏过滤开关（用户要求的信息流过滤）
                            //   只影响【之后拉取】的视频：已经在列表里的不追溯移除，
                            //   否则用户按一下开关当前视频就消失了，体验很怪。
                            val v = !Prefs.isLandscapeOnly(this)
                            Prefs.setLandscapeOnly(this, v)
                            flash(if (v) "只显示横屏视频（下次加载生效）" else "显示全部视频（下次加载生效）")
                        }
                        7 -> toggleDanmaku()
                        8 -> showErrorHistory()
                        9 -> {
                            startActivity(Intent(this, SettingsActivity::class.java))
                            finish()
                        }
                        10 -> showQualityDialog()
                        11 -> showSpeedDialog()
                    }
                    showControls()
                }
                .setOnCancelListener { showControls() }
                .show()
                // ★ 必须记下引用：dispatchKeyEvent 的「弹层显示中不吞按键」守卫靠它。
                //   原来只有 setOnDismissListener { menuDialog = null }，从未赋过值
                //   → 守卫恒为假 = 死代码（菜单开着时方向键穿透到换视频）。
                menuDialog = dlg
                dlg.setOnDismissListener { menuDialog = null }
        } catch (e: Exception) {
            Log.e(TAG, "菜单失败", e)
        }
    }

    private fun fmtSpeed(s: Float): String =
        if (s == s.toInt().toFloat()) s.toInt().toString() + "×" else s.toString() + "×"

    /** 倍速二级选择：Exo / IJK 即时生效，并记住下次起播沿用 */
    private fun showSpeedDialog() {
        val opts = arrayOf(0.5f, 0.75f, 1.0f, 1.25f, 1.5f, 2.0f)
        val labels = arrayOf("0.5×（慢放）", "0.75×", "1.0×（原速）", "1.25×", "1.5×", "2.0×（快进）")
        try {
            AlertDialog.Builder(this)
                .setTitle("播放倍速 · 当前 " + fmtSpeed(playSpeed))
                .setItems(labels) { _, which ->
                    playSpeed = opts[which]
                    Prefs.setPlaySpeed(this, playSpeed)
                    val e = currentEngine()
                    try { e.setSpeed(playSpeed) } catch (_: Throwable) {}
                    if (e.name != "ExoPlayer" && e.name != "IJKPlayer") {
                        flash("系统播放器不支持倍速，请按菜单键切到 Exo/IJK 引擎")
                    } else {
                        flash("倍速：" + labels[which] + "（已记住）")
                    }
                    showControls()
                }
                .show()
        } catch (e: Exception) {
            Log.e(TAG, "倍速菜单失败", e)
        }
    }

    /** 画质档位二级选择：写 Prefs 并重载当前视频（与「换线路」同交互，立即生效） */
    private fun showQualityDialog() {
        val gears = arrayOf("1080", "720", "480", "auto")
        val labels = arrayOf(
            "1080P（默认 · H.264 硬解）",
            "720P",
            "480P（省流量）",
            "自动（选最高可用档）"
        )
        try {
            AlertDialog.Builder(this)
                .setTitle("画质档位")
                .setItems(labels) { _, which ->
                    Prefs.setQualityGear(gears[which])
                    flash("画质：" + labels[which] + "，重新解析当前视频")
                    val it0 = cur()
                    val vid = it0?.awemeId ?: ""
                    val pos = currentIndex
                    Thread {
                        val d = if (vid.isBlank()) null else try {
                            DouyinApi.fetchVideoDetail(Prefs.getCookie(this), vid)
                        } catch (_: Throwable) { null }
                        handler.post {
                            if (pos == currentIndex && d != null && d.videoUrl.isNotBlank()) {
                                qualityOverride = d
                                overrideForIndex = pos
                                playCurrent()
                            } else {
                                playCurrent()
                            }
                        }
                    }.start()
                }
                .show()
        } catch (e: Exception) {
            Log.e(TAG, "画质菜单失败", e)
        }
    }

    /**
     * 把抖音 status_msg 翻译成可行动的提示：
     * 未登录类 → 大概率浏览器端已被风控踢下线，需重新复制 Cookie；
     * verify/captcha → 触发验证，稍后再试。
     */
    private fun interactErr(msg: String): String {
        val m = msg.lowercase()
        return when {
            m.contains("未登录") || m.contains("login") || m.contains("请登录") || m.contains("need login") ->
                "登录态失效：浏览器端可能已被踢下线，请重新复制完整 Cookie"
            m.contains("verify") || m.contains("captcha") || m.contains("验证") || m.contains("risk") ->
                "触发风控验证：请稍后再试，或重新复制 Cookie"
            else -> msg.take(40)
        }
    }

    // ---------------- 弹幕（评论区文案滚动） ----------------
    private fun toggleDanmaku() {
        val on = !Prefs.getDanmaku()
        Prefs.setDanmaku(this, on)
        flash(if (on) "弹幕：开（读取评论区）" else "弹幕：关")
        applyDanmaku()
    }

    private fun applyDanmaku() {
        val d = danmaku ?: return
        if (!Prefs.getDanmaku()) {
            d.visibility = View.GONE
            d.setRunning(false)
            return
        }
        d.visibility = View.VISIBLE
        // ★ 行数/位置：每次应用都从 Prefs 读一遍，设置页改完立刻生效
        d.setLayoutConfig(Prefs.getDanmakuLines(), Prefs.getDanmakuPos())
        d.setRunning(!paused)
        updateDanmakuBand()
        loadDanmaku()
    }

    /** 后台拉当前视频评论区，喂给弹幕层（30 条封顶） */
    private fun loadDanmaku() {
        if (!Prefs.getDanmaku()) return
        val it = cur() ?: return
        val cookie = Prefs.getCookie(this)
        Thread {
            val texts = try {
                // ★ 只取评论正文：带「昵称：」前缀时，长昵称会把整条弹幕占满，
                //   屏幕上全是人名、看不到内容（实测截图就是这个效果）。
                DouyinApi.fetchComments(cookie, it.awemeId, 0, 30)
                    ?.map { c -> c.text }
                    ?: emptyList()
            } catch (_: Exception) { emptyList() }
            handler.post {
                if (isFinishing || !Prefs.getDanmaku()) return@post
                danmaku?.setContent(texts)
                // ★ 2.34：加载结果为空时必须给反馈 —— 否则用户只看到
                //   「弹幕开着却什么都没有」，分不清是评论为空还是功能坏了。
                if (texts.isEmpty()) {
                    Log.d(TAG, "弹幕：评论区没有可显示文案")
                    flash("弹幕：本视频评论为空")
                } else {
                    Log.d(TAG, "弹幕：已加载 ${texts.size} 条")
                }
            }
        }.start()
    }

    private fun syncDanmakuPause() {
        if (!Prefs.getDanmaku()) return
        try { danmaku?.setRunning(!paused) } catch (_: Throwable) {}
    }

    /**
     * 点赞 / 取消点赞 —— ★ 纯本地 ★
     * 服务器侧 WebProtect 方案太麻烦且易触发风控（实测会踢下线网页端），
     * 用户拍板改成本地记忆：状态只存本机，仅用于按钮回显，不发任何请求。
     */
    private fun doDigg() {
        val v = cur() ?: return
        val it = v
        val now = try { Prefs.toggleDigg(this, it.awemeId) } catch (_: Exception) { false }
        cachedDiggedId = it.awemeId; cachedDigged = now   // 同步缓存，免得 updateInfo 再读盘
        flash(if (now) "已点赞 ✓（本地）" else "已取消点赞（本地）")
        try { updateInfo() } catch (_: Throwable) {}
    }

    private fun openAuthor(it: DouyinApi.VideoItem?) {
        if (it == null || it.secUid.isBlank()) { flash("拿不到作者信息"); return }
        val i = Intent(this, AuthorActivity::class.java)
        i.putExtra("sec_uid", it.secUid)
        i.putExtra("author", it.author)
        startActivity(i)
    }

    /**
     * 打开评论 —— 右侧悬浮小窗（DialogFragment）。
     *
     * 需求明确「跳转离开播放器体验很差」，故从全屏 CommentActivity 改为小窗。
     * DialogFragment 是独立窗口、不触碰宿主生命周期，所以**视频继续播**，
     * 真正实现"边看视频边刷评论"。
     * 面板只占右侧约 1/3 宽：竖屏视频在 16:9 屏上两侧本就是黑边，
     * 面板正落在黑边区，不遮画面主体。
     */
    private fun openComments(it: DouyinApi.VideoItem?) {
        if (it == null) return
        try {
            // 已经开着就不重复弹（连按两次会叠两个窗口）
            if (fragmentManager.findFragmentByTag(TAG_COMMENT) != null) return
            CommentDialogFragment.newInstance(it)
                .show(fragmentManager, TAG_COMMENT)
        } catch (e: Exception) {
            Log.e(TAG, "打开评论失败", e)
            flash("打开评论失败：${e.message}")
        }
    }

    // ---------------- 引擎回调 ----------------
    override fun onPrepared() {
        // ★ 81.4: 同一 playToken 只处理一次（Exo seek/缓冲恢复会重发 STATE_READY → onPrepared 虚涨
        //   虚涨会让 playedCount 提前到上限触发自杀 —— 快进几次就闪断）
        if (lastPreparedToken == playToken) return
        lastPreparedToken = playToken
        // ★ 81.5: 正常起播成功 → 恢复点 retry 归零（熔断只在连续崩时累加）
        try {
            val rj = Prefs.loadResume(this)
            if (rj.isNotBlank()) {
                val ro = org.json.JSONObject(rj)
                if (ro.optInt("retry", 0) > 0) {
                    ro.put("retry", 0)
                    Prefs.saveResume(this, ro.toString())
                }
            }
        } catch (_: Throwable) {}
        // ★ 连播计数：到上限就重建播放页，防止 IJK native 资源堆积被杀
        playedCount++
        // ★ 81.5: 不再挂 1.5s 炸弹（会在下一视频加载途中引爆）→ 改由 playCurrent 开头检查
        // 播放成功 → 清空重试/抖动计数（下次出错仍可先原地重试一次）
        retrySameEngine = 0
        lastErrorAt = 0L
        preparedOnce = true     // ★ 已就绪：Surface 重建时只需重新绑定，不必重播
        // 起播成功 → 清掉这条的「已刷新」标记，之后再失效还能再救一次
        cur()?.awemeId?.let { refreshedIds.remove(it) }
        try { currentEngine().setSpeed(playSpeed) } catch (_: Throwable) {}
        paused = false          // ★ 进入播放态 → 控制栏恢复「到点自动隐藏」
        // ★ 起播成功 = 缓冲结束：Exo 只发「缓冲中」，没有对应的结束回调，
        //   不在这里清掉的话，诊断行会永久挂着「⟳缓冲中」（IJK 有 BUFFERING_END）
        buffering = false
        handler.post {
            progressBar.visibility = View.GONE
            tvStatus.text = ""
            seekBar.progress = 0
            // 弹幕开着 → 新视频自动换弹幕文案
            if (Prefs.getDanmaku()) applyDanmaku()
        }
        Prefs.setEngine(this, engineIndex)
        // ★ 崩溃自愈：每次视频成功起播就落盘恢复点。
        //   native 崩（IJK 释放竞态）→ 进程瞬死 → onDestroy 不执行 → 残留；
        //   冷启动 MainActivity 读到残留 → 直接回到本视频继续播。
        saveResumeState()
        handler.removeCallbacks(progressTick)
        handler.post(progressTick)
        Prefs.trace(this, "PLAYING idx=${currentIndex + 1}/${items.size} engine=${currentEngine().name}")
        snapMem("PLAY#" + playedCount + " idx=" + (currentIndex + 1))
        Log.d(TAG, "✓ [${currentEngine().name}] 播放中")
    }

    /**
     * 根源排查探针：每起播一个视频记一行内存快照（写进 mem_log.txt 与 memTick 同文件）。
     * 连播 6~8 个后拉出文件看曲线：rss/native/fd 哪个在线性上涨、每个视频涨多少，
     * 就能确认「第 5 个必死」到底是哪种资源漏到触顶 —— 而不是笼统归因"固件计数"。
     */
    private fun snapMem(tag: String) {
        try {
            var rss = -1L
            var vmSize = -1L
            try {
                for (ln in java.io.File("/proc/self/status").readLines()) {
                    if (ln.startsWith("VmRSS:")) rss = ln.substring(6).trim().split(" ")[0].toLong() / 1024
                    else if (ln.startsWith("VmSize:")) vmSize = ln.substring(7).trim().split(" ")[0].toLong() / 1024
                }
            } catch (_: Throwable) {}
            val fds = try { java.io.File("/proc/self/fd").list()?.size ?: -1 } catch (_: Throwable) { -1 }
            val nheap = try { android.os.Debug.getNativeHeapAllocatedSize() / 1024 / 1024 } catch (_: Throwable) { -1L }
            val jused = (Runtime.getRuntime().totalMemory() - Runtime.getRuntime().freeMemory()) / 1024 / 1024
            val line = java.text.SimpleDateFormat("HH:mm:ss").format(java.util.Date()) +
                " " + tag + " rss=" + rss + "MB vmsize=" + vmSize + "MB native=" + nheap +
                "MB java=" + jused + "MB fd=" + fds
            java.io.File(filesDir, "mem_log.txt").appendText(line + "\n")
        } catch (_: Throwable) {}
    }

    /** 序列化当前播放位置 + 完整列表 → 落盘（org.json，项目红线禁 Gson/Moshi） */
    private fun saveResumeState() {
        try {
            val arr = org.json.JSONArray()
            for (it in items) {
                val o = org.json.JSONObject()
                o.put("id", it.awemeId)
                o.put("d", it.desc)
                o.put("a", it.author)
                o.put("u", it.videoUrl)
                o.put("c", it.coverUrl)
                o.put("dur", it.duration)
                o.put("sec", it.secUid)
                o.put("digg", it.diggCount)
                o.put("cmt", it.commentCount)
                o.put("pt", it.isPortrait)
                val t = org.json.JSONArray()
                for (x in it.topics) t.put(x)
                o.put("t", t)
                val al = org.json.JSONArray()
                for (x in it.altUrls) al.put(x)
                o.put("alt", al)
                // ★ 图文作品：把图片列表写进恢复点（冷启动恢复也能继续看图集）
                if (it.imageUrls.isNotEmpty()) {
                    val imgs = org.json.JSONArray()
                    for (x in it.imageUrls) imgs.put(x)
                    o.put("imgs", imgs)
                }
                // ★ 作品发布日期（恢复点也保留，冷启动回来仍显示）
                if (it.createTime > 0L) o.put("ct", it.createTime)
                arr.put(o)
            }
            val root = org.json.JSONObject()
            root.put("items", arr)
            root.put("idx", currentIndex)
            root.put("src", feedSource)
            root.put("ts", System.currentTimeMillis())
            root.put("retry", 0)   // 崩溃重启后 MainActivity 会 +1
            Prefs.saveResume(this, root.toString())
        } catch (_: Exception) {}
    }

    /** 低内存（onTrimMemory）→ 暂停+GC 给内存喘息（B站动态缓冲的App层等价） */
    override fun onLowMemory() {
        Log.w(TAG, "低内存回调 → 暂停+GC 防杀")
        try {
            if (currentIndex < 0) return
            val eng = currentEngine()
            val wasPlaying = !paused
            eng.pause()
            System.gc()
            handler.postDelayed({
                if (!isFinishing && wasPlaying && !paused) {
                    try { currentEngine().start() } catch (_: Throwable) {}
                }
            }, 800)
        } catch (_: Throwable) {}
    }

    override fun onError(msg: String) {
        Log.e(TAG, "✗ [${currentEngine().name}] $msg")
        handler.post {
            progressBar.visibility = View.GONE
            noteError(msg)

            // ★ 不自动做任何动作 ★
            //   用户明确要求：换引擎、换线路都手动决定。
            //   之前自动换引擎会在三个引擎间来回刷屏；自动换线路也会在
            //   直链过期时反复重试 → 表现为「没一会就提示一下」。
            //   现在只把原因显示出来，让用户自己按菜单处理。
            tvStatus.text = "播放失败：${shortMsg(msg)}　→ 菜单可换线路 / 换引擎"
            paused = true       // ★ 出错等同暂停态：控制栏留在屏幕上，方便立刻换线路/换引擎
            showControls()
            syncDanmakuPause()
        }
    }

    /** 把刷新到的新地址写进当前条目（保留原时间戳） */
    private fun adoptFreshUrl(old: DouyinApi.VideoItem, fresh: DouyinApi.VideoItem) {
        val merged = fresh.copy(ts = old.ts)
        val list = items.toMutableList()
        if (currentIndex in list.indices) list[currentIndex] = merged
        items = list
    }

    /** 直链过期：按 awemeId 重新解析真实播放地址，替换后重播（https 直链 403/过期时用） */
    private fun refreshUrlAndReplay(old: DouyinApi.VideoItem) {
        val cookie = Prefs.getCookie(this)
        Thread {
            val fresh = try {
                DouyinApi.refreshVideoUrl(cookie, old)
            } catch (e: Exception) {
                Log.e(TAG, "刷新地址异常: ${e.message}"); null
            }
            handler.post {
                if (isFinishing) return@post
                // 图文作品刷新后 videoUrl 为空但 imageUrls 非空，也视为有效
                if (fresh == null ||
                    (fresh.videoUrl.isBlank() && fresh.imageUrls.isEmpty())) {
                    tvStatus.text = "加载失败：${DouyinApi.lastError}　→ 可换线路试试"
                    paused = true
                    showControls()
                    return@post
                }
                adoptFreshUrl(old, fresh)
                urlAttempt = 0
                retrySameEngine = 0
                playCurrent()
            }
        }.start()
    }

    private fun noteError(msg: String) {
        errHistory.add(0, "${currentEngine().name}: ${shortMsg(msg)}")
        while (errHistory.size > 6) errHistory.removeAt(errHistory.size - 1)
    }

    private fun shortMsg(m: String): String = m.replace("\n", " ").replace("\r", " ").take(42)

    override fun onComplete() {
        // 单集循环：重播当前；否则自动连播下一条
        if (Prefs.isLoopOne(this)) {
            Log.d(TAG, "单集循环 → 重播")
            playCurrent()
            return
        }
        if (currentIndex < items.size - 1) {
            currentIndex++
            playCurrent()
        } else {
            // ★ 修复：hasMore=false 时 maybeLoadMore 直接 return，别再谎报"正在加载更多…"
            if (!hasMore) {
                flash("已到最后一条")
                return
            }
            maybeLoadMore()
            flash("正在加载更多…")
        }
    }

    override fun onInfo(msg: String) {
        if (msg.contains("缓冲")) {
            val wasBuffering = buffering
            buffering = true
            if (!wasBuffering) bufferingSince = SystemClock.elapsedRealtime()
            // ★ 不上屏就挂提示：IJK 在网络水位一抖就发 BUFFERING_START 却不发 END，
            //   提示会像焊在屏幕上一样一直闪。这里延迟 1.2 秒确认：
            //   真卡住（位置不动）才显示；短抖动直接忽略，位置一动就清掉。
            handler.removeCallbacks(bufferNoticeTask)
            handler.postDelayed(bufferNoticeTask, 1200L)
            return
        }
        buffering = false
        bufferingSince = 0L
        posAdvancing = false
        handler.removeCallbacks(bufferNoticeTask)
        handler.post { if (msg.isNotEmpty()) tvStatus.text = msg }
    }

    /** 延迟上屏的「缓冲中...」：只在真的卡住时打扰用户 */
    private val bufferNoticeTask = Runnable {
        if (buffering && !posAdvancing) {
            tvStatus.text = "缓冲中..."
        }
    }

    /** 清掉「缓冲中」状态与提示（恢复播放 / 暂停 / 换引擎后都可能残留） */
    private fun clearBufferNotice() {
        buffering = false
        bufferingSince = 0L
        posAdvancing = false
        handler.removeCallbacks(bufferNoticeTask)
        if (tvStatus.text?.contains("缓冲") == true) tvStatus.text = ""
    }

    /** 换引擎时必须清零画面几何：上一种引擎的涂层缩放 / 容器比例会残留 */
    private fun resetVideoGeometry() {
        try { hideSwitchCover() } catch (_: Throwable) {}
        try { resetSurfaceScale() } catch (_: Throwable) {}
        try { resetAspect() } catch (_: Throwable) {}
        lastVw = 0
        lastVh = 0
        aspectAppliedToken = 0
        hasRenderedVideo = false
    }

    // ---------------- 画面比例（回到 v11 做法） ----------------
    //   ★ 不碰布局、不碰容器尺寸 ★
    //   SurfaceView 铺满全屏，由解码器按 SCALE_TO_FIT 原生 letterbox。
    //   两种历史做法都踩过雷：
    //     ① 运行时改 SurfaceView.layoutParams → Surface 尺寸变化 → 回调链
    //     ② AspectFrame 容器按比例约束 → 同样触发 Surface 尺寸变化
    //   在 Mali450 上两者的表现都是「画面黑方块 + 卡顿」，故全部移除。
    override fun onVideoSize(w: Int, h: Int) {
        if (w <= 0 || h <= 0) return
        lastVw = w
        lastVh = h
        // ★ 出帧信号：黑屏看门狗靠它区分「黑屏」与「正常在播」
        firstFrameForToken = playToken
        val ratio = w.toFloat() / h.toFloat()
        Log.d(TAG, "videoSize ${w}x$h → ratio=$ratio")

        // ★ 不再按「每条只应用一次」去防抖 ★
        //   以前用 playToken 做一次性闸门，但切视频时上一条的 onVideoSize 可能迟到，
        //   把新 token 的额度用掉 → 新视频真正的尺寸不再应用，画面沿用上一条的比例：
        //   横屏视频被塞进竖屏窄条（拉长）、竖屏视频被拉满全屏（变宽），都是这个原因。
        //   现在每次回调都按当前真实尺寸重算；AspectFrame 自身对相同比例是 no-op，
        //   图层缩放重复赋值也无副作用，所以不会引起 Surface 反复重建。
        // ★ 横竖切换的「先放大再缩回」就发生在这几帧：新视频的第一帧被画进
        //   「还没改尺寸」的旧容器（9:16 竖屏塞进 16:9 容器 = 拉满一屏），
        //   等 Surface 变成新比例才恢复正常。
        //   这里在新尺寸已知的这一刻盖一层黑，等 surfaceChanged 报出新比例再撤；
        //   比例没变（横切横 / 竖切竖）就不盖，避免无谓的一下黑。
        val curFrameRatio = try { aspectFrame.aspectRatio } catch (_: Throwable) { 0f }
        if (hasRenderedVideo && curFrameRatio > 0f && Math.abs(curFrameRatio - ratio) > 0.02f) {
            pendingCoverRatio = ratio
            showSwitchCover()
        }
        applyVideoGeometry(w, h)
        handler.post {
            tvHint.text = "视频 ${w}×${h}　比例 ${"%.2f".format(ratio)}"
        }
        hasRenderedVideo = true
    }

    /** 按真实视频尺寸设置容器比例 / 图层缩放（可重复调用，幂等） */
    private fun applyVideoGeometry(w: Int, h: Int) {
        if (w <= 0 || h <= 0) return
        val ratio = w.toFloat() / h.toFloat()
        val eng = currentEngine()
        try { eng.setVideoAspect(w, h) } catch (_: Throwable) {}
        // 弹幕只能在真实画面里飞：几何一变就重算边界（缩放在 post 里生效，故延后一拍）
        handler.postDelayed({ updateDanmakuBand() }, 350L)
        if (eng is ExoEngine) {
            // ★ 回到用户认可的 AspectFrame 约束方式 ★
            //   Exo 的 setVideoScalingMode(SCALE_TO_FIT) 只对 TextureView 生效，
            //   对本项目用的 SurfaceView 无效 —— 上一版去掉容器约束后画面被拉伸变形。
            //   必须用 AspectFrame 按视频比例约束 SurfaceView 尺寸来等比显示。
            // ★ 2.42-fix：同步应用比例（不等下一轮 post）
            //   切条时容器还保持上一条的比例，这里在「新视频尺寸已知」的同一时刻切过去，
            //   新视频第一帧就落在正确的容器里，不会出现「先放大再缩小」的中间态。
            try { applyAspect(ratio) } catch (_: Throwable) {}
            handler.post { applyAspect(ratio) }
            handler.post { resetSurfaceScale() }
        } else if (eng.stretchesToFillSurface()) {
            // IJK / 系统播放器：不碰 Surface 尺寸（会触发整段重播），只缩放图层
            handler.post { resetAspect() }
            handler.post { applySurfaceScale(ratio) }
        } else {
            // VLC 自带等比显示 → 容器铺满，两层缩放都复位，避免和 VLC 自身 aspect 叠加
            handler.post { resetAspect() }
            handler.post { resetSurfaceScale() }
        }
    }

    /** 定时自检：容器 / 图层比例与已知视频尺寸不符就重新应用（治「有时变形」） */
    private fun resyncGeometryIfNeeded() {
        if (!::surface.isInitialized || lastVw <= 0 || lastVh <= 0) return
        val ratio = lastVw.toFloat() / lastVh.toFloat()
        val eng = try { currentEngine() } catch (_: Throwable) { return }
        val sw = surface.width
        val sh = surface.height
        if (sw <= 0 || sh <= 0) return
        try {
            // 极端比例（1:20 长图之类）本就放弃等比缩放 → 直接跳过，
            // 否则每秒「自愈」一次 = 每秒重置一次缩放 + 刷一条日志
            if (eng.stretchesToFillSurface()) {
                val vr = sw.toFloat() / sh.toFloat()
                val tx = if (ratio < vr) ratio / vr else 1f
                val ty = if (ratio > vr) vr / ratio else 1f
                if (isUnusableScale(sw, sh, tx, ty)) return
            }
            if (eng is ExoEngine) {
                // 容器比例不对，或容器比例对但 Surface 实际尺寸没跟上（布局残留）
                val frameRatio = aspectFrame.aspectRatio
                val surfRatio = sw.toFloat() / sh.toFloat()
                if (Math.abs(frameRatio - ratio) > 0.01f ||
                    (frameRatio > 0f && Math.abs(surfRatio - ratio) > 0.02f)
                ) {
                    Log.d(TAG, "比例自愈 Exo frame=$frameRatio surf=${sw}x${sh} video=$ratio")
                    applyAspect(ratio)
                    aspectFrame.requestLayout()
                }
            } else if (eng.stretchesToFillSurface()) {
                // 图层缩放值 + Surface 尺寸反推的实际显示比要和视频比一致
                val dispRatio = (sw * surface.scaleX) / (sh * surface.scaleY)
                if (Math.abs(dispRatio - ratio) > 0.02f) {
                    Log.d(TAG, "比例自愈 ${eng.name} disp=$dispRatio video=$ratio")
                    resetAspect()
                    applySurfaceScale(ratio)
                }
            }
        } catch (_: Throwable) {}
        updateDanmakuBand()
    }

    /**
     * 把「真实画面的左右边界」告诉弹幕层。
     * 竖屏视频在 16:9 屏上左右各有大黑边，弹幕跑到黑边上非常出戏；
     * 这里用 SurfaceView 的布局位置 + 图层缩放反推出实际显示矩形。
     * （每一秒自检会调一次，值不变时 DanmakuView 内部直接 return，零开销）
     */
    private fun updateDanmakuBand() {
        val dm = danmaku ?: return
        if (!::surface.isInitialized) return
        try {
            val vw = surface.width
            val vh = surface.height
            if (vw <= 0 || vh <= 0 || dm.width <= 0) return
            // 图层缩放是围绕中心缩放的 → 实际显示宽度 = 布局宽 × scaleX
            val dw = vw * surface.scaleX
            val cx = (aspectFrame.left + surface.left) + vw / 2f
            val left = (cx - dw / 2f).coerceIn(0f, dm.width.toFloat())
            val right = (cx + dw / 2f).coerceIn(0f, dm.width.toFloat())
            if (right - left < 80f) return      // 极端细条（1:20 之类）不退让，直接用整屏
            dm.setBand(left, right)
        } catch (_: Throwable) {}
    }

    /**
     * ★ 2.42 起播预置比例：只对 Exo 生效（Exo 靠 AspectFrame 约束容器）。
     * IJK / VLC 走的是「容器铺满 + 图层缩放」，预置容器比例反而会和它们的
     * 缩放叠加，所以那两条线路仍保持「先复位、尺寸到了再缩放」的老逻辑。
     */
    private fun presetAspect(portrait: Boolean) {
        if (!::aspectFrame.isInitialized) return
        val eng = try { currentEngine() } catch (_: Throwable) { return }
        if (eng !is ExoEngine) return
        val guess = if (portrait) 9f / 16f else 16f / 9f
        aspectFrame.aspectRatio = guess
        Log.d(TAG, "起播预置比例 " + "%.4f".format(guess) + "（竖屏=" + portrait + "）")
    }

    /** 按比例约束画面容器；比例恒定则不动（避免 Surface 反复重建） */
    private fun applyAspect(ratio: Float) {
        if (!::aspectFrame.isInitialized) return
        if (ratio <= 0f || ratio.isNaN()) return
        if (Math.abs(aspectFrame.aspectRatio - ratio) >= 1e-3f) {
            Log.d(TAG, "容器比例 → " + "%.4f".format(ratio))
        }
        aspectFrame.aspectRatio = ratio
    }

    /** 容器回到「铺满」（非 Exo 引擎用，配合图层缩放） */
    private fun resetAspect() {
        if (!::aspectFrame.isInitialized) return
        if (aspectFrame.aspectRatio != 0f) Log.d(TAG, "容器比例复位为铺满")
        aspectFrame.aspectRatio = 0f
    }

    /** ★ 横竖切换过渡遮罩：盖住「新画面被旧容器拉变形」的那几帧 */
    private fun showSwitchCover() {
        if (!::switchCover.isInitialized) return
        if (switchCover.visibility == View.VISIBLE) return
        switchCover.visibility = View.VISIBLE
        Log.d(TAG, "切换遮罩盖上（目标比例 " + "%.4f".format(pendingCoverRatio) + "）")
        handler.removeCallbacks(coverFallback)
        handler.postDelayed(coverFallback, 1500L)
    }

    private fun hideSwitchCover() {
        handler.removeCallbacks(coverFallback)
        pendingCoverRatio = 0f
        if (!::switchCover.isInitialized) return
        if (switchCover.visibility != View.GONE) {
            switchCover.visibility = View.GONE
            Log.d(TAG, "切换遮罩撤除")
        }
    }

    /** 兜底：新比例一直等不到（极端比例 / Surface 不重建）也别一直黑着 */
    private val coverFallback = Runnable {
        if (pendingCoverRatio != 0f) {
            Log.d(TAG, "切换遮罩兜底撤除（等不到新尺寸）")
            hideSwitchCover()
        }
    }

    /** 清掉图层缩放（切回 Exo / 退出播放时） */
    private fun resetSurfaceScale() {
        if (!::surface.isInitialized) return
        try {
            surface.scaleX = 1f
            surface.scaleY = 1f
        } catch (_: Throwable) {}
        if (::barMask.isInitialized) barMask.clear()
    }

    /**
     * 等比缩放后是否「小到看不清」。
     *
     * 只有 1:20 长图这类比例才会触发：等比后宽度只剩几十像素，等于全黑。
     * 9:16 竖屏在 16:9 屏上等比后仍有 607×1080，绝不应当放弃等比。
     */
    private fun isUnusableScale(viewW: Int, viewH: Int, sx: Float, sy: Float): Boolean {
        if (viewW <= 0 || viewH <= 0) return false
        return viewW * sx < MIN_VISIBLE_PX || viewH * sy < MIN_VISIBLE_PX
    }

    /**
     * 非 Exo 引擎的「比例矫正」——只缩放图层，不动 Surface 尺寸。
     *
     * 背景：IJK 把解码画面硬拉到 Surface 的尺寸（1080×1920 的竖屏在 1920×1080 的
     * Surface 上会被横向拉宽 3.16 倍），于是人物变形。这里反算一个缩放系数：
     *   · 视频比屏幕「窄」（竖屏）：横向压缩 scaleX = 视频比 / 屏幕比
     *   · 视频比屏幕「宽」：纵向压缩 scaleY = 屏幕比 / 视频比
     * 以中心为轴，画面保持比例居中，两侧/上下自然留黑边。
     */
    private fun applySurfaceScale(videoRatio: Float) {
        if (!::surface.isInitialized) return
        if (videoRatio <= 0f || videoRatio.isNaN()) return
        surface.post {
            try {
                val vw = surface.width
                val vh = surface.height
                if (vw <= 0 || vh <= 0) return@post
                val viewRatio = vw.toFloat() / vh.toFloat()
                var sx = 1f
                var sy = 1f
                if (videoRatio < viewRatio) {
                    sx = videoRatio / viewRatio
                } else if (videoRatio > viewRatio) {
                    sy = viewRatio / videoRatio
                }
                // ★「有声音无画面」加固（v2 修正）★
                //   宽高比极端（如 1:20 长图）等比缩放后只剩一条几十像素的细线，
                //   肉眼几乎全黑（音轨照常）—— 只有这种情况才放弃等比。
                //   ★ 旧实现用「缩放系数 < 0.5」当判据是错的 ★
                //     9:16 竖屏在 16:9 屏上 sx = 0.5625/1.7778 = 0.316，必然触发，
                //     → 竖屏视频全被拉满全屏（横向拉宽 3.16 倍）。
                //     这就是「IJK / 系统播放器下竖屏视频变形」的真正原因。
                if (isUnusableScale(vw, vh, sx, sy)) {
                    surface.scaleX = 1f
                    surface.scaleY = 1f
                    if (::barMask.isInitialized) barMask.clear()
                    Log.w(TAG, "跳过极端比例缩放 video=$videoRatio view=$viewRatio")
                    return@post
                }
                surface.pivotX = vw / 2f
                surface.pivotY = vh / 2f
                surface.scaleX = sx
                surface.scaleY = sy
                // 内容区域 = 缩放后的实际显示矩形；四周交给遮罩涂黑
                if (::barMask.isInitialized) {
                    val dispW = vw * sx
                    val dispH = vh * sy
                    val left = (vw - dispW) / 2f
                    val top = (vh - dispH) / 2f
                    barMask.setContentRect(left, top, left + dispW, top + dispH)
                }
                Log.d(TAG, "图层缩放 sx=$sx sy=$sy (video=$videoRatio view=$viewRatio)")
            } catch (e: Throwable) {
                Log.w(TAG, "图层缩放失败: ${e.message}")
            }
        }
    }

    // 说明：旧版的「比例诊断横幅」与 AspectFrame 一并移除 ——
    // 它们是黑方块问题的来源，v11 里都没有。

    // ---------------- Surface ----------------
    override fun surfaceCreated(h: SurfaceHolder) {
        holderReady = h
        val e = currentEngine()
        if (preparedOnce && e is ExoEngine) {
            // ★ Surface 因比例容器调整尺寸而重建 ★
            //   ExoEngine 用 setVideoSurfaceHolder 托管 Surface，重新绑定即恢复画面；
            //   不重新 prepare → 视频不会从头重播、不闪。
            Log.d(TAG, "Surface 重建 → 仅重新绑定（不重播）")
            e.holder = h
        } else {
            playCurrent()
        }
    }

    override fun surfaceChanged(h: SurfaceHolder, f: Int, w: Int, h2: Int) {
        Log.d(TAG, "surfaceChanged ${w}x$h2")
        // ★ 横竖切换：Surface 真的变成新比例了 → 再等一拍（让首帧画上去）撤遮罩
        if (pendingCoverRatio > 0f && w > 0 && h2 > 0) {
            val sr = w.toFloat() / h2.toFloat()
            if (Math.abs(sr - pendingCoverRatio) < 0.02f) {
                pendingCoverRatio = 0f
                handler.postDelayed({ hideSwitchCover() }, 80L)
            }
        }
    }

    override fun surfaceDestroyed(h: SurfaceHolder) {
        playToken++
        val e = currentEngine()
        // ★ ExoEngine 用 setVideoSurfaceHolder 托管 Surface：销毁时它自行解绑，
        //   这里不能 release —— 否则比例容器调整尺寸时会整段重播。
        //   IJK / 系统播放器不托管，销毁时必须释放，否则持有无效 Surface 会崩。
        if (e !is ExoEngine) {
            try { e.release() } catch (_: Exception) {}
        }
        holderReady = null
        // ★ 清掉上一帧引用，防止 surface 被销毁后仍有残留画面
        lastVw = 0
        lastVh = 0
    }

    // ---------------- 共用操作 ----------------
    /** IJK 引擎连刷节流（Exo/系统引擎不限制） */
    private var lastIJKSwitchAt = 0L
    private fun ijkSwitchAllowed(): Boolean {
        // 图文作品不涉及解码器切换，跳过 IJK 节流，翻看图集更跟手
        if (imageSlides.isNotEmpty() || cur()?.isImagePost == true) return true
        if (currentEngine().name != "IJKPlayer") return true
        val now = SystemClock.uptimeMillis()
        if (now - lastIJKSwitchAt < IJK_SWITCH_GUARD_MS) {
            flash("连刷太快，等一下…")
            return false
        }
        lastIJKSwitchAt = now
        return true
    }

    private fun nextVideo() {
        if (!ijkSwitchAllowed()) return
        if (currentIndex < items.size - 1) {
            currentIndex++; urlAttempt = 0; retrySameEngine = 0; playCurrent()
        } else if (hasMore) {
            maybeLoadMore(); flash("加载更多…")
        } else flash("已是最后一条")
    }

    private fun prevVideo() {
        if (!ijkSwitchAllowed()) return
        if (currentIndex > 0) {
            currentIndex--; urlAttempt = 0; retrySameEngine = 0; playCurrent()
        } else flash("已是第一条")
    }

    private fun togglePlayPause() {
        // ★ 图文作品：暂停/继续只控制轮播定时器，与视频引擎无关
        if (imageSlides.isNotEmpty()) {
            imageSlidePaused = !imageSlidePaused
            paused = imageSlidePaused
            if (!imageSlidePaused) handler.postDelayed(imageSlideTick, 4000L)
            else handler.removeCallbacks(imageSlideTick)
            ivPause.text = if (imageSlidePaused) "❚❚" else "▶"
            ivPause.visibility = if (imageSlidePaused) View.VISIBLE else View.GONE
            showControls()
            return
        }
        val e = currentEngine()
        if (e.isPlaying()) {
            e.pause()
            paused = true                 // ★ 暂停 → 控制栏保持可见（不自动隐藏）
            // ★ 暂停后停留的「缓冲中...」会一直挂在画面上（IJK 不会再发 BUFFERING_END），
            //   暂停期间也不存在"还在缓冲"这回事，直接清掉提示。
            clearBufferNotice()
            ivPause.text = "❚❚"
            ivPause.visibility = View.VISIBLE
        } else {
            // ★ 上一次是「加载失败」→ 这次按 OK 当作重试：重新取地址再播 ★
            val status = tvStatus.text?.toString() ?: ""
            e.start()
            paused = false                // ★ 播放 → 控制栏到点自动隐藏
            ivPause.text = "▶"
            handler.postDelayed({ ivPause.visibility = View.GONE }, 450)
        }
        showControls()
        syncDanmakuPause()
    }

    private fun doSeek(deltaMs: Long) {
        val e = currentEngine()
        val dur = e.duration().coerceAtLeast(1L)
        val p = (e.position() + deltaMs).coerceIn(0L, dur)
        e.seekTo(p)
        flash("${fmt(p)} / ${fmt(dur)}")
        showControls()
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        return gestureDetector.onTouchEvent(event) || super.onTouchEvent(event)
    }

    // ---------------- 遥控器 ----------------
    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        if (event.action == KeyEvent.ACTION_DOWN) {
            if (menuDialog?.isShowing == true) return super.dispatchKeyEvent(event)
            if (event.keyCode == KeyEvent.KEYCODE_VOLUME_UP ||
                event.keyCode == KeyEvent.KEYCODE_VOLUME_DOWN ||
                event.keyCode == KeyEvent.KEYCODE_VOLUME_MUTE) {
                showControls()
                return super.dispatchKeyEvent(event)   // 音量交给系统
            }
            if (handleTvKey(event.keyCode)) return true
        }
        return super.dispatchKeyEvent(event)
    }

    private fun handleTvKey(keyCode: Int): Boolean {
        when (keyCode) {
            // ★ 暂停态下方向键不许划走视频 ★
            //   用户反馈的原症状：暂停后随手按上下 → 视频被划走、还得退回来找。
            //   旧逻辑这两条是无条件 prev/next，暂停态没有任何拦截（2.31 说明里的
            //   「暂停后按方向键划走」并没有真正落到这里）。暂停时方向键只留在
            //   控制栏内，恢复播放（中点/播放键）后上下键才恢复切视频。
            KeyEvent.KEYCODE_DPAD_UP, KeyEvent.KEYCODE_CHANNEL_UP -> {
                if (paused) { showControls(); return true }
                prevVideo(); return true
            }
            KeyEvent.KEYCODE_DPAD_DOWN, KeyEvent.KEYCODE_CHANNEL_DOWN -> {
                if (paused) { showControls(); return true }
                nextVideo(); return true
            }
            // ★ 2.38：←→ 就是快退/快进，不再被控制栏抢走 ★
            //   旧实现只要控制栏可见，←→ 全用来在底栏按钮间挪焦点 ——
            //   按任意键控制栏就出来，所以「永远拖不动进度」。
            //   现在只有焦点真的落在按钮上（用户自己按 ↑ 进按钮行）才移焦点；
            //   图文作品则把 ←→ 当成上一张/下一张。
            KeyEvent.KEYCODE_DPAD_LEFT -> {
                if (imageSlides.isNotEmpty()) { stepImageSlide(-1); return true }
                doSeek(-SEEK_STEP); return true
            }
            KeyEvent.KEYCODE_DPAD_RIGHT -> {
                if (imageSlides.isNotEmpty()) { stepImageSlide(1); return true }
                doSeek(SEEK_STEP); return true
            }
            KeyEvent.KEYCODE_DPAD_CENTER, KeyEvent.KEYCODE_ENTER,
            KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE -> {
                // 焦点已在按钮上 → 交回系统派发 click（避免双重触发）
                if (::btnViews.isInitialized && btnViews.contains(currentFocus)) return false
                togglePlayPause(); return true
            }
            KeyEvent.KEYCODE_MEDIA_NEXT -> { nextVideo(); return true }
            KeyEvent.KEYCODE_MEDIA_PREVIOUS -> { prevVideo(); return true }
            KeyEvent.KEYCODE_MENU, KeyEvent.KEYCODE_INFO, KeyEvent.KEYCODE_SETTINGS -> {
                showMenu(); return true              // ★ 改成弹层菜单，不再跳出
            }
            KeyEvent.KEYCODE_BACK -> { finish(); return true }
        }
        return false
    }

    override fun onResume() {
        super.onResume()
        // 诊断采样：每 2 秒刷新一次播放状态（onPause 里停掉）
        handler.postDelayed(perfTick, 1500)
    }

    override fun onPause() {
        handler.removeCallbacks(perfTick)
        super.onPause()
        try { currentEngine().pause() } catch (_: Exception) {}
        paused = true           // ★ 离开前台 → 暂停态，控制栏保持可见
        showControls()
        syncDanmakuPause()      // 离开前台 → 弹幕停（省电，回来恢复）
    }

    override fun onDestroy() {
        try { (application as App).activePlayer = null } catch (_: Throwable) {}
        try { danmaku?.setRunning(false) } catch (_: Throwable) {}
        try { stopService(Intent(this, PlayService::class.java)) } catch (_: Throwable) {}
        try {
            val am = getSystemService(AUDIO_SERVICE) as android.media.AudioManager
            am.abandonAudioFocus(null)
        } catch (_: Throwable) {}
        // ★ 正常退出清恢复点（崩溃时 onDestroy 不执行 → 恢复点残留 → 冷启动恢复）
        try { Prefs.clearResume(this) } catch (_: Exception) {}
        handler.removeCallbacks(perfTick)
        handler.removeCallbacks(imageSlideTick)
        playToken++
        // 停止进度条更新
        handler.removeCallbacks(progressTick)
        // 清除所有待处理的回调
        handler.removeCallbacksAndMessages(null)
        // 释放播放器资源
        engines.forEach { try { it.release() } catch (_: Exception) {} }
        try { IjkMediaPlayer.native_profileEnd() } catch (_: Exception) {}
        // ★ 释放内存总线引用 —— 不清理的话整条列表（每条约 1KB 字符串）
        //   会随进程一直存活，长刷视频时累积可观。
        //   ★ 例外：自我重建时不能清，否则新实例拿不到列表、直接无片可播。
        if (!restarting) FeedBus.clear()
        // ★ 关闭复用线程池（正常退出；自我重建走 killProcess 无所谓）
        try { bgExec.shutdownNow() } catch (_: Throwable) {}
        super.onDestroy()
    }
}
