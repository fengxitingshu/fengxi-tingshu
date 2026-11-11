package com.fengxi.fnplayer;

import android.app.Activity;
import android.content.Intent;
import android.graphics.Color;
import android.graphics.SurfaceTexture;
import android.graphics.drawable.GradientDrawable;
import android.os.Bundle;
import android.util.Log;
import android.view.Gravity;
import android.view.GestureDetector;
import android.view.MotionEvent;
import android.view.Surface;
import android.view.TextureView;
import android.view.View;
import android.view.ViewGroup;
import android.view.WindowManager;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.TextView;
import android.widget.Toast;

import com.fengxi.fnplayer.core.LocalProxy;
import com.fengxi.fnplayer.core.Net;
import com.fengxi.fnplayer.core.Utils;
import com.fengxi.fnplayer.data.Config;
import com.fengxi.fnplayer.fnos.CmsClient;
import com.fengxi.fnplayer.fnos.FnClient;
import com.fengxi.fnplayer.player.EpCache;
import com.fengxi.fnplayer.player.IjkHolder;
import com.fengxi.fnplayer.player.PlaySession;
import com.fengxi.fnplayer.source.JsSource;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.Map;

/**
 * 短剧竖滑播放页：抖音式交互 ——
 * <ul>
 *   <li>固定竖屏全屏，画面铺满；</li>
 *   <li>上滑 = 下一集，下滑 = 上一集（Fling 判定），单击 = 播放/暂停；</li>
 *   <li>只保留播放相关功能（v2.4：去掉点赞/分享等社交元素）；</li>
 *   <li>播完自动下一集。</li>
 * </ul>
 * 数据与取链按源分发：nas → FnClient；cms → vod_play_url 直链；
 * jssrc|源id|bookUrl → JS 短剧源（Rhino 引擎）取集与直链。
 */
public final class ShortActivity extends Activity implements IjkHolder.Listener {

    private FrameLayout root;
    private IjkHolder player;
    private TextView state, epName, epPos, playIcon;
    private TextureView surf;                 // v2.9：保存引用，按视频比例适配
    private android.widget.SeekBar seekBar;   // v2.9：底部进度条
    private TextView timeText;                // v2.9：时间
    private android.os.Handler ui;
    private String guid, title;
    private JSONArray eps = new JSONArray();   // [{guid|url,title}]
    private int idx = 0;
    private boolean surfaceReady = false, started = false, loading = false;
    private boolean paused = false;
    /** JS 短剧源：sourceId + 脚本缓存（非空 = 当前用 JS 源播放） */
    private String jsSourceId = "", jsScript = "";

    // ---- v2.22 沉浸化 ----
    private LinearLayout topBar, bottomBar;    // 控件层（随 showOverlay 渐隐）
    private TextView epBubble;                 // 中央偏上「第 x 集」气泡
    private ProgressBar loadBar;               // 首集/取链加载圈
    private boolean overlayShown = true;
    // ---- v2.22 悬浮小窗 ----
    private boolean transferred = false;       // 播放器已移交浮窗，onDestroy 不得 release
    private String currentUrl = "";            // 起播地址（移交兜底用）
    private Map<String, String> currentHeaders;
    private int resumeEp = -1;                 // 浮窗回全屏：续播集
    private long resumePos = 0;                // 浮窗回全屏：续播进度
    private boolean autoFloat = false;         // v2.22：前端 openFloat 起播后自动转小窗
    private final Runnable overlayHider = new Runnable() {
        @Override public void run() { if (!isFinishing()) showOverlay(false); }
    };
    private final Runnable bubbleHider = new Runnable() {
        @Override public void run() { if (epBubble != null) epBubble.animate().alpha(0f).setDuration(350).start(); }
    };

    @Override
    protected void onCreate(Bundle b) {
        super.onCreate(b);
        Config.init(this);
        com.fengxi.fnplayer.core.CrashGuard.install(this);   // v2.19：后台线程崩溃不再闪退
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        setRequestedOrientation(android.content.pm.ActivityInfo.SCREEN_ORIENTATION_PORTRAIT);
        guid = getIntent().getStringExtra("guid");
        title = getIntent().getStringExtra("title");
        if (title == null) title = "短剧";
        resumeEp = getIntent().getIntExtra("resumeEp", -1);
        resumePos = getIntent().getLongExtra("resumePos", 0);
        autoFloat = "1".equals(getIntent().getStringExtra("autoFloat"));
        ui = new android.os.Handler(getMainLooper());   // ★ v2.10：必须先于 buildBottomBar（进度条 tick 依赖）

        // v2.22：双播互斥 —— 浮窗还活着又开新视频 → 先关浮窗
        if (PlaySession.active()) stopService(new Intent(this, FloatVideoService.class));

        root = new FrameLayout(this);
        root.setBackgroundColor(Color.BLACK);

        // 画面层：TextureView（v2.9：onVideoSize 按视频宽高比适配，不再强行铺满拉变形）
        surf = new TextureView(this);
        surf.setSurfaceTextureListener(new TextureView.SurfaceTextureListener() {
            @Override public void onSurfaceTextureAvailable(SurfaceTexture st, int w, int h) {
                surfaceReady = true;
                player2surface = new Surface(st);
                playAt(idx);
            }
            @Override public void onSurfaceTextureSizeChanged(SurfaceTexture st, int w, int h) {
                fitVideo();
            }
            @Override public boolean onSurfaceTextureDestroyed(SurfaceTexture st) { return true; }
            @Override public void onSurfaceTextureUpdated(SurfaceTexture st) {}
        });
        root.addView(surf, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));

        // 中央状态
        state = new TextView(this);
        state.setTextColor(0xFFE0E6F0);
        state.setTextSize(15);
        state.setGravity(Gravity.CENTER);
        state.setPadding(40, 0, 40, 0);
        root.addView(state, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT,
                Gravity.CENTER));

        // v2.22：取链/缓冲加载圈（首集不黑屏焦虑）
        loadBar = new ProgressBar(this);
        loadBar.setIndeterminate(true);
        loadBar.setVisibility(View.GONE);
        root.addView(loadBar, new FrameLayout.LayoutParams(dp(48), dp(48), Gravity.CENTER));

        // v2.22：中央偏上「第 x 集」气泡（切集反馈）
        epBubble = new TextView(this);
        epBubble.setTextColor(Color.WHITE);
        epBubble.setTextSize(15);
        epBubble.setGravity(Gravity.CENTER);
        epBubble.setPadding(dp(18), dp(8), dp(18), dp(8));
        GradientDrawable bg = new GradientDrawable();
        bg.setColor(0x99000000);
        bg.setCornerRadius(dp(20));
        epBubble.setBackground(bg);
        epBubble.setAlpha(0f);
        FrameLayout.LayoutParams blp = new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT,
                Gravity.CENTER_HORIZONTAL | Gravity.TOP);
        blp.topMargin = dp(96);
        root.addView(epBubble, blp);

        // 中央播放/暂停图标（暂停时显示）
        playIcon = new TextView(this);
        playIcon.setText("▶");
        playIcon.setTextColor(Color.WHITE);
        playIcon.setTextSize(52);
        playIcon.setGravity(Gravity.CENTER);
        playIcon.setVisibility(View.GONE);
        root.addView(playIcon, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT,
                Gravity.CENTER));

        buildTopBar();
        buildBottomBar();

        // 手势：单击 = 播放/暂停；上滑下一集、下滑上一集
        // ★ v2.9 修复「上下滑动无效」：root 必须 clickable 且 onTouch 恒返回 true，
        //   否则 DOWN 未消费时后续 MOVE/UP 不会派发，Fling 永远收不到。
        final GestureDetector gd = new GestureDetector(this, new GestureDetector.SimpleOnGestureListener() {
            @Override public boolean onDown(MotionEvent e) { return true; }
            // v2.22：单击 = 暂停/继续 + 控件 3s 自动渐隐
            @Override public boolean onSingleTapConfirmed(MotionEvent e) {
                togglePlay();
                showOverlay(true);
                return true;
            }
            @Override public boolean onFling(MotionEvent e1, MotionEvent e2,
                                             float vx, float vy) {
                if (e1 == null || e2 == null) return false;
                float dy = e2.getY() - e1.getY();
                if (Math.abs(dy) > dp(60) && Math.abs(vy) > Math.abs(vx) * 1.2f) {
                    if (dy < 0) playAt(idx + 1); else playAt(idx - 1);
                    return true;
                }
                return false;
            }
        });
        root.setClickable(true);
        root.setOnTouchListener(new View.OnTouchListener() {
            @Override public boolean onTouch(View v, MotionEvent e) {
                gd.onTouchEvent(e);
                return true;
            }
        });

        setContentView(root);
        immersive();
        loadEpisodes();
    }

    private void buildTopBar() {
        LinearLayout top = new LinearLayout(this);
        top.setOrientation(LinearLayout.HORIZONTAL);
        top.setGravity(Gravity.CENTER_VERTICAL);
        top.setPadding(dp(14), dp(30), dp(14), dp(8));
        TextView back = mkIcon("‹", 26, new View.OnClickListener() {
            @Override public void onClick(View v) { finish(); }
        });
        top.addView(back);
        TextView t = new TextView(this);
        t.setText(title);
        t.setTextColor(Color.WHITE);
        t.setTextSize(17);
        t.setSingleLine(true);
        t.setPadding(dp(8), 0, 0, 0);
        top.addView(t, new LinearLayout.LayoutParams(0,
                ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        epPos = new TextView(this);
        epPos.setTextColor(0xFFCCCCCC);
        epPos.setTextSize(13);
        top.addView(epPos);
        // v2.22：缩小为悬浮小窗
        TextView floatBtn = mkIcon("▣", 19, new View.OnClickListener() {
            @Override public void onClick(View v) { goFloat(); }
        });
        top.addView(floatBtn);
        topBar = top;
        root.addView(top, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT,
                Gravity.TOP));
    }

    private void buildBottomBar() {
        LinearLayout bot = new LinearLayout(this);
        bot.setOrientation(LinearLayout.VERTICAL);
        bot.setPadding(dp(16), dp(8), dp(70), dp(24));
        epName = new TextView(this);
        epName.setTextColor(Color.WHITE);
        epName.setTextSize(16);
        epName.setText("加载中…");
        bot.addView(epName);
        TextView tip = new TextView(this);
        tip.setText("上滑切换下一集 · 单击暂停");
        tip.setTextColor(0xAAFFFFFF);
        tip.setTextSize(12);
        tip.setPadding(0, dp(6), 0, 0);
        bot.addView(tip);
        // v2.9：底部进度条（可拖动）+ 时间
        LinearLayout prow = new LinearLayout(this);
        prow.setOrientation(LinearLayout.HORIZONTAL);
        prow.setGravity(Gravity.CENTER_VERTICAL);
        prow.setPadding(0, dp(10), 0, 0);
        timeText = new TextView(this);
        timeText.setTextColor(0xAAFFFFFF);
        timeText.setTextSize(11);
        timeText.setText("0:00");
        prow.addView(timeText);
        seekBar = new android.widget.SeekBar(this);
        seekBar.setMax(1000);
        // v2.22：细进度条（2dp 轨道 + 5dp 圆点 thumb，拖动不放大）
        applySlimSeek();
        seekBar.setOnSeekBarChangeListener(new android.widget.SeekBar.OnSeekBarChangeListener() {
            @Override public void onProgressChanged(android.widget.SeekBar sb, int p, boolean fromUser) {
                if (fromUser && player != null && durMs() > 0) {
                    player.seekTo((long) (durMs() * p / 1000.0));
                }
            }
            @Override public void onStartTrackingTouch(android.widget.SeekBar sb) {
                seeking = true;
                showOverlay(true);   // 拖动期间控件保持可见
            }
            @Override public void onStopTrackingTouch(android.widget.SeekBar sb) { seeking = false; }
        });
        prow.addView(seekBar, new LinearLayout.LayoutParams(0,
                ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        timeText2 = new TextView(this);
        timeText2.setTextColor(0xAAFFFFFF);
        timeText2.setTextSize(11);
        timeText2.setText("0:00");
        timeText2.setPadding(dp(8), 0, 0, 0);
        prow.addView(timeText2);
        bot.addView(prow);
        bottomBar = bot;
        root.addView(bot, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT,
                Gravity.BOTTOM));
        ui.post(tick);
    }

    /** v2.22：细进度条样式。 */
    private void applySlimSeek() {
        try {
            GradientDrawable track = new GradientDrawable();
            track.setColor(0x4DFFFFFF);
            track.setCornerRadius(dp(1));
            GradientDrawable prog = new GradientDrawable();
            prog.setColor(0xFFFF4D67);
            prog.setCornerRadius(dp(1));
            android.graphics.drawable.ClipDrawable clip = new android.graphics.drawable.ClipDrawable(
                    prog, Gravity.LEFT, android.graphics.drawable.ClipDrawable.HORIZONTAL);
            android.graphics.drawable.LayerDrawable ld = new android.graphics.drawable.LayerDrawable(
                    new android.graphics.drawable.Drawable[]{track, clip});
            ld.setId(0, android.R.id.background);
            ld.setId(1, android.R.id.progress);
            seekBar.setProgressDrawable(ld);
            GradientDrawable thumb = new GradientDrawable();
            thumb.setColor(0xFFFFFFFF);
            thumb.setCornerRadius(dp(3));
            thumb.setSize(dp(5), dp(5));
            seekBar.setThumb(thumb);
            seekBar.setPadding(0, dp(10), 0, dp(10));
        } catch (Throwable ignored) {}
    }

    // ==================== v2.22 沉浸化 ====================

    /** 控件层显隐（180ms 渐变）；show=true 时 3s 后自动隐藏。 */
    private void showOverlay(boolean on) {
        if (isFinishing()) return;
        overlayShown = on;
        if (topBar != null) topBar.animate().alpha(on ? 1f : 0f).setDuration(180).start();
        if (bottomBar != null) bottomBar.animate().alpha(on ? 1f : 0f).setDuration(180).start();
        ui.removeCallbacks(overlayHider);
        if (on && !paused && started) ui.postDelayed(overlayHider, 3000);
    }

    /** 中央偏上「第 x 集」气泡，1.2s 后渐隐。 */
    private void showEpBubble(String text) {
        if (isFinishing() || epBubble == null) return;
        epBubble.setText(text);
        epBubble.animate().alpha(1f).setDuration(120).start();
        ui.removeCallbacks(bubbleHider);
        ui.postDelayed(bubbleHider, 1200);
    }

    /** 加载圈 + 文案（首集/取链期间）。 */
    private void showLoading(boolean on, String text) {
        if (loadBar != null) loadBar.setVisibility(on ? View.VISIBLE : View.GONE);
        state.setVisibility(on ? View.VISIBLE : View.GONE);
        if (on && text != null) state.setText(text);
    }

    private boolean seeking = false;
    private TextView timeText2;

    /** 每 500ms 刷新进度条（v2.9）。 */
    private final Runnable tick = new Runnable() {
        @Override public void run() {
            if (!isFinishing() && player != null && started && !loading) {
                long cur = curMs(), dur = durMs();
                if (dur > 0 && !seeking) seekBar.setProgress((int) (cur * 1000 / dur));
                timeText.setText(fmtMs(cur));
                timeText2.setText(fmtMs(dur));
            }
            ui.postDelayed(this, 500);
        }
    };

    private static String fmtMs(long ms) {
        long s = ms / 1000;
        return (s / 60) + ":" + String.format("%02d", s % 60);
    }

    private long curMs() { try { return player == null ? 0 : player.curMs(); } catch (Throwable t) { return 0; } }
    private long durMs() { try { return player == null ? 0 : player.durMs(); } catch (Throwable t) { return 0; } }

    /** 按视频宽高比调整画面（fit：完整显示、居中，不拉伸不裁切）。 */
    private void fitVideo() {
        if (surf == null || videoW <= 0 || videoH <= 0 || root.getWidth() == 0) return;
        int cw = root.getWidth(), ch = root.getHeight();
        float vr = videoW * 1f / videoH, cr = cw * 1f / ch;
        int w, h;
        if (vr > cr) { w = cw; h = Math.round(cw / vr); } else { h = ch; w = Math.round(ch * vr); }
        FrameLayout.LayoutParams lp = new FrameLayout.LayoutParams(w, h, Gravity.CENTER);
        surf.setLayoutParams(lp);
    }

    private int videoW = 0, videoH = 0;

    private TextView mkIcon(String t, float sizeSp, View.OnClickListener l) {
        TextView v = new TextView(this);
        v.setText(t);
        v.setTextColor(Color.WHITE);
        v.setTextSize(sizeSp);
        v.setGravity(Gravity.CENTER);
        v.setPadding(dp(12), dp(6), dp(12), dp(6));
        v.setClickable(true);
        if (l != null) v.setOnClickListener(l);
        return v;
    }

    private TextView mkSide(String icon, View.OnClickListener l) {
        TextView v = new TextView(this);
        v.setText(icon);
        v.setTextColor(Color.WHITE);
        v.setTextSize(26);
        v.setGravity(Gravity.CENTER);
        v.setPadding(dp(14), dp(20), dp(14), dp(20));
        GradientDrawable gd = new GradientDrawable();
        gd.setColor(0x52000000);
        gd.setCornerRadius(dp(24));
        v.setBackground(gd);
        v.setClickable(true);
        v.setOnClickListener(l);
        return v;
    }

    // ==================== 数据 ====================

    private void loadEpisodes() {
        // v2.22：spider 爬虫源短剧卡片（guid = "spid|<源id>|<vod_id>"）也走竖屏沉浸页。
        // 直接复用 Bridge.episodesFor（内部处理 spid| 指定源、msearch: 占位 id、
        // detailContent / spiderPlayerFallback），集条目 guid 为 direct:<url>，
        // playAt 的 direct 分支天然支持。
        if (guid != null && guid.startsWith("spid|")) { loadSpiderEpisodes(); return; }
        // JS 短剧源：guid = "jssrc|<sourceId>|<bookUrl>"
        if (guid != null && guid.startsWith("jssrc|")) {
            String[] p = guid.split("\\|", 3);
            jsSourceId = p.length > 1 ? p[1] : "";
            final String bookUrl = p.length > 2 ? p[2] : "";
            jsScript = findJsScript(jsSourceId);
            state.setVisibility(View.VISIBLE);
            state.setText("正在读取剧集…");
            new Thread(new Runnable() {
                @Override public void run() {
                    try {
                        final JSONObject d = JsSource.bookDetail(jsSourceId, jsScript, bookUrl);
                        JSONArray epl = d.optJSONArray("episodes");
                        eps = epl == null ? new JSONArray() : epl;
                        String n = d.optString("title", "");
                        final String cover = d.optString("coverUrl", "");
                        runOnUiThread(new Runnable() {
                            @Override public void run() {
                                if (isFinishing()) return;
                                if (n.length() > 0) {
                                    title = n;
                                    epName.setText("@" + title);
                                }
                                state.setVisibility(View.GONE);
                                if (eps.length() == 0) {
                                    state.setVisibility(View.VISIBLE);
                                    state.setText("没有读到剧集数据\n（该剧可能已下架）");
                                    return;
                                }
                                EpCache.clear();   // v2.22：换剧清缓存，防下标错位
                                epPos.setText("1/" + eps.length());
                                playAt(startEpIndex());
                            }
                        });
                    } catch (Throwable t) {
                        runOnUiThread(new Runnable() {
                            @Override public void run() {
                                state.setVisibility(View.VISIBLE);
                                state.setText("读取剧集失败：" + t.getMessage());
                            }
                        });
                    }
                }
            }, "jsshort").start();
            return;
        }
        state.setVisibility(View.VISIBLE);
        state.setText("正在读取剧集…");
        new Thread(new Runnable() {
            @Override public void run() {
                try {
                    if ("cms".equals(curSourceType())) {
                        CmsClient c = new CmsClient(Config.base(), Config.trustSsl());
                        FnClient.Result d = c.detail(guid);
                        if (d.ok && d.list != null && d.list.length() > 0) {
                            JSONObject vod = d.list.optJSONObject(0);
                            if (vod != null) {
                                eps = CmsClient.parseEpisodes(vod.optString("vod_play_url", ""));
                                String n = vod.optString("vod_name", "");
                                if (n.length() > 0) title = n;
                            }
                        }
                    } else {
                        FnClient.Result r = FnClient.shared().episodes(guid);
                        if (r.ok && r.list != null) eps = r.list;
                    }
                    runOnUiThread(new Runnable() {
                        @Override public void run() {
                            if (isFinishing()) return;
                            state.setVisibility(View.GONE);
                            if (eps.length() == 0) {
                                state.setVisibility(View.VISIBLE);
                                state.setText("没有读到剧集数据\n（确认当前源里这部剧有内容）");
                                return;
                            }
                            EpCache.clear();   // v2.22：换剧清缓存，防下标错位
                            epPos.setText("1/" + eps.length());
                            playAt(startEpIndex());
                        }
                    });
                } catch (Throwable t) {
                    runOnUiThread(new Runnable() {
                        @Override public void run() {
                            state.setVisibility(View.VISIBLE);
                            state.setText("读取剧集失败：" + t.getClass().getSimpleName());
                        }
                    });
                }
            }
        }, "fnshort").start();
    }

    /** v2.22：spider 爬虫源取集（复用 Bridge.episodesFor，勿重写 spider 逻辑）。 */
    private void loadSpiderEpisodes() {
        state.setVisibility(View.VISIBLE);
        state.setText("正在读取剧集…");
        final String fGuid = guid;
        new Thread(new Runnable() {
            @Override public void run() {
                JSONArray list = new JSONArray();
                String err = "";
                try {
                    list = Bridge.episodesFor(ShortActivity.this, fGuid);
                } catch (Throwable t) {
                    err = String.valueOf(t.getMessage());
                }
                final JSONArray fList = list;
                final String fErr = err;
                runOnUiThread(new Runnable() {
                    @Override public void run() {
                        if (isFinishing()) return;
                        eps = fList;
                        if (eps.length() == 0) {
                            state.setVisibility(View.VISIBLE);
                            state.setText("没有读到剧集数据"
                                    + (fErr.length() > 0 ? "：" + fErr : "\n（源里这部剧可能没有可播内容）"));
                            return;
                        }
                        state.setVisibility(View.GONE);
                        EpCache.clear();
                        epPos.setText("1/" + eps.length());
                        playAt(startEpIndex());
                    }
                });
            }
        }, "spidershort").start();
    }

    /** v2.22：起播集下标（浮窗回全屏续播时用 resumeEp，否则第 0 集）。 */
    private int startEpIndex() {
        return resumeEp >= 0 && resumeEp < eps.length() ? resumeEp : 0;
    }

    /** 播放第 i 集（越界提示）。缓存命中零等待；miss 走 resolveEpisode 并预取下一集。 */
    private void playAt(int i) {
        if (eps.length() == 0) return;
        if (i < 0) { toast("已经是第一集了"); return; }
        if (i >= eps.length()) { toast("没有更多了，上滑回看"); return; }
        idx = i;
        paused = false;
        softTried = false;   // v2.9：每集重置软解重试标记
        playIcon.setVisibility(View.GONE);
        JSONObject ep = eps.optJSONObject(i);
        if (ep == null) return;
        String eg = ep.optString("guid", "");
        if (eg.length() == 0) eg = ep.optString("url", "");   // JS 源剧集存 url
        String n = ep.optString("title", "");
        epName.setText("@" + title + " · " + (n.length() > 0 ? n : ("第 " + (i + 1) + " 集")));
        epPos.setText((i + 1) + "/" + eps.length());
        showEpBubble((i + 1) + " / " + eps.length());
        if (!surfaceReady) return;
        // v2.22：预取命中 → 零等待起播
        EpCache.Ent hit = EpCache.get(i);
        if (hit != null && hit.probedOk) {
            Log.d("FxShort", "cache hit ep" + i);
            loading = false;
            started = true;
            state.setVisibility(View.GONE);
            loadBar.setVisibility(View.GONE);
            startPlayer(hit.url, hit.headers, false);
            prefetch(i + 1);
            return;
        }
        loading = true;
        started = true;
        showLoading(true, "取集加载中…");
        resolveEpisode(i, false, new ResolveCb() {
            @Override public void onDone(String url, Map<String, String> headers, boolean ok, String err) {
                if (isFinishing()) return;
                if (!ok) {
                    loading = false;
                    showLoading(false, null);
                    state.setVisibility(View.VISIBLE);
                    if (err != null && err.contains("HTTP ")) {
                        state.setText("第 " + (idx + 1) + " 集没有可播放的地址（" + err + "）\n"
                                + "多数短剧源只提供免费试看的前几集，后面的集数是付费内容\n上滑回看已能播的集数");
                    } else {
                        state.setText("取播放地址失败" + (err != null && err.length() > 0 ? "：" + err : ""));
                    }
                    return;
                }
                showLoading(false, null);
                startPlayer(url, headers, false);
                prefetch(idx + 1);
            }
        });
    }

    /** 取链结果回调。 */
    private interface ResolveCb {
        void onDone(String url, Map<String, String> headers, boolean ok, String err);
    }

    /**
     * v2.22：统一取链（jssrc / direct / nas+cms 三分支），成功结果入 EpCache。
     * forPrefetch=true 时仅解析不碰 UI（供上滑预取）。
     */
    private void resolveEpisode(final int i, final boolean forPrefetch, final ResolveCb cb) {
        if (i < 0 || i >= eps.length()) {
            cb.onDone(null, null, false, "越界");
            return;
        }
        JSONObject ep = eps.optJSONObject(i);
        if (ep == null) { cb.onDone(null, null, false, "无效条目"); return; }
        String eg = ep.optString("guid", "");
        if (eg.length() == 0) eg = ep.optString("url", "");
        final String fEg = eg;
        final boolean isJs = jsSourceId.length() > 0;
        EpCache.markInflight(i, true);   // v2.22：单飞防抖（预取与手动取链共用）
        new Thread(new Runnable() {
            @Override public void run() {
                String url = "";
                Map<String, String> headers = null;
                String err = "";
                boolean ok = false;
                try {
                    if (isJs) {
                        String real = JsSource.video(jsSourceId, jsScript, fEg);
                        int code = real != null && real.length() > 0 ? probeCode(real) : -1;
                        if (real == null || real.length() == 0) err = "源未返回地址";
                        else if (code >= 400) err = "HTTP " + code + "（可能为付费集）";
                        else { url = real; ok = true; }
                    } else if (fEg.startsWith("direct:")) {
                        url = fEg.substring("direct:".length());
                        int code = probeCode(url);
                        if (code >= 400) err = "HTTP " + code + "（可能为付费集）";
                        else ok = true;
                    } else {
                        FnClient c = FnClient.shared();
                        String real = c.playUrl(fEg);
                        Map<String, String> h = c.playHeaders();
                        String local = LocalProxy.serve(real, h, Config.trustSsl());
                        if (local != null) { url = local; ok = true; }
                        else if (real != null && real.length() > 0) { url = real; headers = h; ok = true; }
                        else err = "无播放地址";
                    }
                } catch (Throwable t) {
                    err = String.valueOf(t.getMessage());
                }
                if (ok) EpCache.put(i, new EpCache.Ent(url, headers, true));
                else Log.d("FxShort", "resolve ep" + i + " fail: " + err
                        + (forPrefetch ? " (prefetch)" : ""));
                EpCache.markInflight(i, false);
                final String fUrl = url;
                final Map<String, String> fHeaders = headers;
                final boolean fOk = ok;
                final String fErr = err;
                runOnUiThread(new Runnable() {
                    @Override public void run() {
                        if (isFinishing()) return;
                        cb.onDone(fOk ? fUrl : null, fOk ? fHeaders : null, fOk, fErr);
                    }
                });
            }
        }, forPrefetch ? "short-prefetch" : "short-resolve").start();
    }

    /** v2.22：预取下一集直链（命中后上滑零等待）。 */
    private void prefetch(int i) {
        if (i < 0 || i >= eps.length()) return;
        if (EpCache.get(i) != null || EpCache.inflight(i)) return;
        resolveEpisode(i, true, new ResolveCb() {
            @Override public void onDone(String url, Map<String, String> headers, boolean ok, String err) {
                // 结果已在 resolveEpisode 内入缓存；失败静默（用户滑到时走正式取链+提示）
            }
        });
    }

    /** 轻量探测直链 HTTP 状态（只拉 1KB 即断开）。 */
    private int probeCode(String url) {
        try {
            Net.Resp r = Net.getBytes(url, new java.util.HashMap<String, String>(), true, 8000, 1024);
            return r.code;
        } catch (Throwable t) { return -1; }
    }

    private void startPlayer(String url, Map<String, String> headers, boolean trustSsl) {
        try {
            // ★ ijkplayer 的 ffmpeg 未编译 openssl，https 直链（红果/资源站）必须走本地代理
            if (url != null && url.startsWith("https")) {
                String local = LocalProxy.serve(url, headers, trustSsl);
                if (local != null) url = local;
            }
            // v2.22：记录起播地址（悬浮小窗移交失败时兜底重建用）
            currentUrl = url == null ? "" : url;
            currentHeaders = headers;
            if (player != null) player.release();
            player = new IjkHolder();
            player.init(this, player2surface);
            if (softTried || Config.preferSoft()) player.useSoftDecoder();   // 重试时保持软解
            player.setListener(this);
            player.setDataSource(url, headers == null
                    ? new java.util.HashMap<String, String>() : headers);
        } catch (Throwable t) {
            state.setVisibility(View.VISIBLE);
            state.setText("播放失败：" + Utils.brief(t));
            loading = false;
            started = false;
        }
    }

    /** 播放出错自动切软解重试一次（硬解不兼容最常见，同 VideoActivity 的自愈策略）。 */
    private boolean softTried = false;

    private Surface player2surface;   // TextureView 的 Surface

    @Override public void onPrepared() {
        loading = false;
        state.setVisibility(View.GONE);
        loadBar.setVisibility(View.GONE);
        // v2.22：浮窗回全屏续播
        if (resumePos > 0 && player != null) {
            player.seekTo(resumePos);
            resumePos = 0;
        }
        showOverlay(true);   // 起播显示控件，3s 自动渐隐
        if (autoFloat) { autoFloat = false; goFloat(); }
    }

    @Override public void onEnded() {
        playAt(idx + 1);
    }

    @Override public void onError(int what, int extra) {
        if (!softTried) {
            softTried = true;   // 先切软解重连一次（重建播放器才生效，playAt 会重走取链）
            playAt(idx);
            return;
        }
        state.setVisibility(View.VISIBLE);
        String hint;
        if (idx > 0) {
            hint = "这一集播不出来。很多短剧源只提供免费试看的前几集，"
                    + "后面的集数源里没有地址（付费内容），上滑回看已能播的集数";
        } else {
            hint = "播放出错 what=" + what + " extra=" + extra;
        }
        state.setText(hint + "\n上滑试试下一集，或返回重新进入");
        loading = false;
    }

    @Override public void onInfo(int what, int extra) {}

    @Override public void onVideoSize(int w, int h) {
        if (w > 0 && h > 0) { videoW = w; videoH = h; fitVideo(); }
    }

    // ==================== 杂项 ====================

    private void togglePlay() {
        if (player == null || loading) return;
        paused = !paused;
        if (paused) {
            player.pause();
            ui.removeCallbacks(overlayHider);   // 暂停时控件不自动隐藏
        } else {
            player.play();
        }
        playIcon.setVisibility(paused ? View.VISIBLE : View.GONE);
    }

    /** 从源列表里找 JS 源脚本内容。 */
    private String findJsScript(String id) {
        try {
            JSONArray arr = new JSONArray(Config.sources());
            for (int i = 0; i < arr.length(); i++) {
                JSONObject s = arr.optJSONObject(i);
                if (s != null && id.equals(s.optString("id")))
                    return s.optString("script", "");
            }
        } catch (Throwable ignored) {}
        return "";
    }

    private void immersive() {
        getWindow().getDecorView().setSystemUiVisibility(
                View.SYSTEM_UI_FLAG_LAYOUT_STABLE | View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION
                        | View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN | View.SYSTEM_UI_FLAG_HIDE_NAVIGATION
                        | View.SYSTEM_UI_FLAG_FULLSCREEN | View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY);
    }

    private int dp(int v) {
        return Math.round(v * getResources().getDisplayMetrics().density);
    }

    private void toast(String s) {
        Toast.makeText(this, s, Toast.LENGTH_SHORT).show();
    }

    private String curSourceType() {
        try {
            JSONArray arr = new JSONArray(Config.sources());
            String cur = Config.curSourceId();
            for (int i = 0; i < arr.length(); i++) {
                JSONObject s = arr.optJSONObject(i);
                if (s != null && cur.equals(s.optString("id"))) return s.optString("type", "fnos");
            }
        } catch (Throwable ignored) {}
        return "fnos";
    }

    @Override
    public void onWindowFocusChanged(boolean hasFocus) {
        super.onWindowFocusChanged(hasFocus);
        if (hasFocus) immersive();
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        if (ui != null) ui.removeCallbacksAndMessages(null);
        // v2.22：播放器已移交浮窗时不得 release（由 FloatVideoService 负责）
        if (!transferred && player != null) player.release();
    }

    // ==================== v2.22 悬浮小窗 ====================

    /** 缩小为悬浮小窗：同进程移交 IjkHolder（零黑屏），Service 兜底重取链。 */
    private void goFloat() {
        if (!android.provider.Settings.canDrawOverlays(this)) {
            new android.app.AlertDialog.Builder(this)
                    .setTitle("需要悬浮窗权限")
                    .setMessage("悬浮小窗需要「显示在其他应用上层」权限，去设置里开启后返回重试。")
                    .setPositiveButton("去授权", new android.content.DialogInterface.OnClickListener() {
                        @Override public void onClick(android.content.DialogInterface d, int w) {
                            try {
                                startActivity(new Intent(android.provider.Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                                        android.net.Uri.parse("package:" + getPackageName())));
                            } catch (Throwable t) {
                                toast("请在系统设置里开启悬浮窗权限");
                            }
                        }
                    })
                    .setNegativeButton("取消", null)
                    .show();
            return;
        }
        if (PlaySession.active()) stopService(new Intent(this, FloatVideoService.class));
        boolean ok = PlaySession.takeover(player, title, curMs(), true,
                "short", guid, "", idx, false);
        if (!ok) { toast("当前没有可缩小的播放"); return; }
        PlaySession.playUrl = currentUrl;
        PlaySession.headers = currentHeaders;
        transferred = true;
        startService(new Intent(this, FloatVideoService.class)
                .setAction(FloatVideoService.ACTION_SHOW));
        finish();
    }
}
