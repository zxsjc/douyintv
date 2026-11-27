package com.example.douyintv

import android.app.Activity
import android.app.AlertDialog
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Color
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.view.KeyEvent
import android.view.View
import android.view.WindowManager
import android.view.animation.DecelerateInterpolator
import android.widget.EditText
import android.widget.ImageView
import android.widget.TextView
import android.widget.Toast
import com.google.zxing.BarcodeFormat
import com.google.zxing.EncodeHintType
import com.google.zxing.qrcode.QRCodeWriter

/**
 * 登录页（独立）
 * ==============
 * 从左侧导航「登录」进入。设置页只留诊断，不再混登录。
 *
 * 为什么是「手机推送 Cookie」而不是账号密码：
 *   抖音的登录接口（login.douyin.com / sso.douyin.com 的 get_qrcode）实测做不了 ——
 *   直接请求返回验证码要求，换 sso 域名路由被封（403），换移动端 UA 直接
 *   4031「存在安全风险，已阻止此次访问」。它要求浏览器 JS 环境 + 强风控，
 *   老盒子（API 17 WebView）跑不动，纯 HTTP 也过不去。所以登录态只能靠
 *   「手机/电脑上已登录的浏览器把 Cookie 导过来」。
 *
 * 于是这一页的价值就是把这件事做顺：
 *   · 二维码直通推送页（手机扫一下就跳到粘贴界面，不用手打地址）
 *   · 状态实时刷新（手机那边一保存，电视这边立刻变「已登录」）
 *   · 有明确的「退出登录」
 */
class LoginActivity : Activity() {

    private companion object {
        private const val TAG = "Login"
    }

    private lateinit var ivQr: ImageView
    private lateinit var tvUrl: TextView
    private lateinit var tvStatus: TextView
    private lateinit var tvHint: TextView
    private lateinit var btnManual: TextView
    private lateinit var btnLogout: TextView
    private lateinit var btnStart: TextView
    private val handler = Handler(Looper.getMainLooper())

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        try {
            window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
            setContentView(R.layout.activity_login)

            ivQr = findViewById(R.id.ivQr) as ImageView
            tvUrl = findViewById(R.id.tvUrl) as TextView
            tvStatus = findViewById(R.id.tvStatus) as TextView
            tvHint = findViewById(R.id.tvHint) as TextView
            btnManual = findViewById(R.id.btnManual) as TextView
            btnLogout = findViewById(R.id.btnLogout) as TextView
            btnStart = findViewById(R.id.btnStart) as TextView

            // ★ Cookie 推送服务已由 App 常驻启动（ConfigServer.startApp）
            render()
            handler.post(refreshTick)   // 手机端保存 Cookie 后轮询刷新

            setupTvFocus(btnManual, btnLogout, btnStart)
            btnManual.setOnClickListener { manualDialog() }
            btnLogout.setOnClickListener { confirmLogout() }
            btnStart.setOnClickListener { goPlay() }

            btnManual.post { btnManual.requestFocus() }
        } catch (t: Throwable) {
            Log.e(TAG, "onCreate 失败", t)
        }
    }

    /** 轮询刷新登录态（手机保存 Cookie 后本页自动更新） */
    private val refreshTick = object : Runnable {
        private var lastCookie = ""
        override fun run() {
            try {
                val c = Prefs.getCookie(this@LoginActivity)
                if (c != lastCookie) { lastCookie = c; render() }
            } catch (_: Throwable) {}
            if (!isFinishing) handler.postDelayed(this, 1500)
        }
    }

    private fun setupTvFocus(vararg views: View) {
        views.forEach { v ->
            v.isFocusable = true
            v.isFocusableInTouchMode = true
            v.setOnFocusChangeListener { view, hasFocus -> FocusAnim.scaleTo(view, hasFocus, 1.03f) }
        }
    }

    private fun urlText(): String {
        val ip = ConfigServer.localIp() ?: "0.0.0.0"
        return "http://$ip:${ConfigServer.PORT}"
    }

    private fun render() {
        val url = urlText()
        tvUrl.text = url
        ivQr.setImageBitmap(makeQr(url, 440))

        if (Prefs.hasCookie(this)) {
            val n = Prefs.getCookie(this).length
            tvStatus.text = "✓ 已登录（登录态 $n 字符）"
            tvStatus.setTextColor(0xFF81C784.toInt())
            btnStart.isEnabled = true
            btnLogout.visibility = View.VISIBLE
            tvHint.text = "想换账号：手机/电脑浏览器打开 $url 重新粘贴新 Cookie 即可；\n" +
                "或先「退出登录」清掉再登。"
        } else {
            tvStatus.text = "✗ 未登录"
            tvStatus.setTextColor(0xFFF06292.toInt())
            btnStart.isEnabled = false
            btnLogout.visibility = View.GONE
            tvHint.text = "登录方式：\n" +
                "① 手机扫左边二维码 → 浏览器打开推送页 → 粘贴 Cookie 保存（本页自动更新）\n" +
                "② 也可以直接点下面「手动输入 Cookie」在这台电视上粘贴"
        }
    }

    private fun makeQr(text: String, size: Int): Bitmap? = try {
        val hints = HashMap<EncodeHintType, Any>()
        hints[EncodeHintType.MARGIN] = 1
        hints[EncodeHintType.CHARACTER_SET] = "UTF-8"
        val m = QRCodeWriter().encode(text, BarcodeFormat.QR_CODE, size, size, hints)
        val bmp = Bitmap.createBitmap(size, size, Bitmap.Config.RGB_565)
        for (x in 0 until size) for (y in 0 until size) {
            bmp.setPixel(x, y, if (m.get(x, y)) Color.BLACK else Color.WHITE)
        }
        bmp
    } catch (e: Exception) {
        Log.e(TAG, "二维码生成失败", e); null
    }

    // ---------------- 手动输入兜底 ----------------
    private fun manualDialog() {
        val et = EditText(this)
        et.hint = "粘贴 Cookie（分号串或 Cookie Editor 的 JSON）"
        et.setText(Prefs.getCookie(this))
        et.isSingleLine = false
        et.minLines = 4
        AlertDialog.Builder(this)
            .setTitle("手动输入 Cookie")
            .setView(et)
            .setPositiveButton("保存") { _, _ ->
                Prefs.setCookie(this, et.text.toString())
                render()
                CustomToast.show(this, "已登录 ✓")
            }
            .setNegativeButton("取消", null)
            .show()
    }

    private fun confirmLogout() {
        AlertDialog.Builder(this)
            .setTitle("退出登录")
            .setMessage("将清除本机保存的登录态，之后需要用手机重新推送 Cookie 登录。")
            .setPositiveButton("退出") { _, _ ->
                Prefs.setCookie(this, "")
                render()
                CustomToast.show(this, "已退出登录")
            }
            .setNegativeButton("取消", null)
            .show()
    }

    private fun goPlay() {
        if (!Prefs.hasCookie(this)) {
            CustomToast.show(this, "请先登录")
            return
        }
        startActivity(Intent(this, MainActivity::class.java))
        finish()
    }

    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        if (event.action == KeyEvent.ACTION_DOWN && event.keyCode == KeyEvent.KEYCODE_BACK) {
            finish(); return true
        }
        return super.dispatchKeyEvent(event)
    }

    override fun onDestroy() {
        super.onDestroy()
    }
}
