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
import android.graphics.Rect;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Environment;
import android.os.IBinder;
import android.os.SystemClock;
import android.text.Editable;
import android.text.InputType;
import android.text.TextWatcher;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.KeyEvent;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewGroup;
import android.view.Window;
import android.view.WindowManager;
import android.view.inputmethod.EditorInfo;
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
import android.widget.LinearLayout;
import android.widget.TextView;

import org.json.JSONObject;

import java.io.File;

/**
 * 主界面：全屏 WebView 加载本地 assets/index.html。
 * - 锁屏 / 退到后台时不调用 webView.onPause()，避免暂停 HTML5 音频；
 *   后台保活由 {@link MusicService}（前台服务 + PARTIAL_WAKE_LOCK + 音频焦点）完成。
 * - 电视 / 遥控器：首次收到方向键时自动开启电视焦点模式；方向键与确认键由原生层拦截，
 *   转发给页面焦点系统；媒体键、菜单键、返回键直接桥接到页面函数。
 * - 输入法分两条路径：
 *   ① 电视端：确认键 → 原生顶部搜索栏（真实 EditText + 系统 IME 附着在原生控件上，
 *      彻底绕开 WebView 的 InputConnection 竞态），文字实时同步进页面原搜索框，
 *      返回键取消、输入法搜索键提交；
 *   ② 手机端：页面内联唤起（restartInput + showSoftInput + isActive 校验重试），
 *      返回键由 ImeWebView.onKeyPreIme 在 IME 消费之前拦截。
 *   windowSoftInputMode=adjustNothing：键盘浮层覆盖，窗口不压缩、页面不重排。
 * - 下载：页面调用 window.Yanzi.downloadFile(url, filename)，由系统 DownloadManager 保存。
 */
public class MainActivity extends Activity {

    private WebView web;
    /** 当前是否按电视交互运行（UI 布局 + 按键转发）。手机误判时可被触摸操作还原。 */
    private boolean tvMode = false;
    /** 设备硬件本身即为电视/盒子（由 UI Mode / leanback / 无触屏判定），触摸不改变该值。 */
    private boolean tvHardware = false;
    private boolean remoteSeen = false;
    /** 电视端"按两次返回才退出"：第一次按下的时间点，超过窗口期就要重新按两次。 */
    private long lastBackPressAt = 0;
    private static final long EXIT_DOUBLE_PRESS_MS = 2000L;
    /** 退出提示浮层（电视端大字提示，2 秒后自动淡出）。 */
    private TextView exitHintView = null;
    private Runnable exitHintHider = null;
    /** 本次启动是否已经排过一次"检查更新"（同一进程只自动查一次，见 Updater.checkAuto）。 */
    private boolean updateCheckedOnce = false;
    /** 系统输入法由本 App 唤起且仍在使用中：此期间方向键/确认键必须放行给系统。 */
    private volatile boolean imeActive = false;
    /**
     * 最近一次收起输入法的时间（uptimeMillis）。
     * 部分 ROM 的 IME 在 hide 之后的一小段时间内会忽略 show 请求，这里做极短冷却。
     */
    private long lastHideAt = 0;
    /** 键盘会话看门狗：会话期间周期校验 InputConnection 是否仍然存活。 */
    private Runnable imeWatch = null;
    /** 页面根布局（WebView 的父容器）。电视端输入不再挂任何原生输入控件，
     *  输入框就是页面自己的那个搜索框 / 歌曲ID框。 */
    private FrameLayout rootView = null;
    /**
     * 键盘会话期间，本 App 窗口是否因"输入法窗口抢走焦点"而失焦。
     * 电视输入法（Leanback 键盘及各家 TV 版输入法）是全屏可获焦窗口，弹出时本窗口
     * 必然失焦；这既是"键盘确实已经接管"的证据，也是"输入法窗口已关闭"的唯一可信判据
     * —— imm.isActive(web) 只说明 InputConnection 还连着，键盘收起后照样为 true。
     */
    private boolean imeLostWindowFocus = false;
    /** 键盘会话开始时间（uptimeMillis）：刚请求唤起后的极短窗口内焦点还在切换，不做判断。 */
    private long imeShowAt = 0;
    /**
     * 唤起输入法的补偿节奏。第 1 次先等 120ms 再 show，之后每 350ms 补一发，最多 8 次。
     * 补发**一有"键盘已经起来"的证据就立刻停手**：反复 showSoftInput 会让输入法不断
     * 重新起始（onStartInputView），表现出来就是"键盘出来了，但方向键好几秒没反应"。
     */
    private static final int IME_FIRST_DELAY = 120;
    private static final int IME_RETRY_GAP = 350;
    private static final int IME_MAX_ATTEMPTS = 8;
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
        rootView = root;

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
                if (web != null) {
                    MusicService.attachActivity(MainActivity.this);
                    view.requestFocus();
                    if (tvMode) {
                        view.evaluateJavascript(
                                "if(window.__setTv){window.__setTv(true);}", null);
                    }
                    // 调试面板已按用户要求默认关闭：它在左上角会压住搜索行。
                    // 需要排查时把 DIAG_ENABLED 改成 true 重新编译即可。
                    diagOn = DIAG_ENABLED;
                    if (diagOn) {
                        runJs("if(window.__diagOn){window.__diagOn();}");
                        diag("pageFinished tvMode=" + tvMode);
                    }
                    // APP 更新：页面加载完再等 3 秒静默查一次（避开启动起播的抢网/抢焦点）。
                    // 没有新版本、没网、接口报错都静默；只有真有新版才弹窗。
                    if (!updateCheckedOnce) {
                        updateCheckedOnce = true;
                        view.postDelayed(new Runnable() {
                            @Override
                            public void run() {
                                Updater.checkAuto(MainActivity.this);
                            }
                        }, 3000);
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
                // 页面已改用自己页面上的输入框，这里只做旧调用兜底（正常不会触发）。
                final EditText input = new EditText(MainActivity.this);
                input.setInputType(InputType.TYPE_CLASS_NUMBER);
                input.setSingleLine(true);
                if (defaultValue != null) input.setText(defaultValue);
                if (defaultValue != null) input.setSelection(input.getText().length());
                try {
                    AlertDialog dialog = new AlertDialog.Builder(MainActivity.this)
                            .setTitle("燕子音乐")
                            .setMessage(message)
                            .setView(input)
                            .setPositiveButton("确定", (d, w) ->
                                    result.confirm(input.getText().toString()))
                            .setNegativeButton("取消", (d, w) -> result.cancel())
                            .setOnCancelListener(d -> result.cancel())
                            .create();
                    // 弹窗是独立窗口，输入法附着在它自己的 EditText 上，不需要借用 WebView 的
                    // imeActive 标记（借用后 show() 一旦抛异常标记就会永久为 true，遥控器方向键
                    // 从此全部被放行，电视上页面焦点彻底失灵）。
                    Window w = dialog.getWindow();
                    if (w != null) {
                        w.setSoftInputMode(
                                WindowManager.LayoutParams.SOFT_INPUT_STATE_ALWAYS_VISIBLE);
                    }
                    dialog.setOnDismissListener(d -> {
                        if (web != null) web.requestFocus();
                    });
                    dialog.show();
                    input.requestFocus();
                } catch (Exception e) {
                    result.cancel();
                }
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

    private int dp(int v) {
        return Math.round(TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, v,
                getResources().getDisplayMetrics()));
    }

    /** 电视端退出提示：屏幕中央大字浮层，2 秒后淡出（遥控器场景下 Toast 太小看不清）。 */
    private void showExitHint() {
        if (rootView == null) {
            return;
        }
        if (exitHintView == null) {
            TextView tv = new TextView(this);
            tv.setText("再按一次返回键退出");
            tv.setTextColor(0xFFFFFFFF);
            tv.setTextSize(TypedValue.COMPLEX_UNIT_SP, 24);
            tv.setSingleLine(true);
            tv.setGravity(Gravity.CENTER);
            tv.setPadding(dp(28), dp(16), dp(28), dp(16));
            android.graphics.drawable.GradientDrawable bg =
                    new android.graphics.drawable.GradientDrawable();
            bg.setColor(0xCC000000);
            bg.setCornerRadius(dp(14));
            tv.setBackground(bg);
            FrameLayout.LayoutParams lp = new FrameLayout.LayoutParams(
                    ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
            lp.gravity = Gravity.BOTTOM | Gravity.CENTER_HORIZONTAL;
            lp.bottomMargin = dp(120);
            rootView.addView(tv, lp);
            exitHintView = tv;
            exitHintHider = new Runnable() {
                @Override
                public void run() {
                    if (exitHintView != null) {
                        exitHintView.animate().alpha(0f).setDuration(250)
                                .withEndAction(new Runnable() {
                                    @Override
                                    public void run() {
                                        if (exitHintView != null) {
                                            exitHintView.setVisibility(View.INVISIBLE);
                                        }
                                    }
                                }).start();
                    }
                }
            };
        }
        exitHintView.animate().cancel();
        exitHintView.setAlpha(1f);
        exitHintView.setVisibility(View.VISIBLE);
        rootView.removeCallbacks(exitHintHider);
        rootView.postDelayed(exitHintHider, EXIT_DOUBLE_PRESS_MS);
    }

    private void hideExitHintNow() {
        if (exitHintView != null) {
            rootView.removeCallbacks(exitHintHider);
            exitHintView.setVisibility(View.INVISIBLE);
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
                // 同步结束会话并回调页面复位焦点（否则返回键会被吞掉一次、
                // 页面还以为"键盘会话中"，按键判断全部失效）。
                endImeSession(true);
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
        diag("endSession notify=" + notifyPage);
        imeActive = false;
        imeLostWindowFocus = false;
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
        // 顺序很重要：先把焦点从页面输入框拿出来（__kbClosed → blur），再收键盘窗口。
        // 反过来的话，Chromium 在输入框仍聚焦时会把刚收起的键盘再拉起来一下，
        // 真机表现就是"按返回键，键盘关了又弹一下"。
        endImeSession(true);
        if (web != null) {
            web.postDelayed(new Runnable() {
                @Override
                public void run() {
                    // 这 120ms 内用户可能又按了确认键重新唤起，此时不能把它收掉
                    if (imeActive || web == null) {
                        return;
                    }
                    InputMethodManager imm = (InputMethodManager)
                            getSystemService(Context.INPUT_METHOD_SERVICE);
                    if (imm != null) {
                        IBinder token = web.getWindowToken();
                        if (token != null) {
                            imm.hideSoftInputFromWindow(token, 0);
                        }
                    }
                }
            }, 120);
        }
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
                    // ① 输入法窗口正持有窗口焦点：键盘一定在，只是连接可能还没绑定好
                    //    （当贝这类盒子冷启动第一秒内 isActive 仍是 false）。
                    if (imeLostWindowFocus) {
                        startImeWatch();
                        return;
                    }
                    // ② 屏幕底部还被输入法占着：键盘就在屏幕上，不能判"消失"。
                    if (imeLooksUp()) {
                        startImeWatch();
                        return;
                    }
                    // ② 刚唤起 1.5s 内的空窗期：输入法服务首次绑定可能更慢，
                    //    此时判"键盘消失"会把刚弹出来的键盘一起收掉。
                    if (SystemClock.uptimeMillis() - imeShowAt < 1500) {
                        startImeWatch();
                        return;
                    }
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

    /**
     * （已移除）电视端原生顶部搜索栏 / 原生输入弹窗体系。
     * 原来的做法是"确认键 → 弹一个原生 EditText（顶部搜索栏或对话框）"，与页面上
     * 用户自己的搜索框并存，属于两套输入控件重复。现在电视端直接使用页面自己的
     * 输入框：页面拿到真实 DOM 焦点 → 原生 showIme() 让系统输入法附着到 WebView，
     * 输入、选字、回车提交、返回收起全部在这一个控件上完成，不再有第二套输入控件。
     */

    @Override
    public void onWindowFocusChanged(boolean hasFocus) {
        super.onWindowFocusChanged(hasFocus);
        diag("windowFocus=" + hasFocus + " imeActive=" + imeActive
                + " lost=" + imeLostWindowFocus);
        if (!hasFocus) {
            // 电视输入法多为全屏可获焦窗口，弹出时本窗口必然失焦；部分盒子在冷启动时
            // 窗口焦点还会抖动一次。因此这里只做记录，绝不结束会话——旧实现在这里
            // 无条件 endImeSession，正是"键盘刚弹就被判失败/会话被清"的根因。
            if (imeActive) {
                imeLostWindowFocus = true;
            }
            return;
        }
        // 焦点回到本窗口时也**不再**以此判定会话结束：某些盒子（当贝）在冷启动第一次
        // 唤起时焦点会抖动，如果在这里结束会话，第一次按确认键就会白按。
        // 会话真正的结束点只有三个：返回键（closeImeFromKey）、连接确认断开（看门狗）、
        // 键盘已不在却又收到方向键/确认键（dispatchKeyEvent 里的放行判据）。
        if (web != null) {
            web.requestFocus();
        }
    }

    /** 本视图所在的窗口当前是否持有焦点（电视输入法窗口接管时返回 false）。 */
    private boolean webHasWindowFocus() {
        return web != null && web.hasWindowFocus();
    }

    /**
     * 输入法窗口在当前画面上占掉的底部像素（0 表示没占）。
     * 用途：当贝这类盒子的输入法**不抢窗口焦点**（windowFocus 一直是 true），
     * 光看焦点判断不出键盘有没有起来；改看"可视区域被输入法吃掉了多少"。
     */
    private int imeBottomInset() {
        if (web == null) {
            return 0;
        }
        Rect r = new Rect();
        try {
            web.getWindowVisibleDisplayFrame(r);
        } catch (Exception e) {
            return 0;
        }
        int screenH = getResources().getDisplayMetrics().heightPixels;
        return Math.max(0, screenH - r.bottom);
    }

    /** 键盘是否已经占了屏幕底部（超过屏高 15% 就认为起来了）。 */
    private boolean imeLooksUp() {
        int screenH = getResources().getDisplayMetrics().heightPixels;
        return imeBottomInset() > screenH * 15 / 100;
    }

    /** 刚请求唤起键盘的极短窗口内，焦点还在切换，不做"按键是否被输入法吃掉"的判断。 */
    private boolean justShowedIme() {
        return SystemClock.uptimeMillis() - imeShowAt < 400;
    }

    /**
     * 屏上诊断面板总开关。默认关闭：面板固定在左上角会压住搜索行（1.7.19 用户反馈）。
     * 排查问题时改成 true 重新编译即可恢复整套链路日志。
     */
    private static final boolean DIAG_ENABLED = false;
    /** 诊断日志开关（运行时状态，由 DIAG_ENABLED 决定初值）。 */
    private boolean diagOn = DIAG_ENABLED;

    /** 页面自绘键盘是否打开：打开时返回键只用来关它，不退出、不回退 WebView。 */
    private volatile boolean tvKbOpen = false;

    private void diag(String msg) {
        if (!diagOn || web == null) {
            return;
        }
        runJs("if(window.__diag){window.__diag(" + JSONObject.quote(msg) + ");}");
    }

    private void runJs(String script) {
        if (web == null) {
            return;
        }
        web.evaluateJavascript(script, null);
    }

    /** 把原生侧动作打到页面的事件时间线上（1.7.27 起播诊断：确认真机上的"停顿"是谁造成的）。 */
    public void logJs(final String tag) {
        runOnUiThread(new Runnable() {
            @Override
            public void run() {
                runJs("(function(){ if(window.__alog) __alog(" + JSONObject.quote(tag) + "); })();");
            }
        });
    }

    /** 供后台服务通知栏 / MediaSession 按键回调，转发到页面播放器。 */
    public void servicePlayPause() {
        runOnUiThread(new Runnable() {
            @Override
            public void run() {
                runJs("(function(){ if(window.__alog) __alog('native:servicePlayPause'); if(typeof togglePlay==='function') togglePlay(); })();");
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
                runJs("(function(){ if(window.__alog) __alog('native:servicePlay'); if(window.audio && audio.src){ if(typeof safePlay==='function') safePlay(); } else if(typeof loadCurrent==='function'){ loadCurrent(true); } })();");
            }
        });
    }

    public void servicePause() {
        runOnUiThread(new Runnable() {
            @Override
            public void run() {
                runJs("(function(){ if(window.__alog) __alog('native:servicePause'); if(window.audio&&typeof audio.pause==='function') audio.pause(); })();");
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
                /* 走页面统一的音量出口：原来直接写 audio.volume 会把"启动静音预热"的 0 冲成 1，
                   那一声就又漏出来了。 */
                runJs("(function(){ if(window.__setDuck) __setDuck(" + (duck ? "true" : "false") + "); else if(window.audio){ audio.volume=" + (duck ? "0.25" : "(audio.muted ? 0 : 1)") + "; } })();");
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

    /** 允许按住连发的键：只有方向键。 */
    private boolean isRepeatableKey(int keyCode) {
        switch (keyCode) {
            case KeyEvent.KEYCODE_DPAD_UP:
            case KeyEvent.KEYCODE_DPAD_DOWN:
            case KeyEvent.KEYCODE_DPAD_LEFT:
            case KeyEvent.KEYCODE_DPAD_RIGHT:
                return true;
            default:
                return false;
        }
    }

    /**
     * 本 App 会自己消费的"一次性"按键：重复 DOWN 只认第一次。
     * 不在此列的键（音量、电源等系统键）必须继续交给系统，否则长按音量会变成一档一档。
     */
    private boolean isSingleShotKey(int keyCode) {
        switch (keyCode) {
            case KeyEvent.KEYCODE_BACK:
            case KeyEvent.KEYCODE_MENU:
            case KeyEvent.KEYCODE_DPAD_CENTER:
            case KeyEvent.KEYCODE_ENTER:
            case KeyEvent.KEYCODE_NUMPAD_ENTER:
            case KeyEvent.KEYCODE_BUTTON_A:
            case KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE:
            case KeyEvent.KEYCODE_MEDIA_PLAY:
            case KeyEvent.KEYCODE_MEDIA_PAUSE:
            case KeyEvent.KEYCODE_MEDIA_PREVIOUS:
            case KeyEvent.KEYCODE_MEDIA_NEXT:
            case KeyEvent.KEYCODE_MEDIA_STOP:
                return true;
            default:
                return false;
        }
    }

    @Override
    public boolean dispatchKeyEvent(KeyEvent event) {
        int keyCode = event.getKeyCode();
        diag("key " + (event.getAction() == KeyEvent.ACTION_DOWN ? "DN" : "UP")
                + " " + keyCode + " rep=" + event.getRepeatCount() + " ime=" + imeActive);

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

        // 遥控器长按产生的重复 DOWN：方向键保留（连续走位）；动作键只认第一次按下——
        // 旧实现不判 repeatCount，按住 OK 不放会连续触发 remoteActivate → 再次唤起键盘，
        // 表现为"输入法反复弹出 / 焦点环跳来跳去"。
        if (event.getRepeatCount() > 0) {
            if (isSingleShotKey(keyCode)) {
                return true;
            }
            if (!isRepeatableKey(keyCode)) {
                return super.dispatchKeyEvent(event);
            }
        }

        // 输入法会话中：方向键 / 确认键原则上放行给系统输入法（选候选词、上字）。
        // 但电视输入法（全屏窗口）持有焦点时按键根本到不了这里；能到这里只说明本窗口
        // 仍持有焦点，即输入法并没有在吃按键（键盘其实已经收起，而 InputConnection
        // 还连着，看门狗察觉不到）。此时继续放行会让遥控器焦点永久失灵，必须结束会话。
        if (imeActive && isImePassThroughKey(keyCode)) {
            if (!tvMode || !webHasWindowFocus() || justShowedIme()) {
                return super.dispatchKeyEvent(event);
            }
            endImeSession(true);
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
                diag("->__remote(ok) tvMode=" + tvMode);
                runJs("if(window.__remote){window.__remote('ok');}");
                return true;
            case KeyEvent.KEYCODE_ENTER:
            case KeyEvent.KEYCODE_NUMPAD_ENTER:
                // 实体键盘 Enter（手机）保持默认行为（交给页面 onkeydown 触发搜索）；
                // 已被遥控器唤起电视模式时才接管确认键
                if (remoteSeen || tvMode) {
                    ensureRemote();
                    diag("->__remote(ok) enter tvMode=" + tvMode);
                    runJs("if(window.__remote){window.__remote('ok');}");
                    return true;
                }
                return false;
            case KeyEvent.KEYCODE_BACK:
                // 优先级：自绘键盘 → 系统输入法 → 页面弹窗 → 页面历史 → 交系统处理（退出）
                // 电视端输入已改成页面自绘键盘，这里必须最先判：打开时返回键只关键盘。
                if (tvKbOpen) {
                    tvKbOpen = false;
                    diag("->__closeTvKb");
                    runJs("if(window.__closeTvKb){window.__closeTvKb();}");
                    return true;
                }
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
                // 电视端：遥控器容易误触返回，改成"第一次弹提示，2 秒内再按一次才退出"。
                // 注意不能走 web.canGoBack()：页面为纯网页端塞了 history 哨兵（armBackGuard），
                // APP 内 canGoBack 恒为 true，goBack 会退到空白历史项 → 白屏 + 要按两次。
                if (tvMode) {
                    long now = System.currentTimeMillis();
                    if (now - lastBackPressAt > EXIT_DOUBLE_PRESS_MS) {
                        lastBackPressAt = now;
                        diag("->back hint");
                        showExitHint();
                        return true;
                    }
                    lastBackPressAt = 0;
                    diag("->back exit");
                    hideExitHintNow();
                    finish();
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

        /**
         * 手动检查更新：页面以后若加"检查更新"入口，直接调 window.Yanzi.checkUpdate()。
         * @JavascriptInterface 方法跑在 JavaBridge 线程，UI 一律切回主线程。
         */
        @JavascriptInterface
        public void checkUpdate() {
            runOnUiThread(new Runnable() {
                @Override
                public void run() {
                    Updater.check(MainActivity.this, true);
                }
            });
        }

        /** 当前版本号文本（页面要显示"当前 v1.7.33"时用）。 */
        @JavascriptInterface
        public String version() {
            return Updater.versionText(MainActivity.this);
        }
        /**
         * 弹出输入法（页面输入框方案）。可靠性来自三点：
         *   ① 页面自己把真实焦点放进输入框（focus 必须在用户手势栈里完成），
         *      这里只负责让系统 IME 附着到 WebView；
         *   ② 补发：每 350ms 重新 showSoftInput 一次（最多 8 次），**一检测到键盘已经起来
         *      就立刻停手**——反复 show 会让输入法不断重新起始，键盘出来了却几秒不响应按键；
         *      补发时不再 requestFocus，避免把焦点从输入法窗口抢回 WebView；
         *   ③ 校验认两种"已经起来"的证据：窗口被输入法接管（全屏输入法），或可视区域底部
         *      被输入法吃掉 15% 以上（当贝这种不抢窗口焦点的输入法）。不用 isActive 判成功。
         * 不使用 toggleSoftInput（切换语义会把已弹出的键盘再关掉）。
         */
        @JavascriptInterface
        public void showKeyboard() {
            // 注意：@JavascriptInterface 方法运行在 WebView 的 JavaBridge 线程，不是 UI 线程。
            // 这里不能直接调用任何 WebView 方法（evaluateJavascript 等），否则会抛
            // "A WebView method was called on thread 'JavaBridge'" → JS 侧收到
            // "Java exception was raised during method invocation"，唤起请求直接丢掉。
            runOnUiThread(new Runnable() {
                @Override
                public void run() {
                    diag("bridge.showKeyboard");
                    showIme(0);
                }
            });
        }

        /**
         * 具体唤起实现（attempt = 第几次尝试）。焦点由页面放进自己的输入框，
         * 这里只做 IME 附着与结果校验。
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
                imeActive = true;
                imeShowAt = SystemClock.uptimeMillis();
                imeLostWindowFocus = false;
                // 只在第 1 次把视图焦点要回 WebView。补发时绝不再 requestFocus：
                // 那会把焦点从已经上来的输入法窗口抢回去，正是"键盘出来了但方向键没反应"。
                web.requestFocus();
            } else {
                // 重试前让页面把焦点重新落回同一个输入框（已聚焦时是空操作，不产生 blur）
                runJs("if(window.__kbRefocus){window.__kbRefocus();}");
            }
            // 不再 restartInput：它会把 IME 当前的输入客户端重置掉，紧跟着的
            // showSoftInput 反而更容易被系统当作"没有客户端"丢弃。
            boolean shown = imm.showSoftInput(web, InputMethodManager.SHOW_IMPLICIT);
            diag("show#".concat(String.valueOf(attempt))
                    + " ret=" + shown
                    + " win=" + webHasWindowFocus()
                    + " active=" + isImeConnectionActive()
                    + " vis=" + imeBottomInset()
                    + " lost=" + imeLostWindowFocus);
            final int delay = attempt == 0 ? IME_FIRST_DELAY : IME_RETRY_GAP;
            web.postDelayed(new Runnable() {
                @Override
                public void run() {
                    if (web == null || !imeActive) {
                        return;   // 期间会话已结束（用户按了返回等）：不打扰
                    }
                    boolean active = isImeConnectionActive();
                    boolean win = webHasWindowFocus();
                    boolean up = imeLooksUp();
                    diag("chk#" + attempt
                            + " active=" + active + " win=" + win
                            + " vis=" + imeBottomInset()
                            + " lost=" + imeLostWindowFocus);
                    // 成功判据：① 窗口被输入法窗口接管（全屏输入法）；② 可视区域底部
                    // 被输入法吃掉一大块（不抢焦点的输入法）。二者有其一就停手。
                    // 绝不用 isActive 单独判成功：它只说明 InputConnection 在，键盘未必出来。
                    if (imeLostWindowFocus || up) {
                        startImeWatch();
                        runJs("if(window.__diag){window.__diag('ime up, stop retries');}");
                        return;
                    }
                    if (active) {
                        // InputConnection 已经在了：说明 show 是落到活客户端上的，再补一发
                        // 安全网（第 1 发可能被系统丢掉）就收手。
                        // 继续补发会让输入法反复重新起始（onStartInputView），
                        // 真机表现就是"键盘调出 → 关闭 → 再调出"。
                        if (attempt == 0) {
                            showIme(1);
                            return;
                        }
                        runJs("if(window.__diag){window.__diag('conn live, stop retries');}");
                        startImeWatch();
                        return;
                    }
                    // 连接还没建好：键盘肯定还没出来，此时补发不会造成闪烁，继续。
                    if (attempt + 1 < IME_MAX_ATTEMPTS) {
                        showIme(attempt + 1);
                        return;
                    }
                    endImeSession(false);
                    runJs("if(window.__kbFailed){window.__kbFailed();}");
                }
            }, delay);
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
                        IBinder token = web.getWindowToken();
                        // token 只在视图未附着时为 null；部分 ROM 对 null token 会抛异常
                        if (token != null) {
                            imm.hideSoftInputFromWindow(token, 0);
                        }
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

        /**
         * 页面自绘键盘开关上报（电视端输入法方案二）。
         * 打开时按返回键只关键盘；页面自己负责键盘 UI 与输入，本类不参与任何 IME 操作。
         */
        @JavascriptInterface
        public void setKbOpen(boolean open) {
            // 注意：这里是 JavaBridge 线程，绝对不能调用 web.evaluateJavascript / diag（diag 内部就是
            // evaluateJavascript），跨线程调 WebView 会抛 "Java exception was raised during
            // method invocation"，桥调用整个丢失（1.7.15 就是这么把键盘彻底搞没的）。
            // 这个桥只改一个状态位，日志由页面自己的 __diag 打。
            tvKbOpen = open;
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
        // 刚下载完但因"允许安装应用"开关被拦下时，用户从设置页回来这里自动续装
        Updater.onResume(this);
    }

    @Override
    protected void onDestroy() {
        // 用户真正退出（返回键 / 划掉最近任务）时，WebView 即将销毁，音频必然中断，
        // 前台服务与通知随之收起，避免留下"通知显示正在播放但已经没声音"的僵尸状态。
        boolean finishing = isFinishing() && !isChangingConfigurations();
        Updater.onDestroy();
        // 会话看门狗必须在 web 置空之前停掉：它还可能被消息队列触发一次。
        imeActive = false;
        stopImeWatch();
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
