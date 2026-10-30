package com.tingshu.app.player;

import android.content.Context;
import android.media.AudioManager;
import android.net.ConnectivityManager;
import android.net.NetworkInfo;

import com.tingshu.app.data.Models.Book;
import com.tingshu.app.data.Models.Chapter;

import java.util.ArrayList;
import java.util.List;

/**
 * 播放状态单例。供 Service / Bridge / 通知 / 前台 UI 共享。
 * 所有字段读写都在主线程（由调用方保证），不做同步。
 */
public final class PlayState {

    /** 播放器状态回调，Service 会实现它把变化推给 UI */
    public interface Listener {
        void onStateChanged();

        void onProgress(int curSec, int durSec);

        void onError(String msg);
    }

    private static PlayState INSTANCE;
    private Context ctx;
    private Listener listener;

    private PlayState() {}

    public static synchronized PlayState get() {
        if (INSTANCE == null) INSTANCE = new PlayState();
        return INSTANCE;
    }

    public static void init(Context c) {
        get().ctx = c.getApplicationContext();
    }

    public void setListener(Listener l) {
        this.listener = l;
    }

    public Listener listener() {
        return listener;
    }

    // ==================== 当前播放内容 ====================

    public Book book;
    public List<Chapter> chapters = new ArrayList<Chapter>();
    public int index = 0;           // 当前集下标（0 基）
    public String audioUrl;
    public String usedSource;       // 实际取链成功的源（自动换源后可能与 book.source 不同）
    public String chapterUrl;       // 该集在 usedSource 里的定位串

    // ==================== 播放器状态 ====================

    public boolean prepared = false;
    public boolean playing = false;
    public boolean buffering = false;
    public int curSec = 0;
    public int durSec = 0;
    public float speed = 1.0f;
    public int playMode = 0;        // 0顺序 1单集循环 2列表循环 3随机

    // 随机模式的播放顺序（预生成，避免重复）
    public List<Integer> shuffleOrder = new ArrayList<Integer>();
    public int shufflePos = 0;

    /** 当前集（UI 显示用） */
    public Chapter currentChapter() {
        if (chapters == null || index < 0 || index >= chapters.size()) return null;
        return chapters.get(index);
    }

    /** 是否已加载了内容 */
    public boolean hasContent() {
        return chapters != null && !chapters.isEmpty() && book != null;
    }

    public void reset() {
        book = null;
        chapters = new ArrayList<Chapter>();
        index = 0;
        audioUrl = null;
        usedSource = null;
        chapterUrl = null;
        prepared = false;
        playing = false;
        buffering = false;
        curSec = 0;
        durSec = 0;
        shuffleOrder = new ArrayList<Integer>();
        shufflePos = 0;
    }

    // ==================== 播放顺序计算 ====================

    /** 生成随机顺序 */
    public void buildShuffle() {
        shuffleOrder = new ArrayList<Integer>();
        for (int i = 0; i < chapters.size(); i++) shuffleOrder.add(i);
        java.util.Collections.shuffle(shuffleOrder);
        shufflePos = 0;
    }

    /**
     * 下一集下标。
     * @return -1 表示应停止播放（顺序模式到末尾）
     */
    public int nextIndex() {
        if (chapters.isEmpty()) return -1;
        int n = chapters.size();
        switch (playMode) {
            case 1:  // 单集循环
                return index;
            case 2:  // 列表循环
                return (index + 1) % n;
            case 3:  // 随机
                if (shuffleOrder.size() != n) buildShuffle();
                shufflePos++;
                if (shufflePos >= shuffleOrder.size()) {
                    buildShuffle();
                }
                return shuffleOrder.isEmpty() ? 0 : shuffleOrder.get(shufflePos % shuffleOrder.size());
            default: // 顺序
                if (index + 1 < n) return index + 1;
                return -1;
        }
    }

    /**
     * 上一集下标。
     * @return -1 表示应停止
     */
    public int prevIndex() {
        if (chapters.isEmpty()) return -1;
        int n = chapters.size();
        switch (playMode) {
            case 2:  // 列表循环
                return (index - 1 + n) % n;
            case 3:  // 随机
                if (shuffleOrder.size() != n) buildShuffle();
                shufflePos--;
                if (shufflePos < 0) shufflePos = shuffleOrder.size() - 1;
                return shuffleOrder.isEmpty() ? 0 : shuffleOrder.get(shufflePos % shuffleOrder.size());
            default:
                if (index - 1 >= 0) return index - 1;
                return 0;
        }
    }

    // ==================== 网络判断 ====================

    public boolean isWifi() {
        try {
            ConnectivityManager cm = (ConnectivityManager)
                    ctx.getSystemService(Context.CONNECTIVITY_SERVICE);
            if (cm == null) return false;
            NetworkInfo ni = cm.getActiveNetworkInfo();
            return ni != null && ni.isConnected() && ni.getType() == ConnectivityManager.TYPE_WIFI;
        } catch (Throwable t) {
            return false;
        }
    }

    public boolean isOnline() {
        try {
            ConnectivityManager cm = (ConnectivityManager)
                    ctx.getSystemService(Context.CONNECTIVITY_SERVICE);
            if (cm == null) return false;
            NetworkInfo ni = cm.getActiveNetworkInfo();
            return ni != null && ni.isConnected();
        } catch (Throwable t) {
            return false;
        }
    }

    /** 音频焦点用的 AudioManager */
    public AudioManager audioManager() {
        return (AudioManager) ctx.getSystemService(Context.AUDIO_SERVICE);
    }

    // ==================== 通知 UI 刷新 ====================

    public void notifyChanged() {
        if (listener != null) listener.onStateChanged();
    }

    public void notifyProgress() {
        if (listener != null) listener.onProgress(curSec, durSec);
    }

    public void notifyError(String msg) {
        if (listener != null) listener.onError(msg);
    }
}
