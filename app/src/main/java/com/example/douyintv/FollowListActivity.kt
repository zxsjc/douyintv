package com.example.douyintv

import android.app.Activity
import android.content.Intent
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.view.KeyEvent
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.widget.ImageView
import android.widget.TextView
import androidx.recyclerview.widget.GridLayoutManager
import androidx.recyclerview.widget.RecyclerView

/**
 * 「我的关注」作者列表页
 * ======================
 * 参考网页版抖音 /follow 页左侧那个「我的关注(N)」列表：
 * 头像 + 昵称 + 作品数，点进去就是该作者的作品列表。
 *
 * 数据源：/aweme/v1/web/user/following/list/（offset 分页，一页 20 条）
 * 与「关注视频流」(/follow/feed/) 是两套接口，互不影响。
 *
 * TV 适配：网格（一行 6 个）、item 自己可聚焦（OK 键=click）、
 * 滑到底自动续拉、返回键直接退出。
 */
class FollowListActivity : Activity() {

    companion object {
        private const val TAG = "FollowList"
        private const val PAGE = 20
        private const val SPAN_COUNT = 6
    }

    private lateinit var recycler: RecyclerView
    private lateinit var tvTitle: TextView
    private lateinit var tvSub: TextView
    private lateinit var tvStatus: TextView

    private val items = ArrayList<DouyinApi.FollowAuthor>()

    /** 补作品数的状态：secUid → 已尝试次数（服务端 following/list 不带 aweme_count） */
    private val countAttempts = HashMap<String, Int>()
    private var fillRunning = false
    private lateinit var adapter: AuthorAdapter

    // ★ 真机实测：服务端忽略 offset、一页最多 21 条。主游标必须是 max_time；
    //   批量同秒关注打滑时，用本页 pageMinTime 跳过整簇。上一版误用 offset 主游标，
    //   每页都是同一批 21 人 → 只显示 21 人。
    private var maxTime = 0L
    private var hasMore = true
    private var loading = false
    private var pagesLoaded = 0
    private var emptyPages = 0
    private var retries = 0
    private var totalCount = 0
    private var firstLoadDone = false
    private val handler = Handler(Looper.getMainLooper())

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        setContentView(R.layout.activity_list)

        recycler = findViewById(R.id.recyclerView) as RecyclerView
        tvTitle = findViewById(R.id.tvTitle) as TextView
        tvSub = findViewById(R.id.tvSub) as TextView
        tvStatus = findViewById(R.id.tvStatus) as TextView

        tvTitle.text = "我的关注"
        tvSub.text = "选一个作者 → 看他的作品　（返回键退出）"
        tvStatus.text = "加载中…"

        val lm = GridLayoutManager(this, SPAN_COUNT)
        adapter = AuthorAdapter(items) { pos -> openAuthor(pos) }
        recycler.layoutManager = lm
        recycler.adapter = adapter
        recycler.isFocusable = true
        recycler.isFocusableInTouchMode = true
        recycler.addOnScrollListener(object : RecyclerView.OnScrollListener() {
            override fun onScrolled(rv: RecyclerView, dx: Int, dy: Int) {
                val last = lm.findLastVisibleItemPosition()
                if (adapter.itemCount > 0 && last >= adapter.itemCount - SPAN_COUNT) loadMore()
            }
        })

        loadMore()
    }

    private fun loadMore() {
        if (loading || !hasMore) return
        loading = true
        tvStatus.text = "加载关注作者…"
        val mt = maxTime
        val cookie = Prefs.getCookie(this)

        Thread {
            val r = try {
                // offset 固定 0（服务端忽略），用 max_time 作唯一翻页游标
                DouyinApi.fetchFollowing(cookie, 0, mt, PAGE)
            } catch (e: Exception) {
                Log.e(TAG, "加载关注列表异常", e); null
            }
            handler.post {
                if (isFinishing) return@post
                loading = false
                if (r == null) {
                    // 单页失败不放弃：等 1.5 秒用同一个游标再试，最多 3 次
                    retries++
                    if (retries <= 3) {
                        tvStatus.text = "加载关注作者…（重试 $retries）"
                        handler.postDelayed({ loadMore() }, 1500)
                    } else {
                        tvStatus.text = "加载失败：" + DouyinApi.lastError
                    }
                    return@post
                }
                retries = 0
                if (r.authors.isEmpty()) {
                    hasMore = false
                    tvStatus.text = if (items.isEmpty()) "还没有关注任何作者" else "已到底，共 ${items.size} 位作者"
                    return@post
                }

                // 去重：翻页偶有重叠，也防止 max_time 没推进时死循环
                val known = HashSet<String>()
                for (a in items) known.add(a.secUid)
                val fresh = ArrayList<DouyinApi.FollowAuthor>()
                for (a in r.authors) if (known.add(a.secUid)) fresh.add(a)

                val start = items.size
                items.addAll(fresh)
                if (start == 0) adapter.notifyDataSetChanged()
                else adapter.notifyItemRangeInserted(start, fresh.size)

                // ★ 翻页策略（真机实测修正）★
                //   服务端忽略 offset、一页最多 21 条 ⇒ 唯一游标是 max_time。
                //   批量关注的人 follow_time 落在同一秒 ⇒ max_time 会原地打滑，
                //   表现为「连翻几页只多出几个人」。判据：本页新增 < 5 即视为打滑，
                //   改用本页 pageMinTime（最老关注时间）跳过整簇。
                pagesLoaded++
                if (fresh.isEmpty()) emptyPages++ else emptyPages = 0
                if (totalCount <= 0) totalCount = r.totalCount

                // 默认游标 = 服务端回的 next max_time
                var nextCursor = r.nextMaxTime
                // 打滑：新增很少（含 0）但服务端还有 → 用本页 min_time 往前挪一毫秒，
                // 越过「同一秒批量关注」这一簇（min_time 是本页最老那条的时间）。
                if (fresh.size < 5 && r.pageMinTime > 0L && r.pageMinTime <= nextCursor) {
                    nextCursor = r.pageMinTime - 1
                }
                if (nextCursor > 0L) maxTime = nextCursor

                val reachedTotal = totalCount > 0 && items.size >= totalCount
                // ★ 放宽停止：服务端 has_more 偶发误报 0，不再单独据此急停。
                //   只有「连续 2 页完全无新增」或「游标耗尽（<=0 且服务端也没更多）」
                //   才真正到底。这样即使中途打滑几页也能继续翻到 35 人。
                val serverDone = !r.hasMore && r.nextMaxTime <= 0L && r.pageMinTime <= 0L
                val stalled = emptyPages >= 2 && nextCursor <= 0L
                if (reachedTotal || serverDone || stalled || pagesLoaded >= 40 || items.size >= 200) {
                    hasMore = false
                }
                tvStatus.text = if (totalCount > 0)
                    "共 ${items.size} 位作者 / 关注 $totalCount 位　OK 看作品　↑↓ 滚动"
                else "共 ${items.size} 位作者　OK 看作品　↑↓ 滚动"

                // ★ 补作品数：following/list 里 aweme_count 恒为 0（实测 TRIN 就是 0），
                //   必须另拉 profile/other 才知道「20 个作品」。慢一点没关系，别让用户
                //   看到一行全是「查看作品」。
                fillMissingCounts()

                // ★ 自动补页 ★：一进来就把全量拉齐，而不是等用户滚到底才发现只有 30 个。
                //   拉到总数、服务端说到底、或已达安全上限才停。
                if (hasMore && items.size < 200) {
                    handler.postDelayed({ loadMore() }, 200)
                }

                if (!firstLoadDone) {
                    firstLoadDone = true
                    recycler.post {
                        val v = recycler.layoutManager?.findViewByPosition(0)
                        if (v != null) v.requestFocus() else recycler.requestFocus()
                    }
                }
            }
        }.start()
    }

    /**
     * 给「作品数为 0 / 未知」的作者补真实作品数。
     *
     * 背景：/user/following/list/ 返回的作者对象里 aweme_count 恒为 0
     * （实测「宽广度TRIN」服务端只给 nickname/sec_uid/follower_count），
     * 关注列表于是显示成「查看作品」。真正的数字在 /user/profile/other/（实测 20）。
     * 这里逐个补拉：串行 + 140ms 间隔，避免一次打 35 个请求被风控；失败的 5 秒后再试一次。
     */
    private fun fillMissingCounts() {
        if (fillRunning) return
        val jobs = ArrayList<Pair<Int, String>>()
        for (i in items.indices) {
            val a = items[i]
            if (a.awemeCount > 0) continue
            val n = countAttempts[a.secUid] ?: 0
            if (n >= 2) continue
            countAttempts[a.secUid] = n + 1
            jobs.add(i to a.secUid)
        }
        if (jobs.isEmpty()) return
        fillRunning = true
        val cookie = Prefs.getCookie(this)

        Thread {
            val got = ArrayList<Pair<Int, Int>>()
            for ((idx, sec) in jobs) {
                val p = try { DouyinApi.fetchUserProfile(cookie, sec) } catch (_: Throwable) { null }
                if (p != null && p.awemeCount > 0) got.add(idx to p.awemeCount)
                try { Thread.sleep(140) } catch (_: InterruptedException) {}
            }
            handler.post {
                fillRunning = false
                if (isFinishing) return@post
                for ((idx, cnt) in got) {
                    val old = items.getOrNull(idx) ?: continue
                    if (old.awemeCount == cnt) continue
                    items[idx] = old.copy(awemeCount = cnt)
                    adapter.notifyItemChanged(idx)
                }
                // 还有漏的（网络抖动）→ 稍后再补一轮（每人不超过 2 次）
                val missing = items.any { it.awemeCount <= 0 && (countAttempts[it.secUid] ?: 0) < 2 }
                if (missing) handler.postDelayed({ fillMissingCounts() }, 5000)
            }
        }.start()
    }

    private fun openAuthor(pos: Int) {
        val a = items.getOrNull(pos) ?: return
        try {
            val i = Intent(this, AuthorActivity::class.java)
            i.putExtra("sec_uid", a.secUid)
            i.putExtra("author", a.nickname)
            startActivity(i)
        } catch (e: Exception) {
            Log.e(TAG, "打开作者页失败", e)
            tvStatus.text = "打开失败: " + e.message
        }
    }

    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        if (event.action == KeyEvent.ACTION_DOWN && event.keyCode == KeyEvent.KEYCODE_BACK) {
            finish(); return true
        }
        return super.dispatchKeyEvent(event)
    }

    override fun onDestroy() {
        handler.removeCallbacksAndMessages(null)
        items.clear()
        super.onDestroy()
    }

    /** 作者卡片适配器：头像走 ImageLoader，作品数走接口 aweme_count */
    private class AuthorAdapter(
        private val data: List<DouyinApi.FollowAuthor>,
        private val onClick: (Int) -> Unit
    ) : RecyclerView.Adapter<AuthorAdapter.VH>() {

        class VH(v: View) : RecyclerView.ViewHolder(v) {
            val avatar: ImageView = v.findViewById(R.id.ivAvatar)
            val name: TextView = v.findViewById(R.id.tvName)
            val meta: TextView = v.findViewById(R.id.tvMeta)
        }

        override fun getItemCount() = data.size

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH {
            val v = LayoutInflater.from(parent.context)
                .inflate(R.layout.item_follow_author, parent, false)
            return VH(v)
        }

        override fun onBindViewHolder(h: VH, pos: Int) {
            val a = data[pos]
            h.name.text = a.nickname
            h.meta.text = if (a.awemeCount > 0) "${a.awemeCount} 个作品" else "查看作品"
            ImageLoader.load(a.avatarUrl, h.avatar, a.secUid)
            h.itemView.setOnClickListener { onClick(pos) }
        }
    }
}
