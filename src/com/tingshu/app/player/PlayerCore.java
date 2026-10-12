package com.tingshu.app.player;

import android.content.Context;
import android.media.AudioAttributes;
import android.media.AudioFocusRequest;
import android.media.AudioManager;
import android.media.MediaPlayer;
import android.media.audiofx.LoudnessEnhancer;
import android.net.Uri;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;

import com.tingshu.app.core.Net;
import com.tingshu.app.data.HistoryRepo;
import com.tingshu.app.data.Models.Book;
import com.tingshu.app.data.Models.Chapter;
import com.tingshu.app.data.Settings;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 播放内核。第 3 批会整体搬到 PlayerService，所以这里：
 * - 不持有 Activity / WebView 引用（只用一个 Callback 接口回吐事件）
 * - 不做 UI 操作（Toast 由调用方处理）
 * - 所有公开方法都在主线程调用
 *
 * 现在的持有者是 MainActivity，之后换成 Service 时只改持有者，本类不动。
 */
public class PlayerCore {

    /** 事件回调，全部在主线程触发 */
    public interface Callback {
        /** 音频开始播放（prepared + start 完成） */
        void onStarted(Book book, int chapterIdx);

        /** 状态变化（播放/暂停），供 UI 更新按钮 */
        void onStateChanged(boolean playing);

        /** 进度，每秒一次 */
        void onProgress(int curSec, int durSec);

        /** 一集播放自然结束 */
        void onEnded();

        /** 出错 */
        void onError(String msg);
    }

    private final Context ctx;
    private final Handler ui = new Handler(Looper.getMainLooper());
    private Callback cb;

    private MediaPlayer player;
    private float speed = 1.0f;
    /** 播放完一集后是否自动下一集（由 playMode 决定，见 onCompletion） */
    private boolean autoNext = true;

    // ---------- ★ v3.1 音量增益 ----------
    /**
     * 音量增益百分比：100 = 原始音量（默认），范围 50~300。
     *
     * <p>为什么不用单一的 dB 值：用户对这个滑杆的心智模型是「音量百分比」，
     * 而不是分贝。底层再按 低于/高于 100 分成两条实现路径（见 applyGainInternal）。
     */
    private int gainPct = 100;
    /**
     * 增益音效（★ 会话级资源）。
     *
     * <p>它绑定 {@code MediaPlayer.getAudioSessionId()}，而我们在
     * {@link #playUrl} 里每换一集都会 new 一个 MediaPlayer —— 会话号变了，
     * 旧 enhancer 就失效了。所以必须在 onPrepared 里重建，
     * 否则表现为「第一集增益有效，从第二集起音量又回到原始」。
     */
    private LoudnessEnhancer enhancer;

    // ---------- ★ v3.1 音频焦点（其他媒体声音打断） ----------
    private AudioManager audioManager;
    private AudioFocusRequest focusRequest;      // API 26+
    private Object legacyFocusListener;          // API 21-25 用的 OnAudioFocusChangeListener
    /** 当前是否处于「被别的应用短暂压制」的降音量状态 */
    private boolean ducking;
    /** 是否是「因为丢失焦点而暂停」的（拿到焦点后要自动恢复） */
    private boolean pausedByFocus;
    /** 拿到音频焦点时是否正在播放（用于判断要不要补请求） */
    private boolean hasFocus;

    // ---------- 当前播放上下文 ----------
    private Book book;
    private List<Chapter> chapters = new ArrayList<Chapter>();
    private int index = 0;               // 0 基
    private String audioUrl;
    private String usedSource;           // 实际取链成功的源
    private String chapterUrl;

    // ---------- 进度上报 ----------
    private boolean progressRunning = false;
    private long lastRecordAt = 0;
    /** 距上次写库的秒数，避免每秒写 */
    private static final long RECORD_INTERVAL_MS = 5000;

    public PlayerCore(Context c) {
        this.ctx = c.getApplicationContext();
        // ★ v3.1：增益与音频焦点设置跟着 App 全局走，构造时就恢复
        this.gainPct = Settings.gain();
    }

    public void setCallback(Callback c) {
        this.cb = c;
    }

    public void setAutoNext(boolean v) {
        this.autoNext = v;
    }

    // ==================== 状态读取 ====================

    public Book book() { return book; }

    public List<Chapter> chapters() { return chapters; }

    public int index() { return index; }

    public String usedSource() { return usedSource; }

    public String chapterUrl() { return chapterUrl; }

    public boolean isPlaying() {
        try {
            return player != null && player.isPlaying();
        } catch (Throwable t) {
            return false;
        }
    }

    public boolean hasContent() {
        return book != null && !chapters.isEmpty();
    }

    public int curSec() {
        try {
            return player == null ? 0 : player.getCurrentPosition() / 1000;
        } catch (Throwable t) {
            return 0;
        }
    }

    public int durSec() {
        try {
            return player == null ? 0 : player.getDuration() / 1000;
        } catch (Throwable t) {
            return 0;
        }
    }

    public float speed() {
        return speed;
    }

    public Chapter currentChapter() {
        if (index < 0 || index >= chapters.size()) return null;
        return chapters.get(index);
    }

    // ==================== 装载播放列表 ====================

    /**
     * 设置当前播放上下文。切书/切集前调用。
     */
    public void setQueue(Book b, List<Chapter> chs, int idx) {
        this.book = b;
        this.chapters = chs == null ? new ArrayList<Chapter>() : chs;
        this.index = Math.max(0, idx);
    }

    // ==================== 播放 ====================

    /**
     * 播放已知直链。
     *
     * @param startMs 起始毫秒（续播传上次偏移；传 0 从头）
     */
    public void playUrl(String source, String chapterUrl, String audio, long startMs) {
        playUrl(source, chapterUrl, audio, startMs, null);
    }

    /**
     * 播放已知直链（带源要求的额外请求头）。
     *
     * <p>很多源的音频 CDN 会校验 Referer，缺了直接 403。源通过
     * {@link com.tingshu.app.source.Source#audioHeaders(String)} 声明这些头，
     * 这里必须原样带上，否则「取链成功但播放失败」。
     *
     * @param extraHeaders 源要求的请求头（Referer 等），可为 null
     */
    public void playUrl(String source, String chapterUrl, String audio, long startMs,
                        Map<String, String> extraHeaders) {
        this.usedSource = source;
        this.chapterUrl = chapterUrl;
        this.audioUrl = audio;

        try {
            release();
            player = new MediaPlayer();
            player.setAudioStreamType(AudioManager.STREAM_MUSIC);

            Map<String, String> headers = new HashMap<String, String>();
            headers.put("User-Agent", Net.UA);
            // 源自己声明的头优先级更高（Referer / Cookie / 自定义 UA 等）
            if (extraHeaders != null) {
                for (Map.Entry<String, String> e : extraHeaders.entrySet()) {
                    if (e.getKey() == null || e.getValue() == null) continue;
                    headers.put(e.getKey(), e.getValue());
                }
            }

            if (audio != null && audio.startsWith("http")) {
                player.setDataSource(ctx, Uri.parse(audio), headers);
            } else if (audio != null && (audio.startsWith("content://")
                    || audio.startsWith("file://"))) {
                // ★ v2.9：本地文件也必须走带 Context 的重载。
                //   下载到外部文件夹（SAF）后地址是 content://，用
                //   setDataSource(String) 会解析失败直接抛异常 —— 表现为
                //   「下载了却播不了」。file:// 走同一分支也无副作用。
                player.setDataSource(ctx, Uri.parse(audio), headers);
            } else {
                player.setDataSource(audio);
            }

            final long start = Math.max(0, startMs);

            player.setOnPreparedListener(new MediaPlayer.OnPreparedListener() {
                public void onPrepared(MediaPlayer mp) {
                    applySpeedInternal(mp, speed);
                    // ★ v3.1：这里必须重新套用增益。mp 是刚 new 出来的，
                    //   audioSessionId 跟上一集不同，旧的 LoudnessEnhancer 已经无效了。
                    applyGainInternal(mp);
                    // ★ v3.1：起播前拿音频焦点，这样微信来语音时我们才能收到通知
                    acquireFocus();
                    // ★ v3.8.1 跳过片头：只在「没有续播进度」时生效。
                    //   playUrl 覆盖了首次起播 / 自动下一集 / 手动切集 / 自动换源
                    //   四条路径，所以改这一处即可，不用分别改。
                    long seekTo = start;
                    if (seekTo <= 0) {
                        int skip = com.tingshu.app.data.Settings.skipIntroSec();
                        if (skip > 0) seekTo = skip * 1000L;
                    }
                    if (seekTo > 0 && seekTo < mp.getDuration()) {
                        mp.seekTo((int) seekTo);
                    }
                    mp.start();
                    startProgress();
                    // ★ 一开播就落一条历史：这样"刚点开听"就能在历史页看到，
                    //   不等 5 秒定时或暂停。之前因为没有 setQueue，这里存不进去。
                    saveProgressNow();
                    if (cb != null) {
                        cb.onStarted(book, index);
                        cb.onStateChanged(true);
                    }
                }
            });
            player.setOnCompletionListener(new MediaPlayer.OnCompletionListener() {
                public void onCompletion(MediaPlayer mp) {
                    stopProgress();
                    // 先把这一集听到末尾的进度存下（换集前必须落盘）
                    saveProgressNow();
                    // ★ 不在这里喊 onStateChanged(false)：结束拉下一集时状态仍然是"在播"，
                    //   过早置 false 会让图标闪一下三角。是否真的停由 onEnded 的调用方决定。
                    if (cb != null) {
                        cb.onEnded();
                    }
                }
            });
            player.setOnErrorListener(new MediaPlayer.OnErrorListener() {
                public boolean onError(MediaPlayer mp, int what, int extra) {
                    stopProgress();
                    if (cb != null) {
                        cb.onStateChanged(false);
                        cb.onError("播放失败(" + what + "," + extra + ")");
                    }
                    return true;
                }
            });
            player.prepareAsync();
        } catch (Throwable t) {
            if (cb != null) cb.onError("播放异常: " + t.getMessage());
        }
    }

    /** 换直链但保持当前集偏移（自动换源后用） */
    public void swapUrl(String source, String chapterUrl, String audio) {
        swapUrl(source, chapterUrl, audio, null);
    }

    /** 换直链但保持当前集偏移，并带上新源要求的请求头 */
    public void swapUrl(String source, String chapterUrl, String audio,
                        Map<String, String> extraHeaders) {
        long pos = 0;
        try {
            pos = player == null ? 0 : player.getCurrentPosition();
        } catch (Throwable ignored) {}
        playUrl(source, chapterUrl, audio, pos, extraHeaders);
    }

    public void pause() {
        // ★ v3.1：用户主动暂停 → 清掉「因焦点暂停」的标记。
        //   不清的话，等下微信语音结束、WeChat 把焦点还回来时，
        //   我们会误以为是自己暂停的，替用户把音频放起来。
        pausedByFocus = false;
        try {
            if (player != null && player.isPlaying()) {
                player.pause();
                saveProgressNow();
            }
        } catch (Throwable ignored) {}
        stopProgress();
        if (cb != null) cb.onStateChanged(false);
    }

    public void resume() {
        pausedByFocus = false;
        try {
            if (player != null) {
                player.start();
                startProgress();
                if (cb != null) cb.onStateChanged(true);
            }
        } catch (Throwable ignored) {}
    }

    public void toggle() {
        if (isPlaying()) pause();
        else resume();
    }

    /**
     * 幂等的「开始播放」：已经在播就什么都不做，暂停/已加载未播则恢复。
     * 供「点开播放页自动播放」使用 —— 用 toggle 会在已播时误暂停。
     *
     * <p>★ v2.6：返回**是否真的在播**。原来返回 void 且 `player == null`
     * 时静默 return，冷启动（进程被杀过、native 侧没有 player）
     * 点悬浮球就变成「既不播也不报错」，前端只能一直转圈。
     * 现在把结果回给调用方，让它知道要改走「重新取链」这条路。
     *
     * @return true = 现在确实在播放（或已恢复播放）；false = 没有可复用的播放器
     */
    public boolean playIfPaused() {
        try {
            if (isPlaying()) return true;
            if (player == null) return false;      // ★ 关键：明确告诉调用方"没播放器"
            if (audioUrl == null || audioUrl.length() == 0) return false;
            resume();
            return true;
        } catch (Throwable t) {
            return false;
        }
    }

    public void seek(int seconds) {
        try {
            if (player != null) {
                player.seekTo(Math.max(0, seconds) * 1000);
                if (cb != null) cb.onProgress(curSec(), durSec());
            }
        } catch (Throwable ignored) {}
    }

    public void seekToMs(long ms) {
        try {
            if (player != null) player.seekTo((int) Math.max(0, ms));
        } catch (Throwable ignored) {}
    }

    public void setSpeed(float s) {
        this.speed = s;
        Settings.setSpeed(s);
        try {
            if (player != null) applySpeedInternal(player, s);
        } catch (Throwable ignored) {}
    }

    // ==================== ★ v3.1 音量增益 ====================

    /** 当前增益百分比（100 = 原始音量） */
    public int gain() { return gainPct; }

    /**
     * 设置音量增益百分比，范围会被钳到 50~300。
     *
     * <p>分两条实现路径：
     * <ul>
     *   <li><b>降幅</b>（&lt;100）→ {@code MediaPlayer.setVolume}。LoudnessEnhancer
     *       的 gain 只能取 0~+2000mB，**做不了负增益**，所以衰减只能走音量接口。</li>
     *   <li><b>增幅</b>（&gt;100）→ {@code LoudnessEnhancer.setTargetGain}。
     *       音量本身已经拉满，靠响度增强把弱音提上来。</li>
     * </ul>
     * 两者听感不完全等价（前者是纯数字衰减，后者带动态压缩），
     * 但对「听书时前后集音量不匀」这个实际需求够用。
     */
    public void setGain(int pct) {
        this.gainPct = clampGain(pct);
        Settings.setGain(this.gainPct);
        try {
            if (player != null) applyGainInternal(player);
        } catch (Throwable ignored) {}
    }

    private static int clampGain(int pct) {
        if (pct < 50) return 50;
        if (pct > 300) return 300;
        return pct;
    }

    /**
     * 把当前 {@link #gainPct} 应用到某个 MediaPlayer 上。
     *
     * <p>★ 每次换集（新 MediaPlayer）都要调一次，见 onPrepared 里的注释。
     *
     * @param mp 目标播放器；传 null 表示用当前 player
     */
    private void applyGainInternal(MediaPlayer mp) {
        if (mp == null) mp = this.player;
        if (mp == null) return;

        try {
            if (gainPct <= 100) {
                // 降幅：纯音量衰减，并把响度增强关掉（避免上面残留的增益叠加）
                float v = gainPct / 100f;
                mp.setVolume(v, v);
                releaseEnhancer();
                return;
            }

            // 增幅：音量拉满 + 响度增强
            mp.setVolume(1f, 1f);
            // 100% → 0mB，200% → +6dB(600mB)，300% → +12dB(1200mB)
            // LoudnessEnhancer 上限是 2000mB，留足余量
            int mb = (gainPct - 100) * 6;
            ensureEnhancer(mp, mb);
        } catch (Throwable ignored) {
            // 个别 ROM 不支持 audiofx，静默降级为「增益无效但播放正常」
        }
    }

    /** 建/复用 LoudnessEnhancer 并套上 targetGain（mB） */
    private void ensureEnhancer(MediaPlayer mp, int millibels) {
        int session = 0;
        try {
            session = mp.getAudioSessionId();
        } catch (Throwable ignored) {}

        // 会话号变了 / 还没有 enhancer → 重建。旧的一定要 release，
        // 否则 native 侧会累积（一个 MediaPlayer 一个 effect，泄漏很快）。
        if (enhancer == null || !enhancerSessionOk(session)) {
            releaseEnhancer();
            try {
                enhancer = new LoudnessEnhancer(session);
                enhancerSession = session;
            } catch (Throwable t) {
                enhancer = null;
                return;
            }
        }
        try {
            enhancer.setTargetGain(millibels);
            enhancer.setEnabled(true);
        } catch (Throwable ignored) {}
    }

    /** 当前 enhancer 绑定的会话号，用于判断换集后是否需要重建 */
    private int enhancerSession = -1;

    private boolean enhancerSessionOk(int session) {
        return enhancerSession == session;
    }

    private void releaseEnhancer() {
        if (enhancer != null) {
            try {
                enhancer.setEnabled(false);
            } catch (Throwable ignored) {}
            try {
                enhancer.release();
            } catch (Throwable ignored) {}
            enhancer = null;
        }
        enhancerSession = -1;
    }

    public void stop() {
        stopProgress();
        saveProgressNow();
        try {
            if (player != null) {
                player.stop();
            }
        } catch (Throwable ignored) {}
        release();
        book = null;
        chapters = new ArrayList<Chapter>();
        index = 0;
        audioUrl = null;
        usedSource = null;
        chapterUrl = null;
    }

    public void release() {
        abandonFocus();
        releaseEnhancer();
        if (player != null) {
            try {
                player.reset();
            } catch (Throwable ignored) {}
            try {
                player.release();
            } catch (Throwable ignored) {}
            player = null;
        }
    }

    // ==================== ★ v3.1 音频焦点（其他媒体声音打断） ====================

    /**
     * 申请音频焦点。
     *
     * <p>为什么需要它：不给系统「我要独占音频」的信号，微信放语音条时
     * 系统不会通知我们，于是两路声音叠着放。拿到焦点后，
     * 微信放语音 → 系统给我们发 LOSS_TRANSIENT_CAN_DUCK / LOSS_TRANSIENT
     * → 我们按用户选的策略降音量或暂停。
     *
     * <p>模式 0（不处理）时**不申请**焦点 —— 有些用户不希望听书被任何东西打断。
     */
    private void acquireFocus() {
        int mode = Settings.audioFocusMode();
        if (mode == 0) return;

        try {
            if (audioManager == null) {
                audioManager = (AudioManager) ctx.getSystemService(Context.AUDIO_SERVICE);
            }
            if (audioManager == null) return;

            if (Build.VERSION.SDK_INT >= 26) {
                if (focusRequest == null) {
                    // AUDIOFOCUS_GAIN：我们要长期独占（听书是长时间播放场景）
                    // CONTENT_TYPE_SPEECH：让系统知道是人声，降音/Duck 的时机更准
                    focusRequest = new AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN)
                            .setAudioAttributes(new AudioAttributes.Builder()
                                    .setUsage(AudioAttributes.USAGE_MEDIA)
                                    .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                                    .build())
                            // 声明「被 Duck 时我会自己降音量」，系统才会优先发 CAN_DUCK
                            .setWillPauseWhenDucked(false)
                            .setOnAudioFocusChangeListener(focusChangeListener())
                            .build();
                }
                hasFocus = audioManager.requestAudioFocus(focusRequest)
                        == AudioManager.AUDIOFOCUS_REQUEST_GRANTED;
            } else {
                // API 21-25：旧签名。API 26 起废弃、31 移除，所以要分版本走。
                // ★ 这里用反射而不是直接调用：目标 SDK 35 编译时旧重载已被标记移除，
                //   直接写会编译不过；而 minSdk 21 又必须保留这条路。
                hasFocus = legacyRequest();
            }
        } catch (Throwable ignored) {
            hasFocus = false;
        }
    }

    private void abandonFocus() {
        try {
            if (audioManager != null && hasFocus) {
                if (Build.VERSION.SDK_INT >= 26) {
                    if (focusRequest != null) audioManager.abandonAudioFocusRequest(focusRequest);
                } else {
                    legacyAbandon();
                }
            }
        } catch (Throwable ignored) {}
        hasFocus = false;
        pausedByFocus = false;
        ducking = false;
    }

    /**
     * API 21-25 的申请焦点（反射调用旧重载）。
     *
     * <p>为什么不直接写 {@code audioManager.requestAudioFocus(listener, ...)}：
     * 编译用的 android.jar 是 API 35，那个以 listener 为第一参的重载
     * 在 API 31 就被移除了，直接写编不过。反射是本工程一贯的兼容手法
     * （见 {@code applySpeedInternal} 里的 setPlaybackParams）。
     */
    private boolean legacyRequest() {
        try {
            java.lang.reflect.Method m = AudioManager.class.getMethod(
                    "requestAudioFocus",
                    Class.forName("android.media.AudioManager$OnAudioFocusChangeListener"),
                    int.class, int.class);
            Object r = m.invoke(audioManager, legacyListener(),
                    Integer.valueOf(AudioManager.STREAM_MUSIC),
                    Integer.valueOf(AudioManager.AUDIOFOCUS_GAIN));
            return r instanceof Integer
                    && ((Integer) r).intValue() == AudioManager.AUDIOFOCUS_REQUEST_GRANTED;
        } catch (Throwable t) {
            return false;
        }
    }

    /** API 21-25 的放弃焦点（反射调用，避免在高版本被 lint/编译器提示已移除） */
    private void legacyAbandon() {
        try {
            java.lang.reflect.Method m = AudioManager.class.getMethod(
                    "abandonAudioFocus",
                    Class.forName("android.media.AudioManager$OnAudioFocusChangeListener"));
            m.invoke(audioManager, legacyListener());
        } catch (Throwable ignored) {}
    }

    /** 焦点变化回调（API 26+ 直接实现匿名类） */
    private AudioManager.OnAudioFocusChangeListener focusChangeListener() {
        return new AudioManager.OnAudioFocusChangeListener() {
            public void onAudioFocusChange(int change) {
                handleFocusChange(change);
            }
        };
    }

    /** API 21-25 用的监听器（同一份逻辑，走反射注册避免编译期告警） */
    @SuppressWarnings("unchecked")
    private Object legacyListener() {
        if (legacyFocusListener == null) {
            legacyFocusListener = java.lang.reflect.Proxy.newProxyInstance(
                    getClass().getClassLoader(),
                    new Class[]{AudioManager.OnAudioFocusChangeListener.class},
                    new java.lang.reflect.InvocationHandler() {
                        public Object invoke(Object proxy, java.lang.reflect.Method method,
                                             Object[] args) {
                            if ("onAudioFocusChange".equals(method.getName())
                                    && args != null && args.length > 0
                                    && args[0] instanceof Integer) {
                                handleFocusChange((Integer) args[0]);
                            }
                            return null;
                        }
                    });
        }
        return legacyFocusListener;
    }

    /**
     * 焦点变化 → 具体动作。两条策略：
     * <ul>
     *   <li>mode 1（Duck）：临时把音量压到 20%，播放不中断</li>
     *   <li>mode 2（暂停）：直接 pause，结束再 resume</li>
     * </ul>
     * 注意 Duck 模式**完全不碰播放状态**，只改音量，所以不会出现
     * 「打断结束后从中间跳一下」的观感问题。
     */
    private void handleFocusChange(int change) {
        final int mode = Settings.audioFocusMode();
        if (mode == 0) return;

        switch (change) {
            case AudioManager.AUDIOFOCUS_LOSS_TRANSIENT_CAN_DUCK:
                // 系统说「你可以继续放，但小点声」（微信语音就是这一类）
                if (mode == 1) duck(true);
                else pauseByFocus();
                break;

            case AudioManager.AUDIOFOCUS_LOSS_TRANSIENT:
                // 短暂完全让出（电话、语音条播放中）
                if (mode == 1) duck(true);
                else pauseByFocus();
                break;

            case AudioManager.AUDIOFOCUS_LOSS:
                // 永久丢失（来电等），两种模式都停
                if (mode == 1) duck(true);
                else pauseByFocus();
                break;

            case AudioManager.AUDIOFOCUS_GAIN:
                // 焦点回来了
                if (mode == 1) duck(false);
                else resumeByFocus();
                break;

            default:
                break;
        }
    }

    /**
     * Duck 开关。
     *
     * <p>★ 恢复时**不能**简单 setVolume(1,1)：那会把用户设的增益（比如 150%）
     * 一起抹掉，表现为「被微信打断一次后音量就变了」。
     * 正确做法是重放 applyGainInternal，让它按当前 gainPct 重新算一遍。
     */
    private void duck(boolean on) {
        if (player == null) return;
        if (on == ducking) return;      // 幂等，避免重复压低
        ducking = on;
        try {
            if (on) {
                player.setVolume(0.2f, 0.2f);
            } else {
                applyGainInternal(player);   // ★ 按用户设置恢复，而不是恢复到 1.0
            }
        } catch (Throwable ignored) {}
    }

    /** 因焦点丢失而暂停（记录标记，拿到焦点后自动续播） */
    private void pauseByFocus() {
        if (player == null) return;
        try {
            if (player.isPlaying()) {
                player.pause();
                pausedByFocus = true;
                stopProgress();
                if (cb != null) cb.onStateChanged(false);
            }
        } catch (Throwable ignored) {}
    }

    /** 拿到焦点后自动续播（仅当是"因焦点暂停"的） */
    private void resumeByFocus() {
        if (!pausedByFocus) return;
        if (player == null) return;
        pausedByFocus = false;
        try {
            player.start();
            startProgress();
            if (cb != null) cb.onStateChanged(true);
        } catch (Throwable ignored) {}
    }

    // ==================== 倍速 ====================

    /** setPlaybackParams 是 API 23+；低版本反射兜底，再不行静默忽略 */
    private void applySpeedInternal(MediaPlayer mp, float s) {
        if (s <= 0 || mp == null) return;
        if (Build.VERSION.SDK_INT >= 23) {
            try {
                mp.setPlaybackParams(mp.getPlaybackParams().setSpeed(s));
                return;
            } catch (Throwable ignored) {}
        }
        try {
            java.lang.reflect.Method m = MediaPlayer.class.getMethod(
                    "setPlaybackParams", Class.forName("android.media.PlaybackParams"));
            Object params = Class.forName("android.media.PlaybackParams")
                    .getConstructor().newInstance();
            params.getClass().getMethod("setSpeed", float.class).invoke(params, s);
            m.invoke(mp, params);
        } catch (Throwable ignored) {}
    }

    /** 供外部（切音效等）拿到 MediaPlayer */
    public MediaPlayer raw() {
        return player;
    }

    // ==================== 进度 ====================

    private final Runnable tick = new Runnable() {
        public void run() {
            if (!progressRunning) return;
            int cur = curSec(), dur = durSec();
            if (cb != null) cb.onProgress(cur, dur);
            maybeRecord(cur);
            ui.postDelayed(this, 1000);
        }
    };

    private void startProgress() {
        if (progressRunning) return;
        progressRunning = true;
        ui.removeCallbacks(tick);
        ui.postDelayed(tick, 700);
    }

    private void stopProgress() {
        progressRunning = false;
        ui.removeCallbacks(tick);
    }

    /** 每 5 秒写一次库，不每秒写 */
    private void maybeRecord(int curSec) {
        long now = System.currentTimeMillis();
        if (now - lastRecordAt < RECORD_INTERVAL_MS) return;
        lastRecordAt = now;
        saveRecord(curSec);
    }

    /** 立即写库（暂停/切集/退出时） */
    public void saveProgressNow() {
        saveRecord(curSec());
    }

    private void saveRecord(final int curSec) {
        final Book b = this.book;
        if (b == null || chapters.isEmpty()) return;
        final int idx = index;
        final String cu = chapterUrl;
        final String ct = currentChapter() == null ? "" : currentChapter().title;
        final int total = chapters.size();
        final long ms = (long) curSec * 1000;
        // SQLite 写在后台，别卡 UI
        com.tingshu.app.source.Aggregator.singlePool().execute(new Runnable() {
            public void run() {
                try {
                    HistoryRepo.record(b, idx + 1, ms, cu, ct, total);
                } catch (Throwable ignored) {}
            }
        });
    }
}
