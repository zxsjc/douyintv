package com.yanzi.music;

import android.Manifest;
import android.app.Activity;
import android.app.AlertDialog;
import android.app.DownloadManager;
import android.app.UiModeManager;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.content.res.Configuration;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Environment;
import android.os.SystemClock;
import android.text.InputType;
import android.view.KeyEvent;
import android.view.MotionEvent;
import android.view.ViewGroup;
import android.view.inputmethod.InputMethodManager;
import android.webkit.DownloadListener;
import android.webkit.JavascriptInterface;
import android.webkit.JsPromptResult;
import android.webkit.WebChromeClient;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.widget.EditText;
import android.widget.FrameLayout;

import java.io.File;

/**
 * 主界面：全屏 WebView 加载本地 assets/index.html。
 * - 锁屏 / 退到后台时不调用 webView.onPause()，避免暂停 HTML5 音频；
 *   后台保活由 {@link MusicService}（前台服务 + PARTIAL_WAKE_LOCK + 音频焦点）完成。
 * - 电视 / 遥控器：首次收到方向键时自动开启电视焦点模式；方向键与确认键由原生层拦截，
 *   转发给页面焦点系统；媒体键、菜单键、返回键直接桥接到页面函数。
 * - 输入法（全新设计）：页面只负责"把焦点放进/拿出输入框"，键盘的弹出、收起、失败重试、
 *   状态同步全部在本层完成——
 *   ① windowSoftInputMode=adjustNothing：键盘浮层覆盖，窗口不压缩、页面不重排，
 *      从根上消除"弹出抖动 / 按一下闪屏"；
 *   ② 唤起用 restartInput + showSoftInput，并以 imm.isActive(web) 真实校验结果
 *      （showSoftInput 的返回值不可靠），失败自动重试，仍失败回调页面复位焦点；
 *   ③ 返回键由 ImeWebView.onKeyPreIme 在 IME 消费之前拦截，isActive 轮询兜底
 *      "键盘自行消失"的边缘情形，两条路径都确定性地结束会话。
 * - 下载：页面调用 window.Yanzi.downloadFile(url, filename)，由系统 DownloadManager 保存。
 */
public class MainActivity extends Activity {

    private WebView web;
    /** 当前是否按电视交互运行（UI 布局 + 按键转发）。手机误判时可被触摸操作还原。 */
    private boolean tvMode = false;
    /** 设备硬件本身即为电视/盒子（由 UI Mode / leanback / 无触屏判定），触摸不改变该值。 */
    private boolean tvHardware = false;
    private boolean remoteSeen = false;
    /** 系统输入法由本 App 唤起且仍在使用中：此期间方向键/确认键必须放行给系统。 */
    private volatile boolean imeActive = false;
    /**
     * 最近一次收起输入法的时间（uptimeMillis）。
     * 部分 ROM 的 IME 在 hide 之后的一小段时间内会忽略 show 请求，这里做极短冷却。
     */
    private long lastHideAt = 0;
    /** 键盘会话看门狗：会话期间周期校验 InputConnection 是否仍然存活。 */
    private Runnable imeWatch = null;
    /** 页面上一次被原生层消费掉的按下键，用于配对吞掉对应的抬起事件。 */
    private int lastConsumedKey = -1;
    /** 页面上的选择弹窗（换词 / 排行榜）是否打开，由页面通过 Yanzi.setPickerOpen 上报。 */
    private volatile boolean pickerOpen = false;

    /**
     * 能在 IME 消费返回键之前拦截它的 WebView。
     * 键盘显示期间，返回键在很多设备上会被 IME 直接吃掉、Activity 收不到 key 事件；
     * onKeyPreIme 保证按键先到视图层——这里消费它并主动收键盘，是确定性的返回键路径
     * （少数 ROM 连视图层都收不到时，由 isActive 轮询兜底结束会话）。
     */
    private class ImeWebView extends WebView {
        ImeWebView(Context context) {
            super(context);
        }

        @Override
        public boolean onKeyPreIme(int keyCode, KeyEvent event) {
            if (keyCode == KeyEvent.KEYCODE_BACK && imeActive) {
                if (event.getAction() == KeyEvent.ACTION_UP) {
                    closeImeFromKey();
                }
                return true;   // 按下/抬起都消费，不传给 IME，也不让它漏到页面
            }
            return super.onKeyPreIme(keyCode, event);
        }
    }

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        FrameLayout root = new FrameLayout(this);
        root.setLayoutParams(new ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT));
        setContentView(root);

        web = new ImeWebView(this);
        web.setLayoutParams(new ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT));
        web.setFocusable(true);
        web.setFocusableInTouchMode(true);
        root.addView(web);

        WebSettings settings = web.getSettings();
        settings.setJavaScriptEnabled(true);
        settings.setDomStorageEnabled(true);
        settings.setDatabaseEnabled(true);
        settings.setMediaPlaybackRequiresUserGesture(false);
        settings.setLoadWithOverviewMode(true);
        settings.setUseWideViewPort(true);
        settings.setAllowFileAccess(true);
        settings.setAllowUniversalAccessFromFileURLs(true);
        settings.setAllowContentAccess(true);
        settings.setMixedContentMode(WebSettings.MIXED_CONTENT_ALWAYS_ALLOW);
        settings.setCacheMode(WebSettings.LOAD_DEFAULT);

        tvHardware = detectTv();
        tvMode = tvHardware;

        // 页面下载桥接：Yanzi.downloadFile(url, filename)
        web.addJavascriptInterface(new JsBridge(), "Yanzi");

        web.setWebViewClient(new WebViewClient() {
            @Override
            public void onPageFinished(WebView view, String url) {
                if (view != null) {
                    MusicService.attachActivity(MainActivity.this);
                    view.requestFocus();
                    if (tvMode) {
                        view.evaluateJavascript(
                                "if(window.__setTv){window.__setTv(true);}", null);
                    }
                }
            }
        });
        web.setWebChromeClient(new WebChromeClient() {
            /**
             * 页面用 prompt() 做"手动输入歌曲ID"。默认 WebChromeClient 的 onJsPrompt 返回 false，
             * 对话框不会显示，该功能在 App 内静默失效——这里用原生输入框实现。
             */
            @Override
            public boolean onJsPrompt(WebView view, String url, String message,
                                      String defaultValue, final JsPromptResult result) {
                final EditText input = new EditText(MainActivity.this);
                input.setInputType(InputType.TYPE_CLASS_NUMBER);
                input.setSingleLine(true);
                if (defaultValue != null) input.setText(defaultValue);
                if (defaultValue != null) input.setSelection(input.getText().length());
                AlertDialog dialog = new AlertDialog.Builder(MainActivity.this)
                        .setTitle("燕子音乐")
                        .setMessage(message)
                        .setView(input)
                        .setPositiveButton("确定", (d, w) ->
                                result.confirm(input.getText().toString()))
                        .setNegativeButton("取消", (d, w) -> result.cancel())
                        .setOnCancelListener(d -> result.cancel())
                        .create();
                // 弹窗关闭（确定/取消/返回）时必须复位：否则 imeActive 永远为 true，
                // 遥控器方向键会一直被放行给系统，电视上页面焦点从此失灵。
                dialog.setOnDismissListener(d -> imeActive = false);
                // 先置位再 show：保证弹窗存活期间的任何时刻标记都为 true，
                // 避免"弹窗已显示但标记未置位"的时序窗口（原生输入框同样需要方向键能被输入法接收）。
                imeActive = true;
                dialog.show();
                input.requestFocus();
                return true;
            }
        });

        // 浏览器回退路径（页面未走原生桥接时）：WebView 不自带“另存为”，统一交给系统下载管理器
        web.setDownloadListener(new DownloadListener() {
            @Override
            public void onDownloadStart(String url, String userAgent,
                                        String contentDisposition, String mimeType,
                                        long contentLength) {
                enqueueDownload(url, null);
            }
        });

        web.loadUrl("file:///android_asset/index.html");

        Intent service = new Intent(this, MusicService.class);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            startForegroundService(service);
        } else {
            startService(service);
        }
        MusicService.attachActivity(this);

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            if (checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS)
                    != PackageManager.PERMISSION_GRANTED) {
                requestPermissions(
                        new String[]{Manifest.permission.POST_NOTIFICATIONS}, 100);
            }
        }
    }

    private boolean detectTv() {
        UiModeManager uiModeManager =
                (UiModeManager) getSystemService(Context.UI_MODE_SERVICE);
        if (uiModeManager != null
                && uiModeManager.getCurrentModeType()
                == Configuration.UI_MODE_TYPE_TELEVISION) {
            return true;
        }
        if (getPackageManager().hasSystemFeature("android.software.leanback")) {
            return true;
        }
        return !getPackageManager().hasSystemFeature("android.hardware.touchscreen");
    }

    /** 切换电视/手机交互模式，并同步页面布局。 */
    private void setTvMode(boolean on) {
        tvMode = on;
        if (web != null) {
            web.evaluateJavascript(
                    "if(window.__setTv){window.__setTv(" + (on ? "true" : "false") + ");}", null);
        }
    }

    /** 首次收到遥控器方向键 / 确认键时，确保页面进入电视焦点模式。 */
    private void ensureRemote() {
        remoteSeen = true;
        if (!tvMode) {
            setTvMode(true);
        }
    }

    /**
     * 触摸屏设备收到触摸：说明是手机（多为 UA/方向键误判进入电视模式），
     * 立即还原为触屏交互，避免"手机卡在电视布局里出不来"。
     * 硬件判定为电视的设备（含用飞鼠/空鼠发送触摸事件的盒子）不受影响。
     */
    @Override
    public boolean dispatchTouchEvent(MotionEvent ev) {
        if (ev.getActionMasked() == MotionEvent.ACTION_DOWN) {
            if (tvMode && !tvHardware) {
                remoteSeen = false;
                setTvMode(false);
            } else if (imeActive && !isImeConnectionActive()) {
                // 点在输入框外导致键盘被系统收起时（InputConnection 已断开），
                // 同步结束会话，否则返回键会被吞掉一次、方向键也会被错误地放行给系统。
                endImeSession(false);
            }
        }
        return super.dispatchTouchEvent(ev);
    }

    /**
     * InputConnection 是否仍然存活。adjustNothing 下窗口不压缩，无法用可视区域
     * 反推键盘可见性；imm.isActive(web) 与软键盘模式无关，是可靠信号。
     */
    private boolean isImeConnectionActive() {
        InputMethodManager imm =
                (InputMethodManager) getSystemService(Context.INPUT_METHOD_SERVICE);
        return imm != null && web != null && imm.isActive(web);
    }

    /**
     * 结束键盘会话：清标记、停看门狗。notifyPage=true 时回调页面把焦点从输入框
     * 拿出（页面的"键盘会话"判定就是焦点在输入框，两边必须同生同灭）。
     */
    private void endImeSession(boolean notifyPage) {
        imeActive = false;
        stopImeWatch();
        if (notifyPage) {
            runJs("if(window.__kbClosed){window.__kbClosed();}");
        }
    }

    /**
     * 返回键收起键盘（ImeWebView.onKeyPreIme 与 dispatchKeyEvent 两条路径共用）。
     * 由原生直接收起并同步页面，不经 JS 中转——这是"按返回键键盘闪退"的确定性路径。
     */
    private void closeImeFromKey() {
        if (!imeActive) {
            return;
        }
        lastHideAt = SystemClock.uptimeMillis();
        InputMethodManager imm =
                (InputMethodManager) getSystemService(Context.INPUT_METHOD_SERVICE);
        if (imm != null && web != null) {
            imm.hideSoftInputFromWindow(web.getWindowToken(), 0);
        }
        endImeSession(true);
    }

    /**
     * 会话看门狗：每 500ms 校验一次 InputConnection。键盘"自行消失"时连接会断开——
     * 部分 ROM 上返回键被 IME 直接吃掉（onKeyPreIme 收不到）、或输入法自带收起按钮，
     * 这里兜底结束会话，保证会话标记永远不滞留。
     */
    private void startImeWatch() {
        stopImeWatch();
        if (web == null) {
            return;
        }
        imeWatch = new Runnable() {
            @Override
            public void run() {
                imeWatch = null;
                if (web == null || !imeActive) {
                    return;
                }
                if (!isImeConnectionActive()) {
                    endImeSession(true);
                    return;
                }
                startImeWatch();   // 会话仍在：继续下一轮
            }
        };
        web.postDelayed(imeWatch, 500);
    }

    private void stopImeWatch() {
        if (web != null && imeWatch != null) {
            web.removeCallbacks(imeWatch);
        }
        imeWatch = null;
    }

    @Override
    public void onWindowFocusChanged(boolean hasFocus) {
        super.onWindowFocusChanged(hasFocus);
        if (!hasFocus) {
            endImeSession(false);   // 一并停掉会话看门狗
            return;
        }
        // 原生对话框（onJsPrompt）/ 通知栏等收回窗口焦点后，把焦点归还 WebView，
        // 否则 WebView 的 InputConnection 已随对话框销毁，下一次 showSoftInput 会静默失败
        // （v1.7.9 注释中"偶发弹不出"的残留入口）。
        if (web != null) {
            web.requestFocus();
        }
    }

    private void runJs(String script) {
        if (web == null) {
            return;
        }
        web.evaluateJavascript(script, null);
    }

    /** 供后台服务通知栏 / MediaSession 按键回调，转发到页面播放器。 */
    public void servicePlayPause() {
        runOnUiThread(new Runnable() {
            @Override
            public void run() {
                runJs("(function(){ if(typeof togglePlay==='function') togglePlay(); })();");
            }
        });
    }

    public void serviceNext() {
        runOnUiThread(new Runnable() {
            @Override
            public void run() {
                runJs("(function(){ if(typeof playNext==='function') playNext(); })();");
            }
        });
    }

    public void servicePrev() {
        runOnUiThread(new Runnable() {
            @Override
            public void run() {
                runJs("(function(){ if(typeof playPrev==='function') playPrev(); })();");
            }
        });
    }

    public void servicePlay() {
        runOnUiThread(new Runnable() {
            @Override
            public void run() {
                runJs("(function(){ if(window.audio && audio.src){ if(typeof safePlay==='function') safePlay(); } else if(typeof loadCurrent==='function'){ loadCurrent(true); } })();");
            }
        });
    }

    public void servicePause() {
        runOnUiThread(new Runnable() {
            @Override
            public void run() {
                runJs("(function(){ if(window.audio&&typeof audio.pause==='function') audio.pause(); })();");
            }
        });
    }

    public void serviceStop() {
        servicePause();
    }

    public void serviceDuck(final boolean duck) {
        runOnUiThread(new Runnable() {
            @Override
            public void run() {
                String volume = duck ? "0.25" : "(audio.muted ? 0 : 1)";
                runJs("(function(){ if(window.audio){ audio.volume=" + volume + "; } })();");
            }
        });
    }

    /** 输入法激活期间必须放行的按键：方向键与确认键。 */
    private boolean isImePassThroughKey(int keyCode) {
        switch (keyCode) {
            case KeyEvent.KEYCODE_DPAD_UP:
            case KeyEvent.KEYCODE_DPAD_DOWN:
            case KeyEvent.KEYCODE_DPAD_LEFT:
            case KeyEvent.KEYCODE_DPAD_RIGHT:
            case KeyEvent.KEYCODE_DPAD_CENTER:
            case KeyEvent.KEYCODE_ENTER:
            case KeyEvent.KEYCODE_NUMPAD_ENTER:
            case KeyEvent.KEYCODE_BUTTON_A:
                return true;
            default:
                return false;
        }
    }

    @Override
    public boolean dispatchKeyEvent(KeyEvent event) {
        int keyCode = event.getKeyCode();

        if (event.getAction() == KeyEvent.ACTION_UP) {
            // 只吞掉与"已被消费的按下键"配对的抬起事件，其余交给系统，
            // 避免实体键盘 Enter 抬起丢失导致输入法/页面行为异常。
            if (keyCode == lastConsumedKey) {
                lastConsumedKey = -1;
                return true;
            }
            return super.dispatchKeyEvent(event);
        }
        if (event.getAction() != KeyEvent.ACTION_DOWN) {
            return super.dispatchKeyEvent(event);
        }

        // 输入法使用中：方向键 / 确认键交给系统，否则输入法收不到按键（电视上无法选字）
        if (imeActive && isImePassThroughKey(keyCode)) {
            return super.dispatchKeyEvent(event);
        }

        if (web != null && handleKeyDown(keyCode)) {
            lastConsumedKey = keyCode;
            return true;
        }
        return super.dispatchKeyEvent(event);
    }

    private boolean handleKeyDown(int keyCode) {
        switch (keyCode) {
            case KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE:
            case KeyEvent.KEYCODE_MEDIA_PLAY:
            case KeyEvent.KEYCODE_MEDIA_PAUSE:
                runJs("(function(){ if(typeof togglePlay==='function') togglePlay(); })();");
                return true;
            case KeyEvent.KEYCODE_MEDIA_NEXT:
            case KeyEvent.KEYCODE_MEDIA_FAST_FORWARD:
                runJs("(function(){ if(typeof playNext==='function') playNext(); })();");
                return true;
            case KeyEvent.KEYCODE_MEDIA_PREVIOUS:
            case KeyEvent.KEYCODE_MEDIA_REWIND:
                runJs("(function(){ if(typeof playPrev==='function') playPrev(); })();");
                return true;
            case KeyEvent.KEYCODE_MEDIA_STOP:
                runJs("(function(){ if(window.audio&&typeof audio.pause==='function') audio.pause(); })();");
                return true;
            case KeyEvent.KEYCODE_MENU:
                runJs("(function(){ if(typeof listToggle==='function') listToggle(); })();");
                return true;
            // 方向键：手机通常没有 D-pad，直接拦截；首次到达自动开启电视模式
            case KeyEvent.KEYCODE_DPAD_UP:
                ensureRemote();
                runJs("if(window.__remote){window.__remote('up');}");
                return true;
            case KeyEvent.KEYCODE_DPAD_DOWN:
                ensureRemote();
                runJs("if(window.__remote){window.__remote('down');}");
                return true;
            case KeyEvent.KEYCODE_DPAD_LEFT:
                ensureRemote();
                runJs("if(window.__remote){window.__remote('left');}");
                return true;
            case KeyEvent.KEYCODE_DPAD_RIGHT:
                ensureRemote();
                runJs("if(window.__remote){window.__remote('right');}");
                return true;
            case KeyEvent.KEYCODE_DPAD_CENTER:
            case KeyEvent.KEYCODE_BUTTON_A:
                ensureRemote();
                runJs("if(window.__remote){window.__remote('ok');}");
                return true;
            case KeyEvent.KEYCODE_ENTER:
            case KeyEvent.KEYCODE_NUMPAD_ENTER:
                // 实体键盘 Enter（手机）保持默认行为（交给页面 onkeydown 触发搜索）；
                // 已被遥控器唤起电视模式时才接管确认键
                if (remoteSeen || tvMode) {
                    ensureRemote();
                    runJs("if(window.__remote){window.__remote('ok');}");
                    return true;
                }
                return false;
            case KeyEvent.KEYCODE_BACK:
                // 优先级：输入法 → 页面弹窗 → 页面历史 → 交系统处理（退出）
                // 键盘真开着时返回键通常已被 ImeWebView.onKeyPreIme 拦截；
                // 走到这里说明键盘已不在（会话标记滞后），复位状态并消费这次返回。
                if (imeActive) {
                    closeImeFromKey();
                    return true;
                }
                if (pickerOpen) {
                    pickerOpen = false;
                    runJs("(function(){ if(typeof closePicker==='function') closePicker(); })();");
                    return true;
                }
                if (web.canGoBack()) {
                    web.goBack();
                    return true;
                }
                return false;
            default:
                return false;
        }
    }

    private String sanitizeFileName(String name) {
        if (name == null || name.trim().isEmpty()) {
            return "歌曲.mp3";
        }
        String f = name.trim().replaceAll("[/\\\\:*?\"<>|]", "_");
        if (!f.toLowerCase(java.util.Locale.ROOT).endsWith(".mp3")) {
            f = f + ".mp3";
        }
        return f;
    }

    /** DownloadManager 目标文件重名会直接失败，这里追加 (1)(2) 序号，避免下第二次同一首歌报错。 */
    private String uniqueDownloadName(String name) {
        try {
            File dir = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS);
            if (!new File(dir, name).exists()) {
                return name;
            }
            int dot = name.lastIndexOf('.');
            String base = dot > 0 ? name.substring(0, dot) : name;
            String ext = dot > 0 ? name.substring(dot) : "";
            for (int i = 1; i <= 99; i++) {
                String candidate = base + " (" + i + ")" + ext;
                if (!new File(dir, candidate).exists()) {
                    return candidate;
                }
            }
            return base + " (" + (System.currentTimeMillis() % 100000) + ")" + ext;
        } catch (Exception e) {
            return name;
        }
    }

    private void enqueueDownload(String url, String filename) {
        if (url == null || url.trim().isEmpty()) {
            return;
        }
        final String name = uniqueDownloadName(sanitizeFileName(filename));
        try {
            DownloadManager.Request request =
                    new DownloadManager.Request(Uri.parse(url.trim()));
            request.setMimeType("audio/mpeg");
            request.setTitle(name);
            request.setDescription("燕子音乐下载");
            request.setNotificationVisibility(
                    DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED);
            request.setAllowedOverMetered(true);
            request.setAllowedOverRoaming(true);
            request.setDestinationInExternalPublicDir(
                    Environment.DIRECTORY_DOWNLOADS, name);

            DownloadManager manager =
                    (DownloadManager) getSystemService(Context.DOWNLOAD_SERVICE);
            if (manager == null) {
                reportDownload("系统下载服务不可用");
                return;
            }
            manager.enqueue(request);
            reportDownload("已加入下载队列，完成后在通知栏或「下载」目录查看");
        } catch (Exception e) {
            reportDownload("下载失败：" + e.getMessage());
        }
    }

    private void reportDownload(final String message) {
        runOnUiThread(new Runnable() {
            @Override
            public void run() {
                String safe = message.replace("\\", "\\\\")
                        .replace("'", "\\'")
                        .replace("\n", " ");
                runJs("(function(){ if(typeof showStatus==='function'){ showStatus('"
                        + safe + "', true); } })();");
            }
        });
    }

    /** 页面 JS 桥接对象，命名为 Yanzi，页面内调用 window.Yanzi.downloadFile(...)。 */
    private class JsBridge {
        @JavascriptInterface
        public void showKeyboard() {
            runOnUiThread(new Runnable() {
                @Override
                public void run() {
                    showIme(0);
                }
            });
        }

        /**
         * 弹出输入法（全新设计）。可靠性来自两点：
         *   ① restartInput：强制 IME 丢弃可能过期/空壳的 InputConnection 并重建，
         *      解决"页面刚 focus、连接还没建好，showSoftInput 被静默忽略"的竞态；
         *   ② isActive 校验：showSoftInput 的返回值本身不可靠，250ms 后改用
         *      imm.isActive(web) 判定连接是否真的建立，没有则重试（最多 3 次），
         *      仍失败回调页面 __kbFailed 复位焦点，用户可再按一次确认键重试。
         * 全程不碰页面任何样式与焦点（焦点由页面在调用前自己放进输入框），
         * 不使用 toggleSoftInput（切换语义会把已弹出的键盘再关掉）。
         */
        private void showIme(final int attempt) {
            if (web == null) {
                return;
            }
            final InputMethodManager imm =
                    (InputMethodManager) getSystemService(Context.INPUT_METHOD_SERVICE);
            if (imm == null) {
                return;
            }
            if (attempt == 0) {
                // 极短冷却：部分 ROM 的 IME 在 hide 后的一小段时间内会忽略 show 请求
                long sinceHide = SystemClock.uptimeMillis() - lastHideAt;
                if (sinceHide < 150) {
                    web.postDelayed(new Runnable() {
                        @Override
                        public void run() {
                            showIme(1);
                        }
                    }, 160 - sinceHide);
                    return;
                }
            }
            imeActive = true;
            web.requestFocus();
            imm.restartInput(web);
            imm.showSoftInput(web, InputMethodManager.SHOW_IMPLICIT);
            web.postDelayed(new Runnable() {
                @Override
                public void run() {
                    if (web == null || !imeActive) {
                        return;   // 期间会话已结束（用户按了返回等）：不打扰
                    }
                    if (isImeConnectionActive()) {
                        startImeWatch();
                        return;
                    }
                    if (attempt < 3) {
                        showIme(attempt + 1);
                    } else {
                        // 多次重试仍弹不出：结束会话并让页面复位焦点，下次确认键可重试
                        endImeSession(false);
                        runJs("if(window.__kbFailed){window.__kbFailed();}");
                    }
                }
            }, 250);
        }

        @JavascriptInterface
        public void hideKeyboard() {
            runOnUiThread(new Runnable() {
                @Override
                public void run() {
                    lastHideAt = SystemClock.uptimeMillis();
                    endImeSession(false);   // 页面自己发起的收起，无需回调页面
                    InputMethodManager imm =
                            (InputMethodManager) getSystemService(Context.INPUT_METHOD_SERVICE);
                    if (imm != null && web != null) {
                        imm.hideSoftInputFromWindow(web.getWindowToken(), 0);
                    }
                }
            });
        }

        @JavascriptInterface
        public void downloadFile(String url, String filename) {
            enqueueDownload(url, filename);
        }

        /** 页面弹窗开关上报：弹窗打开时按返回只关弹窗，不再直接退出 App。 */
        @JavascriptInterface
        public void setPickerOpen(boolean open) {
            pickerOpen = open;
        }

        @JavascriptInterface
        public void updateState(final String title, final String artist,
                                final boolean playing, final double position,
                                final double duration) {
            MusicService service = MusicService.getInstance();
            if (service != null) {
                service.updateState(title, artist, playing, position, duration);
            }
        }
    }

    // 刻意不调用 web.onPause() / web.onResume()，保证锁屏与后台切换时音频不被暂停。
    @Override
    protected void onPause() {
        super.onPause();
    }

    @Override
    protected void onResume() {
        super.onResume();
    }

    @Override
    protected void onDestroy() {
        // 用户真正退出（返回键 / 划掉最近任务）时，WebView 即将销毁，音频必然中断，
        // 前台服务与通知随之收起，避免留下"通知显示正在播放但已经没声音"的僵尸状态。
        boolean finishing = isFinishing() && !isChangingConfigurations();
        MusicService.detachActivity(this);
        if (finishing) {
            MusicService service = MusicService.getInstance();
            if (service != null) {
                service.shutdownForTaskRemoved();
            }
        }
        if (web != null) {
            ViewGroup parent = (ViewGroup) web.getParent();
            if (parent != null) {
                parent.removeView(web);
            }
            web.destroy();
            web = null;
        }
        super.onDestroy();
    }
}
