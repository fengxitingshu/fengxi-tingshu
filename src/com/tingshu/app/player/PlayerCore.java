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

import com.tingshu.app.BridgeSource;
import com.tingshu.app.core.Net;
import com.tingshu.app.data.HistoryRepo;
import com.tingshu.app.data.Models.Book;
import com.tingshu.app.data.Models.Chapter;
import com.tingshu.app.data.Settings;
import com.tingshu.app.source.Aggregator;

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

    /**
     * 播放失败自愈的状态回调（自动重试 / 自动换源时）。
     * 不设置则自愈静默进行（不打扰用户）。
     */
    public interface HealListener {
        /**
         * @param kind       0 = 网络波动自动重试；1 = 已自动切换到新音源
         * @param source     换源后的新源 id（kind=1 时有值）
         * @param sourceName 新源名称（kind=1 时有值）
         */
        void onHeal(int kind, String source, String sourceName);
    }

    private final Context ctx;
    private final Handler ui = new Handler(Looper.getMainLooper());
    private Callback cb;

    /** ★ v4.9.0 双内核：系统 MediaPlayer 或 ijkplayer（FFmpeg 软解 WMA 等） */
    private Engines.Engine player;
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
    /** ★ v3.9.7：focusRequest 建立时的策略值，策略变化则重建 */
    private int focusReqMode = -1;
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

    // ---------- ★ P0-1 自动换源 / P0-2 网络重试（播放失败自愈） ----------
    /** 网络类错误已重试次数（同一集内，成功起播后清零） */
    private int netRetries = 0;
    /** 本集已尝试失败、需跳过的源 id（换源用），跨重试累积 */
    private List<String> triedSources = new ArrayList<String>();
    /** 上次取链用的请求头，重试同一直链时原样复用（带 Referer 的源必须） */
    private Map<String, String> lastHeaders;
    /** 最近一次上报的播放秒数，出错时据此在原位续播 */
    private int lastCurSec = 0;
    /** 自愈代际：stop / 切集 / 起播会自增，过期的延迟重试据此作废 */
    private int healGen = 0;
    /** ★ v3.9.7：上一轮自愈失败时的位置（秒）；同一位置二次失败则从头自愈 */
    private int lastFailPos = -1;
    /** P0-2 网络重试上限与间隔基数（实际间隔 1s / 2s / 4s，最多 3 次） */
    private static final int MAX_NET_RETRIES = 3;
    private static final long NET_RETRY_BASE_MS = 1000L;
    /** 自愈状态回调（可空） */
    private HealListener healCb;

    public void setHealListener(HealListener l) { this.healCb = l; }

    // ---------- 进度上报 ----------
    private boolean progressRunning = false;
    private long lastRecordAt = 0;
    /** 距上次写库的秒数，避免每秒写 */
    private static final long RECORD_INTERVAL_MS = 5000;

    // ---------- ★ v3.8.2 跳过片尾 ----------
    /**
     * 本集是否已经因「跳过片尾」而完结过。
     *
     * <p>onPrepared 里重置为 false；finishForOutro() 内置 true。
     * 有了它，暂停后恢复播放不会因为位置仍落在片尾区里而重复触发。
     */
    private boolean outroDone = false;

    public PlayerCore(Context c) {
        this.ctx = c.getApplicationContext();
        // ★ v3.1：增益与音频焦点设置跟着 App 全局走，构造时就恢复
        this.gainPct = Settings.gain();
        // ★ v3.9.7：注册「第三方媒体播放」兜底监听（API 26+）
        try {
            audioManager = (AudioManager) ctx.getSystemService(Context.AUDIO_SERVICE);
        } catch (Throwable ignored) {}
        registerPlaybackWatcher();
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
            return player == null ? 0 : player.curMs() / 1000;
        } catch (Throwable t) {
            return 0;
        }
    }

    public int durSec() {
        try {
            return player == null ? 0 : player.durMs() / 1000;
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
        // ★ P0-1/P0-2：切集重开自愈代际，作废在途的延迟重试
        healGen++;
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
        // ★ P0-1/P0-2：这是「全新的一集」，清掉上一集的自愈上下文、重开代际
        playUrlInternal(source, chapterUrl, audio, startMs, extraHeaders, true);
    }

    /**
     * 内部起播。
     *
     * <p>resetState=true 表示这是一次「全新的一集」（用户点播 / 自动连播 /
     * 手动切集），清掉自愈计数并重开自愈代际；false 表示这是自愈过程中的
     * 重试 / 换源，必须<b>保留</b> triedSources 等上下文，否则会反复撞同一条死链。
     */
    private void playUrlInternal(String source, String chapterUrl, String audio, long startMs,
                                 Map<String, String> extraHeaders, boolean resetState) {
        if (resetState) {
            netRetries = 0;
            triedSources = new ArrayList<String>();
            lastCurSec = 0;
            lastFailPos = -1;   // ★ v3.9.7：新的一集，重置「同位置二次失败」标记
            healGen++;
        }
        this.usedSource = source;
        this.chapterUrl = chapterUrl;
        this.audioUrl = audio;
        this.lastHeaders = extraHeaders;

        try {
            release();
            final Engines.Engine eng;
            // ★ v4.9.0：按直链扩展名选内核——系统播放器解不了的格式
            //（wma/ape/ac3/dts 等）直接走 FFmpeg 软解，其余走系统播放器
            if (Engines.needsFfmpeg(audio)) {
                eng = new Engines.IjkEngine(ctx);
            } else {
                eng = new Engines.SysEngine(ctx);
            }

            Map<String, String> headers = new HashMap<String, String>();
            headers.put("User-Agent", Net.UA);
            // 源自己声明的头优先级更高（Referer / Cookie / 自定义 UA 等）
            if (extraHeaders != null) {
                for (Map.Entry<String, String> e : extraHeaders.entrySet()) {
                    if (e.getKey() == null || e.getValue() == null) continue;
                    headers.put(e.getKey(), e.getValue());
                }
            }
            final Map<String, String> fHeaders = headers;
            final long start = Math.max(0, startMs);

            eng.setListener(new Engines.Listener() {
                public void onPrepared(Engines.Engine e) {
                    // ★ P0-2：成功起播即清零网络重试计数，
                    //   后续若再出错会重新获得完整的重试额度。
                    netRetries = 0;
                    if (start > 0) lastCurSec = (int) (start / 1000);
                    applySpeedInternal(e, speed);
                    // ★ v3.1：这里必须重新套用增益。新引擎新会话号，
                    //   旧的 LoudnessEnhancer 已经无效了。
                    applyGainInternal(e);
                    // ★ v3.1：起播前拿音频焦点，这样微信来语音时我们才能收到通知
                    acquireFocus();
                    // ★ v3.8.2：新的一集，片尾跳过标记复位
                    outroDone = false;
                    // ★ v3.8.1 跳过片头：只在「没有续播进度」时生效。
                    //   playUrl 覆盖了首次起播 / 自动下一集 / 手动切集 / 自动换源
                    //   四条路径，所以改这一处即可，不用分别改。
                    long seekTo = start;
                    // ★ v3.9.7 修复「已播过的章节再点无法播放」：
                    //   听完/跳过片尾的一集会把续播位置记到 ≈ 时长，直链实际时长
                    //   往往和记录对不上（换源重解析、码率差异）。拿着超界位置
                    //   seek，部分 ROM 会直接进 error，自愈又带着同一坏位置反复
                    //   失败 → 所有源"全部失败"。这里兜底：位置贴着或超出时长
                    //   就视为「听完了」，从头播。
                    if (seekTo > 0 && e.durMs() > 0
                            && seekTo >= e.durMs() - 800) {
                        seekTo = 0;
                    }
                    if (seekTo <= 0) {
                        int skip = com.tingshu.app.data.Settings.skipIntroFor(bookKey());
                        if (skip > 0) seekTo = skip * 1000L;
                    }
                    if (seekTo > 0 && seekTo < e.durMs()) {
                        e.seekTo((int) seekTo);
                    }
                    e.play();
                    startProgress();
                    // ★ 一开播就落一条历史：这样"刚点开听"就能在历史页看到，
                    //   不等 5 秒定时或暂停。之前因为没有 setQueue，这里存不进去。
                    saveProgressNow();
                    if (cb != null) {
                        cb.onStarted(book, index);
                        cb.onStateChanged(true);
                    }
                }

                public void onEnded(Engines.Engine e) {
                    stopProgress();
                    // 先把这一集听到末尾的进度存下（换集前必须落盘）
                    saveProgressNow();
                    // ★ 不在这里喊 onStateChanged(false)：结束拉下一集时状态仍然是"在播"，
                    //   过早置 false 会让图标闪一下三角。是否真的停由 onEnded 的调用方决定。
                    if (cb != null) {
                        cb.onEnded();
                    }
                }

                public boolean onError(Engines.Engine e, int what, int extra) {
                    // ★ P0-1 / P0-2：交给自愈状态机，救不活才会上报 cb.onError
                    //   （那时前端才会按既有逻辑自动停止）。
                    handleError(what, extra);
                    return true;
                }
            });
            player = eng;
            eng.prepare(audio, fHeaders);
        } catch (Throwable t) {
            // ★ 取链/建连阶段的异常（如 setDataSource 抛错）：交给自愈状态机，
            //   自救失败才会最终上报 cb.onError。
            handleError(-1, -1);
        }
    }

    // ==================== ★ P0-1 自动换源 / P0-2 网络重试 ====================

    /**
     * 播放出错后的自愈分发。
     *
     * <p>原则：先尽量原地救活，救不活才 {@code cb.onError} 上报（那时前端才会
     * 按既有逻辑自动停止）。这样大多数「源站抖一下 / 直链过期」用户无感知，
     * 只有真没救了才停。
     *
     * <p>两类策略：
     * <ul>
     *   <li><b>网络类错误</b>（IO / 超时 / 服务端挂）：同一直链、原位续播，
     *       渐增间隔（1s / 2s / 4s）重试最多 {@link #MAX_NET_RETRIES} 次；</li>
     *   <li><b>其它错误</b>（格式不支持 / 不明失败，多半是直链 403 或失效）：
     *       按 Book.variants/sources 顺序换到下一个可用源，保留进度续播；</li>
     * </ul>
     * 网络重试耗尽后也会落到换源分支，双保险。
     */
    private void handleError(int what, int extra) {
        // 优先用「实时位置」，拿不到再退到上次上报的位置
        int live = 0;
        try { live = curSec(); } catch (Throwable ignored) {}
        int posSec = live > 0 ? live : lastCurSec;
        // ★ v3.9.7 修复「已播章节反复播不了」：同一个位置已经失败过一轮
        //   （换源/重试都没救活），说明大概率是这个续播位置本身有问题
        //   （超界 / 直链时长不符），下一轮自愈改从头播。
        if (posSec > 0 && posSec == lastFailPos) posSec = 0;
        lastFailPos = posSec;
        stopProgress();
        // ★ 按出错时的位置落盘，保证自愈后能从原位续播（误差 ≤1 秒）。
        //   不能用 saveProgressNow()：出错态下 getCurrentPosition 可能抛异常返回 0，
        //   会把续播位置覆盖成 0，导致「听完一截又从头播」。
        saveProgressAt(posSec);

        if (isNetworkError(what, extra)) {
            netRetries++;
            if (netRetries <= MAX_NET_RETRIES) {
                final long delay = NET_RETRY_BASE_MS * (1L << (netRetries - 1)); // 1/2/4 秒
                final int myGen = healGen;
                final int retryPos = posSec;   // ★ v3.9.7：posSec 可能已被改为 0（从头自愈）
                if (healCb != null) healCb.onHeal(0, usedSource, null);
                ui.postDelayed(new Runnable() {
                    public void run() {
                        // ★ 期间用户已切集/停止 → 作废这次重试，避免「幽灵续播」
                        if (myGen != healGen) return;
                        retrySameSource(retryPos);
                    }
                }, delay);
                return;
            }
            // 网络重试耗尽 → 也尝试换源
        }
        switchSource(posSec);
    }

    /** 判断是否为「网络类」可重试错误 */
    private static boolean isNetworkError(int what, int extra) {
        if (what == MediaPlayer.MEDIA_ERROR_IO) return true;          // -1004
        if (what == MediaPlayer.MEDIA_ERROR_TIMED_OUT) return true;   // -110
        if (what == MediaPlayer.MEDIA_ERROR_SERVER_DIED) return true; // 100
        if (extra == MediaPlayer.MEDIA_ERROR_IO) return true;
        if (extra == MediaPlayer.MEDIA_ERROR_TIMED_OUT) return true;
        return false;
    }

    /** 同一直链、原位重试（瞬时网络抖动用，直链多半仍有效） */
    private void retrySameSource(int posSec) {
        if (book == null || audioUrl == null || audioUrl.length() == 0) {
            giveUp("播放失败");
            return;
        }
        // resetState=false：同一集的自愈，保留 triedSources 等上下文
        playUrlInternal(usedSource, chapterUrl, audioUrl,
                (long) posSec * 1000, lastHeaders, false);
    }

    /**
     * 自动换源：把当前失败源加入跳过列表，从下一个可用源取链后原位续播。
     * 没有更多可用源时才 {@link #giveUp}。
     *
     * <p>取链是网络操作，必须放到后台线程；取回后再 post 回主线程起播。
     */
    private void switchSource(final int posSec) {
        if (book == null) { giveUp("播放失败"); return; }
        if (triedSources == null) triedSources = new ArrayList<String>();
        if (usedSource != null && !triedSources.contains(usedSource)) {
            triedSources.add(usedSource);
        }
        // 先剔掉当前死链缓存，避免换源后仍命中同一条缓存死链
        if (usedSource != null) {
            Aggregator.invalidateAudio(book, humanIndex());
        }
        final int chapterIdx = humanIndex();
        final Book bk = book;
        final List<String> skip = triedSources;
        final int myGen = healGen;
        Aggregator.singlePool().execute(new Runnable() {
            public void run() {
                String[] outUsed = new String[2];
                @SuppressWarnings("unchecked")
                Map<String, String>[] outHeaders = new Map[1];
                String url = Aggregator.audioWithFallbackNetwork(
                        bk, chapterIdx, null, outUsed, outHeaders, skip);
                final String fUrl = url;
                final String fSrc = outUsed[0];
                final Map<String, String> fHdr = outHeaders[0];
                ui.post(new Runnable() {
                    public void run() {
                        // 期间用户已切集/停止 → 作废这次换源
                        if (myGen != healGen) return;
                        if (fUrl == null || fUrl.length() == 0) {
                            giveUp("播放失败：所有可用音源均不可用");
                            return;
                        }
                        final String newName =
                                BridgeSource.sourceNameOf(fSrc);
                        if (healCb != null) healCb.onHeal(1, fSrc, newName);
                        // 直接带保存的进度起播新源（不读 player 当前位置，避免误差）
                        playUrlInternal(fSrc, outUsed[1], fUrl,
                                (long) posSec * 1000, fHdr, false);
                    }
                });
            }
        });
    }

    /** 本集的「人读集号」：与 MainActivity.gotoChapter 的 humanIdx 保持一致 */
    private int humanIndex() {
        Chapter c = currentChapter();
        if (c != null && c.index > 0) return c.index;
        return index + 1;
    }

    /** 最近一次 giveUp 的时间（elapsedRealtime，toggle 防抖用） */
    private volatile long lastGiveUpAt = 0;

    /** 真的救不活了：通知 UI 失败（前端据此自动停止） */
    private void giveUp(String msg) {
        lastGiveUpAt = android.os.SystemClock.elapsedRealtime();
        if (cb != null) {
            cb.onStateChanged(false);
            cb.onError(msg);
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
            pos = player == null ? 0 : player.curMs();
        } catch (Throwable ignored) {}
        // resetState=false：换源属于同一集的自愈上下文，保留 triedSources
        playUrlInternal(source, chapterUrl, audio, pos, extraHeaders, false);
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
                player.play();
                startProgress();
                if (cb != null) cb.onStateChanged(true);
            }
        } catch (Throwable ignored) {}
    }

    public void toggle() {
        // ★ v3.9.34 抽搐修复：刚 giveUp 后 800ms 内的连点直接忽略 ——
        //   错误态的 player 被 resume 会立刻再 error，状态来回跳像抽搐
        if (!isPlaying()) {
            long now = android.os.SystemClock.elapsedRealtime();
            if (now - lastGiveUpAt < 800) return;
        }
        if (isPlaying()) pause();
        else resume();
    }

    /** 当前专辑标识（按专辑记录跳过设置用）：source|bookId（兜底 url） */
    public String bookKey() {
        String k = "";
        try {
            if (book != null) {
                String id = (book.bookId == null || book.bookId.length() == 0)
                        ? book.url : book.bookId;
                k = (book.source == null ? "" : book.source + "|") + id;
            }
        } catch (Throwable ignored) {}
        sBookKey = k;   // 供静态读取（Bridge 的 PlayerControl 接口拿不到实例）
        return k;
    }

    /** 最近一次 bookKey() 的缓存（静态，Bridge 等外部读取用） */
    private static volatile String sBookKey = "";

    /** 静态读取当前专辑标识（无队列/未播放返回空串） */
    public static String currentBookKey() {
        return sBookKey == null ? "" : sBookKey;
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
        // ★ v3.9.0：倍速角标同步到桌面小组件（换倍速是低频操作，直接重绘）
        try {
            com.tingshu.app.widget.WidgetState.rate = s;
            com.tingshu.app.widget.WidgetState.requestUpdate(ctx, true);
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
        // ★ v3.9.53：上限恢复 300。v3.9.7 砍到 200 是因为旧线性换算
        //   （每 1% 记 6mB）在 300% 时高达 1200mB（+12dB）会削波爆音；
        //   现在换算改成了对数式（见 applyGainInternal），300% 也只有
        //   954mB（+9.5dB），在 LoudnessEnhancer 2000mB 硬限内安全。
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
    private void applyGainInternal(Engines.Engine e) {
        if (e == null) e = player;
        if (e == null) return;

        try {
            if (gainPct <= 100) {
                // 降幅：纯音量衰减，并把响度增强关掉（避免上面残留的增益叠加）
                float v = gainPct / 100f;
                e.setVolume(v, v);
                releaseEnhancer();
                return;
            }

            // 增幅：音量拉满 + 响度增强
            e.setVolume(1f, 1f);
            // ★ v3.9.53：换算改为对数式 —— 修复"增益几乎没效果"。
            //   LoudnessEnhancer 的单位是毫贝（100mB = 1dB，音量翻倍 = +6dB）。
            //   百分比增幅对应的正确 dB 是 20·log10(pct/100)，即
            //   mB = 2000·log10(pct/100)：
            //     150% ≈ +3.5dB（352mB）、200% ≈ +6dB（602mB）、300% ≈ +9.5dB（954mB）。
            //   旧版 v3.9.7 的线性系数 3 让 200% 只有 300mB（+1.8dB）——
            //   这就是用户反馈「增益几乎没有增大音量」的直接原因。
            //   300% 也只有 954mB，远在 LoudnessEnhancer 2000mB 硬限内，
            //   且比旧线性版 300%（1200mB，必爆音）温和。
            int mb = (int) Math.round(2000.0 * Math.log10(gainPct / 100.0));
            if (mb < 0) mb = 0;
            if (mb > 2000) mb = 2000;
            ensureEnhancer(e.sessionId(), mb);
        } catch (Throwable ignored) {
            // 个别 ROM 不支持 audiofx，静默降级为「增益无效但播放正常」
        }
    }

    /** 建/复用 LoudnessEnhancer 并套上 targetGain（mB） */
    private void ensureEnhancer(int session, int millibels) {
        try {
            if (session <= 0) return;
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
        // ★ P0-1/P0-2：停止重开自愈代际，作废在途的延迟重试
        healGen++;
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
                player.stopAndRelease();
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
                // ★ v3.9.7：request 必须跟「用户当前策略」绑定，策略变了要重建。
                //   以前固定 setWillPauseWhenDucked(false)（声称"被压音量时我不暂停"），
                //   选了「暂停播放」策略的用户在部分场景（通知声、语音条）系统就
                //   只发 CAN_DUCK 且认为我们自己会压音量 —— 表现为"设了暂停却不停"。
                if (focusRequest == null || focusReqMode != mode) {
                    releaseFocusRequest();
                    // AUDIOFOCUS_GAIN：我们要长期独占（听书是长时间播放场景）
                    // CONTENT_TYPE_SPEECH：让系统知道是人声，降音/Duck 的时机更准
                    focusRequest = new AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN)
                            .setAudioAttributes(new AudioAttributes.Builder()
                                    .setUsage(AudioAttributes.USAGE_MEDIA)
                                    .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                                    .build())
                            // mode=2（暂停策略）：声明"被 Duck 时我会暂停"，
                            // 系统才会优先发 LOSS_TRANSIENT；mode=1（降音量）保持 false
                            .setWillPauseWhenDucked(mode == 2)
                            .setOnAudioFocusChangeListener(focusChangeListener())
                            .build();
                    focusReqMode = mode;
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

    /** ★ v3.9.7：策略变化时释放旧 AudioFocusRequest（下次 acquireFocus 按新策略重建） */
    private void releaseFocusRequest() {
        if (focusRequest == null) return;
        try {
            if (audioManager != null && hasFocus) {
                audioManager.abandonAudioFocusRequest(focusRequest);
                hasFocus = false;
            }
        } catch (Throwable ignored) {}
        focusRequest = null;
    }

    // ---------- ★ v3.9.7 兜底：不守音频焦点的第三方 App（媒体播放监听） ----------

    /**
     * 为什么需要它：音频焦点是"自觉"机制 —— 对方开始播放前要主动申请焦点，
     * 系统才会通知我们让位。相当一部分国产 App（视频/短视频/某些音乐 App）
     * 根本不申请焦点，两路声音就叠着放，用户表现为「设了暂停却拦不住」。
     *
     * <p>兜底方案（API 26+）：注册 {@link AudioManager.AudioPlaybackCallback}，
     * 感知「除自己之外还有谁在活跃地放 USAGE_MEDIA」，状态翻转时按用户策略
     * 暂停 / 降音；对方全停了再把声音还回来。无法识别对方 uid（隐藏 API），
     * 用「活跃 MEDIA 流条数 &gt;= 2 且自己在播」判定 —— 本 App 同时最多一条。
     */
    private AudioManager.AudioPlaybackCallback playbackWatcher;
    /** 上次判定到的「其他媒体在放」状态（防抖，只对翻转动作） */
    private boolean otherMediaPlaying = false;

    private void registerPlaybackWatcher() {
        if (Build.VERSION.SDK_INT < 26) return;
        if (playbackWatcher != null || audioManager == null) return;
        try {
            playbackWatcher = new AudioManager.AudioPlaybackCallback() {
                @Override
                public void onPlaybackConfigChanged(
                        java.util.List<android.media.AudioPlaybackConfiguration> configs) {
                    handleOtherMedia(configs);
                }
            };
            audioManager.registerAudioPlaybackCallback(playbackWatcher, null);
        } catch (Throwable t) {
            playbackWatcher = null;   // 个别 ROM 不支持则静默退化为仅焦点模式
        }
    }

    /** ★ v3.9.7：isActive() 在部分编译 API 里不可见，反射读取 */
    private static boolean cfgActive(android.media.AudioPlaybackConfiguration c) {
        try {
            Object r = c.getClass().getMethod("isActive").invoke(c);
            return r instanceof Boolean && ((Boolean) r).booleanValue();
        } catch (Throwable t) {
            return false;
        }
    }

    /**
     * ★ v3.9.8 重写：状态机防震荡。
     *
     * <p>v3.9.7 的实现有致命缺陷：暂停自己后，自己的媒体流退出活跃列表，
     * 下一轮回调里 {@code isPlaying()=false} → 判定"别人停了" → 自动恢复
     * → 恢复后又看到别人 → 再暂停……死循环，表现为**播放按钮疯狂抽搐**。
     *
     * <p>新规则：
     * <ul>
     *   <li>自己在播：活跃 MEDIA 流 ≥2 = 有别人 → 按策略暂停/降音（幂等）；</li>
     *   <li>自己已暂停：**绝不因为"别人还在放"而恢复**，只有活跃 MEDIA 流
     *       清零（对方真停了）才恢复，且仅限此前是因它而暂停的。</li>
     * </ul>
     */
    private void handleOtherMedia(java.util.List<android.media.AudioPlaybackConfiguration> configs) {
        final int mode = Settings.audioFocusMode();
        if (mode == 0) { otherMediaPlaying = false; return; }

        int activeMedia = 0;
        if (configs != null) {
            for (int i = 0; i < configs.size(); i++) {
                try {
                    android.media.AudioPlaybackConfiguration c = configs.get(i);
                    if (c == null || !cfgActive(c)) continue;
                    AudioAttributes at = c.getAudioAttributes();
                    if (at == null || at.getUsage() != AudioAttributes.USAGE_MEDIA) continue;
                    activeMedia++;
                } catch (Throwable ignored) {}
            }
        }
        final boolean selfPlaying = isPlaying();

        if (selfPlaying) {
            boolean other = activeMedia >= 2;   // 自己(1) + 别人(≥1)
            if (other == otherMediaPlaying) return;   // 无翻转，幂等
            otherMediaPlaying = other;
            if (other) {
                if (mode == 1) duck(true); else pauseByFocus();
            } else {
                if (mode == 1) duck(false); else resumeByFocus();
            }
        } else {
            // 自己已暂停：别人还在放（activeMedia ≥ 1）时**什么都不做**。
            if (otherMediaPlaying && activeMedia == 0) {
                otherMediaPlaying = false;
                if (mode == 1) duck(false); else resumeByFocus();
            }
        }
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
            player.play();
            startProgress();
            if (cb != null) cb.onStateChanged(true);
        } catch (Throwable ignored) {}
    }

    // ==================== 倍速 ====================

    /** ★ v4.9.0：倍速由引擎自己实现（系统=PlaybackParams，ijk=setSpeed/soundtouch） */
    private void applySpeedInternal(Engines.Engine e, float s) {
        if (e == null) return;
        try { e.setSpeed(s); } catch (Throwable ignored) {}
    }

    // ==================== 进度 ====================

    private final Runnable tick = new Runnable() {
        public void run() {
            if (!progressRunning) return;
            int cur = curSec(), dur = durSec();
            // ★ P0-1/P0-2：记下最近一次位置，出错时据此在原位续播
            if (cur > 0) lastCurSec = cur;
            // ★ v3.8.2 跳过片尾：当前位置落在结尾 N 秒内 → 提前完结本集。
            //   先 pause 再走 onEnded：pause 之后 MediaPlayer 不会再来
            //   onCompletion，避免同一集被完结两次、连跳两集。
            if (!outroDone) {
                int outro = com.tingshu.app.data.Settings.skipOutroFor(bookKey());
                if (outro > 0 && dur > 0 && cur > 0 && cur >= dur - outro) {
                    finishForOutro();
                    return;
                }
            }
            if (cb != null) cb.onProgress(cur, dur);
            maybeRecord(cur);
            ui.postDelayed(this, 1000);
        }
    };

    /**
     * ★ v3.8.2：因「跳过片尾」完结当前集。
     *
     * <p>与 {@link #onCompletion} 的自然结束走同一条下游
     * （{@code stopProgress → 落盘 → cb.onEnded()}），区别有两点：
     * <ol>
     *   <li>先把播放器 pause 掉 —— 否则用户听到真实结尾时 onCompletion
     *       会再来一次，同一集被完结两次、直接连跳两集；</li>
     *   <li>进度记到本集末尾而不是当前秒数 —— 续听重放这一集时会自然
     *       收尾进下一集，不会又落在片尾区里反复触发跳过。</li>
     * </ol>
     * 与 onCompletion 一致，这里也**不**喊 onStateChanged(false)：
     * 下游大概率立刻开播下一集，提前置 false 会让图标闪一下。
     */
    private void finishForOutro() {
        outroDone = true;
        stopProgress();
        try {
            if (player != null && player.isPlaying()) player.pause();
        } catch (Throwable ignored) {}        int d = durSec();
        saveRecord(Math.max(curSec(), d));
        if (cb != null) cb.onEnded();
    }

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

    /** 以指定秒数立即落库（出错自愈时按保存的位置续播，避免读到 0） */
    private void saveProgressAt(int sec) {
        saveRecord(sec);
    }

    private void saveRecord(final int curSec) {
        final Book b = this.book;
        if (b == null || chapters.isEmpty()) return;
        final int idx = index;
        // ★ v3.9.7：进度键统一用「章节自带的 index（humanIdx）」。
        //   以前写库用 idx+1（下标），而 MainActivity.gotoChapter / Bridge 读库
        //   用 ch.index —— 源给的集号若与下标不一致，进度就写读对不上，
        //   续播/已听标记全部错位。
        Chapter cu_ = currentChapter();
        final int humanKey = (cu_ != null && cu_.index > 0) ? cu_.index : idx + 1;
        final String cu = chapterUrl;
        final String ct = currentChapter() == null ? "" : currentChapter().title;
        final int total = chapters.size();
        final long ms = (long) curSec * 1000;
        // SQLite 写在后台，别卡 UI
        com.tingshu.app.source.Aggregator.singlePool().execute(new Runnable() {
            public void run() {
                try {
                    HistoryRepo.record(b, humanKey, ms, cu, ct, total);
                } catch (Throwable ignored) {}
            }
        });
    }
}
