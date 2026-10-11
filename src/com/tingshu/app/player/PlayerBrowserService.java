package com.tingshu.app.player;

import android.media.MediaDescription;
import android.media.browse.MediaBrowser;
import android.media.session.MediaSession;
import android.os.Bundle;
import android.service.media.MediaBrowserService;

import com.tingshu.app.data.Models.Book;
import com.tingshu.app.data.Models.Chapter;

import java.util.ArrayList;
import java.util.List;

/**
 * 媒体浏览服务 —— 让系统级「音源选择器」能发现并列出本 App。
 *
 * <p><b>为什么需要它</b>：
 * iQOO（OriginOS）自带音乐、部分车机、Android Auto、以及一些第三方音乐 App 的
 * 「选择播放来源 / 音源」列表，并不是靠扫描 MediaSession 发现的，而是通过
 * {@link MediaBrowserService} 这套「媒体浏览」协议来枚举设备上可用的播放应用。
 * 只注册 MediaSession 的话，本 App 在音乐 App 里当播放器是不被列出的。
 *
 * <p><b>关键约束</b>：
 * <ul>
 *   <li>{@link #onGetRoot} 必须返回非 null 的 BrowserRoot，否则系统认为本应用
 *       "不提供媒体内容"，直接忽略；返回值里的 rootId 是后续 {@link #onLoadChildren}
 *       的入口。</li>
 *   <li>{@link #onLoadChildren} 至少要能返回一个非空列表（哪怕只有一项），
 *       否则部分 ROM 的列表里会出现本 App 但点进去是空的。</li>
 *   <li>必须把 {@link #setSessionToken} 指到正在用的 MediaSession，
 *       这样系统知道"选了本 App 之后由谁来播"。</li>
 * </ul>
 *
 * <p>本 App 的播放模型是"单队列、从上到下播"，没有多专辑概念，
 * 因此这里暴露一个扁平的层级：根节点 → 当前播放列表（一个可玩项）。
 */
public class PlayerBrowserService extends MediaBrowserService {

    /** 根节点 id：系统首次连接时用它来问"你下面有什么" */
    private static final String ROOT_ID = "tingshu_root";
    /** 当前播放队列这个"容器"的 id */
    private static final String QUEUE_ID = "tingshu_queue";
    /** 可播放项（正在播放的集）的 id 前缀 */
    private static final String ITEM_PREFIX = "tingshu_item:";

    @Override
    public void onCreate() {
        super.onCreate();
        // 与 PlayerService 共用同一个 MediaSession：
        // 系统"选了本 App"之后，播放控制仍走 MusicService 那套（通知/锁屏/蓝牙都一致）
        PlayerService svc = PlayerService.get();
        if (svc != null && svc.sessionToken() != null) {
            setSessionToken(svc.sessionToken());
        }
    }

    /**
     * 系统首次连接时调用，问本应用"是否允许被浏览、根节点是什么"。
     *
     * <p>这里一律允许（本 App 无账号、无权限分级），并返回固定根节点。
     * 返回 null 会导致系统完全不列出本 App —— 这是最常见的"适配了但不显示"原因。
     */
    @Override
    public BrowserRoot onGetRoot(String clientPackageName, int clientUid, Bundle hints) {
        return new BrowserRoot(ROOT_ID, null);
    }

    /**
     * 返回某节点的子项列表。
     *
     * <p>层级很简单：
     * <pre>
     *   ROOT_ID  → [QUEUE_ID]            （一个"当前播放"容器）
     *   QUEUE_ID → [当前集]              （一个可播放项，点它即开始播放）
     * </pre>
     * 即使用户还没开始播放，也要返回一个"可播放"占位项，
     * 否则系统列表里点进来是空白，会觉得本 App 不可用。
     */
    @Override
    public void onLoadChildren(String parentId, Result<List<MediaBrowser.MediaItem>> result) {
        List<MediaBrowser.MediaItem> items = new ArrayList<MediaBrowser.MediaItem>();

        if (ROOT_ID.equals(parentId)) {
            MediaDescription.Builder d = new MediaDescription.Builder()
                    .setMediaId(QUEUE_ID)
                    .setTitle("枫兮听书")
                    .setSubtitle("当前播放列表");
            // 容器节点：Browsable，可继续下钻
            items.add(new MediaBrowser.MediaItem(
                    d.build(), MediaBrowser.MediaItem.FLAG_BROWSABLE));

        } else if (QUEUE_ID.equals(parentId)) {
            // 从 PlayerService 取当前播放的书 / 集 / 集号
            Book book = null;
            Chapter ch = null;
            int idx = -1;
            PlayerService svc = PlayerService.get();
            if (svc != null) {
                book = svc.currentBook();
                ch = svc.currentChapter();
                idx = svc.currentIndex();
            }

            String title = (book != null && book.title != null) ? book.title : "枫兮听书";
            String sub;
            if (ch != null && ch.title != null) {
                sub = ch.title;
            } else if (idx >= 0) {
                sub = "第 " + (idx + 1) + " 集";
            } else {
                sub = "打开 App 选择一本书即可收听";
            }

            MediaDescription.Builder d = new MediaDescription.Builder()
                    .setMediaId(ITEM_PREFIX + Math.max(0, idx))
                    .setTitle(title)
                    .setSubtitle(sub);
            if (book != null && book.artist != null) {
                d.setDescription(book.artist);
            }
            // 可播放项：Playable。系统列表里点它 → 触发 onPlayFromMediaId
            items.add(new MediaBrowser.MediaItem(
                    d.build(), MediaBrowser.MediaItem.FLAG_PLAYABLE));
        }

        result.sendResult(items);
    }

    @Override
    public void onDestroy() {
        super.onDestroy();
    }
}
