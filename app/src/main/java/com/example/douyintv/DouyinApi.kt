package com.example.douyintv

import java.net.URLEncoder
import android.util.Log
import org.json.JSONArray
import org.json.JSONObject
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import java.security.KeyStore
import java.security.SecureRandom
import java.security.cert.CertificateException
import java.util.concurrent.TimeUnit
import javax.net.ssl.SSLContext
import javax.net.ssl.SSLException
import javax.net.ssl.SSLSocketFactory
import javax.net.ssl.TrustManager
import javax.net.ssl.TrustManagerFactory
import javax.net.ssl.X509TrustManager

/**
 * 抖音 Web API 客户端
 * 使用 X-Bogus 签名 (算法已用 Python 验证与官方 f2 实现一致)
 */
object DouyinApi {

    private const val TAG = "DouyinApi"

    /**
     * ⚠️ 路径末尾**不能有斜杠**！
     * 实测（各请求 4 次）：
     *   /aweme/v1/web/tab/feed/   → 稳定只返回 2 条  ← 带斜杠 = 被降级
     *   /aweme/v1/web/tab/feed    → 稳定返回 12 条   ← 正确
     * 且该推荐流**每次请求都返回全新内容（实测批次间零重叠）**，
     * 所以「无限刷」= 反复请求本接口，而不是靠 max_cursor 翻页。
     */
    private const val FEED_URL = "https://www.douyin.com/aweme/v1/web/tab/feed"
    private const val USER_POST_URL = "https://www.douyin.com/aweme/v1/web/aweme/post"
    private const val COMMENT_URL = "https://www.douyin.com/aweme/v1/web/comment/list"

    /** 各接口共用的尾部参数（★顺序敏感：签名串必须与实际请求串逐字一致） */
    private const val COMMON_TAIL =
        "&pc_client_type=1&version_code=170400&version_name=17.4.0" +
        "&cookie_enabled=true&screen_width=1920&screen_height=1080" +
        "&browser_language=zh-CN&browser_platform=Win32&browser_name=Chrome" +
        "&browser_version=122.0.0.0&os_name=Windows&os_version=10" +
        "&cpu_core_num=8&device_memory=8&platform=PC&downlink=10" +
        "&effective_type=4g&round_trip_time=50"
    private const val UA = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/122.0.0.0 Safari/537.36"
    /** 播放器 / 长效 play 接口统一 Referer（抖音要求带此头，否则 403） */
    private const val REFERER = "https://www.douyin.com/"

    /** 互动接口专用 UA：对齐真实抓包（Edge 154 / Chromium 154）—— 签名与请求头必须一致 */
    private const val INTERACT_UA = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/154.0.0.0 Safari/537.36"

    data class VideoItem(
        val awemeId: String,
        val desc: String,
        val author: String,
        val videoUrl: String,
        val coverUrl: String = "",
        val duration: Int = 0,
        val secUid: String = "",     // 作者 sec_uid —— 请求作者主页用
        val diggCount: Int = 0,      // 点赞数
        val commentCount: Int = 0,   // 评论数
        val topics: List<String> = emptyList(),  // 话题名（来自 text_extra）
        val altUrls: List<String> = emptyList(), // 备用播放地址（主链失效时依次重试）
        val isPortrait: Boolean = false,          // 竖屏视频 —— 卡片角标用（解析层按宽高判定）
        val ts: Long = 0,                          // 预留时间戳字段（接口解析恒为 0）
        val imageUrls: List<String> = emptyList(), // ★ 图文/图集作品（无 play_addr，播放器走图片轮播）
        val createTime: Long = 0                   // ★ 作品发布日期（Unix 秒，来自 create_time）
    ) {
        /** 是否图文作品：没有视频地址但有图片列表 */
        val isImagePost: Boolean get() = videoUrl.isBlank() && imageUrls.isNotEmpty()
    }

    /** 一条评论（含图片/视频评论的媒体与评论者主页信息） */
    data class CommentItem(
        val text: String,
        val user: String,
        val diggCount: Int = 0,
        val replyCount: Int = 0,
        val secUid: String = "",       // 评论者 sec_uid —— 点评论进作者主页
        val imageUrl: String = "",     // 评论图片（首图直链）
        val videoCover: String = ""    // 评论视频封面（有视频评论时）
    )

    data class FeedResult(
        val videos: List<VideoItem>,
        val maxCursor: Long,
        val hasMore: Boolean
    )

    private var strictClient: OkHttpClient? = null
    private var relaxedClient: OkHttpClient? = null

    /** 最近一次失败原因（供 UI 显示，电视上没法看 logcat） */
    @Volatile
    var lastError: String = ""
        private set

    /** 是否因老设备信任库过时而降级到宽松校验（诊断面板显示） */
    @Volatile
    var tlsDowngraded: Boolean = false
        private set

    /** 供播放器下载视频复用（带 TLS1.2 + 证书降级） */
    fun httpClient(): OkHttpClient = getClient()

    /**
     * 「不怕证书问题」的 client —— 电视 CA 库过期时用。
     * OkHttp 层会自动降级，但 ExoPlayer 若自己发请求（如解析 302）就拿不到这层保护，
     * 所以把宽松 client 也暴露出来供这类调用使用。
     */
    fun relaxedHttpClient(): OkHttpClient = try {
        getRelaxedClient()
    } catch (e: Exception) {
        Log.w(TAG, "宽松 client 获取失败，退回严格: ${e.message}")
        getClient()
    }

    private fun baseBuilder() = OkHttpClient.Builder()
        .followRedirects(true).followSslRedirects(true)
        .connectTimeout(8, TimeUnit.SECONDS)
        .readTimeout(20, TimeUnit.SECONDS)
        .retryOnConnectionFailure(true)
        // ★ 只用 IPv4 ★
        //   盒子重启后路由器会下发 IPv6（DNS 是 fe80:: 链路本地地址），但上游 IPv6
        //   实际不可达：TCP 443 连 IPv6 地址会被静默丢弃，只有等超时才轮到 IPv4
        //   → 所有 https 请求（关注流、更新检查、播放器解析）都像"断网"一样卡住。
        //   这里在 DNS 层直接把解析结果过滤成 IPv4，绕开这条黑洞路径。
        .dns(object : okhttp3.Dns {
            override fun lookup(hostname: String): List<java.net.InetAddress> {
                val all = try { java.net.InetAddress.getAllByName(hostname).toList() } catch (e: Exception) { emptyList() }
                val v4 = all.filterIsInstance<java.net.Inet4Address>()
                return if (v4.isNotEmpty()) v4 else all
            }
        })
        .cache(null)

    /** 正常客户端：走系统信任链校验 */
    private fun getClient(): OkHttpClient {
        strictClient?.let { return it }
        synchronized(this) {
            strictClient?.let { return it }
            val b = baseBuilder()
            try {
                val tm = TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm())
                    .apply { init(null as KeyStore?) }.trustManagers[0] as X509TrustManager
                Tls12SocketFactory.create(tm)?.let { b.sslSocketFactory(it, tm) }
            } catch (e: Exception) {
                Log.w(TAG, "TLS1.2 失败: ${e.message}")
            }
            return b.build().also { strictClient = it }
        }
    }

    /**
     * 宽松客户端：接受任意证书。
     *
     * 专治 Android 4.x 电视 —— 酷开的 CA 库存停留在出厂年份且无 GMS 更新，
     * 抖音证书链的根 CA 不在其中，握手报
     *   "Trust anchor for certification path not found"。
     * 换 TLS 协议栈(Conscrypt)没用，因为信任库仍读系统 CA。
     *
     * 仅在严格校验失败后兜底，正常设备（手机）永远走不到这里。
     */
    private fun getRelaxedClient(): OkHttpClient {
        relaxedClient?.let { return it }
        synchronized(this) {
            relaxedClient?.let { return it }
            val b = baseBuilder()
            try {
                val tm = RelaxedTrustManager()
                val ctx = SSLContext.getInstance("TLS")
                ctx.init(null, arrayOf<TrustManager>(tm), SecureRandom())
                b.sslSocketFactory(Tls12SocketFactory(ctx.socketFactory as SSLSocketFactory), tm)
                b.hostnameVerifier { _, _ -> true }
                Log.w(TAG, "宽松 TLS 已启用（老设备信任库过时）")
            } catch (e: Exception) {
                Log.e(TAG, "宽松 TLS 构建失败", e)
            }
            return b.build().also { relaxedClient = it }
        }
    }

    /** 是否证书/TLS 层问题（决定要不要降级） */
    private fun isTlsProblem(t: Throwable?): Boolean {
        var e = t
        var depth = 0
        while (e != null && depth++ < 10) {
            if (e is SSLException || e is CertificateException) return true
            val m = e.message ?: ""
            if (m.contains("Trust anchor") || m.contains("CertPathValidator")) return true
            e = e.cause
        }
        return false
    }

    /** 发请求；证书校验失败时自动降级重试一次 */
    private fun exec(req: Request): Response = try {
        getClient().newCall(req).execute()
    } catch (e: Exception) {
        if (!isTlsProblem(e)) throw e
        Log.w(TAG, "严格 TLS 失败 → 宽松重试: ${e.message}")
        tlsDowngraded = true
        getRelaxedClient().newCall(req).execute()
    }

    /** 网络可达性探测（复用同样的降级逻辑，供诊断面板） */
    fun probe(url: String = "https://www.douyin.com/"): Boolean = try {
        val req = Request.Builder().url(url).header("User-Agent", UA).build()
        val r = exec(req)
        val code = r.code()
        r.close()
        Log.d(TAG, "probe $url → HTTP $code (宽松=${tlsDowngraded})")
        code in 200..499
    } catch (e: Exception) {
        Log.w(TAG, "probe 失败: ${e.javaClass.simpleName}: ${e.message}")
        false
    }

    /**
     * 请求推荐 feed
     * 参数列表与签名必须完全一致（顺序敏感）
     */
    fun fetchFeed(cookie: String, maxCursor: Long = 0, count: Int = 12): FeedResult? {
        lastError = ""
        try {
            val params = buildParams(maxCursor, count)

            // 生成 X-Bogus 签名
            val xBogus = try {
                XBogus.generate(params, UA)
            } catch (e: Exception) {
                lastError = "X-Bogus 生成失败: ${e.message}"
                Log.e(TAG, "XBogus 生成失败: ${e.message}")
                return null
            }

            val fullUrl = "$FEED_URL?$params&X-Bogus=$xBogus"
            Log.d(TAG, "→ feed max_cursor=$maxCursor")
            Log.d(TAG, "  X-Bogus=$xBogus")
            Log.d(TAG, "  URL=${fullUrl.take(160)}")

            val req = Request.Builder()
                .url(fullUrl)
                .header("User-Agent", UA)
                .header("Referer", "https://www.douyin.com/")
                .header("Cookie", cookie)
                .header("Accept", "application/json, text/plain, */*")
                .header("Accept-Language", "zh-CN,zh;q=0.9")
                .build()

            val resp = exec(req)
            val code = resp.code()
            val body = resp.body()?.string() ?: ""
            Log.d(TAG, "← HTTP $code body=${body.length}B")

            if (code != 200) {
                lastError = "抖音返回 HTTP $code"
                Log.e(TAG, "✗ HTTP $code")
                return null
            }
            if (body.isEmpty()) {
                lastError = "抖音返回空响应（签名或 Cookie 被拒）"
                Log.e(TAG, "✗ 空响应（签名或参数错误）")
                return null
            }

            return parseFeed(body)
        } catch (e: Exception) {
            lastError = "${e.javaClass.simpleName}: ${e.message}"
            Log.e(TAG, "fetchFeed 异常: ${e.javaClass.simpleName}: ${e.message}", e)
            return null
        }
    }

    // ============================================================
    // 关注流（我关注的人发布的视频，对应网页版 douyin.com/follow）
    // 与推荐流同源同签：Cookie + X-Bogus，参数顺序也参与签名。
    // ============================================================
    private const val FOLLOW_FEED_URL = "https://www.douyin.com/aweme/v1/web/follow/feed/"

    fun fetchFollowFeed(cookie: String, maxCursor: Long = 0, count: Int = 12): FeedResult? {
        lastError = ""
        val params = buildFollowParams(maxCursor, count)
        val xBogus = try {
            XBogus.generate(params, UA)
        } catch (e: Exception) {
            lastError = "X-Bogus 生成失败: ${e.message}"
            Log.e(TAG, "关注流 XBogus 生成失败: ${e.message}")
            return null
        }

        // 抖音对路径尾斜杠敏感（推荐流带斜杠会被降级只回 2 条），
        // 关注流则相反：网页版原样是带斜杠的。两个写法都试一次，谁通用谁。
        val candidates = listOf(FOLLOW_FEED_URL, FOLLOW_FEED_URL.trimEnd('/'))
        for (base in candidates) {
            val fullUrl = "$base?$params&X-Bogus=$xBogus"
            try {
                Log.d(TAG, "→ follow max_cursor=$maxCursor url=${fullUrl.take(170)}")
                val req = Request.Builder()
                    .url(fullUrl)
                    .header("User-Agent", UA)
                    .header("Referer", "https://www.douyin.com/follow")
                    .header("Cookie", cookie)
                    .header("Accept", "application/json, text/plain, */*")
                    .header("Accept-Language", "zh-CN,zh;q=0.9")
                    .build()

                val resp = exec(req)
                val code = resp.code()
                val body = resp.body()?.string() ?: ""
                Log.d(TAG, "← follow HTTP $code body=${body.length}B ${body.take(120)}")

                if (code != 200 || body.isEmpty()) {
                    lastError = "关注流返回 HTTP $code" + if (body.isEmpty()) "（空响应）" else ""
                    continue
                }

                // 「没关注任何人」是正常状态（status_code=0 + data 为空），
                // 不能被当成接口失败——否则用户会以为 Cookie 坏了。
                val emptyOk = try {
                    val root = JSONObject(body)
                    root.optInt("status_code", -1) == 0 &&
                        (root.optJSONArray("data")?.length() ?: 0) == 0
                } catch (_: Exception) {
                    false
                }
                if (emptyOk) {
                    lastError = "关注列表为空：这个账号还没有关注任何人，或关注的人暂无新作品"
                    return FeedResult(emptyList(), maxCursor, false)
                }

                val r = parseFollowFeed(body)
                if (r != null) return r
                // 签名/参数被拒时换下一种写法再试一次
            } catch (e: Exception) {
                lastError = "${e.javaClass.simpleName}: ${e.message}"
                Log.e(TAG, "fetchFollowFeed 异常: ${e.javaClass.simpleName}: ${e.message}")
            }
        }
        return null
    }

    /**
     * 临时诊断：把关注流几种写法一次跑完，结果由 ConfigServer /diagfollow 展示。
     * （调通后即可删除，不属于业务逻辑）
     */
    fun probeFollow(cookie: String): String {
        val sb = StringBuilder()
        val p = buildFollowParams(0, 20)
        val xb = try { XBogus.generate(p, UA) } catch (e: Exception) { "" }
        val targets = linkedMapOf(
            "关注流·签名·斜杠" to "$FOLLOW_FEED_URL?$p&X-Bogus=$xb",
            "关注流·签名·无斜杠" to "${FOLLOW_FEED_URL.trimEnd('/')}?$p&X-Bogus=$xb",
            "关注流·无签名" to "$FOLLOW_FEED_URL?$p",
            "朋友流·签名" to "https://www.douyin.com/aweme/v1/web/familiar/feed/?$p&X-Bogus=$xb",
            "自己资料" to "https://www.douyin.com/aweme/v1/web/user/profile/self/?" +
                "device_platform=webapp&aid=6383&channel=channel_pc_web$COMMON_TAIL"
        )
        for ((label, url) in targets) {
            try {
                val req = Request.Builder().url(url)
                    .header("User-Agent", UA)
                    .header("Referer", "https://www.douyin.com/follow")
                    .header("Cookie", cookie)
                    .header("Accept", "application/json, text/plain, */*")
                    .build()
                val resp = exec(req)
                val code = resp.code()
                val body = resp.body()?.string() ?: ""
                val sum = StringBuilder("len=${body.length}")
                try {
                    val r = JSONObject(body)
                    sum.append(" st=").append(r.optInt("status_code", -1))
                    sum.append(" n=").append(r.optJSONArray("aweme_list")?.length() ?: 0)
                    sum.append(" data=").append(r.optJSONArray("data")?.length() ?: 0)
                    sum.append(" more=").append(r.optInt("has_more", -1))
                    sum.append(" cur=").append(r.optLong("cursor", 0))
                    val u = r.optJSONObject("user")
                    if (u != null) {
                        sum.append(" nick=").append(u.optString("nickname"))
                        sum.append(" following=").append(u.optInt("following_count", -1))
                    }
                } catch (_: Exception) {}
                sb.append(label).append(" → HTTP ").append(code).append(' ').append(sum)
                    .append(" | ").append(body.take(90).replace("\n", " ")).append('\n')
            } catch (e: Exception) {
                sb.append(label).append(" → EX ").append(e.javaClass.simpleName)
                    .append(": ").append(e.message).append('\n')
            }
        }
        return sb.toString()
    }

    /**
     * 关注流的参数串（顺序参与签名，不可随意调整）。
     * 与推荐流相比：要去掉 aweme_id/publish_video_strategy_type 之类，
     * 换成网页版 /follow 实际发送的 filterGids + refer。
     */
    // ======================= 关注作者列表 =======================

    /** 关注列表里的一个作者（「我的关注」），与视频条目无关 */
    data class FollowAuthor(
        val secUid: String,
        val nickname: String,
        val uniqueId: String,
        val avatarUrl: String,
        val awemeCount: Int
    )

    /** 关注列表一页的结果（max_time 为主游标；offset 服务端忽略） */
    data class FollowPage(
        val authors: List<FollowAuthor>,
        val nextMaxTime: Long,
        val hasMore: Boolean,
        /** 本页最老的关注时间 —— 批量关注时很多人同一秒，用它才能跳过整簇 */
        val pageMinTime: Long = 0L,
        /** 自己的关注总数（profile.self 里带，用来显示「共 35 位」） */
        val totalCount: Int = 0,
        /** 服务端回的 offset（真机确认被忽略，仅保留字段兼容，不参与翻页） */
        val serverOffset: Int = 0
    )

    /**
     * 拉取「我的关注」作者列表（网页版 /follow 左侧那个列表）。
     * 接口：/aweme/v1/web/user/following/list/ —— 返回 followings 数组。
     *
     * ★ 翻页策略（真机实测结论，切勿再改回 offset）★
     *   · 服务端**忽略 offset**，且一页最多只给 21 条（count 参数也被忽略）；
     *     上一版误把 offset 当主游标递增 → 每页都返回同一批 21 人、被去重后
     *     fresh 为空 → 只显示 21 人。所以 offset 固定 0。
     *   · 真正的游标是 **max_time**：每页带上服务端回的 nextMaxTime 翻下一页。
     *   · 批量关注的人 follow_time 落在同一秒，max_time 会「原地打滑」——
     *     连翻几页只多出几个人。解决办法：当本页新增很少时，改用本页
     *     pageMinTime（最老关注时间）跳过整簇。详见 FollowListActivity。
     */
    fun fetchFollowing(cookie: String, offset: Int = 0, maxTime: Long = 0L, count: Int = 20): FollowPage? {
        var uid = selfUid
        var sec = selfSecUid
        if (uid.isBlank() || sec.isBlank()) {
            val t = fetchSelfId(cookie)
            if (t != null) { uid = t.first; sec = t.second }
        }
        if (uid.isBlank() && sec.isBlank()) {
            lastError = "拿不到自己的 uid/sec_uid，无法读取关注列表"
            return null
        }
        val params = "device_platform=webapp" +
            "&aid=6383" +
            "&channel=channel_pc_web" +
            "&user_id=$uid" +
            "&sec_user_id=$sec" +
            // ★ 真机实测：服务端忽略 offset、一页最多给 21 条。
            //   上一版把 offset 当主游标递增，服务端每次都返回第一页那 21 条，
            //   去重后 fresh 为空 → 提前停止 → 只显示 21 人。必须固定 offset=0。
            "&offset=0" +
            "&count=$count" +
            // ★ 真正的翻页游标是 max_time（第一页传 0），必须始终带上。
            "&max_time=$maxTime" +
            COMMON_TAIL
        val url = "https://www.douyin.com/aweme/v1/web/user/following/list/?$params" +
            "&X-Bogus=" + try { XBogus.generate(params, UA) } catch (e: Exception) { "" }
        return try {
            val req = Request.Builder().url(url)
                .header("User-Agent", UA)
                .header("Referer", "https://www.douyin.com/follow")
                .header("Cookie", cookie)
                .header("Accept", "application/json, text/plain, */*")
                .build()
            val resp = exec(req)
            val body = resp.body()?.string() ?: ""
            if (resp.code() != 200) {
                lastError = "HTTP ${resp.code()}"
                return null
            }
            val r = JSONObject(body)
            if (r.optInt("status_code", -1) != 0) {
                lastError = "status_code=" + r.optInt("status_code", -1)
                return null
            }
            val arr = r.optJSONArray("followings") ?: return FollowPage(emptyList(), 0L, false)
            val out = ArrayList<FollowAuthor>(arr.length())
            for (i in 0 until arr.length()) {
                val o = arr.optJSONObject(i) ?: continue
                var su = o.optString("sec_uid")
                // ★ sec_uid 偶发缺失（尤其批量关注的老账号）→ 用 uid 兜底，
                //   否则这个作者会被静默丢掉，也是「35 只显示 30」的成因之一。
                if (su.isBlank()) su = o.optString("uid")
                if (su.isBlank()) continue
                out.add(
                    FollowAuthor(
                        secUid = su,
                        nickname = o.optString("nickname", "未知作者"),
                        uniqueId = o.optString("unique_id"),
                        avatarUrl = pickAvatar(o),
                        awemeCount = o.optInt("aweme_count", 0)
                    )
                )
            }
            val next = r.optLong("max_time", 0L)
            // ★ 恢复服务端 has_more 为准（默认 1），并保留 out 非空判断。
            //   上一版改成 `optInt("has_more",0)==1` —— 一旦某页服务端不带该字段
            //   （返回默认 0）就被误判成「到底了」，也是提前停在 21 的原因之一。
            val hasMore = r.optInt("has_more", 1) != 0 && out.isNotEmpty()
            // offset 被服务端忽略，不再用它推进；serverOffset 保留仅为兼容字段。
            val serverOff = r.optInt("offset", -1)
            FollowPage(out, next, hasMore, r.optLong("min_time", 0L), selfFollowingCount, serverOff)
        } catch (e: Exception) {
            lastError = "${e.javaClass.simpleName}: ${e.message}"
            Log.e(TAG, "fetchFollowing 异常", e)
            null
        }
    }

    /** 头像 URL：thumb → medium → larger 依次兜底 */
    private fun pickAvatar(o: JSONObject): String {
        val keys = arrayOf("avatar_thumb", "avatar_medium", "avatar_larger")
        for (k in keys) {
            val a = o.optJSONObject(k) ?: continue
            val list = a.optJSONArray("url_list") ?: continue
            for (i in 0 until list.length()) {
                val u = list.optString(i)
                if (u.startsWith("http")) return u
            }
        }
        return ""
    }

    private var selfUid = ""
    private var selfFollowingCount = 0
    private var selfSecUid = ""

    /** 取自己的 uid / sec_uid（带内存缓存，关注列表每次分页都用得着） */
    private fun fetchSelfId(cookie: String): Pair<String, String>? {
        try {
            val url = "https://www.douyin.com/aweme/v1/web/user/profile/self/?" +
                "device_platform=webapp&aid=6383&channel=channel_pc_web$COMMON_TAIL"
            val req = Request.Builder().url(url)
                .header("User-Agent", UA)
                .header("Referer", "https://www.douyin.com/follow")
                .header("Cookie", cookie)
                .build()
            val body = exec(req).body()?.string() ?: ""
            val u = JSONObject(body).optJSONObject("user") ?: return null
            selfUid = u.optString("uid")
            selfSecUid = u.optString("sec_uid")
            selfFollowingCount = u.optInt("following_count", u.optInt("following_count", 0))
            return selfUid to selfSecUid
        } catch (e: Exception) {
            Log.e(TAG, "fetchSelfId 异常", e)
            return null
        }
    }

    /**
     * 临时诊断：探测「我的关注」作者列表接口（供 ConfigServer /diagfollowing）。
     * 先取自己的 uid / sec_uid，再分别用三种参数组合打作者列表接口。
     */
    fun probeFollowing(cookie: String, offsetArg: Int = 0, countArg: Int = 20, mtArg: Long = 0L): String {
        val sb = StringBuilder()
        var uid = ""
        var secUid = ""
        // ① 自己的 uid / sec_uid
        try {
            val url = "https://www.douyin.com/aweme/v1/web/user/profile/self/?" +
                "device_platform=webapp&aid=6383&channel=channel_pc_web$COMMON_TAIL"
            val req = Request.Builder().url(url)
                .header("User-Agent", UA)
                .header("Referer", "https://www.douyin.com/follow")
                .header("Cookie", cookie)
                .header("Accept", "application/json, text/plain, */*")
                .build()
            val body = exec(req).body()?.string() ?: ""
            val r = JSONObject(body)
            val u = r.optJSONObject("user")
            uid = u?.optString("uid") ?: ""
            secUid = u?.optString("sec_uid") ?: ""
            sb.append("self → uid=$uid sec_uid=${secUid.take(20)}…\n")
        } catch (e: Exception) {
            sb.append("self → EX ${e.javaClass.simpleName}\n")
        }

        val base = "https://www.douyin.com/aweme/v1/web/user/following/list/"
        val tail = "device_platform=webapp&aid=6383&channel=channel_pc_web"
        val targets = linkedMapOf(
            "作者列表·uid+sec_uid" to "$base?$tail&user_id=$uid&sec_user_id=$secUid&offset=$offsetArg&count=$countArg$COMMON_TAIL",
            "作者列表·仅sec_uid" to "$base?$tail&sec_user_id=$secUid&offset=$offsetArg&count=$countArg$COMMON_TAIL",
            "作者列表·仅uid" to "$base?$tail&user_id=$uid&offset=$offsetArg&count=$countArg$COMMON_TAIL",
            "作者列表·count=50" to "$base?$tail&user_id=$uid&sec_user_id=$secUid&offset=$offsetArg&count=50$COMMON_TAIL"
        )
        var lastMaxTime = 0L
        for ((label, raw) in targets) {
            try {
                val xb = try { XBogus.generate(raw.substringAfter('?'), UA) } catch (e: Exception) { "" }
                val req = Request.Builder().url("$raw&X-Bogus=$xb")
                    .header("User-Agent", UA)
                    .header("Referer", "https://www.douyin.com/follow")
                    .header("Cookie", cookie)
                    .header("Accept", "application/json, text/plain, */*")
                    .build()
                val resp = exec(req)
                val body = resp.body()?.string() ?: ""
                val sum = StringBuilder("len=${body.length}")
                try {
                    val r = JSONObject(body)
                    sum.append(" st=").append(r.optInt("status_code", -1))
                    val fa = r.optJSONArray("followings") ?: r.optJSONArray("data")
                    sum.append(" n=").append(fa?.length() ?: 0)
                    sum.append(" more=").append(r.optInt("has_more", -1))
                    sum.append(" off=").append(r.optLong("offset", -1))
                    val first = fa?.optJSONObject(0)
                    if (first != null) {
                        sum.append(" 1st=").append(first.optString("nickname"))
                            .append(" sec=").append(first.optString("sec_uid").take(12))
                    }
                    // 诊断：看服务端到底给了哪些分页字段
                    sum.append(" keys=").append(r.keys().asSequence().take(12).joinToString(","))
                    sum.append(" mt=").append(r.optLong("max_time", 0))
                    if (lastMaxTime == 0L) lastMaxTime = r.optLong("max_time", 0)
                    if (fa != null && fa.length() > 0) {
                        val o = fa.optJSONObject(0)
                        if (o != null) sum.append(" item=")
                            .append(o.keys().asSequence().take(16).joinToString(","))
                    }
                } catch (_: Exception) {}
                sb.append(label).append(" → HTTP ").append(resp.code()).append(' ').append(sum)
                    .append(" | ").append(body.take(90).replace("\n", " ")).append('\n')
            } catch (e: Exception) {
                sb.append(label).append(" → EX ").append(e.javaClass.simpleName)
                    .append(": ").append(e.message).append('\n')
            }
        }
        // 第二页：用服务端回的 max_time 翻页（offset 被服务端忽略，实测无效）
        if (lastMaxTime > 0 || mtArg > 0) {
            val useMt = if (mtArg > 0) mtArg else lastMaxTime
            try {
                val p2 = "$tail&user_id=$uid&sec_user_id=$secUid&offset=0&count=$countArg&max_time=$useMt$COMMON_TAIL"
                val xb2 = try { XBogus.generate(p2, UA) } catch (e: Exception) { "" }
                val req2 = Request.Builder().url("$base?$p2&X-Bogus=$xb2")
                    .header("User-Agent", UA)
                    .header("Referer", "https://www.douyin.com/follow")
                    .header("Cookie", cookie)
                    .header("Accept", "application/json, text/plain, */*")
                    .build()
                val b2 = exec(req2).body()?.string() ?: ""
                val r2 = JSONObject(b2)
                val fa2 = r2.optJSONArray("followings")
                val n2 = fa2?.length() ?: 0
                val first2 = fa2?.optJSONObject(0)?.optString("nickname")
                sb.append("第二页·max_time=$useMt → n=$n2 more=")
                    .append(r2.optInt("has_more", -1))
                    .append(" mt=").append(r2.optLong("max_time", 0))
                    .append(" min=").append(r2.optLong("min_time", 0))
                    .append(" 1st=$first2\n")
            } catch (e: Exception) {
                sb.append("第二页·max_time → EX ${e.javaClass.simpleName}: ${e.message}\n")
            }
        }
        // 诊断：试移动端接口（老的手机接口历史上不校验签名，可能一次给全量）
        try {
            val mob = "https://aweme.snssdk.com/aweme/v1/user/following/list/?user_id=$uid" +
                    "&sec_user_id=$secUid&count=50&offset=0&max_time=0&retry_type=no_retry" +
                    "&with_sec=1&aid=1128&version_code=999&device_platform=android"
            val reqM = Request.Builder().url(mob)
                .header("User-Agent", "com.ss.android.ugc.aweme/999 (Linux; U; Android 10; zh_CN)")
                .header("Cookie", cookie).build()
            val bm = exec(reqM).body()?.string() ?: ""
            val rm = JSONObject(bm)
            val fam = rm.optJSONArray("followings")
            sb.append("移动端接口 → n=").append(fam?.length() ?: 0)
                .append(" more=").append(rm.optInt("has_more", -1))
                .append(" mt=").append(rm.optLong("max_time", 0))
                .append(" 1st=").append(fam?.optJSONObject(0)?.optString("nickname"))
                .append(" st=").append(rm.optInt("status_code", -1)).append('\n')
        } catch (e: Exception) {
            sb.append("移动端接口 → EX ${e.javaClass.simpleName}: ${e.message}\n")
        }
        return sb.toString()
    }

    private fun buildFollowParams(maxCursor: Long, count: Int): String {
        return "device_platform=webapp" +
                "&aid=6383" +
                "&channel=channel_pc_web" +
                "&max_cursor=$maxCursor" +
                "&count=$count" +
                "&filterGids=" +
                "&refer=1" +
                "&show_live_replay_strategy=1" +
                "&need_time_list=1" +
                "&time_list_query=0" +
                "&whale_cut_token=" +
                "&cut_version=1" +
                COMMON_TAIL
    }

    /**
     * 构建请求参数（顺序与签名一致，不可改动）
     */
    private fun buildParams(maxCursor: Long, count: Int): String {
        return "device_platform=webapp" +
                "&aid=6383" +
                "&channel=channel_pc_web" +
                "&aweme_id=0" +
                "&max_cursor=$maxCursor" +
                "&locate_query=false" +
                "&show_live_replay_strategy=1" +
                "&need_time_list=1" +
                "&time_list_query=0" +
                "&whale_cut_token=" +
                "&cut_version=1" +
                "&count=$count" +
                "&publish_video_strategy_type=2" +
                "&pc_client_type=1" +
                "&version_code=170400" +
                "&version_name=17.4.0" +
                "&cookie_enabled=true" +
                "&screen_width=1920" +
                "&screen_height=1080" +
                "&browser_language=zh-CN" +
                "&browser_platform=Win32" +
                "&browser_name=Chrome" +
                "&browser_version=122.0.0.0" +
                "&os_name=Windows" +
                "&os_version=10" +
                "&cpu_core_num=8" +
                "&device_memory=8" +
                "&platform=PC" +
                "&downlink=10" +
                "&effective_type=4g" +
                "&round_trip_time=50"
    }

    // ============================================================
    // 作者主页信息（含作品总数）
    // ============================================================
    //  ★ 为什么要这个：/user/following/list/ 返回的作者对象里 aweme_count 恒为 0
    //    （实测「宽广度TRIN」服务端只给 nickname/sec_uid/follower_count，
    //      aweme_count 缺字段 → optInt 默认 0）。于是关注列表一行显示「查看作品」，
    //    用户看到的就是「有作品却不显示数量」。
    //    真正带 aweme_count 的是 /user/profile/other/（实测 20）。这里按 sec_uid 补拉，
    //    结果进内存缓存，同一位作者只请求一次。
    data class UserProfile(
        val nickname: String,
        val awemeCount: Int,
        val followerCount: Int,
        val signature: String
    )

    private val profileCache = java.util.concurrent.ConcurrentHashMap<String, UserProfile>()

    fun cachedProfile(secUid: String): UserProfile? = profileCache[secUid]

    fun fetchUserProfile(cookie: String, secUid: String): UserProfile? {
        if (secUid.isBlank()) return null
        profileCache[secUid]?.let { return it }
        return try {
            val url = "https://www.douyin.com/aweme/v1/web/user/profile/other/?" +
                    "device_platform=webapp&aid=6383&channel=channel_pc_web" +
                    "&sec_user_id=$secUid" + COMMON_TAIL
            val req = Request.Builder().url(url)
                .header("User-Agent", UA)
                .header("Referer", "https://www.douyin.com/user/$secUid")
                .header("Cookie", cookie)
                .header("Accept", "application/json, text/plain, */*")
                .header("Accept-Language", "zh-CN,zh;q=0.9")
                .build()
            val resp = exec(req)
            val body = resp.body()?.string() ?: ""
            if (resp.code() != 200 || body.isEmpty()) {
                lastError = "作者信息 HTTP ${resp.code()}"
                return null
            }
            val u = JSONObject(body).optJSONObject("user") ?: return null
            val p = UserProfile(
                nickname = u.optString("nickname", ""),
                awemeCount = u.optInt("aweme_count", 0),
                followerCount = u.optInt("follower_count", 0),
                signature = u.optString("signature", "")
            )
            if (p.awemeCount > 0) profileCache[secUid] = p
            p
        } catch (e: Exception) {
            lastError = "作者信息: ${e.javaClass.simpleName}: ${e.message}"
            Log.e(TAG, "fetchUserProfile 异常", e)
            null
        }
    }

    // ============================================================
    // 作者主页作品  （实测：status_code=0, 20 条, max_cursor 有效）
    // ============================================================
    fun fetchUserPosts(cookie: String, secUid: String, maxCursor: Long = 0, count: Int = 20): FeedResult? {
        lastError = ""
        if (secUid.isBlank()) { lastError = "缺少作者 ID"; return null }
        // 作者主页不过「仅横屏」过滤：她/他有作品就必须能看到、能点进去播。
        // 之前全局开关把竖屏作品全滤掉 → 用户看到「实际有作品却显示没有」。
        // 另外某些账号 publish_video_strategy_type=2 会返回空，失败后用精简参数再试一次。
        val paramSets = listOf(
            "device_platform=webapp&aid=6383&channel=channel_pc_web" +
                    "&sec_user_id=$secUid&max_cursor=$maxCursor&locate_item_id=&count=$count" +
                    "&locate_query=false&show_live_replay_strategy=1&need_time_list=1&time_list_query=0" +
                    "&whale_cut_token=&cut_version=1&publish_video_strategy_type=2&from_user_page=1" +
                    "&update_version_code=170400" + COMMON_TAIL,
            // 兜底：网页 /user 页最朴素的写法（去掉策略/来源过滤参数）
            "device_platform=webapp&aid=6383&channel=channel_pc_web" +
                    "&sec_user_id=$secUid&max_cursor=$maxCursor&count=$count" +
                    "&from_user_page=1&update_version_code=170400" + COMMON_TAIL
        )
        var lastErr = ""
        for (params in paramSets) {
            try {
                val xb = XBogus.generate(params, UA)
                if (xb.isEmpty()) { lastErr = "签名失败"; continue }
                val req = Request.Builder()
                    .url("$USER_POST_URL?$params&X-Bogus=$xb")
                    .header("User-Agent", UA)
                    .header("Referer", "https://www.douyin.com/user/$secUid")
                    .header("Cookie", cookie)
                    .header("Accept", "application/json, text/plain, */*")
                    .header("Accept-Language", "zh-CN,zh;q=0.9")
                    .build()
                val resp = exec(req)
                val code = resp.code()
                val body = resp.body()?.string() ?: ""
                Log.d(TAG, "← 主页 HTTP $code body=${body.length}B")
                if (code != 200 || body.isEmpty()) {
                    lastErr = "主页 HTTP $code"
                    continue
                }
                val result = parseFeed(body, respectLandscape = false)
                if (result != null && result.videos.isNotEmpty()) return result
                lastErr = if (result == null) lastError else "主页 aweme_list 为空"
            } catch (e: Exception) {
                lastErr = "主页: ${e.javaClass.simpleName}: ${e.message}"
                Log.e(TAG, "fetchUserPosts 失败", e)
            }
        }
        lastError = lastErr
        return null
    }

    // ============================================================
    // 评论列表  （实测：status_code=0, 19 条评论）
    // ============================================================
    fun fetchComments(cookie: String, awemeId: String, cursor: Long = 0, count: Int = 20): List<CommentItem>? {
        lastError = ""
        return try {
            val params = "device_platform=webapp&aid=6383&channel=channel_pc_web" +
                    "&aweme_id=$awemeId&cursor=$cursor&count=$count&item_type=0" +
                    "&insert_ids=&whale_cut_token=&cut_version=1&rcFT=" + COMMON_TAIL
            val xb = XBogus.generate(params, UA)
            if (xb.isEmpty()) { lastError = "签名失败"; return null }
            val req = Request.Builder()
                .url("$COMMENT_URL?$params&X-Bogus=$xb")
                .header("User-Agent", UA)
                .header("Referer", "https://www.douyin.com/video/$awemeId")
                .header("Cookie", cookie)
                .header("Accept", "application/json, text/plain, */*")
                .header("Accept-Language", "zh-CN,zh;q=0.9")
                .build()
            val resp = exec(req)
            val code = resp.code()
            val body = resp.body()?.string() ?: ""
            Log.d(TAG, "← 评论 HTTP $code body=${body.length}B")
            if (code != 200 || body.isEmpty()) {
                lastError = "评论 HTTP $code"
                return null
            }
            // ★ 改用系统 org.json —— 项目红线禁止 Gson/Moshi。
            //   org.json 随 Android 框架提供，零依赖、零方法数开销。
            val root = JSONObject(body)
            val arr = root.optJSONArray("comments") ?: return emptyList()
            val out = mutableListOf<CommentItem>()
            for (i in 0 until arr.length()) {
                try {
                    val o = arr.optJSONObject(i) ?: continue
                    val userObj = o.optJSONObject("user")
                    // ★ 评论图片（images[].url_list[0]）
                    var imgUrl = ""
                    val imgs = o.optJSONArray("images")
                    if (imgs != null && imgs.length() > 0) {
                        val ul = imgs.optJSONObject(0)?.optJSONArray("url_list")
                        if (ul != null && ul.length() > 0) {
                            imgUrl = ul.optString(0, "").let { if (it.startsWith("//")) "https:$it" else it }
                        }
                    }
                    // ★ 评论视频封面（video.cover.url_list[0]）
                    var vidCover = ""
                    val cv = o.optJSONObject("video")?.optJSONObject("cover")?.optJSONArray("url_list")
                    if (cv != null && cv.length() > 0) {
                        vidCover = cv.optString(0, "").let { if (it.startsWith("//")) "https:$it" else it }
                    }
                    out.add(
                        CommentItem(
                            o.optString("text", ""),
                            userObj?.optString("nickname", "?") ?: "?",
                            o.optInt("digg_count", 0),
                            o.optInt("reply_comment_total", 0),
                            userObj?.optString("sec_uid", "") ?: "",
                            imgUrl,
                            vidCover
                        )
                    )
                } catch (_: Exception) {
                    // 单条解析失败不影响整页评论
                }
            }
            Log.d(TAG, "✓ 评论 ${out.size} 条")
            out
        } catch (e: Exception) {
            lastError = "评论: ${e.javaClass.simpleName}: ${e.message}"
            Log.e(TAG, "fetchComments 失败", e)
            null
        }
    }

    // ============================================================
    // 热搜榜  （★ 实测匿名可用：不需要 Cookie、status_code=0）
    // ============================================================
    // 这是本项目唯一无需登录态就能拿到真实内容的接口。
    // 搜索接口（general/search/single、search/item）需要 a_bogus 签名，
    // X-Bogus 一律被拦（status_code=2483），故搜索页以热搜词为入口。
    private const val HOT_URL = "https://www.douyin.com/aweme/v1/web/hot/search/list/"

    /** 一条热搜 */
    data class HotWord(val word: String, val hotValue: Long, val groupId: String = "")

    /** 热搜榜公共参数（与推荐流同一风格，顺序敏感） */
    private const val HOT_HEAD =
        "device_platform=webapp&aid=6383&channel=channel_pc_web" +
        "&pc_client_type=1&version_code=170400&version_name=17.4.0" +
        "&cookie_enabled=true&screen_width=1920&screen_height=1080" +
        "&browser_language=zh-CN&browser_platform=Win32" +
        "&browser_name=Chrome&browser_version=122.0.0.0" +
        "&os_name=Windows&os_version=10&cpu_core_num=8&device_memory=8&platform=PC" +
        "&downlink=10&effective_type=4g&round_trip_time=50"

    /**
     * 拉热搜榜。
     * @return null = 请求失败（lastError 有原因）；空列表 = 接口通但无数据
     */
    fun fetchHotSearch(): List<HotWord>? {
        lastError = ""
        try {
            val params = HOT_HEAD + "&detail_list=1"
            val xb = XBogus.generate(params, UA)
            if (xb.isEmpty()) { lastError = "签名失败"; return null }

            val req = Request.Builder()
                .url("$HOT_URL?$params&X-Bogus=$xb")
                .header("User-Agent", UA)
                .header("Referer", "https://www.douyin.com/")
                .header("Accept", "application/json, text/plain, */*")
                .header("Accept-Language", "zh-CN,zh;q=0.9")
                .build()

            val resp = exec(req)
            val code = resp.code()
            val body = resp.body()?.string() ?: ""
            Log.d(TAG, "← 热搜 HTTP $code body=${body.length}B")
            if (code != 200 || body.isEmpty()) {
                lastError = "热搜 HTTP $code"
                return null
            }

            val root = JSONObject(body)
            val data = root.optJSONObject("data") ?: return emptyList()
            val arr = data.optJSONArray("word_list") ?: return emptyList()

            val out = mutableListOf<HotWord>()
            for (i in 0 until arr.length()) {
                try {
                    val o = arr.optJSONObject(i) ?: continue
                    val w = o.optString("word", "")
                    if (w.isEmpty()) continue
                    out.add(HotWord(w, o.optLong("hot_value", 0L), o.optString("group_id", "")))
                } catch (_: Exception) {
                    // 单条失败不影响整榜
                }
            }
            Log.d(TAG, "✓ 热搜 ${out.size} 条")
            return out
        } catch (e: Exception) {
            lastError = "热搜: ${e.javaClass.simpleName}: ${e.message}"
            Log.e(TAG, "fetchHotSearch 失败", e)
            return null
        }
    }

    // ============================================================
    // 搜索（a_bogus 签名）★ 移植 ABogus.kt 后新增
    // ============================================================
    // 实测结论（本地 Python 端用同一算法验证）：
    //   · 热搜榜 + a_bogus         → status_code=0  可用
    //   · 搜索建议 search/sug      → status_code=0  可用 ★
    //   · 综合搜索 general/search  → status_code=2483（需「有效登录态 + msToken + a_bogus」）
    // 所以搜索走「先真接口、失败自动降级」的渐进增强路线，不做假入口。
    private const val SEARCH_URL = "https://www.douyin.com/aweme/v1/web/general/search/single/"
    private const val SUG_URL = "https://www.douyin.com/aweme/v1/web/search/sug/"

    private const val SEARCH_HEAD =
        "device_platform=webapp&aid=6383&channel=channel_pc_web" +
        "&pc_client_type=1&version_code=170400&version_name=17.4.0" +
        "&cookie_enabled=true&screen_width=1920&screen_height=1080" +
        "&browser_language=zh-CN&browser_platform=Win32" +
        "&browser_name=Chrome&browser_version=122.0.0.0" +
        "&os_name=Windows&os_version=10&cpu_core_num=8&device_memory=8&platform=PC"

    /** 上次搜索是否打到了真实接口（用于界面如实提示数据来源） */
    @Volatile
    var lastSearchReal = false
        private set

    /**
     * 搜索联想词。实测不需要任何签名、也不需要登录态即可拿到候选。
     * @return null 表示请求失败
     */
    fun fetchSuggest(keyword: String): List<String>? {
        try {
            val kw = URLEncoder.encode(keyword, "UTF-8")
            // ★ 与搜索同理：不带任何签名（实测可用；带签名反而可能触发校验）
            val params = SEARCH_HEAD + "&keyword=" + kw + "&count=10"
            val req = Request.Builder()
                .url(SUG_URL + "?" + params)
                .header("User-Agent", UA)
                .header("Referer", "https://www.douyin.com/")
                .header("Accept", "application/json, text/plain, */*")
                .build()
            val body = exec(req).body()?.string() ?: ""
            if (body.isEmpty()) return null
            val root = JSONObject(body)
            if (root.optInt("status_code", -1) != 0) {
                Log.d(TAG, "联想词 status_code=${root.optInt("status_code")}")
                return null
            }
            val out = mutableListOf<String>()
            val arr = root.optJSONArray("sug_list")
                ?: root.optJSONArray("data") ?: return emptyList()
            for (i in 0 until arr.length()) {
                val o = arr.optJSONObject(i) ?: continue
                val w = o.optString("content", "").ifEmpty { o.optString("word", "") }
                if (w.isNotEmpty()) out.add(w)
            }
            Log.d(TAG, "✓ 联想词 ${out.size} 条")
            return out
        } catch (e: Exception) {
            Log.e(TAG, "fetchSuggest 失败", e)
            return null
        }
    }

    /**
     * 综合搜索（真实接口）★★ 关键：不要带任何签名 ★★
     *
     * 本地实测（同一登录 Cookie、同一参数集，三组对照）：
     *   · 不带签名            → status_code=0，10 条真实结果   ✅
     *   · 带空 a_bogus=       → search_nil_type=verify_check，0 条 ❌
     *   · 带任意 a_bogus 值   → search_nil_type=verify_check，0 条 ❌
     *   · 不带 Cookie         → status_code=2483，0 条          ❌
     *
     * 也就是说：抖音对「声明了签名但签不对」的请求反而更严格；干净的不带签名
     * 请求会按登录态放行。所以这里绝不加 a_bogus / X-Bogus —— 加了就是自找
     * verify_check。（这个结论推翻了此前"必须自产 a_bogus"的判断。）
     *
     * @return null = 请求失败或没有结果，调用方降级处理
     */
    fun searchVideos(cookie: String, keyword: String, offset: Int = 0): FeedResult? {
        lastSearchReal = false
        lastError = ""
        try {
            val kw = URLEncoder.encode(keyword, "UTF-8")
            val params = SEARCH_HEAD +
                "&search_channel=aweme_general&enable_history=1&keyword=" + kw +
                "&search_source=normal_search&query_correct_type=1&is_filter_search=0" +
                "&offset=" + offset + "&count=20"

            val req = Request.Builder()
                .url(SEARCH_URL + "?" + params)
                .header("User-Agent", UA)
                .header("Referer", "https://www.douyin.com/")
                .header("Accept", "application/json, text/plain, */*")
                .header("Accept-Language", "zh-CN,zh;q=0.9")
                .apply { if (cookie.isNotEmpty()) header("Cookie", cookie) }
                .build()

            val resp = exec(req)
            val body = resp.body()?.string() ?: ""
            Log.d(TAG, "← 搜索 HTTP ${resp.code()} body=${body.length}B")
            if (body.isEmpty()) {
                lastError = "搜索响应为空（Cookie 可能失效）"
                return null
            }

            val root = JSONObject(body)
            val sc = root.optInt("status_code", -1)
            if (sc != 0) {
                lastError = "搜索 status_code=$sc" + (if (sc == 2483) "（需要登录 Cookie）" else "")
                Log.d(TAG, "搜索被拒: $lastError")
                return null
            }
            val nilInfo = (root.optJSONObject("search_nil_info") ?: JSONObject())
                .optString("search_nil_type", "")
            if (nilInfo == "verify_check") {
                lastError = "触发风控 verify_check（Cookie 需更新）"
                return null
            }

            val arr = root.optJSONArray("data") ?: return FeedResult(emptyList(), 0L, false)
            val wrapped = JSONArray()
            for (i in 0 until arr.length()) {
                try {
                    val aw = arr.optJSONObject(i)?.optJSONObject("aweme_info") ?: continue
                    wrapped.put(aw)
                } catch (_: Exception) {
                }
            }
            if (wrapped.length() == 0) {
                lastError = "搜索无结果"
                return null
            }
            val fake = JSONObject()
            fake.put("status_code", 0)
            fake.put("aweme_list", wrapped)
            val fr = parseFeed(fake.toString())
            lastSearchReal = fr != null && fr.videos.isNotEmpty()
            Log.d(TAG, "✓ 搜索「$keyword」${fr?.videos?.size ?: 0} 条")
            return fr
        } catch (e: Exception) {
            lastError = "搜索失败: ${e.javaClass.simpleName}"
            Log.e(TAG, "searchVideos 失败", e)
            return null
        }
    }

    /**
     * 从 video.bit_rate[] 里挑【码率最低】的一档，返回 (主链, 该档其余地址)。
     *
     * 为什么需要它：URL 上的 ratio 参数实测被抖音忽略（五种取值返回同一文件），
     * 唯一能真正降码率的就是 bit_rate 各档自己的 play_addr。
     * 老设备上把 2.79Mbps 换成 0.96Mbps，是「能不能流畅播」的分水岭。
     *
     * @return null 表示这条视频没有 bit_rate 数组，调用方走原策略
     */
    /** 诊断用：最近一次选中档的码率与档名（播放页把它打在屏幕上，用来判断是否网络喂不饱） */
    object Diag {
        @Volatile var lastBitrate: Int = 0
        @Volatile var lastGear: String = ""
    }

    /**
     * 从 video.bit_rate[] 里挑一档，返回 (主链, 该档其余地址)。
     *
     * ★★★ 必须跳过 HEVC 档 ★★★（真机实测得出的结论）
     *   设备：创维 8S52 / MStar "nike" 平台 / Android 4.2。
     *   它的硬解清单里只有 OMX.MS.AVC.Decoder(H.264)、MVC、VP8、VC1、AVS……
     *   —— 没有任何 HEVC 解码器。
     *   而抖音同一条视频的不同档编码并不一致（接口里 is_h265 / is_bytevc1 标明）：
     *       540_3_1       0.96Mbps → HEVC(hvc1)   ← 之前"选最低码率"正好挑中它
     *       normal_1080_0 4.57Mbps → H.264(avc1)
     *   真机 logcat 铁证（选到 HEVC 档时的表现）：
     *       E/IJKMEDIA: mediacodec_select_callback: onSelectCodec failed
     *       E/IJKMEDIA: amc: no suitable codec
     *       I/IJKMEDIA: VideoCodec: avcodec, hevc     ← 回落 FFmpeg 软解
     *       结果就是卡 + 音画不同步（软解 HEVC 在四核 A7 上根本追不上音频）。
     *   所以：只在 H.264 档里挑最低码率；全为 HEVC 时才退回不限编码（至少能播）。
     *
     * 另：接口参数 support_h265=0 实测无效（加了返回内容一模一样），
     *     真正的开关就是这里的 is_h265 字段。
     *
     * @return null 表示没有 bit_rate 数组，调用方走原策略
     */
    /**
     * 选播放地址。返回 (主链, 备用链列表)；null 表示没有 bit_rate，调用方走原策略。
     *
     * 三条真机验证过的规则（创维 8S52 / MStar nike / Android 4.2）：
     *
     * ① 必须避开 HEVC —— 该机硬解只有 OMX.MS.AVC.Decoder(H.264)，没有 HEVC。
     *    选到 HEVC 档时的 logcat：
     *        E/IJKMEDIA: amc: no suitable codec
     *        I/IJKMEDIA: VideoCodec: avcodec, hevc   ← 回软解，卡 + 音画不同步
     *    接口里 is_h265 / is_bytevc1 标明编码，据此过滤。
     *
     * ② 必须避开「纯视频流」—— 这是"大部分视频没声音"的根因。
     *    bit_rate[] 各档的 play_addr 有两种形态：
     *        .../media-video-avc1/?...   →  只有视频轨！用了就没声音
     *        .../media-audio-und-mp4a/   →  只有音频轨
     *    而主链 play_addr 是音视频复用的（探测确认含 AAC 音轨）。
     *    真机日志（选到纯视频档时）：
     *        V/IJKMEDIA: setDataSource: path .../media-video-avc1/?...&br=428
     *    所以优先挑「音视频复用」的地址，其次主链兜底。
     *
     * ③ 码率仍取较低者（省带宽+省解码），但只在满足①②的候选里比。
     */
    private fun pickLowestBitrate(videoObj: JSONObject): Pair<String, List<String>>? {
        val arr = videoObj.optJSONArray("bit_rate") ?: return null
        if (arr.length() == 0) return null

        fun urlsOf(o: JSONObject): List<String> {
            val ul = o.optJSONArray("url_list") ?: return emptyList()
            val out = ArrayList<String>()
            for (k in 0 until ul.length()) {
                var u = ul.optString(k, "")
                if (u.startsWith("//")) u = "https:$u"
                if (u.startsWith("http")) out.add(u)
            }
            return out
        }

        // 是否纯视频流（无音轨的 URL）
        fun isVideoOnly(us: List<String>): Boolean =
            us.isNotEmpty() && us[0].contains("media-video-")
        fun isAudioOnly(us: List<String>): Boolean =
            us.isNotEmpty() && us[0].contains("media-audio-")

        var vidRate = Int.MAX_VALUE; var vidUrls: List<String>? = null; var vidGear = ""
        val muxed = mutableListOf<Triple<String, Int, List<String>>>()  // (档名, 码率, 地址) —— H.264 复用档

        for (i in 0 until arr.length()) {
            val g = arr.optJSONObject(i) ?: continue
            val br = g.optInt("bit_rate", Int.MAX_VALUE)
            if (br <= 0) continue
            // ① 跳过 HEVC：本机硬解不支持（真机结论，详见上方大段注释）
            if (g.optInt("is_h265", 0) == 1 || g.optInt("is_bytevc1", 0) == 1) continue
            val us = urlsOf(g.optJSONObject("play_addr") ?: continue)
            if (us.isEmpty() || isAudioOnly(us)) continue
            val gear = g.optString("gear_name", "")
            if (isVideoOnly(us)) {
                // ② 纯视频流：能硬解但没声音，只作最后兜底
                if (br < vidRate) { vidRate = br; vidUrls = us; vidGear = gear }
            } else {
                muxed.add(Triple(gear, br, us))
            }
        }

        // ③ 首选音视频复用；主链 play_addr 兜底；纯视频最后（画面能动）
        val main = urlsOf(videoObj.optJSONObject("play_addr") ?: JSONObject())

        // ★ 用户要求：1080P 起步 + 分档可调。
        //   历史教训：82.x 引入的「isLegacy 一刀切降 540P」是错的 ——
        //   创维 8S52 有 AVC 硬解，真机验证 normal_1080_0（4.57Mbps H.264）流畅且清晰；
        //   而 540P HEVC/软解 = 卡顿 + 音画漂移（Engines.kt 315 行注释自证）。
        //   档位偏好 Prefs.qualityGear："1080"(默认) / "720" / "480" / "auto"(码率最高可用档)
        val pref = Prefs.getQualityGear()
        val sorted = muxed.sortedByDescending { it.second }
        val chosen = when (pref) {
            "auto" -> sorted.firstOrNull()
            else -> sorted.firstOrNull { it.first.contains(pref) }
                ?: sorted.firstOrNull { it.first.contains(if (pref == "480") "540" else "1080") }
                ?: sorted.firstOrNull()
        }

        val pick: List<String>
        val gear: String
        val rate: Int
        if (chosen != null) {
            pick = chosen.third; gear = chosen.first; rate = chosen.second
        } else if (main.isNotEmpty()) {
            pick = main; gear = "主链"; rate = 0
        } else if (vidUrls != null) {
            pick = vidUrls; gear = vidGear + "(纯视频,无声)"; rate = vidRate
        } else {
            return null
        }

        Diag.lastBitrate = rate
        Diag.lastGear = gear
        Log.d(TAG, "选档: " + gear + " " + rate + "bps 主链=" + pick.first().take(70))
        return pick.first() to (pick.drop(1) + main).distinct().filter { it != pick.first() }
    }

    /**
     * @param respectLandscape 是否应用「仅横屏」过滤。
     *   信息流默认 true；作者主页/单视频详情传 false —— 那是用户明确要看的内容，
     *   不该被全局开关悄悄藏掉（否则就会出现「TA 明明有作品，这里却说没有」）。
     */
    private fun parseFeed(json: String, respectLandscape: Boolean = true): FeedResult? {
        try {
            // ★ 系统 org.json（红线禁 Gson）
            val root = JSONObject(json)

            val status = root.optInt("status_code", -1)
            Log.d(TAG, "status_code=$status")
            if (status != 0) {
                val msg = root.optString("status_msg", "")
                lastError = "抖音 status_code=$status, msg=$msg"
                Log.e(TAG, "✗ 状态码异常: $status, msg=$msg")
                return null
            }

            val list = root.optJSONArray("aweme_list")
            if (list == null || list.length() == 0) {
                lastError = "aweme_list 为空（Cookie 可能被风控）"
                Log.e(TAG, "✗ aweme_list 为空")
                return null
            }

            // ★ 横屏过滤开关（用户要求）：竖屏视频在电视上左右大片黑边
            val landscapeOnly = respectLandscape && App.landscapeOnly()
            val skipped = intArrayOf(0)

            val videos = mutableListOf<VideoItem>()
            for (i in 0 until list.length()) {
                try {
                    val item = parseItem(list.optJSONObject(i) ?: continue, landscapeOnly, skipped)
                    if (item != null) videos.add(item)
                } catch (e: Exception) {
                    // 单条失败不影响整页
                    Log.w(TAG, "解析单条失败: ${e.message}")
                }
            }

            if (skipped[0] > 0) {
                Log.d(TAG, "横屏过滤：跳过 ${skipped[0]} 条竖屏视频")
            }

            val maxCursor = root.optLong("max_cursor", 0)
            val hasMore = root.optInt("has_more", 0) == 1
            Log.d(TAG, "✓ 解析 ${videos.size} 条视频, has_more=$hasMore, max_cursor=$maxCursor")
            return FeedResult(videos, maxCursor, hasMore)
        } catch (e: Exception) {
            Log.e(TAG, "parseFeed 异常: ${e.message}", e)
            return null
        }
    }

    /**
     * 关注流专用解析：返回体不是 aweme_list，而是
     * {"cursor":…, "has_more":1, "data":[{"aweme":{…}}, …]} ——
     * 每条包在 data[i].aweme 里，字段结构与推荐流一致，复用 parseItem。
     */
    private fun parseFollowFeed(json: String): FeedResult? {
        try {
            val root = JSONObject(json)
            val status = root.optInt("status_code", -1)
            if (status != 0) {
                lastError = "抖音 status_code=$status, msg=" + root.optString("status_msg", "")
                Log.e(TAG, "✗ 关注流状态码异常: $status")
                return null
            }
            val data = root.optJSONArray("data")
            if (data == null) {
                lastError = "关注流返回结构里没有 data"
                Log.e(TAG, "✗ 关注流无 data 字段")
                return null
            }

            val landscapeOnly = App.landscapeOnly()
            val skipped = intArrayOf(0)
            val videos = mutableListOf<VideoItem>()
            for (i in 0 until data.length()) {
                try {
                    val wrap = data.optJSONObject(i) ?: continue
                    // 兼容两种写法：data[i].aweme，或 data[i] 本身就是 aweme
                    val obj = wrap.optJSONObject("aweme") ?: wrap
                    val item = parseItem(obj, landscapeOnly, skipped) ?: continue
                    videos.add(item)
                } catch (e: Exception) {
                    Log.w(TAG, "关注流单条解析失败: ${e.message}")
                }
            }

            val maxCursor = root.optLong("cursor", root.optLong("max_cursor", 0))
            val hasMore = root.optInt("has_more", 0) == 1
            Log.d(TAG, "✓ 关注流解析 ${videos.size} 条, has_more=$hasMore, cursor=$maxCursor, 过滤竖屏=${skipped[0]}")
            return FeedResult(videos, maxCursor, hasMore)
        } catch (e: Exception) {
            Log.e(TAG, "parseFollowFeed 异常: ${e.message}", e)
            return null
        }
    }

    /**
     * 解析单条 aweme。返回 null = 该条不可用（无播放地址 / 被横屏开关滤掉）。
     * @param skipped 出参：累计被竖屏过滤跳过的条数
     */
    private fun parseItem(obj: JSONObject, landscapeOnly: Boolean, skipped: IntArray): VideoItem? {
        val awemeId = obj.optString("aweme_id", "")
        if (awemeId.isEmpty()) return null
        val desc = obj.optString("desc", "")
        val authorObj = obj.optJSONObject("author")
        val author = authorObj?.optString("nickname", "") ?: ""
        val secUid = authorObj?.optString("sec_uid", "") ?: ""
        val stats = obj.optJSONObject("statistics")
        val digg = stats?.optInt("digg_count", 0) ?: 0
        val cmtCount = stats?.optInt("comment_count", 0) ?: 0

        // 话题标签（text_extra 里 type=1 的是话题）
        val topics = mutableListOf<String>()
        val te = obj.optJSONArray("text_extra")
        if (te != null) {
            for (k in 0 until te.length()) {
                val n = te.optJSONObject(k)?.optString("hashtag_name", "") ?: ""
                if (n.isNotEmpty() && n !in topics) topics.add(n)
            }
        }

        // ★★★ 图文/图集作品支持 ★★★
        // 抖音的「图文」作品（用户说的"图表类"）没有真正视频流：aweme 顶层带
        // images[] 数组，video 对象要么缺失、要么 play_addr 为空。旧版这里直接
        // return null → 作者主页里这类作品全被丢弃 → 表现为"有作品却显示没有/不能播放"。
        // 现在先把图片解析出来：无播放地址但有图 → 构造图文条目，播放器走图片轮播。
        val imgUrls = ArrayList<String>()
        val imagesArr = obj.optJSONArray("images")
        if (imagesArr != null) {
            for (k in 0 until imagesArr.length()) {
                val im = imagesArr.optJSONObject(k) ?: continue
                // ★ url_list 一般按「小 → 大」排列，电视上必须取最大那张（末尾），
                //   取 [0] 会拿到缩略图，轮播时糊成一片。
                val ul = im.optJSONArray("url_list") ?: im.optJSONArray("download_url_list")
                var u = if (ul != null && ul.length() > 0) ul.optString(ul.length() - 1, "")
                        else im.optString("url", "")
                if (u.startsWith("//")) u = "https:$u"
                if (u.startsWith("http") && u !in imgUrls) imgUrls.add(u)
            }
        }

        val videoObj = obj.optJSONObject("video")

        // ★ 横屏过滤：视频真实宽高在 video.width / video.height；图文取首张图尺寸
        var vw = videoObj?.optInt("width", 0) ?: 0
        var vh = videoObj?.optInt("height", 0) ?: 0
        if ((vw <= 0 || vh <= 0) && imgUrls.isNotEmpty()) {
            val im0 = imagesArr?.optJSONObject(0)
            vw = im0?.optInt("width", 0) ?: 0
            vh = im0?.optInt("height", 0) ?: 0
        }
        if (landscapeOnly && vw > 0 && vh > 0 && vh > vw) {
            skipped[0]++
            return null
        }

        val awemeType = obj.optInt("aweme_type", 0)
        val playAddr = videoObj?.optJSONObject("play_addr")
        val urlList = playAddr?.optJSONArray("url_list")
        val dur = videoObj?.optInt("duration", 0) ?: 0

        // ★★★ 图文作品（aweme_type = 68）★★★
        //   实测「宽广度TRIN」整页 20 条全是 type=68：带 images[]，但 video.play_addr
        //   里同时还有 1~2 条「伪视频」地址、duration=0。旧逻辑只要 play_addr 非空
        //   就当视频播 → 拿到一条时长为 0 的流，播放器起不来，表现正是
        //   「关注列表里明明有作品，点进去却播不了」。现在只要带图且（type=68 或 时长为 0）
        //   就一律走图片轮播，不再尝试这条假视频链。
        if (imgUrls.isNotEmpty() && (awemeType == 68 || dur <= 0)) {
            return VideoItem(
                awemeId, desc, author, "", imgUrls[0], 0, secUid, digg, cmtCount,
                topics, emptyList(), isPortrait = vh > vw,
                imageUrls = imgUrls, createTime = obj.optLong("create_time", 0L)
            )
        }
        // 无播放地址（且已在上面处理过带图的）→ 丢弃
        if (urlList == null || urlList.length() == 0) return null

        // 走到这里 urlList 非空 ⇒ videoObj 必非空，取非空引用供后续使用
        val vobj = videoObj ?: return null

        // 选链策略（保守，回到用户认可的那版行为）：
        //   **优先 /aweme/v1/play/** —— 它是接口 URL，
        //   每次请求都 302 到**新鲜**的 CDN 地址；
        //   而 url_list 里的直链是**带时效签名**的，放久了会失效
        //   （这正是"刷一会儿就不放了"的来源）。
        //   其余链接留作备用，供用户手动「换线路」。
        val candidates = ArrayList<String>()
        for (k in 0 until urlList.length()) {
            var u = urlList.optString(k, "")
            if (u.startsWith("//")) u = "https:$u"
            if (u.startsWith("http")) candidates.add(u)
        }
        if (candidates.isEmpty()) return null

        // ★★★ 老设备流畅度关键：从 bit_rate 里选【最低码率档】★★★
        //   为什么不是改 URL 里的 ratio：实测无效 ——
        //   同一条视频用 ratio=1080p/720p/540p/480p/360p 去请求，
        //   抖音返回的是【同一个 42.61MB 的文件】，它根本不看这个参数。
        //   真正能换清晰度的只有 video.bit_rate[] 里各档自带的 play_addr：
        //   实测同一条视频有 20 档，码率从 0.96Mbps 到 4.57Mbps
        //   （体积 15.4MB ~ 73MB）。老盒子（Cortex-A7 软解 + 小内存）
        //   拉 2.79Mbps 那档必然卡；换最低档等于把带宽和解码压力同时砍到 1/3。
        //   代价是画质下降 —— 但「能流畅播」优先于「更清晰」。
        //   原始高清链仍留在备用线路里，用户按「换线路」可以切回去。
        val lowPick = pickLowestBitrate(vobj)

        val videoUrl: String
        val altUrls: List<String>
        if (lowPick != null) {
            videoUrl = lowPick.first
            altUrls = (lowPick.second + candidates)
                .distinct().filter { it != videoUrl }
        } else {
            // 没有 bit_rate 的（老视频等）退回原来的候选策略
            videoUrl = candidates.firstOrNull { it.contains("/aweme/v1/play/") }
                ?: candidates.firstOrNull {
                    it.contains("douyinvod.com") && !it.contains("web-prime")
                } ?: candidates.first()
            altUrls = candidates.filter { it != videoUrl }
        }

        val cover = videoObj?.optJSONObject("cover")
            ?.optJSONArray("url_list")
            ?.let { if (it.length() > 0) it.optString(0, "") else "" }
            ?: imgUrls.firstOrNull() ?: ""
        val duration = videoObj?.optInt("duration", 0) ?: 0

        return VideoItem(awemeId, desc, author, videoUrl, cover, duration,
                         secUid, digg, cmtCount, topics, altUrls,
                         isPortrait = vh > vw, createTime = obj.optLong("create_time", 0L))
    }

    // ============================================================
    // 手机推送到电视：分享链接解析 + 单视频详情
    // ============================================================
    /** 从分享文本里抠出首个 http(s) 链接（v.douyin.com 短链 / www.douyin.com 长链都行） */
    fun extractShareUrl(raw: String): String? {
        val m = Regex("""https?://[^\s，。；、)）】】]+""").find(raw) ?: return null
        return m.value.trim()
    }

    /** 跟随重定向取最终地址（OkHttp 自动跟随 302；短链 v.douyin.com → www.douyin.com/video/…） */
    fun resolveShareUrl(url: String): String {
        return try {
            val req = Request.Builder().url(url)
                .header("User-Agent", UA)
                .header("Referer", "https://www.douyin.com/")
                .build()
            val resp = exec(req)
            val finalUrl = resp.request().url().toString()
            resp.close()
            Log.d(TAG, "分享链接解析: $url → $finalUrl")
            finalUrl
        } catch (e: Exception) {
            Log.e(TAG, "分享链接解析失败: ${e.message}")
            url
        }
    }

    /** 从最终 URL 里抠 aweme_id（/video/、/note/、/share/video/、aweme_id= 均支持） */
    fun extractAwemeId(url: String): String? {
        for (pat in listOf(
            Regex("""/(?:video|note|share/video|share/note)/(\d{5,})"""),
            Regex("""aweme_id=(\d{5,})"""),
            Regex("""modal_id=(\d{5,})""")
        )) {
            pat.find(url)?.let { return it.groupValues[1] }
        }
        return null
    }

    /** 按 aweme_id 拉单条视频详情（复用 parseFeed 的条目解析） */
    fun fetchVideoDetail(cookie: String, awemeId: String): VideoItem? {
        lastError = ""
        return try {
            val params = "device_platform=webapp&aid=6383&channel=channel_pc_web" +
                "&aweme_id=$awemeId" + COMMON_TAIL
            val xb = try {
                XBogus.generate(params, UA)
            } catch (e: Exception) {
                lastError = "签名失败: ${e.message}"
                return null
            }
            val fullUrl = "https://www.douyin.com/aweme/v1/web/aweme/detail?$params&X-Bogus=$xb"
            Log.d(TAG, "→ detail aweme_id=$awemeId")
            val req = Request.Builder()
                .url(fullUrl)
                .header("User-Agent", UA)
                .header("Referer", "https://www.douyin.com/")
                .header("Cookie", cookie)
                .header("Accept", "application/json, text/plain, */*")
                .build()
            val resp = exec(req)
            val code = resp.code()
            val body = resp.body()?.string() ?: ""
            Log.d(TAG, "← detail HTTP $code len=${body.length}")
            if (code != 200 || body.isEmpty()) {
                lastError = "detail HTTP $code"
                return null
            }
            val root = JSONObject(body)
            val detail = root.optJSONObject("aweme_detail")
                ?: run {
                    lastError = "无 aweme_detail: st=${root.optInt("status_code", -1)} " +
                        "${root.optString("status_msg", "")}"
                    Log.w(TAG, "detail 无 aweme_detail: $lastError")
                    return null
                }
            val wrapped = JSONObject()
                .put("status_code", 0)
                .put("aweme_list", JSONArray().put(detail))
            // ★ 关闭「仅横屏」过滤 ★
            //   详情接口取回的这一条若是竖屏，旧代码会因 landscapeOnly 把它滤成 null，
            //   上层据此把 detailDisabled 永久置位 —— 一条竖屏旧记录就把快速通道废掉，
            //   从此所有旧记录只能走慢速的作者作品列表翻页。刷新地址必须尊重原始比例。
            val r = parseFeed(wrapped.toString(), respectLandscape = false)?.videos?.firstOrNull()
            if (r == null) Log.w(TAG, "detail 解析失败: $lastError")
            r
        } catch (e: Exception) {
            lastError = "${e.javaClass.simpleName}: ${e.message}"
            Log.w(TAG, "detail 异常: $lastError")
            null
        }
    }

    /**
     * 临时诊断：视频详情接口（刷新过期直链要靠它）。
     * 实测现状：HTTP 200 但 body 为空 → 签名/参数/路径有一处不对。
     */
    fun probeDetail(cookie: String, awemeId: String): String {
        val sb = StringBuilder()
        val q = "device_platform=webapp&aid=6383&channel=channel_pc_web&aweme_id=$awemeId$COMMON_TAIL"
        val targets = linkedMapOf(
            "detail·无斜杠" to "https://www.douyin.com/aweme/v1/web/aweme/detail?$q",
            "detail·有斜杠" to "https://www.douyin.com/aweme/v1/web/aweme/detail/?$q",
            "detail·有斜杠+Origin" to "https://www.douyin.com/aweme/v1/web/aweme/detail/?$q",
            "detail·post接口(作者作品)" to "https://www.douyin.com/aweme/v1/web/aweme/post?$q"
        )
        for ((label, raw) in targets) {
            try {
                val xb = try { XBogus.generate(raw.substringAfter('?'), UA) } catch (e: Exception) { "" }
                val b = Request.Builder().url("$raw&X-Bogus=$xb")
                    .header("User-Agent", UA)
                    .header("Referer", "https://www.douyin.com/")
                    .header("Cookie", cookie)
                    .header("Accept", "application/json, text/plain, */*")
                    .apply { if (label.contains("Origin")) header("Origin", "https://www.douyin.com") }
                    .build()
                val resp = exec(b)
                val body = resp.body()?.string() ?: ""
                val sum = StringBuilder("len=${body.length}")
                try {
                    val r = JSONObject(body)
                    sum.append(" st=").append(r.optInt("status_code", -1))
                        .append(" detail=").append(r.optJSONObject("aweme_detail") != null)
                        .append(" list=").append(r.optJSONArray("aweme_list")?.length() ?: -1)
                } catch (_: Exception) {}
                sb.append(label).append(" → HTTP ").append(resp.code()).append(' ').append(sum)
                    .append(" | ").append(body.take(70).replace("\n", " ")).append('\n')
            } catch (e: Exception) {
                sb.append(label).append(" → EX ").append(e.javaClass.simpleName).append('\n')
            }
        }
        return sb.toString()
    }

    /**
     * 作者作品列表的会话内缓存（按 sec_uid）。
     *
     * 为什么需要：同一作者常有好几条视频要取地址，逐条去翻作者作品
     * 会把同一次请求重复做很多遍（每页 1~3 秒）。缓存后同一作者只翻一次，
     * 后面几条瞬间命中 —— 这是「旧记录要等很久才能播」的主要提速点。
     */
    private class AuthorPostsCacheEntry(
        val at: Long,
        var videos: List<VideoItem>,
        var maxCursor: Long,
        var hasMore: Boolean,
        var pages: Int
    )

    // ★ 线程安全：作者页与主页可能并发请求，多条线程会同时写缓存，
    //   普通 HashMap 并发写可能结构损坏 → 换 ConcurrentHashMap。
    private val authorPostsCache = java.util.concurrent.ConcurrentHashMap<String, AuthorPostsCacheEntry>()
    /** detail 通道上次失败时刻；0 = 可用。失败后冷却期内不再打（避免刷屏 + 省 1~2s/条） */
    @Volatile private var detailFailAt = 0L
    private const val DETAIL_COOLDOWN_MS = 60_000L

    /**
     * 拿某个作者的作品（带缓存）。pages 表示本次至少翻到第几页（1 页 = 20 条）。
     * 命中且已翻够页数就直接返回缓存，不再打网络。
     */
    fun cachedAuthorPosts(cookie: String, secUid: String, pages: Int = 1): List<VideoItem> {
        if (secUid.isBlank()) return emptyList()
        val now = System.currentTimeMillis()
        var entry = authorPostsCache[secUid]
        if (entry == null) {
            entry = AuthorPostsCacheEntry(now, emptyList(), 0L, true, 0)
            val prev = authorPostsCache.putIfAbsent(secUid, entry)
            if (prev != null) entry = prev
        }
        // 按「作者」加锁：同一作者不会并发重复翻页；不同作者仍可并行预取（不拖慢播放）
        synchronized(entry) {
            if (now - entry.at < 180_000L && entry.pages >= pages) return entry.videos
            val acc = ArrayList(entry.videos)
            var cursor = entry.maxCursor
            while (entry.pages < pages && entry.hasMore) {
                val r = try { fetchUserPosts(cookie, secUid, cursor, 20) } catch (e: Exception) { null }
                    ?: break
                acc.addAll(r.videos.filter { v -> acc.none { it.awemeId == v.awemeId } })
                entry.pages++
                entry.hasMore = r.hasMore && r.maxCursor != cursor
                cursor = r.maxCursor
                entry.maxCursor = cursor
            }
            entry.videos = acc
        }
        // 只保留最近 40 位作者，防止内存无限增长（电视内存紧张）
        if (authorPostsCache.size > 40) {
            val oldest = authorPostsCache.entries.sortedBy { it.value.at }.take(authorPostsCache.size - 40)
            for (e in oldest) authorPostsCache.remove(e.key)
        }
        return entry.videos
    }

    /**
     * 拿到某条视频的「新鲜」播放地址（列表里存的是当初的直链，签名隔天失效）。
     *
     * ① detail 快速通道：失败后冷却 60 秒再试（不再永久禁用），成功即恢复。
     *    既避免风控下每次 1~2 秒的无效请求，也不会因一次竖屏/波动把通道废掉。
     * ② 作者作品列表走【会话缓存】cachedAuthorPosts：同一位作者只翻一次，多条旧视频共用，
     *    不再每条各翻各的。翻 5 页（100 条）覆盖绝大多数「早些时间」的记录。
     * ③ 找不到返回 null，由上层提示换线路/换引擎（此时换线路会触发重新解析）。
     */
    /**
     * ★ 旧直链刷新提速的最快通道 ★
     * 直接打长效播放接口 /aweme/v1/play/?aweme_id=xxx —— 它每次都会 302 到
     * 当前有效的 CDN 直链（签名是新鲜的）。一次轻量 GET 即可，不依赖 detail
     * 接口是否被风控、也不需要翻作者作品列表（那要 5 次网络、十几秒）。
     * 返回的 VideoItem 里视频地址就是 302 后的最终地址。
     */
    /**
     * ★ 「最新连接」直取入口 ★
     * 长效播放接口每次请求都会 302 到当前有效的 CDN 直链（签名新鲜），
     * 一次轻量 GET 即可拿到最新地址，不必先拿过期直链卡满看门狗。
     */
    fun stablePlayUrl(awemeId: String): String =
        "https://www.douyin.com/aweme/v1/play/" +
            "?device_platform=webapp&aid=6383&aweme_id=$awemeId&" + COMMON_TAIL

    fun refreshViaPlayApi(cookie: String, item: VideoItem): VideoItem? {
        if (item.awemeId.isBlank()) return null
        return try {
            val url = stablePlayUrl(item.awemeId)
            val req = Request.Builder().url(url)
                .header("User-Agent", UA)
                .header("Referer", REFERER)
                .header("Cookie", cookie)
                .header("Accept", "video/*,*/*")
                .build()
            val resp = exec(req)
            val finalUrl = resp.request().url().toString()
            val code = resp.code()
            resp.close()
            // 长效接口返回的最终 URL 必须是「视频 CDN 直链」，而不是风控重定向的
            // 登录页/验证页。抖音视频域名特征：douyinvod.com / ixigua.com / byteimg /
            // 或带 sign 的 playwm 地址；同时排除 douyin.com 页面与 /aweme/ 接口路径。
            val looksLikeCdn = finalUrl.contains("douyinvod.com") ||
                finalUrl.contains("ixigua.com") ||
                finalUrl.contains("bytecdn") ||
                finalUrl.contains("byteimg.com") ||
                (finalUrl.contains(".mp4") && !finalUrl.contains("douyin.com/"))
            if (code == 200 && finalUrl.startsWith("http") && looksLikeCdn) {
                item.copy(videoUrl = finalUrl)
            } else null
        } catch (e: Exception) {
            Log.w(TAG, "refreshViaPlayApi 失败: ${e.message}")
            null
        }
    }

    fun refreshVideoUrl(cookie: String, item: VideoItem): VideoItem? {
        // ⓪ 最快通道：长效 play 接口一次 302 拿新鲜直链 ——
        //    列表里的旧地址签名过期，先走这条（一次轻量请求），
        //    比 detail（常被风控）和翻作者列表（5 次请求）都快得多。
        refreshViaPlayApi(cookie, item)?.let { return it }

        // ① detail 快速通道：成功一次就用一次；失败仅「退避」而非永久禁用 ——
        //    旧代码一旦失败就把 detailDisabled 永久置 true，之后的竖屏/风控波动
        //    再也无法走快速通道，全部落到慢速翻页。改成带时间戳的冷却（60s），
        //    冷却过后自动重试，既不刷屏也不把通道彻底关死。
        val now = System.currentTimeMillis()
        if (now - detailFailAt >= DETAIL_COOLDOWN_MS) {
            val d = fetchVideoDetail(cookie, item.awemeId)
            if (d != null) {
                detailFailAt = 0L   // 成功即清零，恢复快速通道
                return d
            }
            detailFailAt = now
        }
        if (item.secUid.isBlank()) return null

        // ② 作者作品列表：走【带会话缓存】的 cachedAuthorPosts，而不是自己从 0 翻页。
        //    修复要点：旧代码在这里手写 repeat(3){ fetchUserPosts }，翻到的结果既不
        //    写缓存也不复用缓存 —— 同一作者的多条旧记录各翻各的，每条十几秒。
        //    cachedAuthorPosts 会边翻边入缓存，同一作者后续条目零网络命中。
        //    翻 5 页（100 条）覆盖绝大多数「较早发布」的作品。
        val posts = cachedAuthorPosts(cookie, item.secUid, pages = 5)
        return posts.firstOrNull { it.awemeId == item.awemeId }
    }

    // 互动操作（点赞/评论）★ 写操作 ★
    // 真实抓包结构（用户提供的 DevTools 抓包，2026-10-09）：
    //   POST /aweme/v1/web/commit/item/digg/?
    //        device_platform…round_trip_time=100
    //        &webid=<19位>&uifid=<长串>&msToken=<长串>&a_bogus=<签>&verifyFp=<s_v_web_id>
    //        &fp=<同上>&uid=<32位hex>
    //   body: application/x-www-form-urlencoded → aweme_id=<id>&item_type=0&type=1|0
    //   ★ a_bogus 签的就是上面那一整串 query（不含 a_bogus 自己），
    //     所以生成签名用的 UA 必须与实际发送的 UA 完全一致（INTERACT_UA）。
    //   响应：{status_code:0, is_digg:0|1}；status_code≠0 时 status_msg 给出原因。

    data class InteractResult(val ok: Boolean, val msg: String)

    fun diggVideo(cookie: String, awemeId: String, digg: Boolean): InteractResult =
        interactPostForm("https://www.douyin.com/aweme/v1/web/commit/item/digg/", cookie,
            "aweme_id=" + awemeId + "&item_type=0&type=" + (if (digg) "1" else "0"))

    /**
     * 互动接口的公共浏览器指纹参数（字段顺序照抄抓包）。
     * a_bogus 是对这一整串做的签名，因此调用方必须原样拼进 URL。
     *
     * ★ 值必须用【登录浏览器 Cookie 里的真实 webid / msToken / uid】：
     *   抖音把这些凭据写进 Cookie，本地随机串会被风控判定异常 → 踢下线网页端。
     *   Cookie 缺失时才退到 Prefs 的本地生成值（provisionInteractCreds 会先尝试补齐）。
     */
    private fun interactParams(cookie: String): String {
        val uifid = uifidOf(cookie)
        val webid = Prefs.cookieValue(cookie, "webid").ifBlank { Prefs.getWebid() }
        val msToken = Prefs.cookieValue(cookie, "msToken").ifBlank { Prefs.getMsToken() }
        val uid = listOf("uid", "uid_tt").map { Prefs.cookieValue(cookie, it) }
            .firstOrNull { it.isNotEmpty() } ?: Prefs.getUid()
        val fp = verifyFp(cookie)
        return "device_platform=webapp&aid=6383&channel=channel_pc_web" +
            "&pc_client_type=1&pc_libra_divert=Windows&update_version_code=170400" +
            "&support_h265=1&support_dash=1&version_code=170400&version_name=17.4.0" +
            "&cookie_enabled=true&screen_width=1920&screen_height=1080" +
            "&browser_language=zh-CN&browser_platform=Win32&browser_name=Edge" +
            "&browser_version=154.0.0.0&browser_online=true" +
            "&engine_name=Blink&engine_version=154.0.0.0" +
            "&os_name=Windows&os_version=10&cpu_core_num=4&device_memory=8" +
            "&platform=PC&downlink=10&effective_type=4g&round_trip_time=100" +
            "&webid=" + webid + "&uifid=" + uifid +
            (if (msToken.isNotEmpty()) "&msToken=" + msToken else "") +
            (if (fp.isNotEmpty()) "&verifyFp=" + fp + "&fp=" + fp else "") +
            (if (uid.isNotEmpty()) "&uid=" + uid else "")
    }

    /**
     * 写操作的 UA：优先浏览器真实 UA（推送页 JS 捕获的 navigator.userAgent），
     * 没有则用 Edge/Chromium 154 兜底。a_bogus 必须用同一个 UA 生成。
     */
    private fun interactUA(): String = Prefs.getBrowserUa().ifBlank { INTERACT_UA }

    /**
     * Cookie 缺真实 webid / msToken 时的补救：以浏览器的 UA + Cookie 的身份
     * GET www.douyin.com 首页，从 Set-Cookie 里取服务端签发的 webid / msToken，
     * 合并进 Cookie 串。成功即返回补齐后的 Cookie（调用方回写 Prefs）。
     */
    private fun provisionInteractCreds(cookie: String): String {
        try {
            val req = Request.Builder()
                .url("https://www.douyin.com/")
                .header("User-Agent", interactUA())
                .header("Cookie", cookie)
                .header("Accept", "text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8")
                .build()
            val resp = exec(req)
            val got = HashMap<String, String>()
            for (sc in resp.headers("Set-Cookie")) {
                val kv = sc.substringBefore(";").trim()
                val i = kv.indexOf('=')
                if (i > 0) got[kv.substring(0, i).trim()] = kv.substring(i + 1)
            }
            resp.close()
            var out = cookie
            for (k in listOf("msToken", "webid")) {
                val v = got[k]
                if (!v.isNullOrEmpty() && Prefs.cookieValue(out, k).isEmpty()) {
                    out = if (out.isBlank()) "$k=$v" else "$out; $k=$v"
                }
            }
            if (out != cookie) Log.d(TAG, "provision: 补齐 webid/msToken 成功")
            return out
        } catch (e: Exception) {
            Log.e(TAG, "provision 失败: " + e.message)
            return cookie
        }
    }

    /** UIFID：优先 cookie 里的 UIFID，退而取 UIFID_TEMP */
    private fun uifidOf(cookie: String): String {
        val a = cookieValue(cookie, "UIFID")
        return if (a.isNotEmpty()) a else cookieValue(cookie, "UIFID_TEMP")
    }

    fun createComment(cookie: String, awemeId: String, text: String, replyTo: String = ""): InteractResult {
        val kw = URLEncoder.encode(text, "UTF-8")
        val extra = if (replyTo.isBlank()) "" else "&reply_to_comment_id=" + replyTo
        return interact("https://www.douyin.com/aweme/v1/web/comment/create", cookie,
            "aweme_id=" + awemeId + "&text=" + kw + extra)
    }

    private fun interactPostForm(url: String, cookie: String, formBody: String): InteractResult {
        try {
            // ★ 写操作前：Cookie 若缺真实 webid/msToken，先用浏览器身份补一份，
            //   避免本地随机串触发风控踢下线网页端。
            var c = cookie
            if (Prefs.cookieValue(c, "webid").isEmpty() || Prefs.cookieValue(c, "msToken").isEmpty()) {
                c = provisionInteractCreds(c)
                if (c != cookie) {
                    try { Prefs.setCookie(App.appContext()!!, c) } catch (_: Exception) {}
                }
            }
            val ua = interactUA()
            val params = interactParams(c)
            // ★ a_bogus 对整个 query 签名；生成用的 UA 必须与发送 UA 一致
            val ab = ABogus(ua).generate(params)
            if (ab.isEmpty()) return InteractResult(false, "a_bogus 签名失败")
            val req = Request.Builder()
                .url(url + "?" + params + "&a_bogus=" + ab)
                .header("User-Agent", ua)
                .header("Referer", "https://www.douyin.com/")
                .header("Origin", "https://www.douyin.com")
                .header("Cookie", c)
                .header("Accept", "application/json, text/plain, */*")
                .header("Content-Type", "application/x-www-form-urlencoded; charset=UTF-8")
                .header("uifid", uifidOf(c))
                .post(okhttp3.RequestBody.create(
                    okhttp3.MediaType.parse("application/x-www-form-urlencoded; charset=UTF-8"), formBody))
                .build()
            val rb = req.newBuilder()
            // ★ 写接口的 CSRF 防伪：x-secsdk-csrf-token 取 cookie 里的 passport_csrf_token
            //   （浏览器端由 sec-sdk 自动带上，缺失会导致 403 / verify 校验失败）
            val csrf = cookieValue(c, "passport_csrf_token")
            if (csrf.isNotEmpty()) rb.header("x-secsdk-csrf-token", csrf)
            // ★ WebProtect / bd_ticket_guard 层（凭据导入齐全才发）。
            //   头名按公开资料实现，待真机抓包确认后微调（见 WebProtectSigner 注释）。
            if (Prefs.hasWebProtect()) {
                try {
                    val path = java.net.URL(url).path
                    val gd = WebProtectSigner.buildClientData(path)
                    if (gd != null) {
                        rb.header("x-bd-ticket-guard-client-data", gd)
                        rb.header("x-bd-ticket-guard-web-version", "2")
                        rb.header("x-bd-ticket-guard-iteration-version", "1")
                        val pub = Prefs.getServerCert()
                        if (pub.isNotBlank()) rb.header("x-bd-ticket-guard-ree-public-key", pub)
                        Log.d(TAG, "已附加 bd_ticket_guard 头, 数据长度=${gd.length}")
                    } else Log.w(TAG, "WebProtect 凭据不完整，跳过 guard 头")
                } catch (e: Exception) {
                    Log.w(TAG, "guard 头构造失败: ${e.message}")
                }
            }
            return parseInteract(exec(rb.build()))
        } catch (e: Exception) {
            Log.e(TAG, "互动失败: " + e.message, e)
            return InteractResult(false, e.javaClass.simpleName + ": " + e.message)
        }
    }

    private fun interact(url: String, cookie: String, bodyParams: String): InteractResult {
        try {
            val ua = interactUA()
            val params = bodyParams + COMMON_TAIL + "&verifyFp=" + verifyFp(cookie)
            val ab = ABogus(ua).generate(params)
            if (ab.isEmpty()) return InteractResult(false, "签名失败")
            val req = Request.Builder()
                .url(url + "?" + params + "&a_bogus=" + ab)
                .header("User-Agent", ua)
                .header("Referer", "https://www.douyin.com/")
                .header("Cookie", cookie)
                .header("Accept", "application/json, text/plain, */*")
                .header("Content-Type", "application/x-www-form-urlencoded")
                .post(okhttp3.RequestBody.create(null, ByteArray(0)))
                .build()
            return parseInteract(exec(req))
        } catch (e: Exception) {
            Log.e(TAG, "互动失败: " + e.message, e)
            return InteractResult(false, e.javaClass.simpleName + ": " + e.message)
        }
    }

    private fun parseInteract(resp: okhttp3.Response): InteractResult {
        try {
            val code = resp.code()
            val body = resp.body()?.string() ?: ""
            Log.d(TAG, "← 互动 HTTP $code body=" + body.take(200))
            if (code != 200 || body.isEmpty()) return InteractResult(false, "HTTP $code 空响应")
            val root = JSONObject(body)
            val sc = root.optInt("status_code", -1)
            val msg = root.optString("status_msg", "").ifBlank { "status_code=$sc" }
            return if (sc == 0) InteractResult(true, "成功")
            else InteractResult(false, msg.ifBlank { "status_code=$sc" })
        } catch (e: Exception) {
            return InteractResult(false, "解析失败: " + e.message)
        }
    }

    private fun cookieValue(cookie: String, key: String): String =
        cookie.split(";").map { it.trim() }.firstOrNull { it.startsWith(key + "=") }
            ?.substringAfter("=") ?: ""

    private fun verifyFp(cookie: String): String = cookieValue(cookie, "s_v_web_id")
}
