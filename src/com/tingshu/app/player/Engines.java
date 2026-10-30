package com.tingshu.app.player;

import android.content.Context;
import android.media.AudioManager;
import android.media.MediaPlayer;
import android.net.Uri;
import android.os.Build;

import java.util.HashMap;
import java.util.Map;

import tv.danmaku.ijk.media.player.IjkMediaPlayer;

/**
 * ★ v4.9.0 双播放内核。
 *
 * <p>系统 {@link MediaPlayer} 只认设备解码器支持的格式——很多 ROM 没有
 * WMA 解码器，网盘里的 .wma 有声书就会「取链成功但播放失败」。
 *
 * <p><b>★ v4.9.3 重要更正：</b>当初接入 ijkplayer 是奔着「FFmpeg 软解能解
 * WMA」去的，但实际打包进来的 {@code libijkffmpeg.so} 是 ijkplayer 官方
 * 精简编译——它的编译参数是 {@code --disable-decoders} + 只 enable
 * {@code aac / aac_latm / mp3* / flac}，{@code --disable-demuxers} + 只 enable
 * {@code mp3 / flac / mov / aac / hls …}，<b>既没有 asf demuxer、也没有
 * wmav1/wmav2/wmapro 解码器</b>。也就是说它对音频格式的覆盖是系统播放器的
 * 子集，WMA 走它 100% 失败。
 *
 * <p>因此选内核策略改为「<b>系统优先</b>」：先在系统内核上试（部分国产 ROM
 * 自带 WMA 解码器，是当下唯一可能播通 WMA 的路径），失败后才视
 * {@link #ffmpegDecodes(String)} 决定要不要换 FFmpeg。
 * 想要真正支持 WMA/APE，需要换一份完整编译的 libijkffmpeg
 * （enable-decoder=wma*、enable-demuxer=asf），届时把
 * {@link #ffmpegDecodes} 一并放开即可。
 */
final class Engines {

    private Engines() {}

    /** 引擎统一接口（方法形状贴近 MediaPlayer，迁移成本最低） */
    interface Engine {
        /** setDataSource + prepareAsync（headers 供 Referer/Cookie 校验用） */
        void prepare(String audio, Map<String, String> headers) throws Exception;

        void setListener(Listener l);

        boolean isPlaying();

        int curMs();

        int durMs();

        void play();

        void pause();

        void seekTo(int ms);

        void setSpeed(float s);

        void setVolume(float l, float r);

        int sessionId();

        void stop();

        void stopAndRelease();
    }

    /** 引擎事件（onPrepared 后 PlayerCore 统一做续播/倍速/增益/起播） */
    interface Listener {
        void onPrepared(Engine e);

        void onEnded(Engine e);

        boolean onError(Engine e, int what, int extra);
    }

    /**
     * 系统播放器大概率解不了的音频扩展名（换内核回退的**触发条件**）。
     * 匹配前截掉 query（网盘直链带签名参数）。
     *
     * <p>★ v4.9.3 更正：v4.9.0 时以为「换成 ijkplayer 就能解 WMA」，
     * 实际打包进 APK 的 {@code libijkffmpeg.so} 是 ijkplayer 官方精简编译，
     * 编译参数里明确写着 {@code --disable-decoders} 只 enable 了
     * {@code aac / aac_latm / mp3* / flac}，并且**没有 asf demuxer**——
     * WMA/APE/AC3/DTS 它一个都解不了。所以这个方法的语义从
     * 「交给 FFmpeg」改成了「系统多半不支持，值得换内核试一次」，
     * 是否真的换由 {@link #ffmpegDecodes(String)} 兜底判断。
     */
    static boolean needsFfmpeg(String url) {
        if (url == null) return false;
        String u = lowerNoQuery(url);
        return u.endsWith(".wma")   // Windows Media Audio —— 最常见的「播不了」
                || u.endsWith(".ape")   // Monkey's Audio
                || u.endsWith(".ac3")
                || u.endsWith(".dts")
                || u.endsWith(".tta")
                || u.endsWith(".tak")
                || u.endsWith(".vqf");
    }

    /**
     * ★ v4.9.3：当前打包的 FFmpeg 库到底能解哪些（官方精简编译，只含音频 aac/mp3/flac）。
     *
     * <p>用来避免「明知解不了还换内核白等一轮」——WMA 换到 ijk 必然失败，
     * 不如直接走本地代理或如实报错。将来换成完整编译的 libijkffmpeg
     * （带 asf / wma 解码器）时，改这里一处即可让内核回退重新生效。
     */
    static boolean ffmpegDecodes(String url) {
        if (url == null) return false;
        String u = lowerNoQuery(url);
        return u.endsWith(".aac") || u.endsWith(".m4a")
                || u.endsWith(".mp3") || u.endsWith(".flac");
    }

    private static String lowerNoQuery(String url) {
        String u = url.toLowerCase();
        int q = u.indexOf('?');
        if (q > 0) u = u.substring(0, q);
        return u;
    }

    // ==================== 系统内核 ====================

    static final class SysEngine implements Engine {
        private final Context ctx;
        private final MediaPlayer mp;

        SysEngine(Context c) {
            ctx = c.getApplicationContext();
            mp = new MediaPlayer();
            mp.setAudioStreamType(AudioManager.STREAM_MUSIC);
        }

        @Override public void setListener(final Listener l) {
            mp.setOnPreparedListener(new MediaPlayer.OnPreparedListener() {
                public void onPrepared(MediaPlayer m) { l.onPrepared(SysEngine.this); }
            });
            mp.setOnCompletionListener(new MediaPlayer.OnCompletionListener() {
                public void onCompletion(MediaPlayer m) { l.onEnded(SysEngine.this); }
            });
            mp.setOnErrorListener(new MediaPlayer.OnErrorListener() {
                public boolean onError(MediaPlayer m, int what, int extra) {
                    return l.onError(SysEngine.this, what, extra);
                }
            });
        }

        @Override public void prepare(String audio, Map<String, String> headers) throws Exception {
            Map<String, String> h = (headers == null)
                    ? new HashMap<String, String>() : headers;
            if (audio.startsWith("http") || audio.startsWith("content://")
                    || audio.startsWith("file://")) {
                // ★ 本地 content:// 也必须走带 Context 的重载（SAF 下载文件）
                mp.setDataSource(ctx, Uri.parse(audio), h);
            } else {
                mp.setDataSource(audio);
            }
            mp.prepareAsync();
        }

        @Override public boolean isPlaying() {
            return mp.isPlaying();
        }

        @Override public int curMs() { return mp.getCurrentPosition(); }

        @Override public int durMs() { return mp.getDuration(); }

        @Override public void play() { mp.start(); }

        @Override public void pause() { mp.pause(); }

        @Override public void seekTo(int ms) { mp.seekTo(ms); }

        @Override public void setSpeed(float s) {
            if (s <= 0) return;
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

        @Override public void setVolume(float l, float r) { mp.setVolume(l, r); }

        @Override public int sessionId() { return mp.getAudioSessionId(); }

        @Override public void stop() {
            try { mp.stop(); } catch (Throwable ignored) {}
        }

        @Override public void stopAndRelease() {
            try { mp.stop(); } catch (Throwable ignored) {}
            try { mp.reset(); } catch (Throwable ignored) {}
            try { mp.release(); } catch (Throwable ignored) {}
        }
    }

    // ==================== FFmpeg 内核（ijkplayer） ====================

    static final class IjkEngine implements Engine {
        private final Context ctx;
        private final IjkMediaPlayer mp;
        private static volatile boolean libLoaded = false;

        IjkEngine(Context c) {
            ctx = c.getApplicationContext();
            synchronized (IjkEngine.class) {
                if (!libLoaded) {
                    // null → 默认 System.loadLibrary("ijkplayer")，
                    // libijkplayer.so 依赖的 libijkffmpeg/libijksdl 自动链接
                    IjkMediaPlayer.loadLibrariesOnce(null);
                    libLoaded = true;
                }
            }
            mp = new IjkMediaPlayer();
            mp.setAudioStreamType(AudioManager.STREAM_MUSIC);
            // 统一在 onPrepared 里起播（与系统内核行为一致，续播 seek 才生效）
            mp.setOption(IjkMediaPlayer.OPT_CATEGORY_PLAYER, "start-on-prepared", 0);
            // 倍速走 soundtouch 保音调；WMA 无硬解，音频一律软解
            mp.setOption(IjkMediaPlayer.OPT_CATEGORY_PLAYER, "soundtouch", 1);
            mp.setOption(IjkMediaPlayer.OPT_CATEGORY_PLAYER, "mediacodec", 0);
            mp.setOption(IjkMediaPlayer.OPT_CATEGORY_PLAYER, "opensles", 0);
        }

        @Override public void setListener(final Listener l) {
            mp.setOnPreparedListener(new IjkMediaPlayer.OnPreparedListener() {
                public void onPrepared(tv.danmaku.ijk.media.player.IMediaPlayer m) { l.onPrepared(IjkEngine.this); }
            });
            mp.setOnCompletionListener(new IjkMediaPlayer.OnCompletionListener() {
                public void onCompletion(tv.danmaku.ijk.media.player.IMediaPlayer m) { l.onEnded(IjkEngine.this); }
            });
            mp.setOnErrorListener(new IjkMediaPlayer.OnErrorListener() {
                public boolean onError(tv.danmaku.ijk.media.player.IMediaPlayer m, int what, int extra) {
                    return l.onError(IjkEngine.this, what, extra);
                }
            });
        }

        @Override public void prepare(String audio, Map<String, String> headers) throws Exception {
            Map<String, String> h = (headers == null)
                    ? new HashMap<String, String>() : headers;
            if (audio.startsWith("http")) {
                // ijk 的 Uri 重载会把 headers 转成 imedia 的 "headers" 参数
                mp.setDataSource(ctx, Uri.parse(audio), h);
            } else {
                mp.setDataSource(audio);
            }
            mp.prepareAsync();
        }

        @Override public boolean isPlaying() {
            try { return mp.isPlaying(); } catch (Throwable t) { return false; }
        }

        @Override public int curMs() {
            try { return (int) mp.getCurrentPosition(); } catch (Throwable t) { return 0; }
        }

        @Override public int durMs() {
            try { return (int) mp.getDuration(); } catch (Throwable t) { return 0; }
        }

        @Override public void play() { mp.start(); }

        @Override public void pause() { mp.pause(); }

        @Override public void seekTo(int ms) { mp.seekTo(ms); }

        @Override public void setSpeed(float s) {
            if (s <= 0) return;
            try { mp.setSpeed(s); } catch (Throwable ignored) {}
        }

        @Override public void setVolume(float l, float r) {
            try { mp.setVolume(l, r); } catch (Throwable ignored) {}
        }

        @Override public int sessionId() {
            try { return mp.getAudioSessionId(); } catch (Throwable t) { return 0; }
        }

        @Override public void stop() {
            try { mp.stop(); } catch (Throwable ignored) {}
        }

        @Override public void stopAndRelease() {
            try { mp.stop(); } catch (Throwable ignored) {}
            try { mp.release(); } catch (Throwable ignored) {}
        }
    }
}
