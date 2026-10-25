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
 * WMA 解码器（WMA 是 Windows 格式），网盘里的 .wma 有声书就会
 * 「取链成功但播放失败」。ijkplayer（B 站开源）内置 FFmpeg 软解，
 * WMA 全家族（v1/v2/pro/lossless）都能解。
 *
 * <p>选内核的策略极简：<b>按直链扩展名</b>——系统播放器大概率解不了的
 * 格式（wma/ape/ac3/dts 等）直接走 FFmpeg 引擎，其余仍走系统播放器
 * （更省电、倍速/增益路径成熟）。引擎对 PlayerCore 暴露统一接口，
 * 播放状态机（自愈/换源/进度/历史）完全不感知内核差异。
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
     * 系统播放器大概率解不了的音频扩展名 → 走 FFmpeg 引擎。
     * 匹配前截掉 query（网盘直链带签名参数）。
     */
    static boolean needsFfmpeg(String url) {
        if (url == null) return false;
        String u = url.toLowerCase();
        int q = u.indexOf('?');
        if (q > 0) u = u.substring(0, q);
        return u.endsWith(".wma")   // Windows Media Audio —— 最常见的「播不了」
                || u.endsWith(".ape")   // Monkey's Audio
                || u.endsWith(".ac3")
                || u.endsWith(".dts")
                || u.endsWith(".tta")
                || u.endsWith(".tak")
                || u.endsWith(".vqf");
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
