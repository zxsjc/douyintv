package com.yanzi.music;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.Context;
import android.content.DialogInterface;
import android.content.Intent;
import android.content.pm.PackageInfo;
import android.content.pm.PackageManager;
import android.net.Uri;
import android.os.Build;
import android.provider.Settings;
import android.util.TypedValue;
import android.view.ViewGroup;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.TextView;
import android.widget.Toast;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.RandomAccessFile;
import java.net.HttpURLConnection;
import java.net.URL;
import java.net.URLEncoder;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * APP 内更新（数据源：GitHub Releases https://github.com/zxsjc/YanZiMusic/releases）。
 *
 * 流程：查 releases/latest → 用 tag 里的版本号跟当前 versionName 比 → 有新版才弹窗 →
 * 下载 .apk 到应用缓存目录（带进度）→ 校验包名 / versionCode → 走本应用自带的
 * {@link ApkProvider} 生成 content:// uri → 系统安装器。
 *
 * 几个刻意的取舍：
 * - 不在页面里做 HTTP：WebView 以 file:// 打开，跨域访问 api.github.com 要看 CORS 和
 *   allowUniversalAccessFromFileURLs，原生直连更可靠，也不受页面脚本错误影响。
 * - 不用 DownloadManager：它下载完的 uri 属于系统下载 provider，交给安装器经常拿不到读权限，
 *   而且进度回不到我们的界面上。这里自己下到 cacheDir，配合极简 ContentProvider 授权。
 * - 下载失败（国内网络对 github release 域名的直连时通时不通）不是死路：给「打开发布页」兜底。
 * - 自动检查是静默的：没网、请求失败、已是最新都只在手动检查时说话，不打扰启动起播。
 */
final class Updater {

    private static final String OWNER = "zxsjc";
    private static final String REPO  = "YanZiMusic";
    private static final String API_LATEST = "https://api.github.com/repos/" + OWNER + "/" + REPO + "/releases/latest";
    private static final String WEB_PAGE   = "https://github.com/" + OWNER + "/" + REPO + "/releases";
    private static final String UA = "YanZiMusic-Updater/1.0";

    /** 自动检查的最小间隔：同一进程里 6 小时内不重复请求（手动检查不受限制）。 */
    private static final long AUTO_INTERVAL_MS = 6L * 60 * 60 * 1000;

    /** 本次检查里"探测成功"的那条下载地址；下载时排第一条，null 表示没有。 */
    private static volatile String verifiedUrl = null;

    /**
     * 下载线路，顺序：
     * 1. jsDelivr CDN —— 前提是仓库里放了一份 APK（路径见 REPO_APK_PATHS）。jsDelivr 在国内有节点，
     *    实测同一时间点 166~250 KB/s，比各种 GitHub 加速镜像快 2~3 倍，是目前最稳的一条。
     *    用「无限期缓存的 commit SHA」拼地址，避免分支缓存（最长 12 小时）导致拿到旧包。
     * 2. GitHub release 资源 + 加速镜像兜底（镜像时通时断，只作后备）。
     * 安全上不必担心镜像篡改：系统安装器会校验签名，签名不符的包根本装不上。
     */
    private static final String[] JSDELIVR_HOSTS = {
            "https://testingcf.jsdelivr.net",
            "https://fastly.jsdelivr.net",
            "https://gcore.jsdelivr.net",
            "https://cdn.jsdelivr.net"
    };
    /**
     * 仓库里存放安装包的候选路径，从上到下第一个"真的存在"的生效。
     * release/app-debug.apk 放第一位：以后构建产物原名上传即可，两份并存时也优先取新名那份，
     * 忘了删旧文件也不会拿到旧包；release/YanZiMusic.apk 保留作兜底（老仓库结构仍然可用）。
     */
    private static final String[] REPO_APK_PATHS = {
            "release/app-debug.apk",
            "release/YanZiMusic.apk",
            "YanZiMusic.apk"
    };
    private static final String[] DOWNLOAD_SOURCES = {
            "https://v6.gh-proxy.org/",
            "https://ghproxy.net/",
            "https://ghfast.top/",
            ""

    };

    private static boolean checking = false;
    private static long lastCheckAt = 0;
    /** 已下载完、等用户把「允许安装应用」开关打开后自动续装的包路径。 */
    private static String pendingApk = null;
    /** 进度对话框引用，退出界面时关掉，避免窗口泄漏。 */
    private static AlertDialog aliveDialog = null;

    private Updater() {
    }

    /** 一条 release 里我们用得上的字段。 */
    private static final class Release {
        String tag = "";
        String name = "";
        String body = "";
        String published = "";
        String apkName = "";
        String apkUrl = "";
        long size = 0;
    }

    /* ============================ 对外入口 ============================ */

    /** 页面/原生都可以调的手动检查：会明确告诉用户结果（已最新 / 失败）。 */
    static void check(final Activity act, final boolean manual) {
        if (act == null || act.isFinishing()) {
            return;
        }
        if (checking) {
            if (manual) {
                toast(act, "正在检查更新，请稍候…");
            }
            return;
        }
        checking = true;
        lastCheckAt = System.currentTimeMillis();
        new Thread(new Runnable() {
            @Override
            public void run() {
                Release rel = null;
                String err = null;
                try {
                    rel = fetchLatest();
                } catch (Exception e) {
                    err = describe(e);
                }
                // 先看远端那个 APK 真实的 versionCode：只下几十 KB，包不比本机新就根本不弹窗
                final long remoteCode = (rel == null) ? -1 : probeRemoteCode(rel);
                final Release fRel = rel;
                final String fErr = err;
                act.runOnUiThread(new Runnable() {
                    @Override
                    public void run() {
                        checking = false;
                        if (act.isFinishing()) {
                            return;
                        }
                        if (fRel == null) {
                            if (manual) {
                                simple(act, "检查更新失败",
                                        "连不上更新服务器（" + fErr + "）。\n"
                                                + "可以稍后再试，或者打开发布页手动下载。", true);
                            }
                            return;
                        }
                        handleRelease(act, fRel, manual, remoteCode);
                    }
                });
            }
        }).start();
    }

    /** 启动后自动检查：静默，只在新版本存在时弹窗。 */
    static void checkAuto(Activity act) {
        if (System.currentTimeMillis() - lastCheckAt < AUTO_INTERVAL_MS) {
            return;
        }
        check(act, false);
    }

    /** 回到前台时调用：如果刚下载完但卡在「允许安装应用」授权，这里自动续装。 */
    static void onResume(Activity act) {
        if (act == null || pendingApk == null) {
            return;
        }
        File f = new File(pendingApk);
        if (!f.exists()) {
            pendingApk = null;
            return;
        }
        PackageManager pm = act.getPackageManager();
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O && !pm.canRequestPackageInstalls()) {
            return;   // 用户还没把开关打开，继续等
        }
        pendingApk = null;
        install(act, f);
    }

    /** 界面销毁时收尾。 */
    static void onDestroy() {
        closeDialog();
        checking = false;
    }

    /** 当前版本号文本，供页面显示或调试用。 */
    static String versionText(Context c) {
        return currentVersionName(c);
    }

    /* ============================ 查版本 ============================ */

    private static Release fetchLatest() throws Exception {
        HttpURLConnection conn = null;
        InputStream in = null;
        try {
            conn = (HttpURLConnection) new URL(API_LATEST).openConnection();
            conn.setConnectTimeout(12000);
            conn.setReadTimeout(15000);
            conn.setInstanceFollowRedirects(true);
            conn.setRequestProperty("User-Agent", UA);
            conn.setRequestProperty("Accept", "application/vnd.github+json");
            int code = conn.getResponseCode();
            if (code != 200) {
                throw new Exception("HTTP " + code + (code == 403 ? "（请求过于频繁）" : ""));
            }
            in = conn.getInputStream();
            StringBuilder sb = new StringBuilder();
            byte[] buf = new byte[4096];
            int n;
            while ((n = in.read(buf)) > 0) {
                sb.append(new String(buf, 0, n, "UTF-8"));
            }
            return parseRelease(sb.toString());
        } finally {
            try {
                if (in != null) in.close();
            } catch (Exception ignored) {
            }
            if (conn != null) conn.disconnect();
        }
    }

    private static Release parseRelease(String json) throws Exception {
        JSONObject o = new JSONObject(json);
        Release r = new Release();
        r.tag = o.optString("tag_name", "");
        r.name = o.optString("name", "");
        r.body = o.optString("body", "");
        r.published = o.optString("published_at", "");
        JSONArray assets = o.optJSONArray("assets");
        if (assets != null) {
            for (int i = 0; i < assets.length(); i++) {
                JSONObject a = assets.optJSONObject(i);
                if (a == null) {
                    continue;
                }
                String name = a.optString("name", "");
                if (name.toLowerCase().endsWith(".apk")) {
                    r.apkName = name;
                    r.apkUrl = a.optString("browser_download_url", "");
                    r.size = a.optLong("size", 0);
                    break;
                }
            }
        }
        return r;
    }

    private static void handleRelease(final Activity act, Release rel, boolean manual, long remoteCode) {
        String curName = currentVersionName(act);
        String newVer = extractVersion(rel.tag.length() > 0 ? rel.tag : rel.name);
        if (rel.apkUrl.length() == 0) {
            if (manual) {
                simple(act, "没有可下载的安装包",
                        "最新发布 " + rel.tag + " 里没有 .apk 附件。\n可以打开发布页手动下载。", true);
            }
            return;
        }
        // 以远端包的真实 versionCode 为准：不比本机新就什么都不弹（tag 写错也不会误报）
        if (remoteCode > 0) {
            long curCode = currentVersionCode(act);
            if (remoteCode <= curCode) {
                if (manual) {
                    simple(act, "已是最新版本",
                            "当前版本 " + curName + "（versionCode " + curCode + "），"
                                    + "网上最新包 versionCode " + remoteCode + "，不比本机新。", false);
                }
                return;
            }
        } else if (newVer.length() == 0 || !isNewer(curName, newVer)) {
            // 探测不到（网络差/不支持 Range）才退回按 tag 版本比较
            if (manual) {
                simple(act, "已是最新版本",
                        "当前版本 " + curName + "，发布页最新为 " + (rel.tag.length() > 0 ? rel.tag : "同版本") + "。",
                        false);
            }
            return;
        }
        prompt(act, rel, curName, newVer);
    }

    /**
     * 探测远端 APK 的 versionCode（只下几十 KB）。
     *
     * 探测同时兼任"线路体检"：命中的那条 URL 记进 {@link #verifiedUrl}，下载时直接排第一条。
     * 这样就不再依赖"文件到底存不存在"的推断——GitHub 的 commits?path= 接口会顺着 rename
     * 历史返回已删/已改名路径的 sha（拼出来是 404），只按它选线路会白试几条、探测失败后
     * 退回按 tag 比较，进而误弹"发现新版本"。
     */
    private static long probeRemoteCode(Release rel) {
        verifiedUrl = null;
        try {
            List<String> urls = probeCandidateList(rel);
            int tried = 0;
            for (int i = 0; i < urls.size() && tried < 10; i++) {
                tried++;
                long v = ApkProbe.probe(urls.get(i));
                if (v > 0) {
                    verifiedUrl = urls.get(i);
                    return v;
                }
            }
        } catch (Exception ignored) {
        }
        return -1;
    }

    /**
     * 探测用的候选地址：仓库里可能的几个文件名 × jsDelivr 两个节点，再加 Release 资产。
     * 每条都会真的发一次 Range 请求，404 的会被快速跳过，所以顺序不怕"猜错文件名"。
     */
    private static List<String> probeCandidateList(Release rel) {
        List<String> urls = new ArrayList<String>();
        for (int i = 0; i < REPO_APK_PATHS.length; i++) {
            String path = REPO_APK_PATHS[i];
            String sha = fetchCommitSha(path);
            String ref = (sha != null && sha.length() > 0) ? sha : "main";
            String tail = "/gh/" + OWNER + "/" + REPO + "@" + ref + "/" + encodePath(path);
            urls.add(JSDELIVR_HOSTS[0] + tail);
            urls.add(JSDELIVR_HOSTS[1] + tail);
        }
        if (rel.apkUrl.length() > 0) {
            urls.add(rel.apkUrl);
            for (int i = 1; i < DOWNLOAD_SOURCES.length; i++) {
                urls.add(DOWNLOAD_SOURCES[i] + rel.apkUrl);
            }
        }
        return urls;
    }

    private static void prompt(final Activity act, final Release rel, String curName, String newVer) {
        StringBuilder msg = new StringBuilder();
        msg.append("当前版本：").append(curName);
        msg.append("\n最新版本：").append(rel.tag.length() > 0 ? rel.tag : ("v" + newVer));
        if (rel.size > 0) {
            msg.append("\n安装包：").append(rel.apkName).append("（").append(mb(rel.size)).append("）");
        }
        String body = cleanBody(rel.body);
        if (body.length() > 0) {
            if (body.length() > 360) {
                body = body.substring(0, 360) + "…";
            }
            msg.append("\n\n更新说明：\n").append(body);
        }
        msg.append("\n\n下载完成后会自动弹出系统安装界面。");

        new AlertDialog.Builder(act)
                .setTitle("发现新版本 " + (rel.tag.length() > 0 ? rel.tag : newVer))
                .setMessage(msg.toString())
                .setPositiveButton("立即更新", new DialogInterface.OnClickListener() {
                    @Override
                    public void onClick(DialogInterface dialog, int which) {
                        download(act, rel);
                    }
                })
                .setNeutralButton("发布页", new DialogInterface.OnClickListener() {
                    @Override
                    public void onClick(DialogInterface dialog, int which) {
                        openPage(act);
                    }
                })
                .setNegativeButton("以后再说", null)
                .show();
    }

    /* ============================ 下载 ============================ */

    private static void download(final Activity act, final Release rel) {
        final File out = new File(act.getCacheDir(),
                "yanzi_update_" + sanitize(extractVersion(rel.tag)) + ".apk");

        final LinearLayout box = new LinearLayout(act);
        box.setOrientation(LinearLayout.VERTICAL);
        int pad = Math.round(TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, 18,
                act.getResources().getDisplayMetrics()));
        box.setPadding(pad, pad, pad, pad);
        final TextView tv = new TextView(act);
        tv.setText("正在准备下载…");
        box.addView(tv);
        final ProgressBar pb = new ProgressBar(act, null, android.R.attr.progressBarStyleHorizontal);
        pb.setMax(100);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.topMargin = pad / 2;
        box.addView(pb, lp);

        final AlertDialog dlg = new AlertDialog.Builder(act)
                .setTitle("下载更新")
                .setView(box)
                .setCancelable(false)
                .create();
        closeDialog();
        aliveDialog = dlg;
        dlg.show();

        new Thread(new Runnable() {
            @Override
            public void run() {
                act.runOnUiThread(new Runnable() {
                    @Override
                    public void run() {
                        tv.setText("正在选择下载线路…");
                    }
                });
                final List<String> urls = buildLineList(rel);
                Exception last = null;
                boolean ok = false;
                final int totalLines = urls.size();
                for (int i = 0; i < totalLines && !ok; i++) {
                    final int line = i + 1;
                    final String url = urls.get(i);
                    act.runOnUiThread(new Runnable() {
                        @Override
                        public void run() {
                            pb.setProgress(0);
                            tv.setText("第 " + line + "/" + totalLines + " 条线路（" + hostOf(url)
                                    + "）连接中…");
                        }
                    });
                    try {
                        downloadOnce(act, url, out, rel.size, pb, tv, line, totalLines);
                        ok = true;
                    } catch (Exception e) {
                        last = e;
                        try {
                            out.delete();
                        } catch (Exception ignored) {
                        }
                    }
                }
                if (!ok) {
                    final Exception fErr = last;
                    act.runOnUiThread(new Runnable() {
                        @Override
                        public void run() {
                            closeDialog();
                            if (act.isFinishing()) {
                                return;
                            }
                            simple(act, "下载失败",
                                    "已试过 " + totalLines + " 条下载线路，都没成功（最后一条：" + describe(fErr) + "）。\n"
                                            + "可以稍后再试，或打开发布页用浏览器手动下载。", true);                        }
                    });
                    return;
                }
                final long size = out.length();
                act.runOnUiThread(new Runnable() {
                    @Override
                    public void run() {
                        closeDialog();
                        if (act.isFinishing()) {
                            return;
                        }
                        if (size <= 0) {
                            simple(act, "下载失败", "下载到 0 字节，请稍后再试。", true);
                            return;
                        }
                        install(act, out);
                    }
                });
            }
        }).start();
    }

    /**
     * 拼出这次要依次尝试的下载地址。
     * 第一条固定是 {@link #verifiedUrl}（本次检查里探测成功的那条，已验证能取到最新包），
     * 后面再按 jsDelivr（commit SHA）→ 分支 → Release 资产 + 镜像的顺序兜底。
     */
    private static List<String> buildLineList(Release rel) {
        List<String> urls = new ArrayList<String>();
        if (verifiedUrl != null) {
            urls.add(verifiedUrl);
        }
        boolean shaAdded = false;
        for (int i = 0; i < REPO_APK_PATHS.length; i++) {
            String path = REPO_APK_PATHS[i];
            String sha = fetchCommitSha(path);
            if (sha != null && sha.length() > 0) {
                String tail = "/gh/" + OWNER + "/" + REPO + "@" + sha + "/" + encodePath(path);
                for (int h = 0; h < JSDELIVR_HOSTS.length; h++) {
                    urls.add(JSDELIVR_HOSTS[h] + tail);
                }
                shaAdded = true;
                break;
            }
        }
        if (!shaAdded) {
            // 查不到 commit（接口限流等）时退一步用 main 分支取，jsDelivr 对分支有缓存，
            // 可能拿到旧包，但后面的 versionCode 校验会拦住，不会误装
            String tail = "/gh/" + OWNER + "/" + REPO + "@main/" + encodePath(REPO_APK_PATHS[0]);
            urls.add(JSDELIVR_HOSTS[0] + tail);
            urls.add(JSDELIVR_HOSTS[1] + tail);
        }
        if (rel.apkUrl.length() > 0) {
            for (int i = 0; i < DOWNLOAD_SOURCES.length; i++) {
                urls.add(DOWNLOAD_SOURCES[i] + rel.apkUrl);
            }
        }
        return urls;
    }

    /** 取「最后一次改动了这个文件」的 commit SHA；仓库里没这个文件返回 null。 */
    private static String fetchCommitSha(String path) {
        HttpURLConnection conn = null;
        InputStream in = null;
        try {
            String api = "https://api.github.com/repos/" + OWNER + "/" + REPO + "/commits?path="
                    + encodePath(path) + "&per_page=1";
            conn = (HttpURLConnection) new URL(api).openConnection();
            conn.setConnectTimeout(10000);
            conn.setReadTimeout(12000);
            conn.setRequestProperty("User-Agent", UA);
            conn.setRequestProperty("Accept", "application/vnd.github+json");
            if (conn.getResponseCode() != 200) {
                return null;
            }
            in = conn.getInputStream();
            StringBuilder sb = new StringBuilder();
            byte[] buf = new byte[2048];
            int n;
            while ((n = in.read(buf)) > 0) {
                sb.append(new String(buf, 0, n, "UTF-8"));
            }
            JSONArray arr = new JSONArray(sb.toString());
            if (arr.length() == 0) {
                return null;
            }
            return arr.getJSONObject(0).optString("sha", "");
        } catch (Exception e) {
            return null;
        } finally {
            try {
                if (in != null) in.close();
            } catch (Exception ignored) {
            }
            if (conn != null) {
                conn.disconnect();
            }
        }
    }

    /** 逐段做 URL 编码（保留 /），仓库里带中文名的文件也能拼进地址。 */
    private static String encodePath(String path) {
        StringBuilder sb = new StringBuilder();
        String[] segs = path.split("/");
        for (int i = 0; i < segs.length; i++) {
            if (i > 0) {
                sb.append('/');
            }
            try {
                sb.append(URLEncoder.encode(segs[i], "UTF-8").replace("+", "%20"));
            } catch (Exception e) {
                sb.append(segs[i]);
            }
        }
        return sb.toString();
    }

    /** 进度里给用户看的线路名。 */
    private static String hostOf(String url) {
        try {
            String h = new URL(url).getHost();
            if (h == null || h.length() == 0) {
                return "GitHub 直连";
            }
            return h.endsWith("jsdelivr.net") ? "jsDelivr 加速" : h;
        } catch (Exception e) {
            return "线路";
        }
    }

    /** 从一条线路把 APK 下到 out，失败（含"太慢"）就抛异常交给下一条。 */
    private static void downloadOnce(final Activity act, String url, File out, long hint,
                                     final ProgressBar pb, final TextView tv,
                                     final int line, final int totalLines) throws Exception {
        HttpURLConnection conn = null;
        InputStream in = null;
        FileOutputStream fo = null;
        // jsDelivr 支持 Range（实测 206），多连接拼接比单连接快，先试分段下载
        if (url.indexOf("jsdelivr.net") >= 0) {
            try {
                if (downloadSegmented(act, url, out, pb, tv, line, totalLines)) {
                    return;
                }
            } catch (Exception ignored) {
                // 分段这条路不通就退回单连接，不改变上层"换线路"的判断
                try {
                    out.delete();
                } catch (Exception ignored2) {
                }
            }
        }
        try {
            conn = (HttpURLConnection) new URL(url).openConnection();
            conn.setConnectTimeout(8000);
            conn.setReadTimeout(20000);
            conn.setInstanceFollowRedirects(true);
            conn.setRequestProperty("User-Agent", UA);
            int code = conn.getResponseCode();
            if (code != 200) {
                throw new Exception("HTTP " + code);
            }
            // minSdk 23：getContentLengthLong() 是 API 24 才有的，这里用 int 版本再兜底
            int len = conn.getContentLength();
            final long total = len > 0 ? (long) len : hint;
            in = conn.getInputStream();
            fo = new FileOutputStream(out);
            byte[] buf = new byte[8192];
            long got = 0;
            int n;
            int lastPct = -1;
            long t0 = System.currentTimeMillis();
            long tMark = t0;
            long gotAtMark = 0;
            while ((n = in.read(buf)) > 0) {
                fo.write(buf, 0, n);
                got += n;
                long now = System.currentTimeMillis();
                // 连上了却几乎不传数据（部分镜像会一直吊着连接）就直接换下一条，别让用户干等
                if (got < 131072L && now - t0 > 15000L) {
                    throw new Exception("这条线路 15 秒只下到 " + Math.round(got / 1024) + " KB");
                }
                if (now - tMark >= 800L) {
                    long speed = (got - gotAtMark) * 1000L / Math.max(1L, now - tMark);
                    tMark = now;
                    gotAtMark = got;
                    final String spd = speedText(speed);
                    final long fg = got;
                    final int fp = total > 0 ? (int) Math.min(100L, got * 100L / total) : -1;
                    lastPct = fp;
                    act.runOnUiThread(new Runnable() {
                        @Override
                        public void run() {
                            if (fp >= 0) {
                                pb.setProgress(fp);
                                tv.setText("第 " + line + "/" + totalLines + " 条（" + hostOf(url) + "） "
                                        + fp + "%  " + mb(fg) + " / " + mb(total) + "  " + spd);
                            } else {
                                tv.setText("第 " + line + "/" + totalLines + " 条（" + hostOf(url) + "） "
                                        + mb(fg) + "  " + spd);
                            }
                        }
                    });
                } else if (total > 0) {
                    int pct = (int) (got * 100L / total);
                    if (pct > 100) {
                        pct = 100;
                    }
                    if (pct != lastPct) {
                        lastPct = pct;
                        final int fp = pct;
                        final long fg = got;
                        act.runOnUiThread(new Runnable() {
                            @Override
                            public void run() {
                                pb.setProgress(fp);
                                tv.setText("第 " + line + "/" + totalLines + " 条（" + hostOf(url) + "） "
                                        + fp + "%（" + mb(fg) + " / " + mb(total) + "）");
                            }
                        });
                    }
                }
            }
            fo.flush();
            if (total > 0 && got < total) {
                throw new Exception("只下到 " + mb(got) + " / " + mb(total) + "，文件不完整");
            }
        } finally {
            try {
                if (fo != null) fo.close();
            } catch (Exception ignored) {
            }
            try {
                if (in != null) in.close();
            } catch (Exception ignored) {
            }
            if (conn != null) {
                conn.disconnect();
            }
        }
    }

    /**
     * 分段并发下载（只在服务端支持 Range 时用）：把文件切成 SEG_THREADS 段，各自从不同偏移写同一个文件。
     * 返回 false 表示服务端不支持 Range，交给调用方走单连接；抛异常表示下载本身失败。
     */
    private static boolean downloadSegmented(final Activity act, final String url, File out,
                                             final ProgressBar pb, final TextView tv,
                                             final int line, final int totalLines) throws Exception {
        final long size = probeRangeSize(url);
        if (size <= 0) {
            return false;
        }
        final int parts = 4;
        final long chunk = (size + parts - 1) / parts;
        final RandomAccessFile raf = new RandomAccessFile(out, "rw");
        try {
            raf.setLength(size);
            final AtomicLong done = new AtomicLong(0);
            final AtomicReference<Exception> err = new AtomicReference<Exception>(null);
            final Object writeLock = new Object();
            final boolean[] stopReport = {false};

            Thread reporter = new Thread(new Runnable() {
                @Override
                public void run() {
                    long last = 0;
                    long lastT = System.currentTimeMillis();
                    while (!stopReport[0]) {
                        try {
                            Thread.sleep(600);
                        } catch (InterruptedException ignored) {
                        }
                        if (stopReport[0]) {
                            break;
                        }
                        long got = done.get();
                        long now = System.currentTimeMillis();
                        long dt = Math.max(1L, now - lastT);
                        final String spd = speedText((got - last) * 1000L / dt);
                        last = got;
                        lastT = now;
                        final int pct = (int) Math.min(100L, got * 100L / size);
                        final long fg = got;
                        final long ft = size;
                        act.runOnUiThread(new Runnable() {
                            @Override
                            public void run() {
                                pb.setProgress(pct);
                                tv.setText("第 " + line + "/" + totalLines + " 条（" + hostOf(url)
                                        + " 4线并发） " + pct + "%  " + mb(fg) + " / " + mb(ft) + "  " + spd);
                            }
                        });
                    }
                }
            });
            reporter.setDaemon(true);
            reporter.start();

            Thread[] threads = new Thread[parts];
            for (int i = 0; i < parts; i++) {
                final long from = i * chunk;
                final long to = Math.min(size - 1, from + chunk - 1);
                threads[i] = new Thread(new Runnable() {
                    @Override
                    public void run() {
                        if (err.get() != null) {
                            return;
                        }
                        HttpURLConnection c = null;
                        InputStream s = null;
                        try {
                            c = (HttpURLConnection) new URL(url).openConnection();
                            c.setConnectTimeout(10000);
                            c.setReadTimeout(20000);
                            c.setRequestProperty("User-Agent", UA);
                            c.setRequestProperty("Range", "bytes=" + from + "-" + to);
                            int code = c.getResponseCode();
                            if (code != 206 && code != 200) {
                                throw new Exception("HTTP " + code);
                            }
                            s = c.getInputStream();
                            byte[] buf = new byte[8192];
                            long pos = from;
                            int n;
                            while ((n = s.read(buf)) > 0) {
                                synchronized (writeLock) {
                                    raf.seek(pos);
                                    raf.write(buf, 0, n);
                                }
                                pos += n;
                                done.addAndGet(n);
                                if (pos > to + 1) {
                                    throw new Exception("分段越界");
                                }
                            }
                            if (pos != to + 1) {
                                throw new Exception("分段没下完（" + (pos - from) + "/" + (to - from + 1) + "）");
                            }
                        } catch (Exception e) {
                            err.compareAndSet(null, e);
                        } finally {
                            try {
                                if (s != null) s.close();
                            } catch (Exception ignored) {
                            }
                            if (c != null) {
                                c.disconnect();
                            }
                        }
                    }
                });
                threads[i].start();
            }
            for (int i = 0; i < parts; i++) {
                if (threads[i] != null) {
                    threads[i].join();
                }
            }
            stopReport[0] = true;
            Exception e = err.get();
            if (e != null) {
                throw e;
            }
            if (done.get() != size || out.length() != size) {
                throw new Exception("分段下载长度不符（" + out.length() + " / " + size + "）");
            }
            return true;
        } finally {
            try {
                raf.close();
            } catch (Exception ignored) {
            }
        }
    }

    /** 用 Range 探测文件总长度；服务端不支持 Range 返回 -1。 */
    private static long probeRangeSize(String url) {
        HttpURLConnection conn = null;
        try {
            conn = (HttpURLConnection) new URL(url).openConnection();
            conn.setConnectTimeout(10000);
            conn.setReadTimeout(12000);
            conn.setRequestProperty("User-Agent", UA);
            conn.setRequestProperty("Range", "bytes=0-1");
            if (conn.getResponseCode() != 206) {
                return -1;
            }
            String cr = conn.getHeaderField("Content-Range");
            if (cr == null) {
                return -1;
            }
            int slash = cr.lastIndexOf('/');
            if (slash < 0) {
                return -1;
            }
            return Long.parseLong(cr.substring(slash + 1).trim());
        } catch (Exception e) {
            return -1;
        } finally {
            if (conn != null) {
                conn.disconnect();
            }
        }
    }

    /* ============================ 安装 ============================ */

    private static void install(final Activity act, final File apk) {
        PackageManager pm = act.getPackageManager();
        PackageInfo info;
        try {
            info = pm.getPackageArchiveInfo(apk.getAbsolutePath(), 0);
        } catch (Exception e) {
            info = null;
        }
        if (info == null) {
            simple(act, "安装包异常", "下载的 APK 无法解析（可能下载不完整），已删除，请重试。", false);
            try {
                apk.delete();
            } catch (Exception ignored) {
            }
            return;
        }
        if (!act.getPackageName().equals(info.packageName)) {
            simple(act, "安装包不匹配",
                    "下载到的包名是 " + info.packageName + "，不是燕子音乐（" + act.getPackageName() + "），已删除。", false);
            try {
                apk.delete();
            } catch (Exception ignored) {
            }
            return;
        }
        long newCode = Build.VERSION.SDK_INT >= Build.VERSION_CODES.P
                ? info.getLongVersionCode() : info.versionCode;
        long curCode = currentVersionCode(act);
        if (newCode <= curCode) {
            simple(act, "版本不比当前新",
                    "下载到的 APK versionCode = " + newCode + "，当前 = " + curCode
                            + "，系统会拒绝降级安装，已删除。\n"
                            + "请确认 GitHub 上放的是新编译的安装包。", false);
            try {
                apk.delete();
            } catch (Exception ignored) {
            }
            return;
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O && !pm.canRequestPackageInstalls()) {
            pendingApk = apk.getAbsolutePath();
            new AlertDialog.Builder(act)
                    .setTitle("需要允许安装应用")
                    .setMessage("系统要求先允许「燕子音乐」安装应用。\n点「去设置」打开开关，返回本应用后会自动继续安装。")
                    .setPositiveButton("去设置", new DialogInterface.OnClickListener() {
                        @Override
                        public void onClick(DialogInterface dialog, int which) {
                            openInstallSettings(act);
                        }
                    })
                    .setNegativeButton("取消", null)
                    .show();
            return;
        }
        launchInstaller(act, apk);
    }

    private static void launchInstaller(Activity act, File apk) {
        try {
            Uri uri = ApkProvider.uriFor(act, apk);
            Intent i = new Intent(Intent.ACTION_VIEW);
            i.setDataAndType(uri, "application/vnd.android.package-archive");
            i.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION | Intent.FLAG_ACTIVITY_NEW_TASK);
            act.startActivity(i);
        } catch (Exception e) {
            simple(act, "打不开安装界面",
                    "系统里没有可用的安装器（" + describe(e) + "）。\n安装包已保留在：" + apk.getAbsolutePath(), false);
        }
    }

    /* ============================ 小工具 ============================ */

    private static String currentVersionName(Context c) {
        try {
            PackageInfo pi = c.getPackageManager().getPackageInfo(c.getPackageName(), 0);
            return pi.versionName == null ? "未知" : pi.versionName;
        } catch (Exception e) {
            return "未知";
        }
    }

    private static long currentVersionCode(Context c) {
        try {
            PackageInfo pi = c.getPackageManager().getPackageInfo(c.getPackageName(), 0);
            return Build.VERSION.SDK_INT >= Build.VERSION_CODES.P ? pi.getLongVersionCode() : pi.versionCode;
        } catch (Exception e) {
            return 0;
        }
    }

    /** 从 "v2.0" / "燕子音乐 1.7.33" 这类字符串里抠出第一个版本号数字串。 */
    private static String extractVersion(String s) {
        if (s == null) {
            return "";
        }
        Matcher m = Pattern.compile("\\d+(?:\\.\\d+)*").matcher(s);
        return m.find() ? m.group() : "";
    }

    /** 逐段数字比较：a 比 b 旧返回 true。 */
    private static boolean isNewer(String current, String candidate) {
        String[] a = current.split("\\.");
        String[] b = candidate.split("\\.");
        int len = Math.max(a.length, b.length);
        for (int i = 0; i < len; i++) {
            long x = 0;
            long y = 0;
            try {
                x = i < a.length ? Long.parseLong(a[i].trim()) : 0;
            } catch (Exception ignored) {
            }
            try {
                y = i < b.length ? Long.parseLong(b[i].trim()) : 0;
            } catch (Exception ignored) {
            }
            if (y > x) {
                return true;
            }
            if (y < x) {
                return false;
            }
        }
        return false;
    }

    private static String cleanBody(String body) {
        if (body == null) {
            return "";
        }
        String s = body.replace("\r", "").trim();
        // 里面常是带 ### / - 的 markdown，去掉符号留正文，够用户判断更新内容即可
        s = s.replaceAll("(?m)^#{1,6}\\s*", "");
        s = s.replaceAll("[`*_>]", "");
        s = s.replaceAll("\n{3,}", "\n\n");
        return s;
    }

    private static String sanitize(String s) {
        if (s == null || s.length() == 0) {
            return "latest";
        }
        return s.replaceAll("[^0-9A-Za-z.]", "_");
    }

    /** 进度里显示的实时速度。 */
    private static String speedText(long bytesPerSec) {
        if (bytesPerSec <= 0) {
            return "";
        }
        if (bytesPerSec < 1024) {
            return bytesPerSec + " B/s";
        }
        double kb = bytesPerSec / 1024.0;
        if (kb < 1024) {
            return String.format(java.util.Locale.US, "%.0f KB/s", kb);
        }
        return String.format(java.util.Locale.US, "%.2f MB/s", kb / 1024.0);
    }

    private static String mb(long bytes) {
        if (bytes <= 0) {
            return "0 B";
        }
        if (bytes < 1024) {
            return bytes + " B";
        }
        double kb = bytes / 1024.0;
        if (kb < 1024) {
            return String.format(java.util.Locale.US, "%.0f KB", kb);
        }
        return String.format(java.util.Locale.US, "%.2f MB", kb / 1024.0);
    }

    private static String describe(Exception e) {
        String m = e.getMessage();
        if (m == null || m.length() == 0) {
            return e.getClass().getSimpleName();
        }
        return m;
    }

    private static void closeDialog() {
        if (aliveDialog != null) {
            try {
                aliveDialog.dismiss();
            } catch (Exception ignored) {
            }
            aliveDialog = null;
        }
    }

    private static void simple(Activity act, String title, String msg, boolean withPageButton) {
        if (act.isFinishing()) {
            return;
        }
        AlertDialog.Builder b = new AlertDialog.Builder(act)
                .setTitle(title)
                .setMessage(msg)
                .setPositiveButton("知道了", null);
        if (withPageButton) {
            b.setNeutralButton("打开发布页", new DialogInterface.OnClickListener() {
                @Override
                public void onClick(DialogInterface dialog, int which) {
                    openPage(act);
                }
            });
        }
        b.show();
    }

    private static void openPage(Activity act) {
        try {
            Intent i = new Intent(Intent.ACTION_VIEW, Uri.parse(WEB_PAGE));
            i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            act.startActivity(i);
        } catch (Exception e) {
            toast(act, "没有可用的浏览器打开 " + WEB_PAGE);
        }
    }

    private static void openInstallSettings(Activity act) {
        try {
            Intent i = new Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES,
                    Uri.parse("package:" + act.getPackageName()));
            i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            act.startActivity(i);
        } catch (Exception e) {
            try {
                act.startActivity(new Intent(Settings.ACTION_SECURITY_SETTINGS));
            } catch (Exception ignored) {
                toast(act, "请到系统设置里允许本应用安装应用");
            }
        }
    }

    private static void toast(Activity act, String msg) {
        try {
            Toast.makeText(act, msg, Toast.LENGTH_SHORT).show();
        } catch (Exception ignored) {
        }
    }
}
