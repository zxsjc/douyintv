package com.example.douyintv

import android.app.Activity
import android.content.Intent
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.view.Gravity
import android.view.KeyEvent
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.widget.EditText
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.TextView
import androidx.recyclerview.widget.GridLayoutManager
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView

/**
 * 搜索页（三段式：键盘 / 联想 / 结果）
 * ====================================
 *  ① 左栏：输入框（+ 清空 / 退格）+ 6 列字母数字键盘
 *  ② 中栏：猜你想搜（实时联想，点一下直接搜）
 *  ③ 右栏：结果列表（海报 + 标题，滚到底自动翻页）
 *
 * ★ 键盘只要字母数字 ★
 *   实测抖音搜索接口认拼音（打 meishi 自己纠错成美食），联想接口还会直接返回
 *   中文候选（meishi → 美食教程 / 美食测评），所以不需要内置拼音词库。
 *   老电视盒子基本没有输入法，键盘是自绘的，遥控器上下左右 + OK 就能打字。
 *
 * ★ 2.40：搜索改成「搜作者」★★
 *   网页版视频搜索接口（general/search/single 等）在这台设备/IP 上恒返回
 *   verify_check（0 条），带签名/换 UA/换 ttwid/App 内开 WebView 都绕不过去
 *   （详见 DouyinApi.searchAuthors 的注释）。
 *   唯一稳定可用且真有内容的是 discover/search —— 它是「搜人」接口。
 *   所以本页定位改成：输入作者昵称 → 右侧列出作者（头像/昵称/粉丝/认证）→
 *   OK 进作者页看 TA 的全部作品。
 *
 *   顺带的好处：这台盒子的键盘只有字母数字，中文作者名只能走联想词 / 历史 /
 *   热搜里的中文词，或者先打拼音让联想给出中文候选。
 *
 * 热搜继续放在中栏（搜索前显示，开始搜索后自动收起）。
 */
class SearchActivity : Activity() {

    private companion object {
        const val TAG = "Search"
        const val SUG_DELAY = 320L          // 联想防抖：老设备别每敲一下就发请求
        const val SPAN_AUTHOR = 1           // 作者结果：一列一张（昵称动辄七八个字，两列会被截断）

        /** 6 列键盘：照参考图，字母 5 行 + 数字 1 行 */
        val KEY_ROWS = listOf("ABCDEF", "GHIJKL", "MNOPQR", "STUVWX", "YZ1234", "567890")
    }

    // 左栏
    private lateinit var etKeyword: EditText
    private lateinit var btnClear: TextView
    private lateinit var btnBack: TextView
    private lateinit var keyboardBox: LinearLayout

    // 中栏
    private lateinit var sugRecycler: RecyclerView
    private lateinit var sugBox: View
    private lateinit var tvSugTitle: TextView

    // 右栏
    private lateinit var tvTitle: TextView
    private lateinit var tvCount: TextView
    private lateinit var resultRecycler: RecyclerView
    private lateinit var placeholder: View
    private lateinit var pbLoading: ProgressBar
    private lateinit var tvPlaceholder: TextView
    private lateinit var btnRetry: TextView

    private val typed = StringBuilder()
    private val sugWords = ArrayList<String>()
    private val authors = ArrayList<DouyinApi.AuthorHit>()

    private lateinit var sugAdapter: SugAdapter
    private lateinit var resultAdapter: AuthorResultAdapter

    private val handler = Handler(Looper.getMainLooper())
    private var sugTask: Runnable? = null

    private var keyword = ""
    private var loading = false
    private var loadGen = 0

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        try {
            window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
            setContentView(R.layout.activity_search)

            etKeyword = findViewById(R.id.etKeyword) as EditText
            btnClear = findViewById(R.id.btnClear) as TextView
            btnBack = findViewById(R.id.btnBack) as TextView
            keyboardBox = findViewById(R.id.keyboardBox) as LinearLayout
            sugRecycler = findViewById(R.id.sugRecycler) as RecyclerView
            sugBox = findViewById(R.id.sugBox)
            tvSugTitle = findViewById(R.id.tvSugTitle) as TextView
            tvTitle = findViewById(R.id.tvTitle) as TextView
            tvCount = findViewById(R.id.tvCount) as TextView
            resultRecycler = findViewById(R.id.resultRecycler) as RecyclerView
            placeholder = findViewById(R.id.placeholder)
            pbLoading = findViewById(R.id.pbLoading) as ProgressBar
            tvPlaceholder = findViewById(R.id.tvPlaceholder) as TextView
            btnRetry = findViewById(R.id.btnRetry) as TextView

            sugAdapter = SugAdapter(sugWords) { w -> doSearch(w) }
            sugRecycler.layoutManager = LinearLayoutManager(this)
            sugRecycler.adapter = sugAdapter
            sugRecycler.isFocusable = true
            sugRecycler.isFocusableInTouchMode = true

            resultAdapter = AuthorResultAdapter(authors) { pos -> openAuthor(pos) }
            resultRecycler.layoutManager = GridLayoutManager(this, SPAN_AUTHOR)
            resultRecycler.adapter = resultAdapter
            resultRecycler.isFocusable = true
            resultRecycler.isFocusableInTouchMode = true

            buildKeyboard()
            btnClear.setOnClickListener { clearInput() }
            btnBack.setOnClickListener { backspace() }
            btnRetry.setOnClickListener { if (keyword.isEmpty()) resetAll() else doSearch(keyword) }

            val kw0 = intent?.getStringExtra("kw")
            if (!kw0.isNullOrBlank()) {
                typed.setLength(0); typed.append(kw0); syncInput(); doSearch(kw0)
            } else {
                resetAll()
            }
        } catch (t: Throwable) {
            Log.e(TAG, "onCreate 失败", t)
            try { tvPlaceholder.text = "搜索页异常: " + t.javaClass.simpleName } catch (_: Throwable) {}
        }
    }

    // ================================================================
    // 键盘（6 列）
    // ================================================================
    private fun buildKeyboard() {
        val dp = resources.displayMetrics.density
        val kh = (46 * dp).toInt()
        val kw = (52 * dp).toInt()
        val gap = (5 * dp).toInt()

        for (r in KEY_ROWS) {
            val row = LinearLayout(this)
            row.orientation = LinearLayout.HORIZONTAL
            val lp = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            )
            lp.bottomMargin = gap
            row.layoutParams = lp
            for (ch in r) {
                val key = makeKey(ch.toString(), kh, kw, gap) { typeChar(ch.toString()) }
                // ★ 每行最后一个键：→ 键把焦点送进中栏（热搜 / 历史 / 联想）
                //   否则中栏那一列焦点永远进不去（FocusFinder 在这套布局下找不到它）
                if (ch == r.last()) hookRightToSug(key)
                row.addView(key)
            }
            keyboardBox.addView(row)
        }

        // 功能行：空格 / 搜索
        val fn = LinearLayout(this)
        fn.orientation = LinearLayout.HORIZONTAL
        fn.addView(makeKey("空格", kh, (52 * dp).toInt() + 5 + 52 * 2 + 10, gap) { typeChar(" ") })
        val go = makeKey("搜索", kh, kw, gap) { submitTyped() }
        hookRightToSug(go)
        fn.addView(go)
        keyboardBox.addView(fn)
    }

    /** 给键盘上的键挂一个「→ 进中栏」：有热搜/联想时把焦点交给第一条 */
    private fun hookRightToSug(key: TextView) {
        key.setOnKeyListener { _, code, ev ->
            if (ev.action == KeyEvent.ACTION_DOWN && code == KeyEvent.KEYCODE_DPAD_RIGHT) {
                focusSug(); true
            } else false
        }
    }

    /** 焦点进中栏：热搜/历史/联想的第一条；中栏空则原地不动 */
    private fun focusSug() {
        if (::sugBox.isInitialized && sugBox.visibility != View.VISIBLE) return
        if (sugWords.isEmpty()) return
        sugRecycler.post {
            val vh = sugRecycler.findViewHolderForAdapterPosition(0)
            if (vh != null) vh.itemView.requestFocus()
            else sugRecycler.requestFocus()
        }
    }

    private fun makeKey(label: String, h: Int, w: Int, gap: Int, onClick: () -> Unit): TextView {
        val tv = TextView(this)
        tv.text = label
        tv.gravity = Gravity.CENTER
        tv.isFocusable = true
        tv.isFocusableInTouchMode = true
        tv.background = resources.getDrawable(R.drawable.focus_chip)
        tv.setTextColor(resources.getColorStateList(R.color.btn_text))
        tv.textSize = 16f
        val lp = LinearLayout.LayoutParams(w, h)
        lp.rightMargin = gap
        tv.layoutParams = lp
        tv.setOnClickListener { onClick() }
        return tv
    }

    private fun typeChar(s: String) {
        if (typed.length >= 24) return
        typed.append(s); syncInput(); scheduleSuggest()
    }

    private fun backspace() {
        if (typed.isEmpty()) return
        typed.deleteCharAt(typed.length - 1); syncInput(); scheduleSuggest()
    }

    private fun clearInput() {
        typed.setLength(0); syncInput(); scheduleSuggest()
    }

    private fun submitTyped() {
        val q = typed.toString().trim()
        if (q.isEmpty()) return
        doSearch(q)
    }

    /** 输入框只做展示（老盒子没输入法，聚焦它也没用），所以不抢焦点 */
    private fun syncInput() {
        try { etKeyword.setText(typed.toString()) } catch (_: Exception) {}
    }

    // ================================================================
    // 联想
    // ================================================================
    private fun scheduleSuggest() {
        sugTask?.let { handler.removeCallbacks(it) }
        val q = typed.toString().trim()
        if (q.isEmpty()) {
            // ★ 输入为空 → 显示搜索历史（本地，点词即搜）
            showSearchHistory()
            return
        }
        val task = Runnable {
            Thread {
                val list = try { DouyinApi.fetchSuggest(q) } catch (e: Exception) { null }
                handler.post {
                    if (isFinishing) return@post
                    if (typed.toString().trim() != q) return@post   // 输入已变，丢弃乱序结果
                    sugWords.clear()
                    if (list != null) sugWords.addAll(list)
                    sugAdapter.notifyDataSetChanged()
                    // ★ 两段式：有联想词才显示（嵌在结果区顶部），无词则收起
                    if (::tvSugTitle.isInitialized) tvSugTitle.text = "猜你想搜"
                    sugBox.visibility = if (sugWords.isEmpty()) View.GONE else View.VISIBLE
                }
            }.start()
        }
        sugTask = task
        handler.postDelayed(task, SUG_DELAY)
    }

    /** 输入为空：热搜榜（匿名接口）+ 搜索历史 合并展示（点词即搜） */
    private fun showSearchHistory() {
        val hist = try { Prefs.getSearchHistory(this) } catch (e: Exception) { emptyList() }
        // 先立即显示本地历史（快），热搜异步合入
        if (::sugAdapter.isInitialized) {
            sugWords.clear()
            sugWords.addAll(hist)
            sugAdapter.notifyDataSetChanged()
        }
        if (::tvSugTitle.isInitialized) tvSugTitle.text = if (hist.isEmpty()) "热搜榜" else "热搜 · 历史"
        if (::sugBox.isInitialized) sugBox.visibility = View.VISIBLE

        // ★ 后台拉热搜词（匿名可用），合并到列表最前
        Thread {
            val hot = try {
                DouyinApi.fetchHotSearch()?.map { it.word } ?: emptyList()
            } catch (e: Exception) { emptyList() }
            handler.post {
                if (isFinishing) return@post
                if (typed.toString().trim().isNotEmpty()) return@post   // 用户已开始输入，丢弃
                val merged = (hot + hist).distinct()
                if (merged.isEmpty()) {
                    if (::sugBox.isInitialized) sugBox.visibility = View.GONE
                    return@post
                }
                sugWords.clear()
                sugWords.addAll(merged)
                sugAdapter.notifyDataSetChanged()
                if (::tvSugTitle.isInitialized) tvSugTitle.text = "热搜 · 历史"
                if (::sugBox.isInitialized) sugBox.visibility = View.VISIBLE
            }
        }.start()
    }

    // ================================================================
    // 搜索（带代际保护：迟到的响应不覆盖新搜索）
    // ================================================================
    private fun doSearch(kw: String) {
        if (kw.isBlank()) return
        if (keyword == kw && loading) return
        keyword = kw
        // 搜索历史：每次搜索记录一条（本地）
        try { Prefs.addSearchHistory(this, kw) } catch (_: Throwable) {}
        // 开始搜索后收起联想条，结果占满右栏
        if (::sugBox.isInitialized) sugBox.visibility = View.GONE
        val gen = ++loadGen
        loading = true
        authors.clear()
        resultAdapter.notifyDataSetChanged()
        tvTitle.text = "作者 · " + kw
        tvCount.text = ""
        showPlaceholder("正在找作者「" + kw + "」…", true, false)

        val cookie = Prefs.getCookie(this)
        Thread {
            val r = try { DouyinApi.searchAuthors(cookie, kw) } catch (e: Exception) { null }
            handler.post {
                if (gen != loadGen) return@post     // 已被新搜索取代
                if (isFinishing) return@post
                loading = false
                if (r == null) {
                    showPlaceholder(
                        "搜作者失败\n\n" + DouyinApi.lastError +
                            "\n\n可到「登录」页重新推送 Cookie，或点「重试」。",
                        false, true
                    )
                    return@post
                }
                if (r.isEmpty()) {
                    showPlaceholder(
                        "「" + kw + "」没找到作者\n\n" +
                            "本版搜索按「作者」检索：请输入作者昵称（也可以先打拼音，让联想词给出中文名），\n" +
                            "找到人后点进去看 TA 的全部作品。\n" +
                            "抖音的网页视频搜索接口在这台设备上被风控拦住，暂时只能搜人。",
                        false, false
                    )
                    // ★ 空结果没有可聚焦控件，焦点必须还给键盘，否则遥控器就"死"在这一屏
                    keyboardBox.post {
                        (keyboardBox.getChildAt(0) as? LinearLayout)?.getChildAt(0)?.requestFocus()
                    }
                    return@post
                }
                authors.clear(); authors.addAll(r)
                resultAdapter.notifyDataSetChanged()
                hidePlaceholder()
                tvCount.text = "共 " + authors.size + " 位作者"
                resultRecycler.post { resultRecycler.requestFocus() }
            }
        }.start()
    }

    // ================================================================
    // 作者页导航 / 复位
    // ================================================================
    /** OK 键：进作者主页看 TA 的全部作品（和「我的关注」点作者走的是同一套接口） */
    private fun openAuthor(pos: Int) {
        if (pos < 0 || pos >= authors.size) return
        val a = authors[pos]
        try {
            val i = Intent(this, AuthorActivity::class.java)
            i.putExtra("sec_uid", a.secUid)
            i.putExtra("author", a.nickname)
            startActivity(i)
        } catch (e: Exception) {
            Log.e(TAG, "打开作者页失败", e)
            tvCount.text = "打开失败：" + e.message
        }
    }

    /** v 是否在 parent 的子树里（焦点来源判断用） */
    private fun isUnder(v: View, parent: View): Boolean {
        var p = v.parent
        while (p is View) {
            if (p === parent) return true
            p = p.parent
        }
        return false
    }

    private fun resetAll() {
        keyword = ""
        loadGen++
        authors.clear()
        resultAdapter.notifyDataSetChanged()
        hidePlaceholder()
        tvTitle.text = "搜索作者"
        tvCount.text = ""
        typed.setLength(0)
        syncInput()
        showSearchHistory()
        keyboardBox.post {
            (keyboardBox.getChildAt(0) as? LinearLayout)?.getChildAt(0)?.requestFocus()
        }
    }

    override fun onNewIntent(intent: Intent?) {
        super.onNewIntent(intent)
        setIntent(intent)
        intent?.getStringExtra("kw")?.takeIf { it.isNotBlank() }?.let { kw ->
            typed.setLength(0); typed.append(kw); syncInput(); doSearch(kw)
        }
    }

    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        // ★ 在中栏（热搜/历史/联想）上按 ← 回键盘，否则遥控器一进中栏就出不来
        if (event.action == KeyEvent.ACTION_DOWN && event.keyCode == KeyEvent.KEYCODE_DPAD_LEFT) {
            val f = currentFocus
            if (f != null && isUnder(f, sugRecycler)) {
                val first = (keyboardBox.getChildAt(0) as? LinearLayout)?.getChildAt(0)
                if (first != null && first.requestFocus()) return true
            }
        }
        if (event.action == KeyEvent.ACTION_DOWN && event.keyCode == KeyEvent.KEYCODE_BACK) {
            // 有结果 → 先把结果清掉回到输入态；已经是输入态 → 退出
            if (authors.isNotEmpty()) { resetAll(); return true }
            finish(); return true
        }
        return super.dispatchKeyEvent(event)
    }

    override fun onDestroy() {
        sugTask?.let { handler.removeCallbacks(it) }
        handler.removeCallbacksAndMessages(null)
        super.onDestroy()
    }

    // ================================================================
    private fun showPlaceholder(text: String, loading: Boolean, showRetry: Boolean) {
        placeholder.visibility = View.VISIBLE
        pbLoading.visibility = if (loading) View.VISIBLE else View.GONE
        tvPlaceholder.text = text
        btnRetry.visibility = if (showRetry) View.VISIBLE else View.GONE
        if (showRetry) btnRetry.post { btnRetry.requestFocus() }
    }

    private fun hidePlaceholder() {
        placeholder.visibility = View.GONE
    }

    /** 作者结果卡：头像 / 昵称 / 粉丝数·认证 / 简介（OK 进作者作品页） */
    private class AuthorResultAdapter(
        private val data: List<DouyinApi.AuthorHit>,
        private val onClick: (Int) -> Unit
    ) : RecyclerView.Adapter<AuthorResultAdapter.VH>() {

        class VH(v: View) : RecyclerView.ViewHolder(v) {
            val avatar: ImageView = v.findViewById(R.id.ivAvatar)
            val name: TextView = v.findViewById(R.id.tvName)
            val meta: TextView = v.findViewById(R.id.tvMeta)
            val sig: TextView = v.findViewById(R.id.tvSig)
        }

        override fun getItemCount() = data.size

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH {
            val v = LayoutInflater.from(parent.context)
                .inflate(R.layout.item_search_author, parent, false)
            return VH(v)
        }

        override fun onBindViewHolder(h: VH, pos: Int) {
            if (pos < 0 || pos >= data.size) return
            val a = data[pos]
            h.name.text = TextCompat.forTv(a.nickname)
            val fans = when {
                a.followerCount >= 100000000L -> "%.1f亿".format(a.followerCount / 100000000.0)
                a.followerCount >= 10000L -> "%.1f万".format(a.followerCount / 10000.0)
                a.followerCount > 0L -> a.followerCount.toString()
                else -> ""
            }
            val parts = ArrayList<String>()
            if (fans.isNotEmpty()) parts.add("粉丝 " + fans)
            if (a.verify.isNotEmpty()) parts.add(a.verify)
            h.meta.text = parts.joinToString(" · ")
            h.sig.text = TextCompat.forTv(a.signature)
            h.sig.visibility = if (a.signature.isBlank()) View.GONE else View.VISIBLE
            ImageLoader.load(a.avatarUrl, h.avatar, a.secUid)
            h.itemView.setOnClickListener { onClick(pos) }
        }
    }

    /** 联想词适配器（复用热搜条目布局） */
    private class SugAdapter(
        private val data: List<String>,
        private val onClick: (String) -> Unit
    ) : RecyclerView.Adapter<SugAdapter.VH>() {

        class VH(v: View) : RecyclerView.ViewHolder(v) {
            val tv: TextView = v.findViewById(R.id.tvWord)
        }

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH {
            val v = LayoutInflater.from(parent.context)
                .inflate(R.layout.item_hot_word, parent, false)
            // 联想条目不需要榜单那两列
            (v.findViewById(R.id.tvRank) as? TextView)?.visibility = View.GONE
            (v.findViewById(R.id.tvHot) as? TextView)?.visibility = View.GONE
            return VH(v)
        }

        override fun getItemCount(): Int = data.size

        override fun onBindViewHolder(holder: VH, position: Int) {
            if (position < 0 || position >= data.size) return
            val w = data[position]
            holder.tv.text = TextCompat.forTv(w)
            holder.itemView.setOnClickListener { onClick(w) }
        }
    }
}
