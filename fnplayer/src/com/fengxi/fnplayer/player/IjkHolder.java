package com.fengxi.fnplayer.player;

import android.content.Context;
import android.net.Uri;
import android.view.SurfaceHolder;

import java.util.Map;

import tv.danmaku.ijk.media.player.IjkMediaPlayer;

/** ijkplayer 视频封装（照搬听书 App Engines.IjkEngine，补上视频所需的 setDisplay）。 */
public final class IjkHolder {

    public interface Listener {
        void onPrepared();
        void onEnded();
        void onError(int what, int extra);
        void onInfo(int what, int extra);
        void onVideoSize(int w, int h);
    }

    private static volatile boolean loaded = false;
    private IjkMediaPlayer mp;
    private Listener listener;
    private Context ctx;

    public synchronized void init(Context c, SurfaceHolder holder) {
        init(c, holder == null ? null : holder.getSurface());
    }

    /** 支持直接传 Surface（TextureView 渲染方式）。 */
    public synchronized void init(Context c, android.view.Surface surface) {
        ctx = c.getApplicationContext();
        synchronized (IjkHolder.class) {
            if (!loaded) {
                IjkMediaPlayer.loadLibrariesOnce(null);
                loaded = true;
            }
        }
        mp = new IjkMediaPlayer();
        mp.setAudioStreamType(android.media.AudioManager.STREAM_MUSIC);
        mp.setOption(IjkMediaPlayer.OPT_CATEGORY_PLAYER, "start-on-prepared", 0);
        mp.setOption(IjkMediaPlayer.OPT_CATEGORY_PLAYER, "soundtouch", 1);
        mp.setOption(IjkMediaPlayer.OPT_CATEGORY_PLAYER, "mediacodec", 1);   // 视频硬解
        mp.setOption(IjkMediaPlayer.OPT_CATEGORY_PLAYER, "mediacodec-auto-rotate", 1);
        mp.setOption(IjkMediaPlayer.OPT_CATEGORY_PLAYER, "opensles", 0);
        mp.setOption(IjkMediaPlayer.OPT_CATEGORY_PLAYER, "framedrop", 1);
        mp.setOption(IjkMediaPlayer.OPT_CATEGORY_FORMAT, "reconnect", 1);
        mp.setScreenOnWhilePlaying(true);
        try { if (surface != null) mp.setSurface(surface); } catch (Throwable ignored) {}

        mp.setOnPreparedListener(new IjkMediaPlayer.OnPreparedListener() {
            @Override public void onPrepared(tv.danmaku.ijk.media.player.IMediaPlayer p) {
                try { mp.start(); } catch (Throwable ignored) {}
                if (listener != null) listener.onPrepared();
            }
        });
        mp.setOnCompletionListener(new IjkMediaPlayer.OnCompletionListener() {
            @Override public void onCompletion(tv.danmaku.ijk.media.player.IMediaPlayer p) {
                if (listener != null) listener.onEnded();
            }
        });
        mp.setOnErrorListener(new IjkMediaPlayer.OnErrorListener() {
            @Override public boolean onError(tv.danmaku.ijk.media.player.IMediaPlayer p,
                                             int what, int extra) {
                if (listener != null) listener.onError(what, extra);
                return true;
            }
        });
        mp.setOnInfoListener(new IjkMediaPlayer.OnInfoListener() {
            @Override public boolean onInfo(tv.danmaku.ijk.media.player.IMediaPlayer p,
                                            int what, int extra) {
                if (listener != null) listener.onInfo(what, extra);
                return false;
            }
        });
        mp.setOnVideoSizeChangedListener(new IjkMediaPlayer.OnVideoSizeChangedListener() {
            @Override public void onVideoSizeChanged(tv.danmaku.ijk.media.player.IMediaPlayer p,
                                                     int w, int h, int sar, int par) {
                if (listener != null) listener.onVideoSize(w, h);
            }
        });
    }

    public void setListener(Listener l) { this.listener = l; }

    /** v2.22：悬浮小窗移交 —— 换绑渲染目标（解码管线不重建，零黑屏）。 */
    public synchronized void setSurface(android.view.Surface s) {
        try { if (mp != null && s != null) mp.setSurface(s); } catch (Throwable ignored) {}
    }

    /** v2.22：浮窗态关掉 FLAG_KEEP_SCREEN_ON 依赖（车机浮窗常驻，屏幕策略交给车机）。 */
    public synchronized void keepScreenOn(boolean on) {
        try { if (mp != null) mp.setScreenOnWhilePlaying(on); } catch (Throwable ignored) {}
    }

    public void setDataSource(String url, Map<String, String> headers) throws Exception {
        if (url != null && url.startsWith("http"))
            mp.setDataSource(ctx, Uri.parse(url), headers);
        else mp.setDataSource(url);
        mp.prepareAsync();
    }

    /** 硬解失败/花屏时切软解（需重新 prepare，由 Activity 决定是否重启） */
    public void useSoftDecoder() {
        try { mp.setOption(IjkMediaPlayer.OPT_CATEGORY_PLAYER, "mediacodec", 0); }
        catch (Throwable ignored) {}
    }

    /** 直播模式（v2.8）：小探测窗 + 禁缓冲，频道出画面更快；必须在 prepareAsync 前调用。 */
    public void setLiveMode() {
        try {
            mp.setOption(IjkMediaPlayer.OPT_CATEGORY_FORMAT, "probesize", 500000);
            mp.setOption(IjkMediaPlayer.OPT_CATEGORY_FORMAT, "analyzeduration", 500000);
            mp.setOption(IjkMediaPlayer.OPT_CATEGORY_FORMAT, "fflags", "nobuffer");
            mp.setOption(IjkMediaPlayer.OPT_CATEGORY_FORMAT, "infbuf", 1);
            mp.setOption(IjkMediaPlayer.OPT_CATEGORY_PLAYER, "packet-buffering", 0);
        } catch (Throwable ignored) {}
    }

    public boolean isPlaying() { try { return mp != null && mp.isPlaying(); } catch (Throwable t) { return false; } }
    public long curMs() { try { return mp.getCurrentPosition(); } catch (Throwable t) { return 0; } }
    public long durMs() { try { return mp.getDuration(); } catch (Throwable t) { return 0; } }
    public void play() { try { mp.start(); } catch (Throwable ignored) {} }
    public void pause() { try { mp.pause(); } catch (Throwable ignored) {} }
    public void seekTo(long ms) { try { mp.seekTo(ms); } catch (Throwable ignored) {} }
    public void setSpeed(float s) { try { mp.setSpeed(s); } catch (Throwable ignored) {} }

    /** 音效：音量增益（1.0 原声 / 1.5 / 2.0），走播放器内部音量不影响系统。 */
    public void setVolume(float v) { try { mp.setVolume(v, v); } catch (Throwable ignored) {} }

    public void release() {
        if (mp == null) return;
        try { mp.stop(); } catch (Throwable ignored) {}
        try { mp.setDisplay(null); } catch (Throwable ignored) {}
        try { mp.release(); } catch (Throwable ignored) {}
        mp = null;
    }
}
