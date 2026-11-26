package com.example.douyintv

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.util.AttributeSet
import android.view.View
import java.util.Random

/**
 * 轻量弹幕层（纯 Canvas 绘制，无第三方库，API 17 兼容）。
 *
 * 文案由播放器喂入（默认取当前视频的评论区文案），从右向左飞过画面。
 * 透明叠加在视频之上：不拦截焦点/触摸，纯被动显示。
 *
 * ★★ 2.34 关键修复 ★★
 *   本类此前只有「动画循环」(tick / maybeSpawn) —— 数据拉到了、定时器也在跑、
 *   items 里也有条目，但**从来没有 onDraw**：一帧都没画到画布上。
 *   于是表现成「弹幕开关是开的，屏幕上却什么都没有」，而日志里评论区明明
 *   拿到了 29 条文案。现在补齐真正的绘制。
 *
 * ★ 视频区域限制：竖屏视频两侧有大黑边，弹幕只允许在真实画面区域内飞，
 *   由 PlayerActivity 调 setBand() 告知左右边界；未设置时退化为整屏宽度。
 *
 * ★ 2.35：显示行数（默认 3 行）与位置（默认顶部）可配置 —— 设置页写 Prefs，
 *   PlayerActivity 用 setLayoutConfig() 喂进来。
 */
class DanmakuView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyle: Int = 0
) : View(context, attrs, defStyle) {

    private class Item(
        val text: String,
        val paint: Paint,      // 自带字号/颜色的画笔
        var x: Float,          // 当前左边缘 x（px）
        val y: Float,          // 基线 y（px）
        val speed: Float,      // px / 秒
        val tw: Float,         // 文字宽度（px，缓存，避免每帧重新测量）
        val lane: Int          // 所在横道（0..laneCount-1）
    )

    private companion object {
        const val LANES_MAX = 5             // 横道数量上限（设置里可选 1~5，默认 3）
        const val MAX_ITEMS = 24            // 同屏上限（A7 上也能跑满 30fps）
        const val SPAWN_GAP_MS = 240L       // 发射间隔 ≈ 4 条/秒（再密就糊成一片）
        const val CROSS_SECONDS = 8f        // 一条弹幕横穿画面的耗时
        const val MAX_TEXT = 28             // 单条字数上限（抖音评论常常是整段话）
        const val FRAME_MS = 33L            // ≈30fps
    }

    private val items = ArrayList<Item>()
    private val handler = Handler(Looper.getMainLooper())
    private var running = false
    private var pool = ArrayList<String>()     // 待发射文案池
    private var poolIdx = 0
    private val rnd = Random()
    private var lastFrame = 0L
    private var lastSpawn = 0L

    // 画面可视区域（默认 0 表示「未设置 → 用整屏」）
    private var bandL = 0f
    private var bandR = 0f

    // 排版配置（设置页可改）：行数 1~LANES_MAX，位置 top/middle/bottom
    private var laneCount = 3
    private var posMode = "top"

    // 白（主力）/ 黄 / 品牌红 / 绿 / 蓝 / 橙
    private val COLORS = intArrayOf(
        0xFFFFFFFF.toInt(),
        0xFFFFE066.toInt(),
        0xFFFE2C55.toInt(),
        0xFF81C784.toInt(),
        0xFF64B5F6.toInt(),
        0xFFFFB74D.toInt()
    )

    /** 黑描边：画在彩色字右下方 2px 处，保证亮背景（白墙/浅色画面）上也看得清 */
    private val outlinePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = 0xE6000000.toInt()
        isFakeBoldText = true
    }

    init {
        // 纯显示层：绝不抢焦点、不拦遥控器
        isClickable = false
        isFocusable = false
        importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
    }

    private fun spanLeft(): Float = if (bandR > bandL) bandL else 0f
    private fun spanRight(): Float = if (bandR > bandL) bandR else width.toFloat()

    /** 字号随屏宽变化（1920 → ≈35px），电视远距离观看足够清楚 */
    private fun textSizePx(): Float = (width * 0.0185f).coerceIn(20f, 38f)

    /**
     * 排版配置：显示行数 + 落点位置（由 PlayerActivity 从 Prefs 读出后喂进来）。
     * 配置变了就清屏重排，避免新旧横道混着看着乱。
     */
    fun setLayoutConfig(lines: Int, position: String) {
        val n = lines.coerceIn(1, LANES_MAX)
        val pos = if (position == "middle" || position == "bottom") position else "top"
        if (n == laneCount && pos == posMode) return
        laneCount = n
        posMode = pos
        items.clear()
        invalidate()
    }

    /**
     * 第 lane 条横道的基线 y。
     *   top    → 从画面顶部往下（默认，避开顶端 8% 的标题/信息条）
     *   middle → 画面正中一条带
     *   bottom → 画面底部往上，留出底部控制栏的空当
     */
    private fun laneBaseY(lane: Int): Float {
        val h = height.toFloat()
        val size = textSizePx()
        val laneH = h * 0.10f
        val total = laneH * laneCount
        val start = when (posMode) {
            "middle" -> (h - total) / 2f
            "bottom" -> h * 0.88f - total
            else -> h * 0.085f
        }
        return start + lane * laneH + size * 1.15f
    }

    /**
     * 告知「真实画面」的左右边界（竖向铺满，横向用于限制弹幕范围）。
     * 竖屏视频两侧是黑边，弹幕只应在画面里飞；变化明显时才清屏重排。
     */
    fun setBand(left: Float, right: Float) {
        val l = left.coerceAtLeast(0f)
        val r = if (right > l + 1f) right else width.toFloat()
        if (Math.abs(l - bandL) < 20f && Math.abs(r - bandR) < 20f) return
        bandL = l
        bandR = r
        items.clear()
    }

    private val tick = object : Runnable {
        override fun run() {
            if (!running) return
            val now = SystemClock.uptimeMillis()
            val dt = if (lastFrame == 0L) 0.033f
                     else ((now - lastFrame) / 1000f).coerceIn(0.001f, 0.12f)
            lastFrame = now
            if (width > 0 && height > 0) {
                // ① 移动 + 出画面回收
                var i = 0
                while (i < items.size) {
                    val it = items[i]
                    it.x -= it.speed * dt
                    if (it.x + it.tw < spanLeft() - 20f) items.removeAt(i) else i++
                }
                // ② 按节奏发射
                var guard = 0
                while (now - lastSpawn >= SPAWN_GAP_MS && items.size < MAX_ITEMS
                    && pool.isNotEmpty() && guard < 4) {
                    if (!spawnOne(now)) break
                    guard++
                }
                // ③ 真正画出来（此前缺的就是这一步）
                invalidate()
            }
            handler.postDelayed(this, FRAME_MS)
        }
    }

    /** 发一条弹幕；当前横道都挤着时返回 false（本帧不发） */
    private fun spawnOne(now: Long): Boolean {
        if (width <= 0 || height <= 0 || pool.isEmpty()) return false
        val lane = pickLane() ?: return false

        val text = pool[poolIdx]
        poolIdx = (poolIdx + 1) % pool.size

        val size = textSizePx()
        val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            textSize = size
            color = COLORS[rnd.nextInt(COLORS.size)]
            isFakeBoldText = true
        }
        val tw = paint.measureText(text)

        val y = laneBaseY(lane)
        val right = spanRight()
        // ★ 2.36 关键修复：速度只按「屏幕宽度」算，与文字长短无关 —— 所有弹幕同速。
        //   旧写法 (宽度 + 文字宽) / 8s：字越长跑得越快，后出的长句会从右边追上
        //   前面那句（甚至直接盖在它上面），看起来就像「后面的弹幕撞了前面的」。
        //   同速之后横道内的间距只由发射时的 gap 决定，永不重合。
        val speed = (right - spanLeft()) / CROSS_SECONDS

        items.add(Item(text, paint, right + 8f, y, speed, tw, lane))
        lastSpawn = now
        return true
    }

    /**
     * 选一条「最后一条弹幕已经飞出去足够远」的横道，防止文字叠在一起。
     * 全部挤着就返回 null，等下一帧再试。
     */
    private fun pickLane(): Int? {
        val right = spanRight()
        val rightEdge = FloatArray(laneCount)
        for (l in 0 until laneCount) rightEdge[l] = -1e7f
        for (it in items) {
            if (it.lane >= laneCount) continue
            val r = it.x + it.tw
            if (r > rightEdge[it.lane]) rightEdge[it.lane] = r
        }
        // 同速前提下，间距在发射瞬间就决定了，之后不会变化 → 只要「上一条的
        // 右边缘离右边界还有 gap 以上」就算空闲。gap 给到 3 倍字高，视觉上不挤。
        val gap = textSizePx() * 3f
        val free = ArrayList<Int>(laneCount)
        for (l in 0 until laneCount) if (rightEdge[l] <= right - gap) free.add(l)
        return if (free.isEmpty()) null else free[rnd.nextInt(free.size)]
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        if (items.isEmpty()) return
        // 只画在真实画面区域里：竖屏视频的黑边上不该出现弹幕
        val l = spanLeft()
        val r = spanRight()
        canvas.save()
        canvas.clipRect(l, 0f, r, height.toFloat())
        for (it in items) {
            outlinePaint.textSize = it.paint.textSize
            canvas.drawText(it.text, it.x + 2f, it.y + 2f, outlinePaint)   // 黑描边
            canvas.drawText(it.text, it.x, it.y, it.paint)                  // 彩色字
        }
        canvas.restore()
    }

    /** 播放器喂入弹幕文案（切视频时重新 setContent） */
    fun setContent(texts: List<String>) {
        val clean = ArrayList<String>(texts.size)
        for (raw in texts) {
            var t = raw.replace('\r', ' ').replace('\n', ' ')
                .replace(Regex("\\s+"), " ").trim()
            if (t.isEmpty()) continue
            if (t.length > MAX_TEXT) t = t.substring(0, MAX_TEXT) + "…"
            clean.add(t)
        }
        pool = clean
        poolIdx = 0
        items.clear()
        lastSpawn = 0
        lastFrame = 0
        invalidate()
    }

    /** 开/关：开启启动动画循环；关闭停帧并清屏 */
    fun setRunning(on: Boolean) {
        if (running == on) return
        running = on
        handler.removeCallbacks(tick)
        if (on) {
            lastFrame = 0L
            // 立刻允许发射第一条（否则要等满一个 240ms 间隔）
            lastSpawn = SystemClock.uptimeMillis() - SPAWN_GAP_MS
            handler.post(tick)
        } else {
            items.clear()
            invalidate()
        }
    }

    override fun onDetachedFromWindow() {
        handler.removeCallbacks(tick)
        running = false
        super.onDetachedFromWindow()
    }
}
