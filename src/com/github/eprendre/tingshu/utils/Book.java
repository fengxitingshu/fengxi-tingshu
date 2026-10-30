package com.github.eprendre.tingshu.utils;

/**
 * 兼容原版「我的听书」(com.github.eprendre.tingshu) 插件生态的 Book 数据类。
 *
 * <p>这不是给 App 自己用的模型——App 内部用 {@link com.tingshu.app.data.Models.Book}。
 * 这个类存在的唯一目的，是让第三方 jar 插件（编译时链接的是原版 API）能被加载。
 *
 * <p>字段与签名严格对齐原版 Kotlin data class 编译出的形态：
 * <pre>
 *   data class Book(
 *       var coverUrl: String, val bookUrl: String, var title: String,
 *       var author: String, var artist: String
 *   ) { var id: Int?; var intro: String; ... }
 * </pre>
 * 主构造器有 5 个参数，其余为带默认值的可变属性 → 用 setter/getter 暴露。
 *
 * <p>未在本 App 中用到的字段（playSpeed / isTransientEpisodes 等）也一并保留，
 * 因为它们出现在插件编译期引用的类布局里，缺了会导致插件定位字段失败。
 */
public class Book {

    // --- 主构造器 5 参（顺序 / 类型必须与 Kotlin data class 完全一致）---
    public String coverUrl;
    public String bookUrl;
    public String title;
    public String author;
    public String artist;

    // --- 其余属性 ---
    public Integer id;
    public String intro = "";
    public String currentEpisodeUrl;
    public String currentEpisodeName;
    public long currentEpisodePosition = 0L;
    public long skipBeginning = 0L;
    public long skipEnd = 0L;
    public int volumeBoostLevel = 0;
    public int playOrderType = 0;
    public float playSpeed = 1f;
    public boolean isFree = true;
    public boolean isEpisodesReversed = false;
    public java.util.List<Episode> episodeList;
    public boolean hasFullEpisodes = false;
    public boolean isShowBriefChapterTitle = false;
    public String sourceId;
    public String status = "";
    public long episodesUpdateTime = 0L;
    public boolean isTransientEpisodes = false;
    public boolean isCompleted = false;

    public Book(String coverUrl, String bookUrl, String title, String author, String artist) {
        this.coverUrl = coverUrl;
        this.bookUrl = bookUrl;
        this.title = title;
        this.author = author;
        this.artist = artist;
    }

    // --- Kotlin 属性访问器：JVM 上就是 getXxx/setXxx ---
    public String getCoverUrl() { return coverUrl; }
    public void setCoverUrl(String v) { coverUrl = v; }

    public String getBookUrl() { return bookUrl; }

    public String getTitle() { return title; }
    public void setTitle(String v) { title = v; }

    public String getAuthor() { return author; }
    public void setAuthor(String v) { author = v; }

    public String getArtist() { return artist; }
    public void setArtist(String v) { artist = v; }

    public Integer getId() { return id; }
    public void setId(Integer v) { id = v; }

    public String getIntro() { return intro; }
    public void setIntro(String v) { intro = v == null ? "" : v; }

    public String getCurrentEpisodeUrl() { return currentEpisodeUrl; }
    public void setCurrentEpisodeUrl(String v) { currentEpisodeUrl = v; }

    public String getCurrentEpisodeName() { return currentEpisodeName; }
    public void setCurrentEpisodeName(String v) { currentEpisodeName = v; }

    public long getCurrentEpisodePosition() { return currentEpisodePosition; }
    public void setCurrentEpisodePosition(long v) { currentEpisodePosition = v; }

    public long getSkipBeginning() { return skipBeginning; }
    public void setSkipBeginning(long v) { skipBeginning = v; }

    public long getSkipEnd() { return skipEnd; }
    public void setSkipEnd(long v) { skipEnd = v; }

    public int getVolumeBoostLevel() { return volumeBoostLevel; }
    public void setVolumeBoostLevel(int v) { volumeBoostLevel = v; }

    public int getPlayOrderType() { return playOrderType; }
    public void setPlayOrderType(int v) { playOrderType = v; }

    public float getPlaySpeed() { return playSpeed; }
    public void setPlaySpeed(float v) { playSpeed = v; }

    public boolean isFree() { return isFree; }
    public void setFree(boolean v) { isFree = v; }

    public boolean isEpisodesReversed() { return isEpisodesReversed; }
    public void setEpisodesReversed(boolean v) { isEpisodesReversed = v; }

    public java.util.List<Episode> getEpisodeList() { return episodeList; }
    public void setEpisodeList(java.util.List<Episode> v) { episodeList = v; }

    public boolean isHasFullEpisodes() { return hasFullEpisodes; }
    public void setHasFullEpisodes(boolean v) { hasFullEpisodes = v; }

    public boolean isShowBriefChapterTitle() { return isShowBriefChapterTitle; }
    public void setShowBriefChapterTitle(boolean v) { isShowBriefChapterTitle = v; }

    public String getSourceId() { return sourceId; }
    public void setSourceId(String v) { sourceId = v; }

    public String getStatus() { return status; }
    public void setStatus(String v) { status = v == null ? "" : v; }

    public long getEpisodesUpdateTime() { return episodesUpdateTime; }
    public void setEpisodesUpdateTime(long v) { episodesUpdateTime = v; }

    public boolean isTransientEpisodes() { return isTransientEpisodes; }
    public void setTransientEpisodes(boolean v) { isTransientEpisodes = v; }

    public boolean isCompleted() { return isCompleted; }
    public void setCompleted(boolean v) { isCompleted = v; }

    /** 原版用来把另一本书的非主构造字段拷过来（章节列表刷新时用） */
    public void copyFrom(Book book) {
        if (book == null) return;
        this.id = book.id;
        this.intro = book.intro;
        this.currentEpisodeUrl = book.currentEpisodeUrl;
        this.currentEpisodeName = book.currentEpisodeName;
        this.currentEpisodePosition = book.currentEpisodePosition;
        this.skipBeginning = book.skipBeginning;
        this.skipEnd = book.skipEnd;
        this.volumeBoostLevel = book.volumeBoostLevel;
        this.playOrderType = book.playOrderType;
        this.playSpeed = book.playSpeed;
        this.isFree = book.isFree;
        this.isEpisodesReversed = book.isEpisodesReversed;
        this.episodeList = book.episodeList;
        this.hasFullEpisodes = book.hasFullEpisodes;
        this.isShowBriefChapterTitle = book.isShowBriefChapterTitle;
        this.sourceId = book.sourceId;
        this.status = book.status;
        this.episodesUpdateTime = book.episodesUpdateTime;
        this.isTransientEpisodes = book.isTransientEpisodes;
        this.isCompleted = book.isCompleted;
    }

    /** 原版 data class 的 equals：按「主构造 5 字段 + sourceId」比较 */
    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (!(o instanceof Book)) return false;
        Book b = (Book) o;
        if (!eq(coverUrl, b.coverUrl)) return false;
        if (!eq(bookUrl, b.bookUrl)) return false;
        if (!eq(title, b.title)) return false;
        if (!eq(author, b.author)) return false;
        if (!eq(artist, b.artist)) return false;
        return eq(sourceId, b.sourceId);
    }

    @Override
    public int hashCode() {
        int h = 17;
        h = 31 * h + hc(coverUrl);
        h = 31 * h + hc(bookUrl);
        h = 31 * h + hc(title);
        h = 31 * h + hc(author);
        h = 31 * h + hc(artist);
        h = 31 * h + hc(sourceId);
        return h;
    }

    @Override
    public String toString() {
        return "Book(title=" + title + ", bookUrl=" + bookUrl + ", sourceId=" + sourceId + ")";
    }

    private static boolean eq(Object a, Object b) {
        return a == null ? b == null : a.equals(b);
    }

    private static int hc(Object o) {
        return o == null ? 0 : o.hashCode();
    }
}
