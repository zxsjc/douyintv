package com.example.douyintv

import android.content.Context
import android.util.Log
import fi.iki.elonen.NanoHTTPD
import java.net.Inet4Address
import java.net.NetworkInterface

/**
 * 局域网 Cookie 推送服务（8088）
 * ============================
 * 只做一件事：手机上打开 `http://电视IP:8088` → 粘贴浏览器 Cookie → 保存到电视。
 *
 * ★ 2.35：手机遥控功能整体移除 —— 之前的 /remote 遥控页、/pair 配对、/cmd 命令、
 *   /hot、/search、/clients 白名单、/apk 分发、/status 播放器快照、以及 UDP 发现
 *   （DiscoveryServer）和 WebSocket（WSServer）两条控制通道全部删除。
 *   这里只剩「推送登录」这条链路：`/`、`/save`、`/savecreds`、`/status`。
 */
class ConfigServer(
    private val appCtx: Context,
    private val onSaved: (String) -> Unit
) : NanoHTTPD(PORT) {

    companion object {
        const val PORT = 8088
        private const val TAG = "ConfigServer"

        /** App 级常驻实例（App.onCreate 启动） */
        @Volatile
        var appServer: ConfigServer? = null
            private set

        /** 启动常驻服务（幂等：已在跑就复用） */
        fun startApp(ctx: Context) {
            if (appServer != null) return
            synchronized(this) {
                if (appServer != null) return
                try {
                    val s = ConfigServer(ctx) { /* 常驻实例的 Cookie 保存回调为空：登录页自己轮询刷新 */ }
                    s.start(NanoHTTPD.SOCKET_READ_TIMEOUT, false)
                    appServer = s
                    Log.i(TAG, "✓ Cookie 推送服务启动: http://${localIp()}:$PORT")
                } catch (e: Exception) {
                    Log.e(TAG, "服务启动失败: ${e.message}")
                }
            }
        }

        /** 本机局域网 IPv4（登录页展示推送地址 / 生成二维码用） */
        fun localIp(): String? = try {
            NetworkInterface.getNetworkInterfaces().toList()
                .flatMap { it.inetAddresses.toList() }
                .filterIsInstance<Inet4Address>()
                .firstOrNull { !it.isLoopbackAddress && it.isSiteLocalAddress }
                ?.hostAddress
        } catch (e: Exception) {
            null
        }
    }

    override fun serve(session: IHTTPSession): Response {
        val uri = session.uri ?: "/"
        Log.d(TAG, "${session.method} $uri")
        return when {
            uri == "/" || uri.startsWith("/index") -> page()
            uri == "/save" -> save(session)
            uri == "/savecreds" -> saveCreds(session)
            uri == "/status" -> status()
            else -> newFixedLengthResponse(Response.Status.NOT_FOUND, "text/plain; charset=utf-8", "404")
        }
    }

    /** 推送页状态：Cookie 是否已配置、缺哪些关键键、WebProtect 凭据缺哪些 */
    private fun status(): Response {
        val cookie = Prefs.getCookie(appCtx)
        val missing = if (cookie.isBlank()) emptyList() else Prefs.missingKeyCookies(cookie)
        val creds = mutableListOf<String>()
        if (Prefs.getEcPrivateKey().isBlank()) creds.add("EC私钥")
        if (Prefs.getTicket().isBlank()) creds.add("ticket")
        if (Prefs.getTsSign().isBlank()) creds.add("ts_sign")
        val version = try { BuildConfig.VERSION_NAME } catch (_: Throwable) { "2.x" }
        val sb = StringBuilder()
        sb.append("{\"hasCookie\":").append(Prefs.hasCookie(appCtx))
        sb.append(",\"version\":\"").append(version).append("\"")
        sb.append(",\"missing\":[").append(missing.joinToString(",") { "\"$it\"" }).append("]")
        sb.append(",\"wpMissing\":[").append(creds.joinToString(",") { "\"$it\"" }).append("]")
        sb.append("}")
        return newFixedLengthResponse(Response.Status.OK, "application/json; charset=utf-8", sb.toString())
    }

    /**
     * 导入浏览器 localStorage 里的 WebProtect 凭据（点赞用，可选）。
     * 文本格式：每行 key=value 或 key:value。
     */
    private fun saveCreds(session: IHTTPSession): Response {
        return try {
            val files = HashMap<String, String>()
            session.parseBody(files)
            val raw = (files["postData"] ?: "").trim()
            if (raw.isBlank()) {
                newFixedLengthResponse(Response.Status.OK, "text/plain; charset=utf-8", "内容为空，未保存")
            } else {
                val saved = Prefs.importLocalStorageCreds(appCtx, raw)
                newFixedLengthResponse(
                    Response.Status.OK, "text/plain; charset=utf-8",
                    if (saved.isEmpty()) "没有识别到 WebProtect 凭据键。\n确认导出了 https://www.douyin.com 的 localStorage（key=value 每行一个）"
                    else "已导入: ${saved.joinToString(", ")} ✓")
            }
        } catch (e: Exception) {
            Log.e(TAG, "凭据保存失败", e)
            newFixedLengthResponse(Response.Status.INTERNAL_ERROR, "text/plain; charset=utf-8", "失败: ${e.message}")
        }
    }

    private fun save(session: IHTTPSession): Response {
        return try {
            val files = HashMap<String, String>()
            session.parseBody(files)
            val raw = (files["postData"] ?: "").trim()
            // ★ 浏览器真实 UA：推送页 JS 用 ?ua=… 带上（写操作风控要求 UA 与会话一致）
            val ua = session.parameters["ua"]?.firstOrNull() ?: ""
            if (raw.isBlank()) {
                newFixedLengthResponse(Response.Status.OK, "text/plain; charset=utf-8", "内容为空，未保存")
            } else {
                Prefs.setCookie(appCtx, raw)
                if (ua.isNotBlank()) Prefs.setBrowserUa(appCtx, ua)
                // ★ 自动薅取 Cookie 里能解出的 WebProtect 凭据
                val c = Prefs.getCookie(appCtx)
                val auto = mutableListOf<String>()
                val webid = Prefs.extractWebidFromCookie(c)
                if (webid.isNotBlank()) { Prefs.setWebid(appCtx, webid); auto.add("webid=${webid.take(6)}…") }
                val (pub, cfg) = Prefs.parseTicketGuardClientData(c)
                if (pub.isNotBlank()) {
                    Prefs.setServerCert(appCtx, pub)
                    auto.add("客户端公钥(" + pub.take(8) + "…)")
                    Prefs.setTicket(appCtx, cfg)
                }
                val tsId = Prefs.cookieValue(c, "bd_ticket_guard_ts_sign_id")
                if (tsId.isNotBlank()) Prefs.setTsSign(appCtx, tsId)
                onSaved(c)
                val missing = Prefs.missingKeyCookies(c)
                val warn = if (missing.isEmpty()) " ✓ Cookie 完整，可刷视频+点赞"
                    else "\n⚠ 缺少关键 Cookie: ${missing.joinToString(", ")}\n请用浏览器全选复制（含 msToken/webid），否则点赞会被判定未登录"
                Log.d(TAG, "Cookie 已保存, 长度=${c.length}, 缺=${missing.joinToString(",")}, 自动薅取=${auto.joinToString(",")}")
                newFixedLengthResponse(
                    Response.Status.OK, "text/plain; charset=utf-8",
                    "保存成功 ✓  电视已可以刷视频了$warn" +
                    (if (auto.isEmpty()) "" else "\n自动薅取: ${auto.joinToString("、")}")
                )
            }
        } catch (e: Exception) {
            Log.e(TAG, "保存失败", e)
            newFixedLengthResponse(Response.Status.INTERNAL_ERROR, "text/plain; charset=utf-8", "保存失败: ${e.message}")
        }
    }

    /** 推送页（手机/电脑浏览器打开，粘贴 Cookie） */
    private fun page(): Response {
        val html = """
<!DOCTYPE html>
<html lang="zh-CN">
<head>
<meta charset="utf-8">
<meta name="viewport" content="width=device-width,initial-scale=1">
<title>Dtv · Cookie 登录</title>
<style>
  *{box-sizing:border-box;-webkit-tap-highlight-color:transparent}
  body{margin:0;min-height:100vh;display:flex;align-items:center;justify-content:center;
       padding:20px;background:#0b0b0f;color:#fff;
       font-family:-apple-system,BlinkMacSystemFont,"Segoe UI",Roboto,sans-serif}
  .card{width:100%;max-width:600px;background:#16161d;border:1px solid #23232e;
        border-radius:20px;padding:26px;box-shadow:0 24px 70px rgba(0,0,0,.65)}
  h1{margin:0 0 4px;font-size:21px;letter-spacing:.3px}
  .sub{color:#8a8a99;font-size:13px;margin-bottom:18px}
  .status{padding:11px 14px;border-radius:11px;font-size:13px;margin-bottom:16px;font-weight:500}
  .ok{background:rgba(76,175,80,.14);color:#81c784;border:1px solid rgba(76,175,80,.25)}
  .no{background:rgba(233,30,99,.14);color:#f06292;border:1px solid rgba(233,30,99,.25)}
  textarea{width:100%;height:190px;background:#0e0e13;color:#dcdce4;border:1px solid #2a2a35;
           border-radius:12px;padding:13px;font-size:12px;line-height:1.55;resize:vertical;
           outline:none;font-family:ui-monospace,SFMono-Regular,Menlo,Consolas,monospace;transition:.15s}
  textarea:focus{border-color:#E91E63;box-shadow:0 0 0 3px rgba(233,30,99,.12)}
  textarea::placeholder{color:#4a4a58}
  button{width:100%;margin-top:14px;padding:15px;border:0;border-radius:12px;background:#E91E63;
         color:#fff;font-size:16px;font-weight:600;cursor:pointer;transition:.15s}
  button:hover{background:#d1165a}
  button:active{transform:scale(.985)}
  button:disabled{opacity:.55;cursor:default}
  .tip{margin-top:16px;padding-top:14px;border-top:1px solid #23232e;color:#6a6a7a;font-size:12px;line-height:1.85}
  .tip b{color:#9a9aa8;font-weight:600}
  .sep{height:1px;background:#23232e;margin:20px 0}
  .lab{font-size:13px;color:#9a9aa8;margin-bottom:9px;font-weight:600}
  .sres{margin-top:10px;font-size:13px;color:#81c784;white-space:pre-line;min-height:18px}
  textarea.short{height:96px;font-size:12px}
  code{background:#0e0e13;padding:2px 6px;border-radius:5px;color:#c8c8d4;font-size:11px}
</style>
</head>
<body>
<div class="card">
  <h1>Dtv 登录</h1>
  <div class="sub">把电脑浏览器的 Cookie 粘贴进来，点保存</div>
  <div id="st" class="status no">检测中…</div>
  <textarea id="ck" placeholder="支持两种格式：&#10;&#10;1) 分号串：  ttwid=xxx; odin_tt=yyy; ...&#10;&#10;2) Cookie Editor 导出的 JSON 数组&#10;[{&quot;name&quot;:&quot;ttwid&quot;,&quot;value&quot;:&quot;xxx&quot;}, ...]"></textarea>
  <button id="btn" onclick="save()">保存到电视</button>
  <div class="sep"></div>
  <div class="lab">WebProtect 凭据（点赞用，可选）</div>
  <textarea id="wp" class="short" placeholder="浏览器 F12 → Console 执行下面命令复制结果，粘到这里点导入：&#10;&#10;copy(Object.entries(localStorage).filter(x=&gt;x[1].length&gt;40).map(x=&gt;x[0]+&#39;=&#39;+x[1]).join(&#39;\n&#39;))"></textarea>
  <button id="wpbtn" onclick="saveCreds()">导入 WebProtect 凭据</button>
  <div class="sres" id="wpres"></div>
  <div class="tip">
    <b>怎么拿 Cookie？</b><br>
    电脑 Chrome 登录 <code>douyin.com</code> → F12 → Application → Cookies → <code>https://www.douyin.com</code> → 全选复制<br>
    或装 <b>Cookie Editor</b> 插件 → Export → 选 JSON
  </div>
</div>
<script>
function refresh(){
  fetch('/status').then(function(r){return r.json()}).then(function(j){
    var el=document.getElementById('st');
    if(j.hasCookie){
      var extra=[];
      if(j.missing && j.missing.length>0){el.className='status no';extra.push('缺 Cookie: '+j.missing.join('/'))}
      else {el.className='status ok'}
      if(j.wpMissing && j.wpMissing.length>0){extra.push('缺 WebProtect: '+j.wpMissing.join('/'))}
      el.textContent = extra.length>0 ? '✓ 已配置，但 '+extra.join('；') : '✓ 电视已配置 Cookie + WebProtect，可以点赞了';
    }
    else{el.className='status no';el.textContent='✗ 尚未配置 Cookie'}
  }).catch(function(){});
}
function saveCreds(){
  var v=document.getElementById('wp').value.trim();
  if(!v){alert('请先粘贴 localStorage 内容');return}
  var b=document.getElementById('wpbtn');
  b.disabled=true;b.textContent='导入中…';
  fetch('/savecreds',{method:'POST',headers:{'Content-Type':'text/plain'},body:v})
    .then(function(r){return r.text()})
    .then(function(t){
      document.getElementById('wpres').textContent=t;
      document.getElementById('wp').value='';
      b.disabled=false;b.textContent='导入 WebProtect 凭据';
      refresh();
    })
    .catch(function(e){document.getElementById('wpres').textContent='失败: '+e;b.disabled=false;b.textContent='导入 WebProtect 凭据'});
}
function save(){
  var v=document.getElementById('ck').value.trim();
  if(!v){alert('请先粘贴 Cookie');return}
  var b=document.getElementById('btn');
  b.disabled=true;b.textContent='保存中…';
  fetch('/save?ua='+encodeURIComponent(navigator.userAgent),
        {method:'POST',headers:{'Content-Type':'text/plain'},body:v})
    .then(function(r){return r.text()})
    .then(function(t){
      alert(t);
      document.getElementById('ck').value='';
      b.disabled=false;b.textContent='保存到电视';
      refresh();
    })
    .catch(function(e){alert('保存失败: '+e);b.disabled=false;b.textContent='保存到电视'});
}
refresh();
</script>
</body>
</html>
        """.trimIndent()
        return newFixedLengthResponse(Response.Status.OK, "text/html; charset=utf-8", html)
    }
}
