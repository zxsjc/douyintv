package com.example.douyintv

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.util.Log
import org.json.JSONObject
import java.io.File
import java.net.HttpURLConnection
import java.net.URL

/**
 * 在线更新（GitHub Releases 渠道）
 * ==============================
 * 渠道设计：
 *   · 版本清单：仓库根目录 version.json（raw.githubusercontent.com 直读，无鉴权、无频率限制困扰）
 *   · APK 本体：GitHub Releases 的 assets（打 tag 发布时上传）
 *   · 客户端流程：拉清单 → 比对 versionCode → 有新版则弹窗（更新说明）→ 下载到
 *     filesDir/update.apk → 复用 FileProvider 唤起系统安装器（4.2 需手动确认，已知约束）
 *
 * 清单格式（version.json）：
 * {
 *   "versionCode": 116,
 *   "versionName": "2.16",
 *   "apkUrl": "https://github.com/<user>/<repo>/releases/download/v2.16/Dtv-2.16-release.apk",
 *   "notes": "1. xxx\n2. yyy",
 *   "force": false
 * }
 */
object UpdateChecker {

    private const val TAG = "Update"

    /** ★ 用户的发布渠道：https://github.com/zxsjc/douyintv/releases ★ */
    const val RELEASES_PAGE = "https://github.com/zxsjc/douyintv/releases"
    /** GitHub Releases API：取最新 release（tag=版本号 / assets=APK / body=更新说明） */
    const val RELEASES_API = "https://api.github.com/repos/zxsjc/douyintv/releases/latest"
    /** 兼容旧常量名（设置里若存过自定义清单地址仍走 version.json 通道） */
    const val DEFAULT_MANIFEST = "https://raw.githubusercontent.com/zxsjc/douyintv/main/version.json"
    const val CDN_MANIFEST = "https://cdn.jsdelivr.net/gh/zxsjc/douyintv@main/version.json"

    data class Info(
        val versionCode: Int,
        val versionName: String,
        val apkUrl: String,
        val notes: String,
        val sha256: String,
        val force: Boolean
    )

    /** 拉取版本清单；失败返回 null（静默失败，不打扰用户） */
    fun fetchManifest(ctx: Context): Info? {
        // ★ 默认走用户的 GitHub Releases（zxsjc/douyintv）。
        //   仅当用户在设置里显式配置了自定义 version.json 地址时，才走旧清单通道。
        val custom = Prefs.getUpdateUrl()
        if (custom.isNotBlank() && !custom.contains("CHANGE_ME")) {
            return fetchFromManifest(custom)
        }
        return fetchFromReleases()
    }

    /** 默认通道：GitHub Releases API 读最新 release，无需仓库里的 version.json */
    private fun fetchFromReleases(): Info? {
        return try {
            val conn = (URL(RELEASES_API).openConnection() as HttpURLConnection).apply {
                connectTimeout = 10000
                readTimeout = 10000
                setRequestProperty("User-Agent", "DTV-Updater")
                setRequestProperty("Accept", "application/vnd.github+json")
            }
            val text = conn.inputStream.bufferedReader().use { it.readText() }
            conn.disconnect()
            val o = JSONObject(text)
            // tag 形如 "2.31" / "v2.31" → versionName="2.31"，versionCode=231
            val tag = o.optString("tag_name", "")
            val vName = tag.removePrefix("v").removePrefix("V").ifBlank {
                o.optString("name", "").removePrefix("v")
            }
            if (vName.isBlank()) return null
            val vCode = versionNameToCode(vName)
            // 从 assets 里挑第一个 .apk
            var apkUrl = ""
            var sha256 = ""
            val assets = o.optJSONArray("assets")
            if (assets != null) {
                for (i in 0 until assets.length()) {
                    val a = assets.optJSONObject(i) ?: continue
                    val name = a.optString("name", "")
                    if (!name.endsWith(".apk", ignoreCase = true)) continue
                    apkUrl = a.optString("browser_download_url", "")
                    // GitHub 返回形如 "sha256:xxxx"
                    sha256 = a.optString("digest", "")
                        .removePrefix("sha256:").removePrefix("SHA256:")
                    break
                }
            }
            if (apkUrl.isBlank()) {
                Log.w(TAG, "最新 release 未附带 APK，跳过更新")
                return null
            }
            val notes = o.optString("body", "")
            Info(vCode, vName, apkUrl, notes, sha256, false)
        } catch (e: Exception) {
            Log.w(TAG, "Releases 获取失败: ${e.message}")
            null
        }
    }

    /** 旧清单通道：用户自定义 version.json（保留兼容） */
    private fun fetchFromManifest(url: String): Info? {
        return try {
            val conn = (URL(url).openConnection() as HttpURLConnection).apply {
                connectTimeout = 8000
                readTimeout = 8000
                setRequestProperty("User-Agent", "DTV-Updater")
            }
            val text = conn.inputStream.bufferedReader().use { it.readText() }
            conn.disconnect()
            val o = JSONObject(text)
            Info(
                o.optInt("versionCode", 0),
                o.optString("versionName", ""),
                o.optString("apkUrl", ""),
                o.optString("notes", ""),
                o.optString("sha256", ""),
                o.optBoolean("force", false)
            )
        } catch (e: Exception) {
            Log.w(TAG, "清单获取失败: ${e.message}")
            null
        }
    }

    /** "2.31" → 231；"2.9" → 209；保证十进制小进位单调递增 */
    private fun versionNameToCode(name: String): Int {
        val parts = name.split(".").mapNotNull { it.toIntOrNull() }
        var code = 0
        for (p in parts.take(3)) code = code * 100 + p
        return code
    }

    /** 与当前安装版本比对；有新版返回 Info，否则 null */
    fun check(ctx: Context): Info? {
        val info = fetchManifest(ctx) ?: return null
        if (info.apkUrl.isBlank()) return null
        // 优先按 versionName 逐段比较（Releases 的 tag 与显示版本一致，最可靠）；
        // versionName 为空时退回 versionCode 比较（兼容旧清单）。
        val curName = try { BuildConfig.VERSION_NAME } catch (_: Throwable) { "" }
        val newer = if (info.versionName.isNotBlank() && curName.isNotBlank()) {
            compareVersion(info.versionName, curName) > 0
        } else {
            val cur = try { BuildConfig.VERSION_CODE } catch (_: Throwable) { 0 }
            info.versionCode > cur
        }
        return if (newer) info else null
    }

    /** 语义化版本比较："2.31" > "2.9" > "2.8"。返回 1/0/-1 */
    private fun compareVersion(a: String, b: String): Int {
        val pa = a.removePrefix("v").split(".").mapNotNull { it.toIntOrNull() }
        val pb = b.removePrefix("v").split(".").mapNotNull { it.toIntOrNull() }
        val n = maxOf(pa.size, pb.size)
        for (i in 0 until n) {
            val x = pa.getOrElse(i) { 0 }
            val y = pb.getOrElse(i) { 0 }
            if (x != y) return if (x > y) 1 else -1
        }
        return 0
    }

    /**
     * 下载 APK 到 filesDir/update.apk（覆盖旧文件）。
     * onProgress(0..100)；成功返回文件，失败返回 null。
     */
    fun download(ctx: Context, apkUrl: String, expectedSha256: String = "", onProgress: (Int) -> Unit = {}): File? {
        val target = File(ctx.filesDir, "update.apk")
        return try {
            val conn = (URL(apkUrl).openConnection() as HttpURLConnection).apply {
                connectTimeout = 10000
                readTimeout = 20000
                instanceFollowRedirects = true
                setRequestProperty("User-Agent", "DTV-Updater")
            }
            // ★ contentLengthLong 是 API 24；目标机 4.2 上没有。
            //   用 Content-Length 响应头解析（API 1 就有），拿不到再退化成 contentLength。
            val headerLen = conn.getHeaderField("Content-Length")?.trim()?.toLongOrNull()
            @Suppress("DEPRECATION")
            val legacyLen = conn.contentLength.toLong()
            val total = (headerLen ?: legacyLen).coerceAtLeast(0L)
            conn.inputStream.use { input ->
                target.outputStream().use { out ->
                    val buf = ByteArray(128 * 1024)
                    var got = 0L
                    while (true) {
                        val n = input.read(buf)
                        if (n < 0) break
                        out.write(buf, 0, n)
                        got += n
                        if (total > 0) onProgress((got * 100 / total).toInt())
                    }
                }
            }
            conn.disconnect()
            onProgress(100)
            // ★ 下载完成 → SHA-256 校验（version.json 提供期望值，防篡改/防坏包）
            if (expectedSha256.isNotBlank() && !sha256Of(target).equals(expectedSha256.trim().lowercase(), ignoreCase = true)) {
                Log.e(TAG, "SHA-256 校验失败，拒绝安装（期望 ${expectedSha256.take(12)}… 实际 ${sha256Of(target).take(12)}…）")
                target.delete()
                return null
            }
            target
        } catch (e: Exception) {
            Log.e(TAG, "下载失败: ${e.message}")
            null
        }
    }

    /** 计算文件 SHA-256（小写 hex） */
    private fun sha256Of(file: File): String {
        return try {
            val md = java.security.MessageDigest.getInstance("SHA-256")
            file.inputStream().use { ins ->
                val buf = ByteArray(128 * 1024)
                while (true) {
                    val n = ins.read(buf)
                    if (n < 0) break
                    md.update(buf, 0, n)
                }
            }
            md.digest().joinToString("") { String.format("%02x", it) }
        } catch (e: Exception) {
            Log.e(TAG, "SHA-256 计算失败: ${e.message}")
            ""
        }
    }

    /** 唤起系统安装器（走 FileProvider，targetSdk 26 不允许裸 file://） */
    fun install(ctx: Context, expectedSha256: String = ""): Boolean {
        return try {
            val f = File(ctx.filesDir, "update.apk")
            if (!f.exists()) return false
            if (expectedSha256.isNotBlank() && !sha256Of(f).equals(expectedSha256.trim().lowercase(), ignoreCase = true)) {
                Log.e(TAG, "安装前 SHA-256 校验失败，拒绝安装")
                return false
            }
            val uri: Uri = androidx.core.content.FileProvider.getUriForFile(
                ctx, ctx.packageName + ".fileprovider", f)
            val i = Intent(Intent.ACTION_VIEW).apply {
                setDataAndType(uri, "application/vnd.android.package-archive")
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }
            ctx.startActivity(i)
            true
        } catch (e: Exception) {
            Log.e(TAG, "唤起安装器失败: ${e.message}")
            false
        }
    }
}
