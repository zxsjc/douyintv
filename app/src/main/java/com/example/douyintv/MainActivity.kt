package com.example.douyintv

import android.app.Activity
import android.app.AlertDialog
import android.content.Intent
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import androidx.recyclerview.widget.GridLayoutManager
import androidx.recyclerview.widget.RecyclerView
import android.util.Log
import android.view.KeyEvent
import android.view.View
import android.view.WindowManager
import android.widget.ProgressBar
import android.widget.TextView
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * 首页 —— 左侧垂直导航 + 右侧视频网格（参照抖音 PC 网页版）
 *
 * 与旧版的根本区别：
 *   旧版 MainActivity 是个「路由」：拉完 feed 就直接跳播放器，没有列表这一层。
 *   现在它才是真正的首页：网格浏览 → 选中某条 → 才进 [PlayerActivity]。
 *
 * TV 硬性规则落地清单：
 *   ① 所有导航项 / 卡片 focusable=true + focusableInTouchMode=true
 *   ② 焦点态用 @drawable/focus_nav_item · @drawable/focus_card（描边 + 提亮）
 *   ③ 右侧列表 = RecyclerView（替换掉原来的 ScrollView），
 *      并开启 descendantFocusability / focusable，配 GridLayoutManager
 *   ④ onCreate 末尾显式 requestFocus 到"推荐"；切分类后焦点回网格首位
 *   6  加载中 / 加载失败 / 空数据三种占位都走同一块 placeholder
 *
 * 焦点流设计（D-Pad 只有上下左右 + 确定 + 返回）：
 *   侧边栏 ←→ 网格          用 nextFocusRight / nextFocusLeft 显式打通
 *   网格内部                交给 GridLayoutManager 自动找焦点
 *   返回键                  优先退出占位态，其次才 finish
 */
class MainActivity : Activity() {

    private companion object {
        const val TAG = "Main"

        /** 网格列数：1920 宽屏下 260dp 卡片约可放 6 列，取 4 更宽松好按 */
        // 列数按屏宽自适应（TV 1280dp → 5 列；手机竖屏 ~411dp → 2 列）
    private var spanCount = 5

        const val NAV_RECOMMEND = 0

        /**
         * 恢复点有效期 —— 从「最后一次证明还在播的时刻」起算（ts 与心跳取较晚者）。
         * 5 分钟足以覆盖"看完一个短视频、再往下滑、进程被杀"的整个窗口。
         */
        const val RESUME_WINDOW_MS = 5 * 60_000L
    }

    // ---- 侧边栏（★ 一步直达：内容在上、个人在下、登录沉底）----
    // 图标居中修正：布局里已改为 FrameLayout + ImageView，这里用 View 承接，
    // 不能再强转 TextView —— 否则新布局一加载就 ClassCastException。
    private lateinit var navRecommend: View
    private lateinit var navFollow: View
    private lateinit var navSearch: View
    private lateinit var navSettings: View
    private lateinit var navLogin: View

    // ---- 内容区 ----
    private lateinit var tvSectionTitle: TextView
    private lateinit var tvCount: TextView
    private lateinit var tvHint: TextView
    private lateinit var gridView: RecyclerView
    private lateinit var layoutManager: GridLayoutManager
    private lateinit var adapter: VideoGridAdapter
    // 改版：焦点信息栏（卡片纯封面后，标题/作者在此显示）
    private lateinit var tvInfoTitle: TextView
    private lateinit var tvInfoMeta: TextView
    private lateinit var infobar: android.view.View

    // ---- 占位区 ----
    private lateinit var placeholder: View
    private lateinit var pbLoading: ProgressBar
    private lateinit var tvPlaceholder: TextView
    private lateinit var btnRetry: TextView

    private val handler = Handler(Looper.getMainLooper())
    private val items = ArrayList<DouyinApi.VideoItem>()
    private var loading = false
    private var currentNav = NAV_RECOMMEND

    /**
     * 加载代际号 —— 防「迟到的响应盖掉新内容」。
     * 每次发起加载先自增并记下，回调时对不上就丢弃。
     * 修的是这个 bug：点分类→选类别→切回推荐，分类的响应稍后才回来，
     * 把推荐流内容覆盖成刚才那个分类的内容。
     */
    private var loadGen = 0

    // ================================================================
    // 分类定义（★ 真实可用：拉推荐流后按关键词/话题筛选）
    // ================================================================
    // 抖音的分类流接口（tab/feed?tag_id=）与搜索接口都需要额外签名，
    // 且 tag_id 无法凭猜测得到，所以采用「推荐流 + 内容筛选」的方式：
    // 拉若干轮推荐流，按关键词匹配标题 / 话题 / 作者名，归入对应分类。
    // 好处：不依赖任何不可用接口，点进去一定能看到内容。
    private val CATEGORY_KEYWORDS: LinkedHashMap<String, List<String>> = linkedMapOf(
        "推荐" to emptyList(),
        "美食" to listOf("美食", "吃", "料理", "做饭", "探店", "火锅", "小吃", "菜"),
        "游戏" to listOf("游戏", "王者", "吃鸡", "手游", "电竞", "对决", "通关"),
        "影视" to listOf("电影", "剧", "影视", "解说", "片段", "演技"),
        "音乐" to listOf("音乐", "歌", "翻唱", "演唱", "乐器", "弹唱"),
        "健身" to listOf("健身", "运动", "减肥", "瑜伽", "跑步", "锻炼", "拉伸"),
        "萌宠" to listOf("猫", "狗", "宠物", "萌宠", "动物", "铲屎"),
        "搞笑" to listOf("搞笑", "沙雕", "段子", "逗", "笑", "整活"),
        "旅行" to listOf("旅行", "旅游", "风景", "自驾", "露营", "徒步"),
        "生活" to listOf("生活", "日常", "记录", "vlog", "分享", "技巧")
    )

    /** 顶部频道名「关注」：直接走关注流接口（我关注的人发的视频） */
    private val FOLLOW_TAB = "关注"

    /** 自动刷新周期（5 分钟） */
    private val AUTO_REFRESH_MS = 5 * 60 * 1000L

    /** Tab 实际顺序：关注 + 推荐 + 各分类 */
    private val TAB_NAMES: List<String> by lazy {
        listOf(FOLLOW_TAB) + CATEGORY_KEYWORDS.keys.toList()
    }

    /** 关注流分页状态 */
    private var followCursor = 0L
    private var followHasMore = true

    /** 当前选中的分类名（默认进「关注」：打开电视就是关注列表，推荐一键可切） */
    private var activeCategory = "关注"

    /** 分类 Tab 控件（用于切换选中态） */
    private val tabViews = ArrayList<TextView>()

    private lateinit var tabScroll: View
    private lateinit var tabContainer: android.widget.LinearLayout
    private var lastCursor = 0L
    private var hasMore = true

    /**
     * 分类搜索的【批次起点偏移】——「换一批」靠它。
     * 搜索接口是 offset 分页，同一个 offset 返回同一批内容；
     * 旧代码 reset 永远从 offset=0 拉，所以「刷新」等于原地踏步。
     */
    private var categoryOffset = 0

    /** 本次分类内容是否走了「风控兜底」（推荐流筛关键词），用于如实提示 */
    private var lastLoadFallback = false

    /** 频道自动刷新：停在顶部时每 5 分钟换一批 */
    private val autoRefreshTick = object : Runnable {
        override fun run() {
            try {
                if (!isFinishing && uiReady && uiForeground && !loading &&
                    Prefs.getAutoRefresh(this@MainActivity) &&
                    gridView.visibility == View.VISIBLE &&
                    (layoutManager.findFirstVisibleItemPosition() <= 0)
                ) {
                    refreshChannel(nextBatch = true, silent = true)
                }
            } catch (_: Throwable) {
            }
            handler.postDelayed(this, AUTO_REFRESH_MS)
        }
    }

    /** 首页 UI 是否已建（恢复直通时不建，等真正回到前台再补建） */
    private var uiReady = false

    /** 恢复直通后紧接着的那次 onResume 是「路过」（播放页马上盖上来），不要建 UI */
    private var skipNextResumeUi = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        try {
            window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)

            // 无 Cookie 直接去登录页（登录入口现收在「我的」页，无登录态引导去登录）
            if (!Prefs.hasCookie(this)) {
                ensureUi()
                Log.d(TAG, "无 Cookie → 登录页")
                openLogin()
                return
            }

            // ★ 崩溃自愈：冷启动检测恢复点（播放中实时存的 + 心跳的）。
            //   进程被杀 → 直接回到崩前视频；正常退出已 clearResume。
            //   过期判定看「最后一次证明还在播的时刻」（见 tryRestorePlayback）。
            //   ★ 放在建首页 UI 之前 ★：每 4 个视频一次的自杀重启都走这里，
            //   恢复直通时跳过 setContentView/侧栏/列表初始化/拉推荐流 ——
            //   这些在 Cortex-A7 上是黑屏窗口里白白烧掉的大头。
            val resumeJson = Prefs.loadResume(this)
            Prefs.trace(this, "COLD_START resume_blank=${resumeJson.isBlank()} len=${resumeJson.length}")
            if (resumeJson.isNotBlank() && tryRestorePlayback(resumeJson)) {
                return
            }

            ensureUi()

            // 进入首页立刻拉「关注」流（用户要求：打开电视直接看关注列表）
            tvSectionTitle.text = FOLLOW_TAB
            loadFollowFeed(reset = true)

            // ★ 规则④：显式指定默认焦点 ★
            //   放在 post 里，等布局完成后再 requestFocus，否则会被系统清掉。
            navRecommend.post {
                navRecommend.requestFocus()
                Log.d(TAG, "默认焦点 → 推荐")
            }
        } catch (t: Throwable) {
            Log.e(TAG, "onCreate 失败", t)
            try { tvPlaceholder.text = "启动异常: ${t.javaClass.simpleName}: ${t.message}" } catch (_: Throwable) {}
        }
    }

    /** 建首页 UI（布局 + 侧栏 + 列表 + 分类 Tab）。恢复直通时跳过，回到前台才补建 */
    private fun ensureUi() {
        if (uiReady) return
        uiReady = true
        setContentView(R.layout.activity_main)
        bindViews()
        setupNav()
        setupGrid()
        buildCategoryTabs()   // ★ 分类 Tab 常驻
        // ★ 在线更新：启动 3 秒后静默检查（GitHub 渠道），有新版才弹窗
        navRecommend.postDelayed({ checkUpdate() }, 3000)
    }

    /**
     * 检查更新（GitHub Releases 渠道）：
     * 拉 version.json 比对 versionCode → 有新版弹「更新说明 + 立即升级」→ 下载 → 调起系统安装器。
     * 失败静默（不打扰用户）；未配置仓库地址时直接跳过。
     */
    private fun checkUpdate() {
        Thread {
            // ★ 先看本地有没有「已下好待安装」的包：有就直接给安装入口，不用联网、不用重下 7MB
            val ready = Prefs.getUpdateReady(this)
            if (ready.isNotBlank() && ready != BuildConfig.VERSION_NAME &&
                UpdateChecker.downloadedApk(this) != null) {
                // 有下好的包：不用联网、不用重下，直接给安装入口
                handler.post { if (!isFinishing) maybeOfferInstall() }
                return@Thread
            }

            val info = try { UpdateChecker.check(this) } catch (_: Throwable) { null } ?: return@Thread
            // 「稍后」过的版本不再每次启动都弹（点「立即升级」时清掉记录）
            if (info.versionName == Prefs.getSkipVersion(this)) return@Thread
            handler.post {
                if (isFinishing) return@post
                try {
                    android.app.AlertDialog.Builder(this)
                        .setTitle("发现新版本 ${info.versionName}")
                        .setMessage(
                            "当前版本 ${BuildConfig.VERSION_NAME} → 新版本 ${info.versionName}\n\n" +
                            info.notes.ifBlank { "（无更新说明）" } +
                            "\n\n点「立即升级」→ 后台下载（下载期间可照常看视频、正常操作），下好后再点安装。"
                        )
                        .setPositiveButton("立即升级") { _, _ ->
                            Prefs.setSkipVersion(this, "")
                            downloadAndInstall(info)
                        }
                        .setNegativeButton("稍后") { _, _ -> Prefs.setSkipVersion(this, info.versionName) }
                        .setOnDismissListener { showControlsIfAny() }
                        .show()
                } catch (_: Throwable) {}
            }
        }.start()
    }

    /**
     * 下载并安装
     * ==========
     * ★ 2.37 关键改动：不再用模态 ProgressDialog。
     *   旧写法弹的是一个 setCancelable(false) 的进度对话框 —— 它是独立窗口、独占输入焦点，
     *   7MB 下载几秒到几十秒里遥控器按上下左右/确定/返回全都落到这个对话框上，等于整个 app 被冻住，
     *   也没法切去看视频。现在：
     *     · 后台线程下载（低优先级），全程零对话框；
     *     · 进度用自绘 Toast 轻提示（每 10% 一次），不抢焦点，不影响任何按键；
     *     · 下载完成后不自动拉起安装器（会打断播放），只记录「待安装版本」+ 提示；
     *       首页在前台时就地弹一句「立即安装 / 稍后」，用户在播放页则完全静默，等他回来再装。
     */
    private fun downloadAndInstall(info: UpdateChecker.Info) {
        if (updateDownloading) {
            CustomToast.show(this, "更新已在后台下载，不用重复点")
            return
        }
        // 本地已有同版本包：直接进安装，不重复下载
        if (Prefs.getUpdateReady(this) == info.versionName && UpdateChecker.downloadedApk(this) != null) {
            doInstall()
            return
        }
        updateDownloading = true
        CustomToast.show(this, "开始后台下载 ${info.versionName}：可照常看视频，下好会提示")
        Thread {
            try {
                android.os.Process.setThreadPriority(android.os.Process.THREAD_PRIORITY_BACKGROUND)
            } catch (_: Throwable) {}
            var lastPct = -10
            val f = try {
                UpdateChecker.download(this, info.apkUrl, info.sha256) { p ->
                    // 节流：每 10% 提示一次，避免刷屏
                    if (p - lastPct >= 10 || p >= 100) {
                        lastPct = p
                        CustomToast.show(
                            this,
                            if (p >= 100) "正在校验 ${info.versionName} …"
                            else "正在下载 ${info.versionName} · $p%（可正常看视频）"
                        )
                    }
                }
            } catch (_: Throwable) { null }
            updateDownloading = false
            handler.post {
                if (f == null) {
                    CustomToast.show(this, "下载失败（直连与镜像都试过了），稍后点「检查更新」可重试")
                    return@post
                }
                Prefs.setUpdateReady(this, info.versionName)
                // 用系统 Toast：用户此刻可能正在播放页，自绘 Toast 挂在首页上是看不见的
                try {
                    android.widget.Toast.makeText(
                        this,
                        "${info.versionName} 下载完成，回到首页即可一键安装（不影响当前播放）",
                        android.widget.Toast.LENGTH_LONG
                    ).show()
                } catch (_: Throwable) {}
                if (uiForeground && !isFinishing) maybeOfferInstall()
            }
        }.start()
    }

    /**
     * 有「已下好待安装」的包时，就地给一键安装入口。
     * 只在首页前台时调用 —— 在播放页里绝不弹窗，等用户自己回到首页再问，不打断播放。
     */
    private fun maybeOfferInstall() {
        if (installPromptShowing) return
        val ready = Prefs.getUpdateReady(this)
        val apk = UpdateChecker.downloadedApk(this) ?: return
        if (ready.isBlank() || ready == BuildConfig.VERSION_NAME) return
        if (ready == Prefs.getSkipVersion(this)) return
        installPromptShowing = true
        try {
            android.app.AlertDialog.Builder(this)
                .setTitle("${ready} 已下载完成")
                .setMessage(
                    "当前版本 ${BuildConfig.VERSION_NAME}，安装包已在本地（约 ${apk.length() / 1024 / 1024} MB）。\n\n" +
                    "点「立即安装」调起系统安装器（安装时会短暂退出当前界面，装好重新打开即可）。"
                )
                .setPositiveButton("立即安装") { _, _ ->
                    installPromptShowing = false
                    Prefs.setSkipVersion(this, "")
                    doInstall()
                }
                .setNeutralButton("删除") { _, _ ->
                    installPromptShowing = false
                    apk.delete()
                    Prefs.setUpdateReady(this, "")
                    CustomToast.show(this, "已删除本地安装包")
                }
                .setNegativeButton("稍后") { _, _ ->
                    installPromptShowing = false
                    Prefs.setSkipVersion(this, ready)
                    CustomToast.show(this, "已保存，下次仍可点「检查更新」安装")
                }
                .setOnDismissListener {
                    installPromptShowing = false
                    showControlsIfAny()
                }
                .show()
        } catch (_: Throwable) {
            installPromptShowing = false
        }
    }

    /** 调起系统安装器（内部再校验一次 APK，损坏则自动清掉待安装标记） */
    private fun doInstall() {
        val ok = try { UpdateChecker.install(this, "") } catch (_: Throwable) { false }
        if (ok) {
            Prefs.setUpdateReady(this, "")
            return
        }
        if (UpdateChecker.needsInstallPermission(this)) {
            CustomToast.show(this, "请在系统设置里允许本应用「安装未知应用」，再点一次「立即安装」")
        } else {
            UpdateChecker.downloadedApk(this)?.delete()
            Prefs.setUpdateReady(this, "")
            CustomToast.show(this, "安装包异常，已删除，请重新下载")
        }
    }

    /** 首页是否在前台：决定下载完成后是就地弹安装确认，还是只静默记下不打断 */
    @Volatile private var uiForeground = false
    /** 正在后台下载，避免重复触发 */
    @Volatile private var updateDownloading = false
    /** 安装提示正在显示（避免 onResume 反复弹） */
    @Volatile private var installPromptShowing = false

    override fun onPause() {
        uiForeground = false
        handler.removeCallbacks(autoRefreshTick)
        super.onPause()
    }

    /** 弹窗关闭后把控制栏恢复（各页实现不同，这里尽力而为） */
    private fun showControlsIfAny() {
        try { navRecommend.requestFocus() } catch (_: Throwable) {}
    }

    override fun onResume() {
        super.onResume()
        uiForeground = true
        // 从播放页返回时：若冷启动走了「恢复直通」没建首页 UI，这里补建并拉流
        if (!uiReady) {
            if (skipNextResumeUi) {
                skipNextResumeUi = false   // 播放页正盖上来，这次是路过
                return
            }
            ensureUi()
            tvSectionTitle.text = FOLLOW_TAB
            loadFollowFeed(reset = true)
            navRecommend.post { navRecommend.requestFocus() }
        }
        // ★ 在播放页里下完的更新，回到首页才提示安装（绝不打断正在看的视频）
        navRecommend.postDelayed({ if (uiForeground) maybeOfferInstall() }, 1200)
        // ★ 2.38：频道自动刷新（默认 5 分钟；停在列表顶部才刷新，不打扰翻页中的用户）
        handler.removeCallbacks(autoRefreshTick)
        handler.postDelayed(autoRefreshTick, AUTO_REFRESH_MS)
    }

    // ================================================================
    // 视图绑定
    // ================================================================
    /**
     * 崩溃自愈恢复：解析恢复点 → FeedBus 交回列表 → 直接进播放页。
     * 连续在同一位置恢复 2 次（IJK 反复崩）→ 强制 VLC 兜底（引擎 3）。
     */
    private fun tryRestorePlayback(json: String): Boolean {
        try {
            val root = org.json.JSONObject(json)
            val now = System.currentTimeMillis()
            // ★ 过期判定改看「最后一次证明还在播的时刻」★
            //   JSON 里的 ts 只在起播那一刻写一次 = 【上一个视频的起播时间】，
            //   不是死亡时间。看一个视频超过窗口再往下滑，崩溃时 ts 早已过期，
            //   恢复点被白白清掉 → 用户被丢回推荐页、而且再也回不去。
            //   现在取 max(起播时间, 心跳时间)：心跳由播放中每 5 秒刷新。
            val lastAlive = maxOf(root.optLong("ts", 0L), Prefs.resumeAliveAt(this))
            val ageSec = (now - lastAlive) / 1000
            if (now - lastAlive > RESUME_WINDOW_MS) {
                Prefs.trace(this, "RESTORE expired age=${ageSec}s > ${RESUME_WINDOW_MS / 1000}s -> 推荐页")
                Log.d(TAG, "恢复点已过期(${ageSec}s) → 回首页")
                Prefs.clearResume(this)
                return false
            }
            val arr = root.getJSONArray("items")
            val list = ArrayList<DouyinApi.VideoItem>()
            for (i in 0 until arr.length()) {
                val o = arr.getJSONObject(i)
                val t = ArrayList<String>()
                val ta = o.optJSONArray("t")
                if (ta != null) for (j in 0 until ta.length()) t.add(ta.getString(j))
                val al = ArrayList<String>()
                val aa = o.optJSONArray("alt")
                if (aa != null) for (j in 0 until aa.length()) al.add(aa.getString(j))
                val imgs = ArrayList<String>()
                val ia = o.optJSONArray("imgs")
                if (ia != null) for (j in 0 until ia.length()) imgs.add(ia.getString(j))
                list.add(
                    DouyinApi.VideoItem(
                        o.getString("id"), o.getString("d"), o.getString("a"),
                        o.getString("u"), o.getString("c"), o.getInt("dur"),
                        o.getString("sec"), o.getInt("digg"), o.getInt("cmt"),
                        t, al, o.optBoolean("pt", false), 0L, imgs, o.optLong("ct", 0L)
                    )
                )
            }
            if (list.isEmpty()) { Prefs.trace(this, "RESTORE empty_items -> 推荐页"); Prefs.clearResume(this); return false }
            val idx = root.getInt("idx").coerceIn(0, list.size - 1)
            val src = root.optString("src", FeedBus.SOURCE_FEED)
            val retry = root.optInt("retry", 0) + 1
            // ★ 81.5: retry 必须写回（原来只加不存 → >=2 永不成立 → 熔断死代码）
            root.put("retry", retry)
            Prefs.saveResume(this, root.toString())

            // ★ 先分清「计划内重启」和「真崩溃」★
            //   每 4 个视频一次的自杀（绕固件起播计数）在冷启动侧看起来和崩溃一模一样，
            //   不区分就会被当成连崩累积 → 误把用户从自选的 IJK 强制切到别的引擎。
            val planned = now - Prefs.plannedRestartAt(this) < 60_000L
            if (planned) {
                Prefs.trace(this, "RESTORE planned=true idx=$idx age=${ageSec}s -> 播放页")
                Log.i(TAG, "计划内重启恢复 → 第 ${idx + 1} 条")
            } else {
                val streak = Prefs.bumpCrashStreak(this)
                Prefs.trace(this, "RESTORE planned=false idx=$idx streak=$streak age=${ageSec}s -> 播放页")
                // ★ 82.x: 熔断策略改为「IJK 崩 1 次 → 自动降级 Exo」，不再等崩 2 次才切 VLC。
                //   真机实测（10-08）：IJK 在这台 MStar 电视上每 4~6 个视频稳定 native 崩溃
                //   （3 条不同路径：Mali EGL abort / MediaCodec CSD / ff_read 撞 OMX freeNode，
                //   均见 /data/tombstones），逐个修是打地鼠。Exo（复用实例，不重建解码器）
                //   实测刷 18 个不崩。折中：开头几个视频享受 IJK 流畅，崩一次立刻切 Exo，
                //   保证后面不黑屏、也不会落到声音更差的 VLC。
                //   注意：仅当当前引擎是 IJK 时降级；用户手动选的其它引擎不覆盖。
                val curEngine = Prefs.getEngine(this)
                if (streak >= 1 && curEngine == 1) {
                    Prefs.setEngine(this, 0)   // 0 = ExoPlayer（复用实例，这台机上不崩）
                    Prefs.resetCrashStreak(this)
                    Log.w(TAG, "IJK 崩溃（${streak} 次）→ 自动降级 ExoPlayer 引擎")
                }
            }
            FeedBus.put(list, idx, src)
            // 恢复直通：本次没建首页 UI，紧接着的 onResume 是「路过」，标记跳过
            skipNextResumeUi = true
            startActivity(Intent(this, PlayerActivity::class.java))
            return true
        } catch (e: Exception) {
            Prefs.trace(this, "RESTORE parse_fail ${e.javaClass.simpleName}: ${e.message} -> 推荐页")
            Log.w(TAG, "恢复点解析失败: ${e.message}")
            Prefs.clearResume(this)
            return false
        }
    }

    private fun bindViews() {
        navRecommend = findViewById(R.id.navRecommend)
        navFollow = findViewById(R.id.navFollow)
        navSearch = findViewById(R.id.navSearch)
        navSettings = findViewById(R.id.navSettings)
        navLogin = findViewById(R.id.navLogin)

        tvSectionTitle = findViewById(R.id.tvSectionTitle) as TextView
        tvCount = findViewById(R.id.tvCount) as TextView
        tvHint = findViewById(R.id.tvHint) as TextView
        gridView = findViewById(R.id.gridView) as RecyclerView
        tvInfoTitle = findViewById(R.id.tvInfoTitle) as TextView
        tvInfoMeta = findViewById(R.id.tvInfoMeta) as TextView
        infobar = findViewById(R.id.infobar)

        tabScroll = findViewById(R.id.tabScroll)
        tabContainer = findViewById(R.id.tabContainer) as android.widget.LinearLayout
        placeholder = findViewById(R.id.placeholder)
        pbLoading = findViewById(R.id.pbLoading) as ProgressBar
        tvPlaceholder = findViewById(R.id.tvPlaceholder) as TextView
        btnRetry = findViewById(R.id.btnRetry) as TextView
    }

    // ================================================================
    // 侧边栏
    // ================================================================
    private fun setupNav() {
        // 规则①：可操作控件必须显式可聚焦（老 ROM 上布局里的 focusable 有时不生效，
        //   代码里再兜一次，成本极低但能避免"看得见按不到"）
        val navs = navArray()
        for (n in navs) {
            n.isFocusable = true
            n.isFocusableInTouchMode = true
            // ★ 焦点拓扑统一在 wireFocusTopology() 里建立（tab 建好后才有目标 id）。
            //   右键不再指向 gridView —— 那会让纵向标签右移直接掉进列表。
            // ★ 统一动效：焦点放大(1.08) + 按下回弹（FocusAnim 全应用同入口）
            n.setOnFocusChangeListener { v, has -> FocusAnim.scaleTo(v, has, 1.08f) }
        }

        // ★ 一步直达：点哪进哪，不经过任何中转页
        navRecommend.setOnClickListener { switchNav(NAV_RECOMMEND) }
        navFollow.setOnClickListener { openFollowList() }
        navSearch.setOnClickListener { openSearch() }
        navSettings.setOnClickListener { openSettings() }
        navLogin.setOnClickListener { openLogin() }
    }

    private fun navArray(): Array<View> = arrayOf(
        navRecommend, navFollow, navSearch, navSettings, navLogin
    )

    /**
     * ★ 三区域焦点拓扑（遥控器上/下/左/右各走各的通道，绝不串区）
     *
     *   ① 纵向导航列：上/下只在 7 个图标间循环（首尾相接），不进列表、不进 tab。
     *   ② 纵向 → 右：进入横向 tab 条的「当前选中项」（不是直接掉进 gridView 列表）。
     *   ③ 横向 tab 条：左/右只在 tab 间循环（首尾相接），上/下不越界到别区。
     *      · 上 → 回到纵向导航「推荐」位（首页）
     *      · 下 → 进列表（由 dispatchKeyEvent 兜底，直接落到第一张可见卡片）
     *
     * 动态 tab 默认没有 id，先分配 id 才能作为 nextFocusXxxId 的目标。
     */
    private fun wireFocusTopology() {
        // ① 纵向列：上下循环
        val navs = navArray()
        for (i in navs.indices) {
            navs[i].nextFocusUpId = navs[(i - 1 + navs.size) % navs.size].id
            navs[i].nextFocusDownId = navs[(i + 1) % navs.size].id
            // ② 纵向右键 → 当前选中的横向 tab
            navs[i].nextFocusRightId = selectedTabId()
        }
        // ③ 横向 tab：左右循环；上回纵向首页，下进列表
        for (i in tabViews.indices) {
            val t = tabViews[i]
            t.nextFocusLeftId = tabViews[(i - 1 + tabViews.size) % tabViews.size].id
            t.nextFocusRightId = tabViews[(i + 1) % tabViews.size].id
            t.nextFocusUpId = R.id.navRecommend
            t.nextFocusDownId = R.id.gridView
        }
    }

    /** 当前选中分类对应的 tab id；找不到就退回第一个 tab */
    private fun selectedTabId(): Int {
        val i = TAB_NAMES.indexOf(activeCategory)
        return if (i in tabViews.indices) tabViews[i].id
               else tabViews.firstOrNull()?.id ?: R.id.gridView
    }

    private fun openSearch() {
        startActivity(Intent(this, SearchActivity::class.java))
    }

    /** 「我的关注」作者列表（参考网页 /follow 左侧栏） */
    private fun openFollowList() {
        startActivity(Intent(this, FollowListActivity::class.java))
    }

    private fun openSettings() {
        startActivity(Intent(this, SettingsActivity::class.java))
    }

    private fun openLogin() {
        startActivity(Intent(this, LoginActivity::class.java))
    }

    /** 点「推荐」：回首页并切回推荐档（分类 Tab 常驻，点 Tab 即切频道） */
    private fun switchNav(nav: Int) {
        if (currentNav == nav) return
        currentNav = nav
        markNavSelected()
        activeCategory = "推荐"
        markCategorySelected()
        tvSectionTitle.text = "推荐"
        loadFeed(NAV_RECOMMEND, reset = true)
        gridView.post {
                // 焦点落到第一张卡（否则遥控器按方向键前没有任何焦点高亮）
                val first = layoutManager?.findViewByPosition(0)
                if (first != null) first.requestFocus() else gridView.requestFocus()
            }
    }

    private fun markNavSelected() {
        navRecommend.isSelected = currentNav == NAV_RECOMMEND
    }

    /**
     * 把焦点同步交到网格第一张可见卡片上。
     * @return 是否成功把焦点交出去（没可见卡片时返回 false，调用方交回框架处理）
     */
    private fun focusFirstGridCard(): Boolean {
        if (gridView.visibility != View.VISIBLE || adapter.itemCount == 0) return false
        val first = layoutManager.findFirstCompletelyVisibleItemPosition().let {
            if (it >= 0) it else layoutManager.findFirstVisibleItemPosition()
        }
        val v = if (first >= 0) layoutManager.findViewByPosition(first) else null
        if (v != null && v.isFocusable) {
            v.requestFocus()
            return true
        }
        return false
    }

    /**
     * 按网格实际列宽重算并统一封面高度。
     * 卡片自带 marginRight = tv_card_margin，故卡片宽 = 列宽 - 8dp。
     * 封面宽 = 卡片宽，高 = 宽 * 9/16。所有卡片用同一个 coverHeight，尺寸完全一致。
     */
    private fun recomputeCoverHeight() {
        val w = gridView.width
        if (w <= 0) return
        val margin = resources.getDimensionPixelSize(R.dimen.tv_card_margin)
        val colW = (w - margin * spanCount) / spanCount   // 每列减去自身 8dp margin
        if (colW <= 0) return
        val h = colW * 9 / 16
        if (h > 0 && h != adapter.coverHeight) {
            adapter.coverHeight = h
            adapter.notifyDataSetChanged()
        }
    }

    // ================================================================
    // 网格
    // ================================================================
    private fun setupGrid() {
        // ★ 手机适配：按屏宽决定列数（原先写死 5 列，在手机竖屏上卡片会小到无法点击）
        val wDp = resources.displayMetrics.widthPixels / resources.displayMetrics.density
        spanCount = when {
            wDp >= 1000f -> 5     // TV / 大屏
            wDp >= 760f -> 4
            wDp >= 560f -> 3
            else -> 2             // 手机竖屏
        }
        layoutManager = GridLayoutManager(this, spanCount)
        gridView.layoutManager = layoutManager
        adapter = VideoGridAdapter(items) { pos -> playAt(pos) }
        adapter.spanCount = spanCount   // 让适配器能给第一列卡片设置左键焦点目标
        // 改版：焦点项 -> 信息栏
        adapter.onFocusItem = { title, meta ->
            tvInfoTitle.text = title
            tvInfoMeta.text = meta
            // H2：底栏已砍，信息只在焦点卡片就近显示
        }
        // ★ 间距修正（图片大小不一致的根因）★
        //   item_video_card.xml 里每张卡已经有 layout_marginRight/Bottom = 8dp，
        //   旧代码又加了一层 ItemDecoration（右 8dp、下 8dp，且最后一列不加右 gap），
        //   于是「普通列 = 8+8=16dp、最后一列 = 8+0=8dp」——最后一列卡片更宽，
        //   封面 match_parent 跟着变宽 → 同一屏图片大小不一。
        //   其它页面（作者/关注/搜索）都用卡片自带的 margin，只有这里多了一层，
        //   所以直接移除 ItemDecoration，全应用统一靠 XML margin 布局，宽度自然一致。
        //   （不再 addItemDecoration）

        gridView.adapter = adapter
        // 规则③：RecyclerView 自身可聚焦 + 子项优先获得焦点
        gridView.isFocusable = true
        gridView.isFocusableInTouchMode = true
        // 规则③防飞出：网格在右，左键回侧边栏
        gridView.nextFocusLeftId = R.id.navRecommend
        // ★ 焦点转交改为「同步」★
        //   旧代码用 gridView.post{} 异步转交给第一张卡：标签按 ↓ 后焦点先落在容器上，
        //   post 还没执行时若连按方向键，框架按容器的 nextFocusLeftId 就把焦点甩到左侧图标。
        //   同步 requestFocus 不留这个时间窗；没有可见卡片时保持容器焦点，方向键在
        //   dispatchKeyEvent 里兜住，绝不外逃。
        gridView.setOnFocusChangeListener { _, has ->
            if (has && gridView.isFocused) {
                val first = layoutManager.findFirstVisibleItemPosition()
                val v = if (first >= 0) layoutManager.findViewByPosition(first) else null
                if (v != null && v.isFocusable) v.requestFocus()
            }
        }

        // ★ 封面统一高度（按实际列宽算，随宽度变化重算）★
        //   每张卡有 marginRight = tv_card_margin（8dp），GridLayoutManager 把可用宽度
        //   均分成 spanCount 列，卡片 match_parent 宽 = 列宽 - 8dp。
        //   封面宽 = 卡片宽，高 = 宽 * 9/16 → 16:9，所有卡片完全一致。
        //   用 OnLayoutChangeListener：宽度变化（旋屏/分屏）时自动重算，而不是只算一次。
        gridView.addOnLayoutChangeListener { _, _, _, _, _, _, _, _, _ -> recomputeCoverHeight() }
        recomputeCoverHeight()

        // 滚到底自动翻页（cursor 分页）
        gridView.addOnScrollListener(object : RecyclerView.OnScrollListener() {
            override fun onScrolled(rv: RecyclerView, dx: Int, dy: Int) {
                val total = layoutManager.itemCount
                val last = layoutManager.findLastVisibleItemPosition()
                if (hasMore && !loading && total > 0 && last >= total - 3) {
                    // 关注频道 → 关注流翻页；分类 Tab 选中「推荐」→ 推荐流翻页；
                    // 其它分类 → 分类搜索翻页
                    when {
                        activeCategory == FOLLOW_TAB -> loadFollowFeed(reset = false)
                        activeCategory != "推荐" -> loadCategoryFeed(activeCategory, false)
                        else -> loadFeed(NAV_RECOMMEND, reset = false)
                    }
                }
            }
        })
    }

    /** 选中某条 → 交给内存总线 → 进播放页 */
    private fun playAt(pos: Int) {
        if (pos < 0 || pos >= items.size) return
        // 关注频道进来的列表，续拉要继续打关注流，而不是推荐流
        val isFollow = activeCategory == FOLLOW_TAB
        val source = if (isFollow) FeedBus.SOURCE_FOLLOW else FeedBus.SOURCE_FEED
        // 走内存总线而不是 Intent extra：
        //   传大列表会逼近 Binder 1MB 上限 → TransactionTooLarge → "点进去播不了"
        FeedBus.put(items, pos, source, if (isFollow) followCursor else 0L)
        startActivity(Intent(this, PlayerActivity::class.java))
    }

    // ================================================================
    // 分类：Tab 条（★ 常驻主界面顶部，点 Tab 即切频道）
    // ================================================================
    /** 构建分类 Tab（只构建一次；主界面常驻显示） */
    private fun buildCategoryTabs() {
        if (tabViews.isNotEmpty()) return      // 已构建过

        val dp = resources.displayMetrics.density
        val padH = (18 * dp).toInt()
        for (name in TAB_NAMES) {
            val tv = TextView(this)
            // ★ 动态 View 默认 NO_ID，必须显式分配才能当 nextFocusXxxId 的目标
            tv.id = View.generateViewId()
            tv.text = name
            tv.textSize = 17f
            tv.gravity = android.view.Gravity.CENTER
            tv.isFocusable = true
            tv.isFocusableInTouchMode = true
            // 视觉对齐：分类标签统一固定宽度（视觉等宽），文字内部居中
            val chipW = resources.getDimensionPixelSize(R.dimen.chip_min_width)
            val chipH = resources.getDimensionPixelSize(R.dimen.tab_height)
            tv.gravity = android.view.Gravity.CENTER
            tv.includeFontPadding = false
            tv.setPadding(0, 0, 0, 0)
            tv.height = chipH
            tv.background = resources.getDrawable(R.drawable.tab_item_bg)
            tv.setTextColor(resources.getColorStateList(R.color.tab_text))
            val lp = android.widget.LinearLayout.LayoutParams(chipW, chipH)
            lp.rightMargin = resources.getDimensionPixelSize(R.dimen.space_s)
            tv.layoutParams = lp
            tv.setOnClickListener { FocusAnim.press(tv); onCategoryClick(name) }
            tv.setOnFocusChangeListener { v, has -> FocusAnim.scaleTo(v, has, 1.08f) }
            // ↓ 进列表：显式指定，避免快速连按时焦点被框架甩到左侧图标
            tv.nextFocusDownId = R.id.gridView
            tabContainer.addView(tv)
            tabViews.add(tv)
        }
        // markCategorySelected() 内部会在 tab 就绪后统一接线（见 wireFocusTopology）
        markCategorySelected()
    }

    private fun onCategoryClick(name: String) {
        if (activeCategory == name && items.isNotEmpty()) return
        activeCategory = name
        categoryOffset = 0           // ★ 换频道 = 从第一批看起
        markCategorySelected()
        tvSectionTitle.text = name   // ★ 顶栏标题随分类频道切换
        if (name == FOLLOW_TAB) loadFollowFeed(reset = true)
        else loadCategoryFeed(name, reset = true)
        // ★ 点完标签不要抢焦点 ★
        //   以前这里会把焦点直接塞给第一张卡片：用户按一下 OK 选频道的瞬间焦点就跳走，
        //   紧接着快速按方向键时焦点已在网格里乱跑，甚至一路甩到左侧图标上。
        //   现在焦点留在标签上，按 ↓ 才进列表（标签都设了 nextFocusDown = 网格）。
        gridView.scrollToPosition(0)
    }

    private fun markCategorySelected() {
        val names = TAB_NAMES
        tabViews.forEachIndexed { i, tv ->
            tv.isSelected = i < names.size && names[i] == activeCategory
        }
        // ★ 切换分类后，纵向右键要指向新的选中 tab → 重新接线
        if (tabViews.isNotEmpty()) wireFocusTopology()
    }

    /**
     * 分类内容加载 —— 直接用搜索接口（真分类）
     *
     * 改前：拉若干轮推荐流再按关键词过滤，命中随机、常常空手 —— 用户吐槽
     *       "只从推荐流里选，分了个寂寞"。
     * 改后：分类名直接当关键词打搜索接口（搜索已实测可用：登录 Cookie +
     *       干净参数，不带任何签名），点「美食」就是真的美食内容。
     *
     * 「推荐」档不走搜索，仍是推荐流。
     */
    /**
     * 分类兜底：搜索被风控时，用推荐流按分类关键词筛。
     * 命中不足 12 条就用推荐流补足（提示里已说明数据来源，不谎称是搜索结果）。
     */
    private fun categoryFallback(cookie: String, cat: String): DouyinApi.FeedResult? {
        val keys = CATEGORY_KEYWORDS[cat] ?: return null
        // ★ 搜索接口在设备端恒被风控 → 用推荐流筛。
        //   推荐流的 max_cursor 实际不回填（每次都是全新一批），所以不靠游标翻页，
        //   而是连拉 2 次（每次 count=20），把命中项排前面，不足 12 条再用
        //   推荐流内容补满格子 —— 宁可频道里有内容看，也不要一整屏空白。
        val hits = ArrayList<DouyinApi.VideoItem>()
        val pool = ArrayList<DouyinApi.VideoItem>()
        for (round in 0 until 2) {
            val f = DouyinApi.fetchFeed(cookie, maxCursor = 0L, count = 20) ?: break
            for (v in f.videos) {
                if (pool.any { it.awemeId == v.awemeId }) continue
                pool.add(v)
                val match = keys.any { k ->
                    v.desc.contains(k, ignoreCase = true) ||
                        v.topics.any { it.contains(k, ignoreCase = true) } ||
                        v.author.contains(k, ignoreCase = true)
                }
                if (match) hits.add(v)
            }
        }
        Log.d(TAG, "分类「$cat」风控兜底：池 ${pool.size} 条，命中 ${hits.size} 条")
        if (pool.isEmpty()) return null
        val out = ArrayList<DouyinApi.VideoItem>()
        out.addAll(hits)
        for (v in pool) {
            if (out.size >= 16) break
            if (hits.none { it.awemeId == v.awemeId }) out.add(v)
        }
        return DouyinApi.FeedResult(out, 0L, true)
    }

    /**
     * 换一批 / 刷新当前频道。
     * · 关注 → 重拉关注流首页
     * · 推荐 → 重拉推荐流（它本身就「每次请求都是新内容」，等于换一批）
     * · 分类 → 把批次偏移 +20，再看下一批搜索结果
     */
    private fun refreshChannel(nextBatch: Boolean, silent: Boolean = false) {
        if (!uiReady || loading) return
        if (nextBatch && activeCategory != FOLLOW_TAB && activeCategory != "推荐") {
            categoryOffset += 20
        }
        when {
            activeCategory == FOLLOW_TAB -> loadFollowFeed(reset = true)
            activeCategory == "推荐" -> loadFeed(NAV_RECOMMEND, reset = true)
            else -> loadCategoryFeed(activeCategory, reset = true)
        }
        try { gridView.scrollToPosition(0) } catch (_: Throwable) {}
        if (!silent) {
            tvHint.text = "正在换一批…"
            try { navRecommend.requestFocus() } catch (_: Throwable) {}
        }
    }

    /** 菜单键：频道级操作（换一批 / 自动刷新开关 / 设置） */
    private fun showChannelMenu() {
        val auto = Prefs.getAutoRefresh(this)
        val labels = arrayOf(
            "换一批 · 当前「" + activeCategory + "」",
            "自动刷新 · " + (if (auto) "开" else "关") + "（每 5 分钟，停在顶部时生效）",
            "打开设置 / 重新配置 Cookie"
        )
        try {
            AlertDialog.Builder(this)
                .setTitle("频道菜单")
                .setItems(labels) { _, which ->
                    when (which) {
                        0 -> refreshChannel(nextBatch = true)
                        1 -> {
                            val v = !Prefs.getAutoRefresh(this)
                            Prefs.setAutoRefresh(this, v)
                            tvHint.text = if (v) "自动刷新已开（每 5 分钟）" else "自动刷新已关"
                        }
                        2 -> openSettings()
                    }
                }
                .show()
        } catch (e: Exception) {
            Log.e(TAG, "频道菜单失败", e)
        }
    }

    private fun loadCategoryFeed(cat: String, reset: Boolean) {
        // 「推荐」= 原生推荐流
        if (cat == "推荐") {
            loadFeed(NAV_RECOMMEND, reset)
            return
        }

        // ★ 抢占式加载：不再 if (loading) return（那会让切导航后的新加载被旧请求挡回去），
        //   改为自增代际、旧响应回来时对不上就丢弃。
        val gen = ++loadGen
        loading = true
        // ★ 2.38：offset = 批次起点 + 本批内已翻的条数
        val offset = if (reset) categoryOffset else categoryOffset + items.size
        lastLoadFallback = false
        if (reset) {
            items.clear()
            adapter.replaceAll(items)
            lastCursor = 0L
            showPlaceholder("正在加载「" + cat + "」…", loading = true, showRetry = false)
            gridView.visibility = View.GONE
        }

        Thread {
            val cookie = Prefs.getCookie(this)
            var r: DouyinApi.FeedResult? = try {
                DouyinApi.searchVideos(cookie, cat, offset)
            } catch (e: Exception) {
                null
            }
            // ★ 2.38 风控兜底 ★
            //   搜索接口一旦被抖音判成 antispam_check / verify_check，就是整块返回 0 条，
            //   界面只能干瞪眼。这里退一步：用【推荐流】（带签名的 /tab/feed，实测一直可用）
            //   按本频道关键词筛一遍，至少让频道有真实内容可看，并在提示里说清楚。
            if (r == null || r.videos.isEmpty()) {
                val fb = try { categoryFallback(cookie, cat) } catch (_: Exception) { null }
                if (fb != null && fb.videos.isNotEmpty()) {
                    r = fb
                    lastLoadFallback = true
                }
            }
            handler.post {
                if (gen != loadGen) return@post   // ★ 已被更新的加载取代，丢弃这份迟到结果
                if (isFinishing) return@post      // ★ 修复：Activity 已销毁不再碰视图
                loading = false
                if (r == null || r.videos.isEmpty()) {
                    if (items.isEmpty()) {
                        showPlaceholder(
                            "「" + cat + "」没取到内容\n\n" + DouyinApi.lastError +
                                "\n\n可在设置页重新推送一次 Cookie。",
                            loading = false, showRetry = true
                        )
                        gridView.visibility = View.GONE
                    } else {
                        hasMore = false
                        tvHint.text = "没有更多了"
                    }
                } else {
                    if (reset) adapter.replaceAll(r.videos) else adapter.append(r.videos)
                    hidePlaceholder()
                    gridView.visibility = View.VISIBLE
                    tvCount.text = "共 " + adapter.itemCount + " 条"
                    tvHint.text = if (lastLoadFallback)
                        "「" + cat + "」搜索被风控 → 已用推荐流筛选　菜单键 换一批"
                    else "分类 · 搜索「" + cat + "」"
                    // ★ 风控兜底来的内容不能当搜索分页用（推荐流有自己的游标）
                    hasMore = if (lastLoadFallback) false else r.hasMore
                    lastCursor = (offset + r.videos.size).toLong()
                }
            }
        }.start()
    }

    // ================================================================
    // 关注流（网页版 douyin.com/follow 对应的接口）
    // ================================================================
    /** 关注流空页自动续拉次数（横屏过滤会把整页滤空，别直接判死） */
    private var followAutoRetry = 0

    private fun loadFollowFeed(reset: Boolean) {
        val gen = ++loadGen
        loading = true
        if (reset) {
            items.clear()
            adapter.replaceAll(items)
            followCursor = 0L
            followHasMore = true
            hasMore = true
            followAutoRetry = 0
            showPlaceholder("正在加载「关注」…", loading = true, showRetry = false)
            tvCount.text = ""
            gridView.visibility = View.GONE
        } else {
            tvHint.text = "加载更多…"
        }

        Thread {
            val cookie = Prefs.getCookie(this)
            val r = try {
                DouyinApi.fetchFollowFeed(
                    cookie,
                    maxCursor = if (reset) 0L else followCursor,
                    count = 12
                )
            } catch (e: Exception) {
                null
            }
            handler.post {
                if (gen != loadGen) return@post
                if (isFinishing) return@post
                loading = false

                if (r == null) {
                    if (items.isEmpty()) {
                        showPlaceholder(
                            "「关注」没取到内容\n\n" + DouyinApi.lastError +
                                "\n\n关注流要带登录 Cookie，可在设置页重新推送一次 Cookie。",
                            loading = false, showRetry = true
                        )
                        gridView.visibility = View.GONE
                    } else {
                        hasMore = false
                        followHasMore = false
                        tvHint.text = "没有更多了"
                    }
                    return@post
                }

                followCursor = r.maxCursor
                followHasMore = r.hasMore
                hasMore = r.hasMore

                if (r.videos.isEmpty()) {
                    // 有空页但还有下一页（多为「仅横屏」把这一页全滤掉）→ 自动续拉两页
                    if (r.hasMore && followAutoRetry < 2) {
                        followAutoRetry++
                        loadFollowFeed(reset = false)
                        return@post
                    }
                    if (items.isEmpty()) {
                        showPlaceholder(
                            "「关注」暂时没有可看的视频\n\n" + DouyinApi.lastError +
                                "\n\n若已关注了人，可能是都只有竖屏视频 —— 设置里关闭「仅横屏」再试。",
                            loading = false, showRetry = true
                        )
                        gridView.visibility = View.GONE
                    } else {
                        tvHint.text = "没有更多了"
                    }
                    return@post
                }

                if (reset) adapter.replaceAll(r.videos) else adapter.append(r.videos)
                hidePlaceholder()
                gridView.visibility = View.VISIBLE
                tvCount.text = "共 ${adapter.size()} 条"
                tvHint.text = "关注 · 我关注的人" + if (hasMore) " ↓ 继续加载" else "（已到底）"
            }
        }.start()
    }

    // ================================================================
    // 数据加载
    // ================================================================
    private fun loadFeed(nav: Int, reset: Boolean) {
        // ★ 同上：抢占式，靠代际丢弃迟到的旧响应
        val gen = ++loadGen
        loading = true
        if (reset) {
            items.clear()
            adapter.notifyDataSetChanged()
            lastCursor = 0L
            hasMore = true
            showPlaceholder("正在加载…", loading = true, showRetry = false)
        } else {
            tvHint.text = "加载更多…"
        }

        Thread {
            var result: DouyinApi.FeedResult? = null
            var err: String? = null
            var netOk = true
            try {
                val cookie = Prefs.getCookie(this)
                // ★ cursor 分页：fetchFeed 的第二个参数就是 maxCursor
                //   （底层早就支持，只是旧首页"拉一次就跳播放器"从没用上）
                result = DouyinApi.fetchFeed(
                    cookie,
                    maxCursor = if (reset) 0L else lastCursor
                )
            } catch (e: Exception) {
                Log.e(TAG, "feed 异常", e)
                err = e.message
            }
            // ★ 网络探测必须放后台线程：buildDiagnosis 原来在主线程同步执行
            //   OkHttp（connectTimeout 20s）→ 失败占位显示时 UI 卡死甚至 ANR。
            //   只在失败时才探测，成功路径零额外开销。
            if (result == null) {
                try { netOk = DouyinApi.probe() } catch (_: Exception) {}
            }

            val r = result
            handler.post {
                if (gen != loadGen) return@post   // ★ 已被更新的加载取代（例如切了导航），丢弃
                if (isFinishing) return@post      // ★ 修复：Activity 已销毁不再碰视图
                loading = false
                if (r != null && r.videos.isNotEmpty()) {
                    lastCursor = r.maxCursor
                    hasMore = r.hasMore
                    if (reset) adapter.replaceAll(r.videos) else adapter.append(r.videos)
                    hidePlaceholder()
                    gridView.visibility = View.VISIBLE
                    tvCount.text = "共 ${adapter.size()} 条"
                    tvHint.text = if (hasMore) "↓ 继续加载" else "已到底"
                    Log.d(TAG, "加载 ${r.videos.size} 条，累计 ${adapter.size()}")
                } else {
                    if (reset) {
                        // 空数据 vs 加载失败，给不同占位（规则 6）
                        val diag = buildDiagnosis(netOk)
                        showPlaceholder(
                            if (r != null) "这里还没有内容\n\n换个分类试试？" else diag,
                            loading = false, showRetry = true
                        )
                        gridView.visibility = View.GONE
                    } else {
                        // ★ 修复：翻页失败（异常）≠ 没有更多。
                        //   原来 r==null 也走"没有更多了"+hasMore=false，
                        //   一次瞬时网络抖动就永久停止翻页且无法重试。
                        if (r == null) {
                            tvHint.text = "加载失败（${DouyinApi.lastError.ifEmpty { err ?: "网络异常" }}）↓ 继续下滑重试"
                            Log.w(TAG, "feed 翻页失败: ${err ?: DouyinApi.lastError}")
                        } else {
                            tvHint.text = "没有更多了"
                            hasMore = false
                        }
                    }
                }
            }
        }.start()
    }

    // ================================================================
    // 占位视图（规则 6：Loading / 网络错误 / 空数据）
    // ================================================================
    private fun showPlaceholder(text: String, loading: Boolean, showRetry: Boolean) {
        placeholder.visibility = View.VISIBLE
        this.pbLoading.visibility = if (loading) View.VISIBLE else View.GONE
        tvPlaceholder.text = text
        btnRetry.visibility = if (showRetry) View.VISIBLE else View.GONE
        if (showRetry) {
            // 规则④：占位态下把焦点交给重试按钮，否则遥控器无处可去
            btnRetry.post { btnRetry.requestFocus() }
        }
    }

    private fun hidePlaceholder() {
        placeholder.visibility = View.GONE
    }

    /** 失败诊断（电视上看不到 logcat，把线索直接摆出来） */
    private fun buildDiagnosis(netOk: Boolean): String {
        val sb = StringBuilder("加载失败\n\n")
        sb.append("原因: ").append(DouyinApi.lastError.ifEmpty { "未知（无异常）" }).append("\n\n")
        sb.append("系统时间: ")
            .append(SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.getDefault()).format(Date()))
            .append("\n  （时间不对会让 TLS 证书校验失败）\n")
        sb.append("Android: ").append(android.os.Build.VERSION.RELEASE)
            .append(" (API ").append(android.os.Build.VERSION.SDK_INT).append(")\n")
        sb.append("机型: ").append(DeviceProfile.summary).append("\n")
        sb.append("TLS 后端: ").append(App.tlsBackend).append("\n")
        sb.append("证书校验: ")
            .append(if (DouyinApi.tlsDowngraded) "已降级（老设备信任库缺根 CA）" else "严格")
            .append("\n")
        sb.append("Cookie: ").append(Prefs.getCookie(this).length).append(" 字符\n")
        sb.append("网络: ").append(if (netOk) "可达" else "不可达 ✗")
        return sb.toString()
    }

    // ================================================================
    // 遥控器
    // ================================================================
    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        if (event.action == KeyEvent.ACTION_DOWN) {
            when (event.keyCode) {
                // ★ 快速按 ↓ 焦点不外逃修复 ★
                //   焦点在横向标签（或网格容器）上按 ↓ 时，直接、同步地把焦点
                //   交给网格第一张可见卡，并消费事件。
                //   旧版靠标签的 nextFocusDownId + 容器 post 转交，连按时容器先拿到
                //   焦点、post 还没跑，第二次 ↓ 就按容器的几何关系把焦点甩到左侧图标。
                //   自己接管后不再有这个时间窗，按多快都稳定落在列表里。
                KeyEvent.KEYCODE_DPAD_DOWN -> {
                    if (!uiReady) return super.dispatchKeyEvent(event)
                    val f = currentFocus
                    val fromTab = f is TextView && tabViews.contains(f)
                    val fromContainer = f == gridView
                    if (fromTab || fromContainer) {
                        if (focusFirstGridCard()) return true
                    }
                }
                // 规则 5①：返回键优先处理弹层/占位，再退回上一级
                KeyEvent.KEYCODE_BACK -> {
                    if (placeholder.visibility == View.VISIBLE) {
                        hidePlaceholder()
                        gridView.visibility = View.VISIBLE
                        gridView.post {
                // 焦点落到第一张卡（否则遥控器按方向键前没有任何焦点高亮）
                val first = layoutManager?.findViewByPosition(0)
                if (first != null) first.requestFocus() else gridView.requestFocus()
            }
                        return true
                    }
                    finish()
                    return true
                }
                // ★ 2.38：菜单键 → 频道菜单（换一批 / 自动刷新）
                KeyEvent.KEYCODE_MENU, KeyEvent.KEYCODE_INFO -> {
                    if (!uiReady) return super.dispatchKeyEvent(event)
                    showChannelMenu()
                    return true
                }
            }
        }
        return super.dispatchKeyEvent(event)
    }

    override fun onDestroy() {
        handler.removeCallbacksAndMessages(null)
        super.onDestroy()
    }
}
