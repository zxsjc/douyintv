package com.example.douyintv

import android.content.Context
import android.graphics.Canvas
import android.graphics.RectF
import android.util.AttributeSet
import android.view.View

/**
 * 黑边遮罩：把「视频内容矩形」以外的区域涂黑。
 *
 * 为什么需要它：非 Exo 引擎（IJK / 系统）不能改 SurfaceView 尺寸（一改就走
 * surfaceDestroyed → release → 整段重播），只能用图层缩放把画面比例扳回来；
 * 缩放后 Surface 图层周围会露出底层，个别 ROM 上 screencap/SurfaceFlinger
 * 会把它显示成白块。这里直接在最上层画黑，保证四周永远是黑边。
 *
 * 只画不拦：不获取焦点、不响应触摸，坐标由 PlayerActivity 每次视频尺寸变化时下发。
 */
class BlackBarMaskView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyle: Int = 0
) : View(context, attrs, defStyle) {

    private val content = RectF()
    private var hasContent = false

    init {
        isFocusable = false
        isClickable = false
        setWillNotDraw(false)
    }

    /** 视频实际显示区域（像素）；传空表示无需遮罩 */
    fun setContentRect(left: Float, top: Float, right: Float, bottom: Float) {
        content.set(left, top, right, bottom)
        hasContent = right > left && bottom > top
        invalidate()
    }

    fun clear() {
        if (hasContent) {
            hasContent = false
            invalidate()
        }
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        if (!hasContent) return
        val w = width.toFloat()
        val h = height.toFloat()
        // 左、右、上、下四条黑带
        if (content.left > 0f) canvas.drawRect(0f, 0f, content.left, h, PAINT)
        if (content.right < w) canvas.drawRect(content.right, 0f, w, h, PAINT)
        if (content.top > 0f) canvas.drawRect(content.left, 0f, content.right, content.top, PAINT)
        if (content.bottom < h) canvas.drawRect(content.left, content.bottom, content.right, h, PAINT)
    }

    private companion object {
        val PAINT = android.graphics.Paint().apply { color = 0xFF000000.toInt() }
    }
}
