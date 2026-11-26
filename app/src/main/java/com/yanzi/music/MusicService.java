package com.yanzi.music;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Intent;
import android.content.pm.ServiceInfo;
import android.media.AudioAttributes;
import android.media.AudioFocusRequest;
import android.media.AudioManager;
import android.media.MediaMetadata;
import android.media.session.MediaSession;
import android.media.session.PlaybackState;
import android.os.Build;
import android.os.IBinder;
import android.os.PowerManager;
import android.os.SystemClock;

import android.graphics.Bitmap;
import android.graphics.BitmapFactory;

import java.io.IOException;
import java.io.InputStream;
import java.lang.ref.WeakReference;

/**
 * 后台播放保活与媒体通知服务。
 *
 * 现有能力合并在同一个 Service 中：
 * 1. 以前台服务（mediaPlayback）常驻，避免系统在后台杀掉进程；
 * 2. 持有 PARTIAL_WAKE_LOCK，锁屏后 CPU 不休眠，WebView 音频管线持续运行；
 * 3. 请求 AUDIOFOCUS_GAIN 音频焦点；
 * 4. 通过 MediaSession 提供通知栏/锁屏上的播放、暂停、上一首、下一首控制；
 * 5. 页面播放状态、歌曲标题和艺术家回传后刷新通知栏媒体信息。
 */
public class MusicService extends Service {

    public static final String ACTION_PLAY_PAUSE = "com.yanzi.music.action.PLAY_PAUSE";
    public static final String ACTION_NEXT = "com.yanzi.music.action.NEXT";
    public static final String ACTION_PREV = "com.yanzi.music.action.PREV";
    public static final String ACTION_STOP = "com.yanzi.music.action.STOP";

    private static final String CHANNEL_ID = "yanzi_music_playback";
    private static final int NOTIFICATION_ID = 1001;

    private static volatile MusicService instance;

    private WeakReference<MainActivity> activityRef;
    private MediaSession mediaSession;
    private Bitmap albumArt;

    private String songTitle = "燕子音乐";
    private String songArtist = "";
    private boolean isPlaying = false;
    private boolean transientPaused = false;
    /** 当前这次暂停是焦点丢失（来电、通知音）引起的，而非用户主动暂停。 */
    private boolean pauseFromFocusLoss = false;
    /** 最近一次成功请求音频焦点的时刻，用于"起播宽限期"判定。 */
    private long focusGrantedAt = 0;
    /**
     * 起播宽限期（1.7.28 起）。真机时间线证明：启动播放后约 0.2~0.3 秒，系统会向本服务
     * 发一次 AUDIOFOCUS_LOSS / LOSS_TRANSIENT（当贝这类盒子的音频路由切换、自带媒体栈
     * 启动都会触发），旧代码收到就 servicePause()，于是页面"响一下 → 停 1.3 秒 → 再正常播放"。
     * 宽限期内这种早到的焦点丢失一律不暂停，播放保持连续。
     */
    private static final long STARTUP_GRACE_MS = 5000;
    private long positionMs = 0;
    private long durationMs = 0;

    private PowerManager.WakeLock wakeLock;
    private AudioManager audioManager;
    private AudioFocusRequest focusRequest;
    private AudioManager.OnAudioFocusChangeListener focusListener;

    public static MusicService getInstance() {
        return instance;
    }

    public static void attachActivity(MainActivity activity) {
        if (instance != null && activity != null) {
            instance.activityRef = new WeakReference<>(activity);
        }
    }

    public static void detachActivity(MainActivity activity) {
        if (instance != null) {
            WeakReference<MainActivity> current = instance.activityRef;
            if (current == null || current.get() == activity) {
                instance.activityRef = null;
            }
        }
    }

    private MainActivity activity() {
        if (activityRef == null) {
            return null;
        }
        return activityRef.get();
    }

    @Override
    public void onCreate() {
        super.onCreate();
        instance = this;

        PowerManager powerManager =
                (PowerManager) getSystemService(POWER_SERVICE);
        wakeLock = powerManager.newWakeLock(
                PowerManager.PARTIAL_WAKE_LOCK, "YanziMusic:cpu-wake-lock");
        wakeLock.setReferenceCounted(false);

        audioManager = (AudioManager) getSystemService(AUDIO_SERVICE);

        loadAlbumArt();
        createNotificationChannel();
        createMediaSession();
        startForegroundNotification();
        acquireAudioFocus();
        updateMediaSessionState();
        // 注意：这里不再无条件持有 WakeLock。只在真正播放（updateState playing=true）时上锁，
        // 否则用户只是打开 App 没播放，CPU 也会被一直钉住，属于明显耗电。
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        handleAction(intent == null ? null : intent.getAction());
        if (isPlaying) {
            acquireWakeLock();
        }
        return START_STICKY;
    }

    private void handleAction(String action) {
        if (action == null) {
            return;
        }
        MainActivity activity = activity();
        if (activity == null) {
            return;
        }
        switch (action) {
            case ACTION_PLAY_PAUSE:
                activity.servicePlayPause();
                break;
            case ACTION_NEXT:
                activity.serviceNext();
                break;
            case ACTION_PREV:
                activity.servicePrev();
                break;
            case ACTION_STOP:
                activity.serviceStop();
                break;
            default:
                break;
        }
    }

    /** 页面通过 window.Yanzi.updateState(...) 回传当前媒体状态。 */
    public void updateState(String title, String artist, boolean playing,
                            double position, double duration) {
        if (title != null && !title.trim().isEmpty()) {
            songTitle = title.trim();
        } else {
            songTitle = "燕子音乐";
        }
        songArtist = artist == null ? "" : artist.trim();
        isPlaying = playing;
        if (playing) {
            pauseFromFocusLoss = false;
        } else if (!pauseFromFocusLoss) {
            // 只有用户主动暂停才清除"待续播"标记。
            // 原实现：焦点丢失（来电）先暂停 → JS 随后回传 playing=false → 标记被清掉 →
            // 焦点恢复后不会自动续播。
            transientPaused = false;
        }

        if (!Double.isNaN(position) && position >= 0) {
            positionMs = (long) (position * 1000);
        }
        if (!Double.isNaN(duration) && duration > 0) {
            durationMs = (long) (duration * 1000);
        }

        updateMetadata();
        updatePlaybackState();
        updateNotification();

        if (isPlaying) {
            acquireWakeLock();
        } else {
            releaseWakeLock();
        }
    }

    private void acquireWakeLock() {
        if (wakeLock != null && !wakeLock.isHeld()) {
            // 带超时上锁：即使异常路径漏掉释放，系统也会到期自动回收，不会长期钉住 CPU
            wakeLock.acquire(30 * 60 * 1000L);
        }
    }

    private void releaseWakeLock() {
        if (wakeLock != null && wakeLock.isHeld()) {
            wakeLock.release();
        }
    }

    private void acquireAudioFocus() {
        focusListener = new AudioManager.OnAudioFocusChangeListener() {
            @Override
            public void onAudioFocusChange(int focusChange) {
                MainActivity activity = activity();
                if (activity == null) {
                    return;
                }
                switch (focusChange) {
                    case AudioManager.AUDIOFOCUS_LOSS:
                        activity.logJs("native:focus LOSS");
                        if (inStartupGrace()) {
                            /* 起播阶段系统自己抖一下焦点：暂停就等于给用户"响一下、停一拍" */
                            activity.logJs("native:focus LOSS ignored(startup)");
                            break;
                        }
                        transientPaused = false;
                        pauseFromFocusLoss = false;
                        activity.serviceStop();
                        releaseAudioFocus();
                        focusGrantedAt = 0;
                        break;
                    case AudioManager.AUDIOFOCUS_LOSS_TRANSIENT:
                        activity.logJs("native:focus LOSS_TRANSIENT");
                        if (inStartupGrace()) {
                            activity.logJs("native:focus LOSS_TRANSIENT ignored(startup)");
                            break;
                        }
                        transientPaused = isPlaying;
                        pauseFromFocusLoss = true;
                        activity.servicePause();
                        break;
                    case AudioManager.AUDIOFOCUS_LOSS_TRANSIENT_CAN_DUCK:
                        activity.logJs("native:focus CAN_DUCK");
                        activity.serviceDuck(true);
                        break;
                    case AudioManager.AUDIOFOCUS_GAIN:
                        activity.logJs("native:focus GAIN");
                        focusGrantedAt = SystemClock.elapsedRealtime();
                        activity.serviceDuck(false);
                        if (transientPaused) {
                            transientPaused = false;
                            pauseFromFocusLoss = false;
                            activity.servicePlay();
                        }
                        break;
                    default:
                        activity.logJs("native:focus " + focusChange);
                        break;
                }
            }
        };

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            AudioAttributes attributes = new AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_MEDIA)
                    .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
                    .build();
            focusRequest = new AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN)
                    .setAudioAttributes(attributes)
                    .setOnAudioFocusChangeListener(focusListener)
                    .build();
            audioManager.requestAudioFocus(focusRequest);
            focusGrantedAt = SystemClock.elapsedRealtime();
        } else {
            audioManager.requestAudioFocus(focusListener,
                    AudioManager.STREAM_MUSIC, AudioManager.AUDIOFOCUS_GAIN);
            focusGrantedAt = SystemClock.elapsedRealtime();
        }
    }

    /** 是否处于起播宽限期：此时早到的焦点丢失不应当被当成"被别的应用抢走"而去暂停。 */
    private boolean inStartupGrace() {
        return focusGrantedAt > 0
                && (SystemClock.elapsedRealtime() - focusGrantedAt) < STARTUP_GRACE_MS;
    }

    private void releaseAudioFocus() {
        if (audioManager == null) {
            return;
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O && focusRequest != null) {
            audioManager.abandonAudioFocusRequest(focusRequest);
        } else {
            audioManager.abandonAudioFocus(focusListener);
        }
    }

    private void loadAlbumArt() {
        try (InputStream input = getAssets().open("11.jpg")) {
            albumArt = BitmapFactory.decodeStream(input);
        } catch (IOException ignored) {
            albumArt = null;
        }
    }

    private void createMediaSession() {
        mediaSession = new MediaSession(this, "YanziMusic");
        mediaSession.setFlags(MediaSession.FLAG_HANDLES_MEDIA_BUTTONS
                | MediaSession.FLAG_HANDLES_TRANSPORT_CONTROLS);
        mediaSession.setActive(true);
        mediaSession.setCallback(new MediaSession.Callback() {
            @Override
            public void onPlay() {
                MainActivity activity = activity();
                if (activity != null) {
                    activity.servicePlay();
                }
            }

            @Override
            public void onPause() {
                MainActivity activity = activity();
                if (activity != null) {
                    activity.servicePause();
                }
            }

            @Override
            public void onSkipToNext() {
                MainActivity activity = activity();
                if (activity != null) {
                    activity.serviceNext();
                }
            }

            @Override
            public void onSkipToPrevious() {
                MainActivity activity = activity();
                if (activity != null) {
                    activity.servicePrev();
                }
            }

            @Override
            public void onStop() {
                MainActivity activity = activity();
                if (activity != null) {
                    activity.serviceStop();
                }
            }
        });
        updateMetadata();
        updatePlaybackState();
    }

    private void updateMetadata() {
        if (mediaSession == null) {
            return;
        }
        MediaMetadata.Builder builder = new MediaMetadata.Builder();
        builder.putString(MediaMetadata.METADATA_KEY_TITLE, songTitle);
        builder.putString(MediaMetadata.METADATA_KEY_ARTIST, songArtist);
        builder.putString(MediaMetadata.METADATA_KEY_ALBUM, "燕子音乐");
        if (durationMs > 0) {
            builder.putLong(MediaMetadata.METADATA_KEY_DURATION, durationMs);
        }
        if (albumArt != null) {
            builder.putBitmap(MediaMetadata.METADATA_KEY_ALBUM_ART, albumArt);
        }
        mediaSession.setMetadata(builder.build());
    }

    private void updatePlaybackState() {
        if (mediaSession == null) {
            return;
        }
        long actions = PlaybackState.ACTION_PLAY
                | PlaybackState.ACTION_PAUSE
                | PlaybackState.ACTION_SKIP_TO_NEXT
                | PlaybackState.ACTION_SKIP_TO_PREVIOUS
                | PlaybackState.ACTION_STOP;

        PlaybackState.Builder builder = new PlaybackState.Builder();
        builder.setActions(actions);
        int state = isPlaying
                ? PlaybackState.STATE_PLAYING
                : PlaybackState.STATE_PAUSED;
        builder.setState(state, positionMs, isPlaying ? 1f : 0f,
                SystemClock.elapsedRealtime());
        mediaSession.setPlaybackState(builder.build());
    }

    private void updateMediaSessionState() {
        updateMetadata();
        updatePlaybackState();
    }

    private void createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            NotificationManager manager =
                    (NotificationManager) getSystemService(NOTIFICATION_SERVICE);
            NotificationChannel channel = new NotificationChannel(
                    CHANNEL_ID,
                    "燕子音乐后台播放",
                    NotificationManager.IMPORTANCE_LOW);
            channel.setDescription("锁屏和后台期间保持音乐持续播放");
            channel.setShowBadge(false);
            manager.createNotificationChannel(channel);
        }
    }

    private PendingIntent serviceIntent(String action, int requestCode) {
        Intent intent = new Intent(this, MusicService.class);
        intent.setAction(action);
        int flags = PendingIntent.FLAG_UPDATE_CURRENT;
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            flags |= PendingIntent.FLAG_IMMUTABLE;
        }
        return PendingIntent.getService(this, requestCode, intent, flags);
    }

    private Notification buildNotification() {
        Intent openIntent = new Intent(this, MainActivity.class);
        openIntent.setFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP
                | Intent.FLAG_ACTIVITY_CLEAR_TOP);
        int pendingFlags = PendingIntent.FLAG_UPDATE_CURRENT;
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            pendingFlags |= PendingIntent.FLAG_IMMUTABLE;
        }
        PendingIntent pendingIntent =
                PendingIntent.getActivity(this, 0, openIntent, pendingFlags);

        Notification.Builder builder;
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            builder = new Notification.Builder(this, CHANNEL_ID);
        } else {
            builder = new Notification.Builder(this);
        }

        builder.setContentTitle(songTitle);
        builder.setContentText(songArtist.isEmpty() ? "后台播放中" : songArtist);
        // 小图标必须是单色 alpha 图：彩色 launcher 图在 Android 5+ 会被渲染成白色方块
        builder.setSmallIcon(R.drawable.ic_stat_music);
        if (albumArt != null) {
            builder.setLargeIcon(albumArt);
        }
        builder.setContentIntent(pendingIntent);
        builder.setOngoing(true);
        builder.setOnlyAlertOnce(true);

        if (mediaSession != null) {
            Notification.MediaStyle style = new Notification.MediaStyle();
            style.setMediaSession(mediaSession.getSessionToken());
            style.setShowActionsInCompactView(0, 1, 2);
            builder.setStyle(style);
        }

        builder.addAction(android.R.drawable.ic_media_previous,
                "上一首", serviceIntent(ACTION_PREV, 10));
        builder.addAction(isPlaying ? android.R.drawable.ic_media_pause
                        : android.R.drawable.ic_media_play,
                isPlaying ? "暂停" : "播放",
                serviceIntent(ACTION_PLAY_PAUSE, 11));
        builder.addAction(android.R.drawable.ic_media_next,
                "下一首", serviceIntent(ACTION_NEXT, 12));

        return builder.build();
    }

    private void startForegroundNotification() {
        Notification notification = buildNotification();
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(NOTIFICATION_ID, notification,
                    ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK);
        } else {
            startForeground(NOTIFICATION_ID, notification);
        }
    }

    private void updateNotification() {
        NotificationManager manager =
                (NotificationManager) getSystemService(NOTIFICATION_SERVICE);
        if (manager != null) {
            manager.notify(NOTIFICATION_ID, buildNotification());
        }
    }

    /** Activity 真正退出（返回键 / 划掉最近任务）时调用：收掉前台通知与常驻服务。 */
    public void shutdownForTaskRemoved() {
        isPlaying = false;
        transientPaused = false;
        releaseWakeLock();
        releaseAudioFocus();
        if (mediaSession != null) {
            mediaSession.setActive(false);
        }
        stopForeground(true);
        stopSelf();
    }

    @Override
    public void onTaskRemoved(Intent rootIntent) {
        // 从最近任务划掉：WebView 随任务销毁，音频必然中断，留下无声的"正在播放"通知只会误导。
        // 与 MainActivity.onDestroy 的退出收尾双保险，哪个先触发都一样。
        super.onTaskRemoved(rootIntent);
        shutdownForTaskRemoved();
    }

    @Override
    public void onDestroy() {
        instance = null;
        activityRef = null;

        if (mediaSession != null) {
            mediaSession.setActive(false);
            mediaSession.release();
            mediaSession = null;
        }
        releaseWakeLock();
        releaseAudioFocus();
        if (albumArt != null) {
            albumArt.recycle();
            albumArt = null;
        }
        super.onDestroy();
    }

    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }
}
