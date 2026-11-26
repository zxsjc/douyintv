package com.example.douyintv

import androidx.recyclerview.widget.RecyclerView
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.TextView

/**
 * 首页 / 作者页的视频网格适配器。
 *
 * 为什么必须用 RecyclerView（TV 硬性规则③）：
 *   1) ViewHolder 由框架强制复用，杜绝"每次 bind 都 findViewById"的开销
 *      —— 1GB 电视上这个开销能直接变成滚动掉帧；
 *   2) 原生支持 GridLayoutManager（规则要求的"网格预览列表"）；
 *   3) support-v7 的 RecyclerView 对 D-Pad 的 focusSearch 有专门处理，
 *      而 ListView / ScrollView 在遥控器场景下焦点经常飞出屏幕或直接丢失。
 *
 * 图片策略：全部交给 ImageLoader，它自带「加载中占位图 + 失败兜底图」
 * （对应规则 4②），适配器这边不做任何 setImageBitmap(null) 之类的裸操作。
 */
class VideoGridAdapter(
    private val items: MutableList<DouyinApi.VideoItem>,
    private val onClick: (position: Int) -> Unit
) : RecyclerView.Adapter<VideoGridAdapter.VH>() {

    class VH(v: View) : RecyclerView.ViewHolder(v) {
        val cover: ImageView = v.findViewById(R.id.ivCover) as ImageView
        val pbCover: View = v.findViewById(R.id.pbCover)
        val title: TextView = v.findViewById(R.id.tvTitle) as TextView
        val meta: TextView = v.findViewById(R.id.tvMeta) as TextView
        val duration: TextView = v.findViewById(R.id.tvDuration) as TextView
        val vertical: TextView = v.findViewById(R.id.tvVertical) as TextView
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH {
        val v = LayoutInflater.from(parent.context)
            .inflate(R.layout.item_video_card, parent, false)
        return VH(v)
    }

    /** 焦点项变化 -> 首页信息栏（改版：卡片纯封面后，标题/作者只在信息栏显示） */
    var onFocusItem: ((String, String) -> Unit)? = null

    /** 封面统一高度（宿主按网格列宽算好，0 = 用布局默认值） */
    var coverHeight: Int = 0

    /**
     * 网格列数（宿主注入），用于给「第一列」卡片显式设置左键焦点目标。
     * 修复：快速连按时焦点可能被框架甩到左侧纵向图标上 —— 第一列卡片的左键
     * 必须有明确的落点（侧栏「推荐」），不能依赖 RecyclerView 容器的隐式搜索。
     */
    var spanCount: Int = 5

    override fun getItemCount(): Int = items.size

    override fun onBindViewHolder(holder: VH, position: Int) {
        val it = items[position]

        // 标题：空文案给个占位，否则卡片看起来像坏了
        holder.title.text = if (it.desc.isBlank()) "（无标题）" else it.desc

        // 元信息：作者 + 点赞数
        val sb = StringBuilder()
        sb.append("@").append(if (it.author.isBlank()) "未知" else it.author)
        if (it.diggCount > 0) {
            sb.append("  赞 ").append(fmtCount(it.diggCount))
        }
        holder.meta.text = sb.toString()

        // 时长角标
        holder.duration.text = fmtDur(it.duration)

        // 竖屏标记：横屏 TV 上竖屏视频两侧是黑边，提前打角标告知
        // ★ 修复：原来 if 两个分支都是 GONE（死代码，角标从未显示）
        holder.vertical.visibility = if (it.isPortrait) View.VISIBLE else View.GONE

        // 封面 —— ImageLoader 内部有占位图 / 兜底图 / 复用防错位
        ImageLoader.load(it.coverUrl, holder.cover, it.awemeId)

        // 遥控器"确定" → 进入播放页（整卡可点击）
        // ★ 封面高度统一 ★
        //   以前是在 post 里按 itemView 当前宽度算 9/16 —— 视图还没测量时宽度为 0，
        //   算不出来就沿用默认高度，结果同一屏里有的卡片高一点、有的矮一点。
        //   现在由宿主（MainActivity）按网格列宽算好一个统一高度，所有卡片都用它。
        if (coverHeight > 0) {
            val coverParent = holder.cover.parent as? View
            if (coverParent != null && coverParent.layoutParams.height != coverHeight) {
                coverParent.layoutParams.height = coverHeight
                coverParent.requestLayout()
            }
        }

        // ★ 焦点外逃修正（第一列左键）★
        //   快速连按方向键时，若第一列卡片没显式 nextFocusLeftId，框架会把焦点
        //   甩给「视觉上最近」的左侧纵向图标。这里给第一列明确指向侧栏推荐项，
        //   非第一列交给 GridLayoutManager 正常处理（设为 -1 表示不干预/自动）。
        holder.itemView.nextFocusLeftId =
            if (position % spanCount == 0) R.id.navRecommend else View.NO_ID

        holder.itemView.setOnClickListener { FocusAnim.press(holder.itemView); onClick(position) }

        // ★ 10 英尺焦点：只留描边 ★
        //   ① 去掉 1.04 倍缩放：卡片贴边时会被 RecyclerView 裁掉 → 「框线显示不全/粗细不一」
        //   ② 去掉封面上的标题浮层：浮层会盖住封面下半部分，同一排里有的卡片像被截短了，
        //      用户看到的就是「图片大小不一致」。标题统一放到屏幕底部信息栏。
        holder.itemView.setOnFocusChangeListener { v, has ->
            val cap = v.findViewById(R.id.captionBox) as? android.view.View
            cap?.visibility = android.view.View.GONE
            val tvT = v.findViewById(R.id.tvTitle) as? android.widget.TextView
            val tvM = v.findViewById(R.id.tvMeta) as? android.widget.TextView
            if (has) {
                onFocusItem?.invoke(tvT?.text?.toString() ?: "", tvM?.text?.toString() ?: "")
            }
        }
    }

    /** 切换分类：整体替换 */
    fun replaceAll(newItems: List<DouyinApi.VideoItem>) {
        // 稳定性优先：v2.28 的增量通知（remove+insert 组合）在切分类时触发
        // RecyclerView 一致性崩溃（点分类闪退的元凶），回滚为全量刷新。
        // 动画以后用 DiffUtil 单独补，不再用这个组合。
        items.clear()
        items.addAll(newItems)
        notifyDataSetChanged()
    }

    /** 分页追加 */
    fun append(newItems: List<DouyinApi.VideoItem>) {
        val from = items.size
        items.addAll(newItems)
        notifyItemRangeInserted(from, newItems.size)
    }

    fun size(): Int = items.size

    fun itemAt(i: Int): DouyinApi.VideoItem? = items.getOrNull(i)

    private fun fmtDur(ms: Int): String {
        if (ms <= 0) return "--:--"
        val s = ms / 1000
        return String.format("%02d:%02d", s / 60, s % 60)
    }

    private fun fmtCount(n: Int): String =
        if (n >= 10000) String.format("%.1f万", n / 10000f) else n.toString()
}
