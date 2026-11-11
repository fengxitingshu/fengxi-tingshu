package com.fengxi.fnplayer;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.Context;
import android.content.DialogInterface;
import android.content.Intent;
import android.graphics.Color;
import android.graphics.drawable.GradientDrawable;
import android.media.AudioManager;
import android.os.Bundle;
import android.os.Handler;
import android.view.Gravity;
import android.view.GestureDetector;
import android.view.MotionEvent;
import android.view.SurfaceHolder;
import android.view.SurfaceView;
import android.view.TextureView;
import android.view.View;
import android.view.ViewGroup;
import android.view.WindowManager;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.SeekBar;
import android.widget.TextView;
import android.widget.Toast;

import com.fengxi.fnplayer.core.LocalProxy;
import com.fengxi.fnplayer.core.Net;
import com.fengxi.fnplayer.core.Utils;
import com.fengxi.fnplayer.data.Config;
import com.fengxi.fnplayer.fnos.FnClient;
import com.fengxi.fnplayer.player.IjkHolder;
import com.fengxi.fnplayer.sub.Subtitle;

import org.json.JSONArray;
import org.json.JSONObject;

import java.net.HttpURLConnection;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * 原生视频播放页（参照 EasyBox / TVBox 系播放器的标配交互，纯代码建视图）：
 * <ul>
 *   <li>画面比例：适配（默认）/ 16:9 / 4:3 / 填充，按视频真实尺寸重排 SurfaceView；</li>
 *   <li>手势：单击=控制栏，双击=暂停，水平滑=快进快退，左半屏竖滑=亮度，
 *       右半屏竖滑=音量，长按=3x 倍速；</li>
 *   <li>横屏全屏沉浸（默认），可切竖屏；</li>
 *   <li>倍速 / 清晰度切换（play/quality）/ 下一集 / 选集 / 锁屏；</li>
 *   <li>取链四步 + 播前探测全程上屏。</li>
 * </ul>
 */
public class VideoActivity extends Activity implements SurfaceHolder.Callback, IjkHolder.Listener {

    private static final float[] SPEEDS = {0.5f, 0.75f, 1f, 1.25f, 1.5f, 2f, 3f};
    private static final String[] RATIO_NAMES = {"适配", "16:9", "4:3", "填充"};

    private FrameLayout videoBox;
    private SurfaceView surface;
    private TextureView texView;
    private android.view.Surface videoSurface;   // 两种渲染视图统一交给播放器
    private TextView state;
    private TextView hud;
    private TextView unlockBtn;
    private LinearLayout topBar;
    private LinearLayout bottomBar;
    private LinearLayout sideBar;    // 左侧竖排：锁屏/截图
    private TextView titleView;
    private TextView timeView;
    private TextView durView;
    private TextView playBtn;
    private TextView speedBtn;
    private TextView nextBtn;
    private TextView blackBtn;       // 去黑边
    private TextView effBtn;         // 音效
    private TextView listBtn;        // 播放列表
    private TextView bigPlay;        // 中央大播放键
    private TextView subView;        // 外挂字幕渲染层
    private SeekBar seek;

    private IjkHolder player;
    // ---- v2.22 悬浮小窗 ----
    private boolean transferred = false;       // 播放器已移交浮窗，onDestroy 不得 release/drop
    private String currentUrl = "";            // 起播地址（移交兜底重建用）
    private Map<String, String> currentHeaders;
    private boolean currentLive = false;
    private long resumePos = 0;                // 浮窗回全屏续播进度
    private boolean autoFloat = false;         // v2.22：前端 openFloat 起播后自动转小窗
    private final Handler ui = new Handler();
    private final ExecutorService io = Executors.newSingleThreadExecutor();
    private AudioManager am;
    private int volMax = 15;

    private String guid;
    private String itemGuid;     // 选集列表用的父条目（TV 时≠guid）
    private String title;
    private String playingLocalUrl;

    private boolean surfaceReady = false;
    private boolean started = false;
    private boolean seeking = false;
    private boolean softDecoded = false;
    private boolean userPaused = false;   // v2.20.1：只有用户主动暂停才显示中央大三角
    private int speedIdx = 2;                 // SPEEDS[2] = 1f
    private boolean boost3x = false;          // 长按 3x
    private int ratioIdx = 0;
    private boolean landscape = true;
    private boolean locked = false;
    private boolean controlsOn = true;

    private int videoW = 0, videoH = 0;
    private float savedBrightness = -1f;
    private float volumeGain = 1f;            // 音效：播放器音量增益
    private String curQuality = null;         // 用户选择的清晰度（null=默认）

    private JSONArray epList = null;          // 选集缓存
    private int epIndex = -1;

    // 外挂字幕
    private boolean subOn = true;             // 字幕开关（有字幕时才生效）
    private String subText = "";              // 当前帧字幕（避免重复 setText）
    private String curRealUrl;                // 字幕匹配用的视频真实直链
    private Map<String, String> curHeaders;   // 对应鉴权头
    private boolean directMode = false;       // 直链模式（直播频道 / cms 集直链）

    // 手势状态
    private int gestureMode = 0;              // 0=未定 1=seek 2=亮度 3=音量
    private long gestureBasePos = 0;
    private float gestureStartY = 0, gestureStartX = 0;
    private float gestureStartBright = 1f;
    private int gestureStartVol = 0;

    // 控制栏自动隐藏
    private final Runnable hideCtrl = new Runnable() {
        @Override public void run() { showControls(false); }
    };

    @Override
    protected void onCreate(Bundle b) {
        super.onCreate(b);
        Config.init(this);
        com.fengxi.fnplayer.core.CrashGuard.install(this);   // v2.19：后台线程崩溃不再闪退
        am = (AudioManager) getSystemService(Context.AUDIO_SERVICE);
        volMax = am.getStreamMaxVolume(AudioManager.STREAM_MUSIC);
        guid = getIntent().getStringExtra("guid");
        itemGuid = getIntent().getStringExtra("item");
        if (itemGuid == null || itemGuid.length() == 0) itemGuid = guid;
        title = getIntent().getStringExtra("title");
        if (title == null) title = "播放";
        resumePos = getIntent().getLongExtra("resumePos", 0);
        autoFloat = "1".equals(getIntent().getStringExtra("autoFloat"));
        // v2.22：双播互斥 —— 浮窗还活着又开新视频 → 先关浮窗
        if (com.fengxi.fnplayer.player.PlaySession.active()) {
            stopService(new Intent(this, FloatVideoService.class));
        }
        if (guid == null || guid.length() == 0) {
            Toast.makeText(this, "缺少影片 ID", Toast.LENGTH_LONG).show();
            finish();
            return;
        }

        // 影视默认横屏全屏（EasyBox 同款），可手动切回竖屏
        setRequestedOrientation(android.content.pm.ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE);
        landscape = true;

        // 屏幕常亮：ijkplayer 的 setScreenOnWhilePlaying 只对 SurfaceHolder 生效，
        // 本项目用 setSurface(Surface)，必须在窗口层加 FLAG_KEEP_SCREEN_ON（否则播一会儿就黑屏）
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);

        // 设置中心：默认画面比例 / 渲染方式
        int dr = Config.defaultRatio();
        if (dr >= 0 && dr < RATIO_NAMES.length) ratioIdx = dr;
        boolean useTexture = Config.textureRender();

        FrameLayout root = new FrameLayout(this);
        root.setBackgroundColor(Color.BLACK);
        root.setClickable(true);

        // 视频容器（比例重排在这里面做）
        videoBox = new FrameLayout(this);
        root.addView(videoBox, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));

        if (useTexture) {
            texView = new TextureView(this);
            texView.setSurfaceTextureListener(new TextureView.SurfaceTextureListener() {
                @Override public void onSurfaceTextureAvailable(android.graphics.SurfaceTexture st,
                                                                int w, int h) {
                    videoSurface = new android.view.Surface(st);
                    videoSurfaceReady();
                }
                @Override public void onSurfaceTextureSizeChanged(android.graphics.SurfaceTexture st,
                                                                  int w, int h) { applyRatio(); }
                @Override public boolean onSurfaceTextureDestroyed(android.graphics.SurfaceTexture st) {
                    surfaceReady = false;
                    videoSurface = null;
                    return true;
                }
                @Override public void onSurfaceTextureUpdated(android.graphics.SurfaceTexture st) {}
            });
            videoBox.addView(texView, new FrameLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT,
                    Gravity.CENTER));
        } else {
            surface = new SurfaceView(this);
            videoBox.addView(surface, new FrameLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT,
                    Gravity.CENTER));
            surface.getHolder().addCallback(this);
        }

        // 中央状态（取链/缓冲/错误）
        state = new TextView(this);
        state.setTextColor(0xFFE0E6F0);
        state.setTextSize(15);
        state.setGravity(Gravity.CENTER);
        state.setPadding(30, 30, 30, 30);
        state.setBackgroundColor(0x66000000);
        root.addView(state, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT,
                Gravity.CENTER));

        // 手势 HUD（亮度/音量/快进提示）
        hud = new TextView(this);
        hud.setTextColor(Color.WHITE);
        hud.setTextSize(18);
        hud.setGravity(Gravity.CENTER);
        hud.setPadding(34, 18, 34, 18);
        GradientDrawable hudBg = new GradientDrawable();
        hudBg.setColor(0xAA000000);
        hudBg.setCornerRadius(22);
        hud.setBackground(hudBg);
        hud.setVisibility(View.GONE);
        root.addView(hud, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT,
                Gravity.CENTER));

        // 解锁按钮（锁屏时点屏出现）
        unlockBtn = mkCtrl("🔓 点击解锁", new View.OnClickListener() {
            @Override public void onClick(View v) {
                locked = false;
                unlockBtn.setVisibility(View.GONE);
                toast("已解锁");
                showControls(true);
            }
        });
        unlockBtn.setVisibility(View.GONE);
        root.addView(unlockBtn, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT,
                Gravity.CENTER));

        buildTopBar(root);
        buildSideBar(root);
        buildBigPlay(root);
        buildBottomBar(root);
        buildSubtitleLayer(root);

        // 手势层：单击/双击/滑动（子控件可点击会优先消费，不冲突）
        final GestureDetector gd = new GestureDetector(this, new Gesture());
        root.setOnTouchListener(new View.OnTouchListener() {
            @Override public boolean onTouch(View v, MotionEvent e) {
                if (e.getAction() == MotionEvent.ACTION_UP || e.getAction() == MotionEvent.ACTION_CANCEL)
                    endGesture();
                return gd.onTouchEvent(e);
            }
        });

        setContentView(root);
        root.post(new Runnable() {
            @Override public void run() { applySafeInsets(); }
        });
        state.setText("正在获取播放地址…");
        showControls(true);
        loadEpisodes();
    }

    // ==================== 外挂字幕（射手网 API） ====================

    /** 字幕渲染层：底部居中、带描边阴影，控制栏隐藏时也保持可见。 */
    private void buildSubtitleLayer(FrameLayout root) {
        subView = new TextView(this);
        subView.setTextColor(Color.WHITE);
        subView.setTextSize(Config.subSize());
        subView.setGravity(Gravity.CENTER);
        subView.setPadding(30, 10, 30, 10);
        subView.setShadowLayer(6, 2, 2, 0xC0000000);
        subView.setVisibility(View.GONE);
        FrameLayout.LayoutParams lp = new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT,
                Gravity.BOTTOM | Gravity.CENTER_HORIZONTAL);
        lp.bottomMargin = 170;
        root.addView(subView, lp);
    }

    /** 「字」按钮面板：匹配 / 开关 / 大小 / 清除。 */
    private void showSubPanel() {
        final boolean has = Subtitle.ready();
        final String[] items = {
                "在线匹配字幕（射手网智能匹配）",
                has ? (subOn ? "字幕：开 → 关" : "字幕：关 → 开") : "字幕：未加载",
                "字幕大小：" + Config.subSize() + " 号（点此切换）",
                has ? "清除当前字幕" : "清除当前字幕（无字幕）"
        };
        new AlertDialog.Builder(this)
                .setTitle("字幕")
                .setItems(items, new DialogInterface.OnClickListener() {
                    @Override public void onClick(DialogInterface d, int which) {
                        if (which == 0) {
                            matchSubtitle();
                        } else if (which == 1) {
                            if (!has) { toast("先「在线匹配字幕」成功后才能开关"); return; }
                            subOn = !subOn;
                            if (!subOn) { subView.setVisibility(View.GONE); subText = ""; }
                            toast(subOn ? "字幕已开启" : "字幕已关闭");
                        } else if (which == 2) {
                            int s = Config.subSize();
                            int ns = s <= 16 ? 20 : (s <= 20 ? 26 : 14);
                            Config.setSubSize(ns);
                            subView.setTextSize(ns);
                            toast("字幕大小：" + ns);
                        } else {
                            Subtitle.clear();
                            subView.setVisibility(View.GONE);
                            subText = "";
                            toast("已清除字幕");
                        }
                    }
                })
                .show();
    }

    /** 后台调射手 API 匹配当前影片的字幕（用播放时的真实直链算文件特征）。 */
    private void matchSubtitle() {
        if (curRealUrl == null || curRealUrl.length() == 0) {
            toast("还没有视频地址，先正常播放");
            return;
        }
        toast("正在匹配字幕…");
        final String name = (title == null || title.length() == 0 ? "movie" : title) + ".mp4";
        final String url = curRealUrl;
        final Map<String, String> hd = curHeaders;
        io.execute(new Runnable() {
            @Override public void run() {
                Subtitle.fetchOnline(url, hd, Config.trustSsl(), name, new Subtitle.Cb() {
                    @Override public void onResult(final boolean ok, final String msg) {
                        runOnUiThread(new Runnable() {
                            @Override public void run() {
                                toast((ok ? "✓ " : "✗ ") + msg);
                            }
                        });
                    }
                });
            }
        });
    }

    // ==================== 安全区（刘海 / 挖孔 / 圆角） ====================

    /**
     * v2.2：按 DisplayCutout 与屏幕圆角半径给控制栏加安全区内边距，
     * 解决「按键溢出屏幕 / 被摄像头遮挡」。横竖屏切换后重算。
     */
    private void applySafeInsets() {
        if (topBar == null || bottomBar == null) return;
        int left = 0, top = 0, right = 0, bottom = 0;
        try {
            if (android.os.Build.VERSION.SDK_INT >= 28) {
                android.view.WindowInsets in = getWindow().getDecorView().getRootWindowInsets();
                if (in != null) {
                    android.view.DisplayCutout dc = in.getDisplayCutout();
                    if (dc != null) {
                        left = Math.max(left, dc.getSafeInsetLeft());
                        top = Math.max(top, dc.getSafeInsetTop());
                        right = Math.max(right, dc.getSafeInsetRight());
                        bottom = Math.max(bottom, dc.getSafeInsetBottom());
                    }
                }
            }
        } catch (Throwable ignored) {}
        // 屏幕圆角：v2.9 用户反馈左右避让太宽 —— 侧向只留圆角的小半份，
        // 顶/底仍按完整刘海与圆角避让
        int corner = 20;
        try {
            if (android.os.Build.VERSION.SDK_INT >= 31) {
                android.view.RoundedCorner rc = getDisplay().getRoundedCorner(
                        android.view.RoundedCorner.POSITION_TOP_LEFT);
                if (rc != null) corner = Math.max(corner, rc.getRadius());
            }
        } catch (Throwable ignored) {}
        int side = Math.max(6, corner / 3);   // 左右：约 1/3 圆角半径（≈8~16px）
        left = Math.max(left, side);
        right = Math.max(right, side);
        top = Math.max(top, corner);
        bottom = Math.max(bottom, corner);

        topBar.setPadding(6 + left, 10 + top, 6 + right, 10);
        bottomBar.setPadding(6 + left, 6, 6 + right, 12 + bottom);
        if (sideBar != null) sideBar.setPadding(6 + left, 8, 6, 8);
        // 字幕层避开底部安全区
        if (subView != null) {
            FrameLayout.LayoutParams lp = (FrameLayout.LayoutParams) subView.getLayoutParams();
            lp.bottomMargin = 170 + bottom;
            subView.setLayoutParams(lp);
        }
    }

    // ==================== 控制栏（参照迅雷播放器布局） ====================

    private void buildTopBar(FrameLayout root) {
        topBar = new LinearLayout(this);
        topBar.setOrientation(LinearLayout.HORIZONTAL);
        topBar.setGravity(Gravity.CENTER_VERTICAL);
        topBar.setPadding(6, 10, 6, 10);
        GradientDrawable bg = new GradientDrawable(GradientDrawable.Orientation.TOP_BOTTOM,
                new int[]{0xD9000000, 0x00000000});
        topBar.setBackground(bg);

        topBar.addView(mkIcon("‹", 26, new View.OnClickListener() {
            @Override public void onClick(View v) { finish(); }
        }));

        titleView = new TextView(this);
        titleView.setText(title);
        titleView.setTextColor(Color.WHITE);
        titleView.setTextSize(17);
        titleView.setSingleLine(true);
        titleView.setPadding(14, 0, 12, 0);
        topBar.addView(titleView, new LinearLayout.LayoutParams(0,
                ViewGroup.LayoutParams.WRAP_CONTENT, 1f));

        // 右侧：字幕 / 画中画 / 悬浮小窗 / 更多（迅雷同款排布）
        topBar.addView(mkText("字", new View.OnClickListener() {
            @Override public void onClick(View v) { showSubPanel(); }
        }));
        topBar.addView(mkText("⧉", new View.OnClickListener() {
            @Override public void onClick(View v) { enterPip(); }
        }));
        // v2.22：缩小为悬浮小窗（车机 Deepal+ 卡片桌面场景）
        topBar.addView(mkText("▣", new View.OnClickListener() {
            @Override public void onClick(View v) { goFloat(); }
        }));
        topBar.addView(mkText("⋯", new View.OnClickListener() {
            @Override public void onClick(View v) { showMoreMenu(); }
        }));

        root.addView(topBar, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT,
                Gravity.TOP));
    }

    /** 左侧竖排：锁屏 / 截图（迅雷同款位置） */
    private void buildSideBar(FrameLayout root) {
        sideBar = new LinearLayout(this);
        sideBar.setOrientation(LinearLayout.VERTICAL);
        sideBar.setGravity(Gravity.CENTER_HORIZONTAL);
        sideBar.setPadding(6, 8, 6, 8);
        sideBar.addView(mkCtrl("🔒", new View.OnClickListener() {
            @Override public void onClick(View v) {
                locked = true;
                showControls(false);
                toast("已锁屏，点屏幕出现解锁按钮");
            }
        }));
        sideBar.addView(mkCtrl("📷", new View.OnClickListener() {
            @Override public void onClick(View v) { screenshot(); }
        }));
        root.addView(sideBar, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT,
                Gravity.START | Gravity.CENTER_VERTICAL));
    }

    /** 中央大播放键（暂停时显示；参考图样式：纯三角无圆底） */
    private void buildBigPlay(FrameLayout root) {
        bigPlay = new TextView(this);
        bigPlay.setText("▶");
        bigPlay.setTextColor(Color.WHITE);
        bigPlay.setTextSize(52);
        bigPlay.setGravity(Gravity.CENTER);
        bigPlay.setPadding(0, 0, 12, 12);
        bigPlay.setBackground(null);
        bigPlay.setClickable(true);
        bigPlay.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) { togglePlay(); }
        });
        bigPlay.setVisibility(View.GONE);
        root.addView(bigPlay, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT,
                Gravity.CENTER));
    }

    private void buildBottomBar(FrameLayout root) {
        bottomBar = new LinearLayout(this);
        bottomBar.setOrientation(LinearLayout.VERTICAL);
        bottomBar.setPadding(6, 6, 6, 12);
        GradientDrawable bg = new GradientDrawable(GradientDrawable.Orientation.BOTTOM_TOP,
                new int[]{0xD9000000, 0x00000000});
        bottomBar.setBackground(bg);

        // 第一行：当前时间 — 细进度条 — 总时长
        LinearLayout row1 = new LinearLayout(this);
        row1.setOrientation(LinearLayout.HORIZONTAL);
        row1.setGravity(Gravity.CENTER_VERTICAL);

        timeView = new TextView(this);
        timeView.setText("00:00");
        timeView.setTextColor(0xFFCCCCCC);
        timeView.setTextSize(14);
        row1.addView(timeView);

        seek = new SeekBar(this);
        seek.setMax(1000);
        applyThinSeekStyle(seek);
        row1.addView(seek, new LinearLayout.LayoutParams(0, dp(24), 1f));

        durView = new TextView(this);
        durView.setText("00:00");
        durView.setTextColor(0xFFCCCCCC);
        durView.setTextSize(14);
        row1.addView(durView);
        bottomBar.addView(row1);

        // 第二行：左 播放/下一集；右 去黑边/倍速/音效/播放列表（迅雷同款文字功能键）
        LinearLayout row2 = new LinearLayout(this);
        row2.setOrientation(LinearLayout.HORIZONTAL);
        row2.setGravity(Gravity.CENTER_VERTICAL);

        playBtn = mkIcon("▶", 20, new View.OnClickListener() {
            @Override public void onClick(View v) { togglePlay(); }
        });
        row2.addView(playBtn);

        nextBtn = mkIcon("⏭", 18, new View.OnClickListener() {
            @Override public void onClick(View v) { playEpisode(epIndex + 1); }
        });
        row2.addView(nextBtn);

        // 中间弹性撑开：功能键靠右（迅雷同款）
        View spacer = new View(this);
        row2.addView(spacer, new LinearLayout.LayoutParams(0, 1, 1f));

        blackBtn = mkText("智能去黑边", new View.OnClickListener() {
            @Override public void onClick(View v) {
                // 近似「智能去黑边」：适配(完整画面有黑边) ↔ 填充裁剪(铺满无黑边)
                ratioIdx = (ratioIdx == 3) ? 0 : 3;
                applyRatio();
                toast(ratioIdx == 3 ? "去黑边：裁剪铺满（可能裁掉画面边缘）" : "去黑边：关闭（完整画面）");
                autoHide();
            }
        });
        row2.addView(blackBtn);

        speedBtn = mkText("倍速", new View.OnClickListener() {
            @Override public void onClick(View v) { showSpeedPanel(); }
        });
        row2.addView(speedBtn);

        effBtn = mkText("音效", new View.OnClickListener() {
            @Override public void onClick(View v) { showEffPanel(); }
        });
        row2.addView(effBtn);

        listBtn = mkText("播放列表", new View.OnClickListener() {
            @Override public void onClick(View v) { pickEpisode(); }
        });
        row2.addView(listBtn);

        bottomBar.addView(row2);

        root.addView(bottomBar, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT,
                Gravity.BOTTOM));

        seek.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            @Override public void onProgressChanged(SeekBar s, int p, boolean fromUser) {}
            @Override public void onStartTrackingTouch(SeekBar s) { seeking = true; }
            @Override public void onStopTrackingTouch(SeekBar s) {
                seeking = false;
                if (player != null && player.durMs() > 0)
                    player.seekTo((long) (player.durMs() * s.getProgress() / 1000.0));
                autoHide();
            }
        });
    }

    /** 图标键：可指定字号（返回箭头 / 播放 / 下一集等大图标用）。 */
    private TextView mkIcon(String t, float sizeSp, View.OnClickListener l) {
        TextView v = new TextView(this);
        v.setText(t);
        v.setTextColor(Color.WHITE);
        v.setTextSize(sizeSp);
        v.setGravity(Gravity.CENTER);
        v.setPadding(12, 8, 12, 8);
        v.setClickable(true);
        if (l != null) v.setOnClickListener(l);
        return v;
    }

    /** 细进度条样式（参考迅雷：白色细轨 + 圆点滑块），替换系统默认的粗橙条。 */
    private void applyThinSeekStyle(SeekBar s) {
        int track = dp(4);
        GradientDrawable bg = new GradientDrawable();
        bg.setColor(0x50FFFFFF);
        bg.setCornerRadius(track / 2f);
        GradientDrawable fg = new GradientDrawable();
        fg.setColor(0xFFFFFFFF);
        fg.setCornerRadius(track / 2f);
        android.graphics.drawable.ClipDrawable clip = new android.graphics.drawable.ClipDrawable(
                fg, Gravity.LEFT, android.graphics.drawable.ClipDrawable.HORIZONTAL);
        android.graphics.drawable.LayerDrawable ld = new android.graphics.drawable.LayerDrawable(
                new android.graphics.drawable.Drawable[]{bg, clip});
        int inset = (dp(24) - track) / 2;
        ld.setLayerInset(0, 0, inset, 0, inset);
        ld.setLayerInset(1, 0, inset, 0, inset);
        s.setProgressDrawable(ld);
        GradientDrawable thumb = new GradientDrawable();
        thumb.setColor(Color.WHITE);
        thumb.setShape(GradientDrawable.OVAL);
        thumb.setSize(dp(14), dp(14));
        s.setThumb(thumb);
        s.setThumbOffset(0);
        s.setPadding(0, 0, 0, 0);
        s.setMinimumHeight(dp(24));
    }

    private int dp(int v) {
        return Math.round(v * getResources().getDisplayMetrics().density);
    }

    /** 底部文字功能键（迅雷样式：纯文字无底），v2.2 放大一档字号便于看清。 */
    private TextView mkText(String t, View.OnClickListener l) {
        TextView v = new TextView(this);
        v.setText(t);
        v.setTextColor(Color.WHITE);
        v.setTextSize(16);
        v.setGravity(Gravity.CENTER);
        v.setPadding(14, 12, 14, 12);
        v.setClickable(true);
        if (l != null) v.setOnClickListener(l);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        v.setLayoutParams(lp);
        return v;
    }

    /** 「⋯」更多菜单：比例 / 竖屏全屏 / 清晰度（锁屏在左侧栏） */
    private void showMoreMenu() {
        final String[] items = {
                "画面比例：" + RATIO_NAMES[ratioIdx],
                landscape ? "切换竖屏" : "切换全屏",
                "清晰度",
                "选集"
        };
        new AlertDialog.Builder(this)
                .setTitle(title)
                .setItems(items, new DialogInterface.OnClickListener() {
                    @Override public void onClick(DialogInterface d, int which) {
                        if (which == 0) {
                            ratioIdx = (ratioIdx + 1) % RATIO_NAMES.length;
                            applyRatio();
                            toast("画面比例：" + RATIO_NAMES[ratioIdx]);
                        } else if (which == 1) {
                            landscape = !landscape;
                            setRequestedOrientation(landscape
                                    ? android.content.pm.ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE
                                    : android.content.pm.ActivityInfo.SCREEN_ORIENTATION_PORTRAIT);
                            videoBox.post(new Runnable() {
                                @Override public void run() { applyRatio(); }
                            });
                        } else if (which == 2) {
                            pickQuality();
                        } else {
                            pickEpisode();
                        }
                    }
                })
                .show();
    }

    /** 倍速面板（迅雷同款列表选择） */
    private void showSpeedPanel() {
        final String[] names = new String[SPEEDS.length];
        int cur = 0;
        for (int i = 0; i < SPEEDS.length; i++) {
            names[i] = SPEEDS[i] + "x";
            if (i == speedIdx) cur = i;
        }
        new AlertDialog.Builder(this)
                .setTitle("倍速")
                .setSingleChoiceItems(names, cur, new DialogInterface.OnClickListener() {
                    @Override public void onClick(DialogInterface d, int which) {
                        speedIdx = which;
                        if (player != null) player.setSpeed(SPEEDS[speedIdx]);
                        d.dismiss();
                    }
                })
                .show();
    }

    /** 音效面板：播放器音量增益（不影响系统音量） */
    private void showEffPanel() {
        final float[] gains = {1f, 1.5f, 2f};
        final String[] names = {"原声 100%", "增强 150%", "增强 200%"};
        int cur = 0;
        for (int i = 0; i < gains.length; i++) if (gains[i] == volumeGain) cur = i;
        new AlertDialog.Builder(this)
                .setTitle("音效")
                .setSingleChoiceItems(names, cur, new DialogInterface.OnClickListener() {
                    @Override public void onClick(DialogInterface d, int which) {
                        volumeGain = gains[which];
                        if (player != null) player.setVolume(volumeGain);
                        toast("音效：" + names[which]);
                        d.dismiss();
                    }
                })
                .show();
    }

    /** 截图：TextureView 渲染时直接取帧保存到 App 图片目录 */
    private void screenshot() {
        try {
            if (texView == null) {
                toast("截图需在设置中心把渲染方式切到 TextureView");
                return;
            }
            android.graphics.Bitmap bmp = texView.getBitmap();
            if (bmp == null) { toast("还没画面可截"); return; }
            java.io.File dir = new java.io.File(getExternalFilesDir(null), "Screenshots");
            dir.mkdirs();
            java.io.File f = new java.io.File(dir, "fx_" + System.currentTimeMillis() + ".jpg");
            java.io.FileOutputStream fo = new java.io.FileOutputStream(f);
            bmp.compress(android.graphics.Bitmap.CompressFormat.JPEG, 90, fo);
            fo.close();
            toast("截图已保存到 App 图片目录");
        } catch (Throwable t) {
            toast("截图失败：" + Utils.brief(t));
        }
    }

    /** 画中画（Android 8.0+） */
    private void enterPip() {
        if (android.os.Build.VERSION.SDK_INT < 26) { toast("画中画需要 Android 8.0 以上"); return; }
        try {
            float ar = (videoW > 0 && videoH > 0) ? videoW / (float) videoH : 16f / 9f;
            android.app.PictureInPictureParams p =
                    new android.app.PictureInPictureParams.Builder()
                            .setAspectRatio(new android.util.Rational(
                                    Math.max(4, Math.min(239, Math.round(ar * 100))), 100))
                            .build();
            enterPictureInPictureMode(p);
        } catch (Throwable t) {
            toast("画中画不可用：" + Utils.brief(t));
        }
    }

    /** 播放状态同步到底部播放键与中央大按钮。
     *  v2.20.1：缓冲/起播阶段 isPlaying()=false，旧版会误显中央大三角，
     *  改为只在用户主动暂停（userPaused）时显示。 */
    private void syncPlayUi() {
        boolean playing = player != null && player.isPlaying();
        playBtn.setText(playing ? "❚❚" : "▶");
        bigPlay.setVisibility(userPaused && !playing ? View.VISIBLE : View.GONE);
    }

    /** 圆角半透明控制按钮（替换系统 Button 的默认样式），v2.2 放大一档。 */
    private TextView mkCtrl(String t, View.OnClickListener l) {
        TextView v = new TextView(this);
        v.setText(t);
        v.setTextColor(Color.WHITE);
        v.setTextSize(16);
        v.setGravity(Gravity.CENTER);
        v.setPadding(24, 12, 24, 12);
        GradientDrawable gd = new GradientDrawable();
        gd.setColor(0x52000000);          // 深色半透明圆角底（参考迅雷左侧图标样式）
        gd.setCornerRadius(20);
        v.setBackground(gd);
        v.setClickable(true);
        if (l != null) v.setOnClickListener(l);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.setMargins(6, 0, 6, 0);
        v.setLayoutParams(lp);
        return v;
    }

    private void showControls(boolean on) {
        controlsOn = on;
        topBar.setVisibility(on ? View.VISIBLE : View.GONE);
        bottomBar.setVisibility(on ? View.VISIBLE : View.GONE);
        if (sideBar != null) sideBar.setVisibility(on ? View.VISIBLE : View.GONE);
        if (on) autoHide();
        else ui.removeCallbacks(hideCtrl);
    }

    private void toggleControls() {
        showControls(!controlsOn);
    }

    private void autoHide() {
        ui.removeCallbacks(hideCtrl);
        ui.postDelayed(hideCtrl, 4000);
    }

    private void toast(String s) {
        Toast.makeText(this, s, Toast.LENGTH_SHORT).show();
    }

    // ==================== 手势 ====================

    private class Gesture extends GestureDetector.SimpleOnGestureListener {
        @Override public boolean onDown(MotionEvent e) {
            gestureMode = 0;
            return true;
        }

        @Override public boolean onSingleTapConfirmed(MotionEvent e) {
            if (locked) {
                unlockBtn.setVisibility(unlockBtn.getVisibility() == View.VISIBLE
                        ? View.GONE : View.VISIBLE);
                return true;
            }
            toggleControls();
            return true;
        }

        @Override public boolean onDoubleTap(MotionEvent e) {
            if (!locked) togglePlay();
            return true;
        }

        @Override public void onLongPress(MotionEvent e) {
            if (locked || player == null) return;
            float sp = Config.longPressSpeed();
            boost3x = true;
            player.setSpeed(sp);
            showHud("⏩ " + sp + "x 倍速（松开恢复）");
        }

        @Override public boolean onScroll(MotionEvent e1, MotionEvent e2,
                                          float dx, float dy) {
            if (locked || player == null || player.durMs() <= 0) return true;
            if (gestureMode == 0) {
                float ddx = Math.abs(e2.getX() - gestureStartX);
                float ddy = Math.abs(e2.getY() - gestureStartY);
                if (ddx < 24 && ddy < 24) return true;
                gestureMode = ddx > ddy ? 1 : (e2.getX() < videoBox.getWidth() / 2f ? 2 : 3);
                if (gestureMode == 1) gestureBasePos = player.curMs();
                if (gestureMode == 2) gestureStartBright = currentBrightness();
                if (gestureMode == 3) gestureStartVol = am.getStreamVolume(AudioManager.STREAM_MUSIC);
            }
            if (gestureMode == 1) {
                long dur = player.durMs();
                long delta = (long) ((e2.getX() - gestureStartX) / videoBox.getWidth() * 100000f);
                long target = Math.max(0, Math.min(dur - 1000, gestureBasePos + delta));
                String sign = delta >= 0 ? "+" : "-";
                showHud("⏩ " + sign + fmt(Math.abs(delta)) + "\n" + fmt(target) + " / " + fmt(dur));
                gestureTarget = target;
            } else if (gestureMode == 2) {
                float d = -(e2.getY() - gestureStartY) / videoBox.getHeight();
                float nb = Math.max(0.01f, Math.min(1f, gestureStartBright + d));
                WindowManager.LayoutParams lp = getWindow().getAttributes();
                lp.screenBrightness = nb;
                getWindow().setAttributes(lp);
                showHud("☀ 亮度 " + Math.round(nb * 100) + "%");
            } else if (gestureMode == 3) {
                int d = Math.round(-(e2.getY() - gestureStartY) / videoBox.getHeight() * volMax);
                int nv = Math.max(0, Math.min(volMax, gestureStartVol + d));
                am.setStreamVolume(AudioManager.STREAM_MUSIC, nv, 0);
                showHud("🔊 音量 " + nv * 100 / volMax + "%");
            }
            return true;
        }
    }

    private long gestureTarget = -1;

    private void endGesture() {
        if (boost3x) {
            boost3x = false;
            if (player != null) player.setSpeed(SPEEDS[speedIdx]);
        }
        if (gestureMode == 1 && gestureTarget >= 0 && player != null) {
            player.seekTo(gestureTarget);
            gestureTarget = -1;
        }
        gestureMode = 0;
        ui.postDelayed(hideHud, 700);
    }

    private final Runnable hideHud = new Runnable() {
        @Override public void run() { hud.setVisibility(View.GONE); }
    };

    private void showHud(String s) {
        hud.setText(s);
        hud.setVisibility(View.VISIBLE);
        ui.removeCallbacks(hideHud);
    }

    private float currentBrightness() {
        WindowManager.LayoutParams lp = getWindow().getAttributes();
        return lp.screenBrightness < 0 ? 0.5f : lp.screenBrightness;
    }

    private void togglePlay() {
        if (player == null) return;
        if (player.isPlaying()) {
            userPaused = true;
            player.pause();
        } else {
            userPaused = false;
            player.play();
        }
        syncPlayUi();
        autoHide();
    }

    // ==================== 比例 ====================

    private void applyRatio() {
        if (videoBox == null || videoBox.getWidth() == 0) return;
        int bw = videoBox.getWidth(), bh = videoBox.getHeight();
        float videoAR = (videoW > 0 && videoH > 0) ? videoW / (float) videoH : 16f / 9f;
        float boxAR = bw / (float) bh;
        int w, h;
        if (ratioIdx == 3) {                     // 填充（拉伸铺满）
            w = bw; h = bh;
        } else {
            float use = videoAR;
            if (ratioIdx == 1) use = 16f / 9f;
            else if (ratioIdx == 2) use = 4f / 3f;
            if (use > boxAR) { w = bw; h = (int) (bw / use); }
            else { h = bh; w = (int) (bh * use); }
        }
        View rv = texView != null ? texView : surface;
        if (rv != null) rv.setLayoutParams(new FrameLayout.LayoutParams(w, h, Gravity.CENTER));
    }

    // ==================== Surface ====================

    @Override public void surfaceCreated(SurfaceHolder h) {
        videoSurface = h.getSurface();
        videoSurfaceReady();
    }

    @Override public void surfaceChanged(SurfaceHolder h, int f, int w, int hh) {
        applyRatio();
    }

    @Override public void surfaceDestroyed(SurfaceHolder h) {
        surfaceReady = false;
        videoSurface = null;
    }

    /** SurfaceView / TextureView 任一就绪后从这里进播放流程。 */
    private void videoSurfaceReady() {
        surfaceReady = true;
        prepareAndPlay();
    }

    private void prepareAndPlay() {
        if (started || !surfaceReady) return;
        started = true;
        userPaused = false;   // v2.20.1：换集重置，避免缓冲期误显大三角
        // v2.15/v2.16：源没解出可播放地址时给可读提示，别再拿影片 id 去走飞牛取链
        boolean knownProto = guid != null && (guid.startsWith("direct:")
                || guid.startsWith("spp:") || guid.startsWith("jssrc|"));
        JSONObject curSrc = com.fengxi.fnplayer.Bridge.currentSourceObj();
        String srcType = curSrc == null ? "nas" : curSrc.optString("type", "nas");
        boolean nasSrc = "nas".equals(srcType) || "fnos".equals(srcType);
        // 非 NAS 源、或没有合法 NAS 地址时，任何未知 guid 进 FnClient 都会拼出
        // "csp_XXX/v/... (no protocol)" 或返回 code=1 数据列表。直接拦下给可读提示。
        if (!knownProto && ((guid != null
                && (guid.startsWith("spid|") || guid.startsWith("nosrc:")))
                || !nasSrc || !Config.base().startsWith("http"))) {
            state.setVisibility(View.VISIBLE);
            state.setText("当前源没有返回可播放的剧集地址\n"
                    + "（影片需由所选线路自身解析）\n请切换线路或换一个源后重试");
            return;
        }
        // v2.3 直链模式：直播频道 / cms 资源站的 direct: 集直链，不走飞牛取链
        if (guid != null && guid.startsWith("direct:")) {
            final String url = guid.substring("direct:".length());
            directMode = true;
            final boolean live = "1".equals(getIntent().getStringExtra("live"))
                    || getIntent().getBooleanExtra("live", false);
            io.execute(new Runnable() {
                @Override public void run() {
                    // v2.19：整段兜底。probeUrl / LocalProxy 对畸形直链抛异常时
                    // 旧版直接炸掉整个进程（点击闪退的主因之一）
                    try {
                        // 非 http(s) 协议（udp: 等）跳过 Range 探测，直接交给播放器
                        String probe = "HTTP 200";
                        if (url.startsWith("http")) probe = probeUrl(url, null);
                        String play = url;
                        // ★ ijkplayer 的 ffmpeg 未编译 openssl，https 一律走本地代理
                        //   （Java 层 https 拉流，ijkplayer 只碰 http://127.0.0.1）
                        if (play.startsWith("https")) {
                            String local = LocalProxy.serve(play, null, Config.trustSsl());
                            if (local != null) play = local;
                        }
                        final String fPlay = play;
                        final String fProbe = probe;
                        runOnUiThread(new Runnable() {
                            @Override public void run() {
                                if (isFinishing()) return;
                                boolean ok = fProbe.startsWith("HTTP 2") || fProbe.startsWith("HTTP 3")
                                        || fProbe.startsWith("HTTP 416");
                                if (!ok) {
                                    started = false;
                                    state.setVisibility(View.VISIBLE);
                                    state.setText("直链探测失败：" + fProbe + "\n地址 " + url);
                                    return;
                                }
                                curRealUrl = url;
                                curHeaders = null;
                                startPlayer(fPlay, null, live);
                            }
                        });
                    } catch (final Throwable t) {
                        runOnUiThread(new Runnable() {
                            @Override public void run() {
                                started = false;
                                state.setVisibility(View.VISIBLE);
                                state.setText("播放地址处理失败：" + t);
                            }
                        });
                    }
                }
            });
            return;
        }
        // v2.13：TVBox 爬虫源需要解析的集（guid = spp:urlencode(flag$id)）
        // → playerContent 拿真实地址 →（parse=1 时嗅探解析页）→ 探测 → 播放
        if (guid.startsWith("spp:")) {
            directMode = true;
            io.execute(new Runnable() {
                @Override public void run() {
                    String err = "";
                    String url = "";
                    Map<String, String> hd = null;
                    try {
                        String dec = java.net.URLDecoder.decode(guid.substring(4), "UTF-8");
                        int d = dec.indexOf('$');
                        String flag = d > 0 ? dec.substring(0, d) : "";
                        String eid = d > 0 ? dec.substring(d + 1) : dec;
                        JSONObject src = com.fengxi.fnplayer.spider.SpiderRuntime.currentSpiderSource();
                        if (src == null) err = "当前源不是爬虫源";
                        else {
                            com.github.catvod.crawler.Spider sp =
                                    com.fengxi.fnplayer.spider.SpiderRuntime.get(getApplicationContext(),
                                            src.optString("jar", ""), src.optString("api", ""),
                                            src.optString("ext", ""));
                            // v2.20：部分源 playerContent 返回空串/非 JSON（旧版直接
                            // new JSONObject("") 抛 End of input at character 0）
                            String raw = "";
                            try { raw = sp.playerContent(flag, eid,
                                    new java.util.ArrayList<String>()); }
                            catch (Throwable t1) { throw t1; }
                            if (raw == null || raw.trim().length() == 0)
                                err = "该源没有返回播放地址（接口返回为空）\n源：" + src.optString("name", "")
                                        + "，请换一个源或线路";
                            else {
                            JSONObject o = new JSONObject(raw);
                            url = o.optString("url", "").trim();
                            if (url.length() == 0) url = o.optString("playUrl", "").trim();
                            if (url.length() == 0) url = o.optString("play_url", "").trim();
                            hd = com.fengxi.fnplayer.spider.SpiderRuntime.headerMap(
                                    o.optJSONObject("header"));
                            if (url.length() == 0) {
                                err = "playerContent 没有返回播放地址\n返回内容：" + o;
                            } else if (o.optInt("parse", 0) == 1) {
                                // 解析页：请求一次并嗅探 m3u8/mp4 直链
                                String real = com.fengxi.fnplayer.spider.SpiderRuntime.sniff(url, hd);
                                if (real.length() == 0)
                                    err = "该集需要网页解析（parse=1），嗅探未拿到直链\n解析页：" + url;
                                else url = real;
                            }
                            }
                        }
                    } catch (Throwable t) {
                        String why = String.valueOf(t);
                        // JSONException: End of input at character 0 of —— 空返回被
                        // 当 JSON 解析，翻译成可读提示
                        if (t instanceof org.json.JSONException && why.contains("End of input at character 0"))
                            err = "该源没有返回播放地址（接口返回为空），请换一个源或线路";
                        else
                            err = "取链失败：" + Utils.brief(t);
                    }
                    final String fUrl = url, fErr = err;
                    final Map<String, String> fHd = hd;
                    // v2.20：探测与本地代理必须在后台线程完成——旧版放在
                    // runOnUiThread 里发网络请求，直接抛 NetworkOnMainThreadException
                    String probeB = "HTTP 200";
                    String playB = fUrl;
                    if (playB.startsWith("http")) probeB = probeUrl(playB, fHd);
                    if (playB.startsWith("https")) {
                        String local = LocalProxy.serve(playB, fHd, Config.trustSsl());
                        if (local != null) playB = local;
                    }
                    final String fPlay = playB;
                    final String fProbe = probeB;
                    runOnUiThread(new Runnable() {
                        @Override public void run() {
                            if (isFinishing()) return;
                            if (fUrl.length() == 0) {
                                started = false;
                                state.setVisibility(View.VISIBLE);
                                state.setText(fErr);
                                return;
                            }
                            boolean ok = fProbe.startsWith("HTTP 2") || fProbe.startsWith("HTTP 3")
                                    || fProbe.startsWith("HTTP 416");
                            if (!ok) {
                                started = false;
                                state.setVisibility(View.VISIBLE);
                                state.setText("直链探测失败：" + fProbe + "\n地址 " + fUrl);
                                return;
                            }
                            curRealUrl = fUrl;
                            curHeaders = fHd;
                            startPlayer(fPlay, fHd, false);
                        }
                    });
                }
            });
            return;
        }
        io.execute(new Runnable() {
            @Override public void run() {
                try {
                    final FnClient c = new FnClient();
                    final String real = c.playUrl(guid, curQuality);
                    final Map<String, String> h = c.playHeaders();
                    final String local = LocalProxy.serve(real, h, Config.trustSsl());
                    final String play = local != null ? local : real;
                    curRealUrl = real;               // 字幕匹配要用真实直链 + 鉴权头
                    curHeaders = h;
                    // 播前探测：用 Range 请求拿真实 HTTP 状态，失败当场给出原因
                    final String probe = probeUrl(real, h);
                    runOnUiThread(new Runnable() {
                        @Override public void run() {
                            if (isFinishing()) return;
                            playingLocalUrl = local;
                            boolean ok = probe.startsWith("HTTP 2") || probe.startsWith("HTTP 3")
                                    || probe.startsWith("HTTP 416");
                            if (!ok) {
                                started = false;
                                state.setVisibility(View.VISIBLE);
                                state.setText("取链探测失败：" + probe + "\n地址 " + real
                                        + "\n【取链过程】\n" + FnClient.lastPlayTrail);
                                return;
                            }
                            startPlayer(play, local == null ? h : null);
                        }
                    });
                } catch (Throwable t) {
                    runOnUiThread(new Runnable() {
                        @Override public void run() {
                            state.setVisibility(View.VISIBLE);
                            state.setText("取链失败：" + Utils.brief(t)
                                    + "\n【取链过程】\n" + FnClient.lastPlayTrail);
                            started = false;
                        }
                    });
                }
            }
        });
    }

    /** Range 探测播放直链，返回「HTTP 状态码」或异常摘要。 */
    private static String probeUrl(String url, Map<String, String> h) {
        javax.net.ssl.HttpsURLConnection hs = null;
        HttpURLConnection c = null;
        try {
            c = (HttpURLConnection) new java.net.URL(url).openConnection();
            c.setConnectTimeout(6000);
            c.setReadTimeout(6000);
            c.setRequestMethod("GET");
            c.setRequestProperty("Range", "bytes=0-1");
            c.setRequestProperty("User-Agent", Net.UA);
            if (h != null) for (Map.Entry<String, String> e : h.entrySet())
                c.setRequestProperty(e.getKey(), e.getValue());
            if (c instanceof javax.net.ssl.HttpsURLConnection && Config.trustSsl()) {
                hs = (javax.net.ssl.HttpsURLConnection) c;
                Net.applyTrustAll(hs);
            }
            int code = c.getResponseCode();
            return "HTTP " + code;
        } catch (Throwable t) {
            return Utils.brief(t);
        } finally {
            if (c != null) try { c.disconnect(); } catch (Throwable ignored) {}
        }
    }

    private void startPlayer(String url, Map<String, String> headers) {
        startPlayer(url, headers, false);
    }

    /** live=true：直播流低延迟选项（小 probe、禁缓冲，m3u8/ts 拉流更快出画面）。 */
    private void startPlayer(String url, Map<String, String> headers, boolean live) {
        try {
            // v2.22：记录起播地址（悬浮小窗移交失败时兜底重建用）
            currentUrl = url == null ? "" : url;
            currentHeaders = headers;
            currentLive = live;
            if (player != null) player.release();
            player = new IjkHolder();
            player.init(this, videoSurface);
            if (softDecoded || Config.preferSoft()) player.useSoftDecoder();   // 重连时保持软解设置
            if (live) player.setLiveMode();
            if (SPEEDS[speedIdx] != 1f) player.setSpeed(SPEEDS[speedIdx]);
            player.setListener(this);
            if (!directMode) Config.pushHistory(guid, title);   // 播放历史（直链不入）
            state.setVisibility(View.VISIBLE);
            state.setText("缓冲中…");
            player.setDataSource(url, headers == null
                    ? new java.util.HashMap<String, String>() : headers);
        } catch (Throwable t) {
            state.setText("播放器初始化失败：" + Utils.brief(t));
            started = false;
        }
    }

    // ==================== 播放回调 ====================

    @Override public void onPrepared() {
        state.setVisibility(View.GONE);
        // v2.22：浮窗回全屏续播
        if (resumePos > 0 && player != null) {
            player.seekTo(resumePos);
            resumePos = 0;
        }
        syncPlayUi();
        ui.post(progressTick);
        if (autoFloat) { autoFloat = false; goFloat(); }
    }

    @Override public void onEnded() {
        state.setVisibility(View.VISIBLE);
        state.setText("播放结束");
        syncPlayUi();
        // 自动连播（设置中心可关）
        if (Config.autoNext()) playEpisode(epIndex + 1);
    }

    @Override public void onVideoSize(int w, int h) {
        if (w > 0 && h > 0) {
            videoW = w;
            videoH = h;
        }
        applyRatio();
    }

    @Override public void onError(final int what, final int extra) {
        runOnUiThread(new Runnable() {
            @Override public void run() {
                // 首次播放错误一律先切软解重连一次（硬解花屏/不兼容最常见），
                // prepareAndPlay 会重新取链（NAS 链接带时效 token，重取即刷新）
                if (!softDecoded) {
                    softDecoded = true;
                    state.setVisibility(View.VISIBLE);
                    state.setText("播放出错，切换软解并重新取链…");
                    started = false;
                    prepareAndPlay();
                    return;
                }
                // v2.20.1：第二次仍失败，给可定位的信息（地址+是否走代理+建议）
                String u = curRealUrl == null ? "" : curRealUrl;
                String host = u;
                try {
                    java.net.URL uu = new java.net.URL(u);
                    host = uu.getHost() + (uu.getPort() > 0 ? ":" + uu.getPort() : "");
                } catch (Throwable ignored) {}
                boolean viaProxy = u.startsWith("http://127.0.0.1");
                state.setVisibility(View.VISIBLE);
                state.setText("播放错误 what=" + what + " extra=" + extra
                        + "\n地址主机：" + host + (viaProxy ? "（经本地代理，已带鉴权头）" : "（直连）")
                        + "\n常见原因：链接时效已过 / 码率过大解码失败 / 网络波动"
                        + "\n建议：返回后重新点开本集（会重新取链），或换一个清晰度/线路"
                        + "\n【取链过程】\n" + FnClient.lastPlayTrail);
                started = false;
            }
        });
    }

    @Override public void onInfo(int what, int extra) {
        if (what == 701) {
            state.setVisibility(View.VISIBLE);
            state.setText("缓冲中…");           // MEDIA_INFO_BUFFERING_START
        } else if (what == 702) {
            state.setVisibility(View.GONE);     // MEDIA_INFO_BUFFERING_END
        }
    }

    private final Runnable progressTick = new Runnable() {
        @Override public void run() {
            if (player != null && !seeking) {
                long d = player.durMs(), p = player.curMs();
                if (d > 0) seek.setProgress((int) (p * 1000 / d));
                timeView.setText(fmt(p));
                durView.setText(fmt(d));
            }
            // 外挂字幕同步渲染（二分查当前时间轴事件）
            if (subOn && Subtitle.ready() && player != null) {
                String t = Subtitle.textAt(player.curMs());
                if (!t.equals(subText)) {
                    subText = t;
                    if (t.length() == 0) subView.setVisibility(View.GONE);
                    else { subView.setText(t); subView.setVisibility(View.VISIBLE); }
                }
            }
            ui.postDelayed(this, 500);
        }
    };

    private static String fmt(long ms) {
        long s = ms / 1000;
        long hh = s / 3600, mm = (s / 60) % 60, ss = s % 60;
        return hh > 0
                ? String.format("%d:%02d:%02d", hh, mm, ss)
                : String.format("%02d:%02d", mm, ss);
    }

    // ==================== 选集 / 清晰度 ====================

    /** 后台预取选集（下一集 / 选集弹窗用）；拿不到就隐藏「下一集」。 */
    private void loadEpisodes() {
        io.execute(new Runnable() {
            @Override public void run() {
                // v2.15：先按当前源类型取选集（CMS / 爬虫源），拿不到再回退飞牛
                JSONArray list = com.fengxi.fnplayer.Bridge.episodesFor(
                        getApplicationContext(), itemGuid);
                if (list == null || list.length() == 0) {
                    list = com.fengxi.fnplayer.Bridge.episodesFor(getApplicationContext(), guid);
                }
                if (list == null || list.length() == 0) {
                    final FnClient.Result r = new FnClient().episodes(itemGuid);
                    list = r.ok && r.list != null ? r.list : null;
                    if ((list == null || list.length() == 0) && !itemGuid.equals(guid)) {
                        FnClient.Result r2 = new FnClient().episodes(guid);
                        if (r2.ok && r2.list != null && r2.list.length() > 0) list = r2.list;
                    }
                }
                final JSONArray got = list;
                runOnUiThread(new Runnable() {
                    @Override public void run() {
                        if (isFinishing()) return;
                        epList = got;
                        epIndex = indexOfEp(guid);
                        nextBtn.setVisibility(
                                epList != null && epIndex >= 0 && epIndex + 1 < epList.length()
                                        ? View.VISIBLE : View.GONE);
                    }
                });
            }
        });
    }

    private int indexOfEp(String g) {
        if (epList == null || g == null) return -1;
        for (int i = 0; i < epList.length(); i++) {
            JSONObject o = epList.optJSONObject(i);
            if (o != null && g.equals(o.optString("guid", o.optString("item_guid", ""))))
                return i;
        }
        return -1;
    }

    private void playEpisode(final int idx) {
        if (epList == null || idx < 0 || idx >= epList.length()) {
            if (idx >= 0) toast("没有更多集了");
            return;
        }
        JSONObject o = epList.optJSONObject(idx);
        final String g = o == null ? guid : o.optString("guid", guid);
        String n = o == null ? "" : o.optString("title", o.optString("name", ""));
        final String t = n.length() > 0 ? n : "第 " + (idx + 1) + " 集";
        runOnUiThread(new Runnable() {
            @Override public void run() { switchTo(g, t, idx); }
        });
    }

    private void pickEpisode() {
        io.execute(new Runnable() {
            @Override public void run() {
                JSONArray list = epList;
                if (list == null || list.length() == 0) {
                    list = com.fengxi.fnplayer.Bridge.episodesFor(getApplicationContext(), itemGuid);
                }
                if (list == null || list.length() == 0) {
                    FnClient.Result r = new FnClient().episodes(itemGuid);
                    if (r.ok && r.list != null && r.list.length() > 0) list = r.list;
                }
                final JSONArray got = list;
                runOnUiThread(new Runnable() {
                    @Override public void run() {
                        if (isFinishing()) return;
                        if (got == null || got.length() == 0) {
                            toast("这部影片没有选集数据");
                            return;
                        }
                        final String[] names = new String[got.length()];
                        final String[] guids = new String[got.length()];
                        for (int i = 0; i < got.length(); i++) {
                            JSONObject o = got.optJSONObject(i);
                            guids[i] = o == null ? guid
                                    : o.optString("guid", o.optString("item_guid", guid));
                            String n = o == null ? "" : o.optString("title", o.optString("name", ""));
                            names[i] = n.length() > 0 ? n : "第 " + (i + 1) + " 集";
                        }
                        new AlertDialog.Builder(VideoActivity.this)
                                .setTitle("选集")
                                .setItems(names, new DialogInterface.OnClickListener() {
                                    @Override public void onClick(DialogInterface d, int which) {
                                        switchTo(guids[which], names[which], which);
                                    }
                                })
                                .show();
                        autoHide();
                    }
                });
            }
        });
    }

    private void pickQuality() {
        if (directMode) { toast("直链播放不支持切换清晰度"); return; }
        final String[] q = FnClient.lastQualityRes;
        if (q == null || q.length == 0) {
            toast("当前影片没有清晰度列表（原画直出）");
            return;
        }
        new AlertDialog.Builder(this)
                .setTitle("清晰度")
                .setItems(q, new DialogInterface.OnClickListener() {
                    @Override public void onClick(DialogInterface d, int which) {
                        curQuality = q[which];
                        toast("切换到 " + curQuality);
                        Subtitle.clear();
                        subView.setVisibility(View.GONE);
                        subText = "";
                        if (playingLocalUrl != null) LocalProxy.drop(playingLocalUrl);
                        started = false;
                        prepareAndPlay();
                    }
                })
                .show();
    }

    private void switchTo(String newGuid, String newTitle, int idx) {
        guid = newGuid;
        title = newTitle;
        epIndex = idx;
        titleView.setText(newTitle);
        curQuality = null;
        Subtitle.clear();                    // 换集后旧字幕不再适用
        subView.setVisibility(View.GONE);
        subText = "";
        if (playingLocalUrl != null) LocalProxy.drop(playingLocalUrl);
        started = false;
        state.setVisibility(View.VISIBLE);
        state.setText("正在切换：" + newTitle);
        prepareAndPlay();
        autoHide();
    }

    // ==================== 生命周期 ====================

    @Override
    public void onWindowFocusChanged(boolean hasFocus) {
        super.onWindowFocusChanged(hasFocus);
        if (hasFocus) {
            immersive();
            applySafeInsets();   // 焦点就位后 inset 才有效，重算安全区
        }
    }

    @Override
    public void onConfigurationChanged(android.content.res.Configuration newConfig) {
        super.onConfigurationChanged(newConfig);
        if (videoBox != null) {
            videoBox.post(new Runnable() {
                @Override public void run() {
                    applyRatio();
                    applySafeInsets();
                }
            });
        }
    }

    /** 沉浸式：隐藏状态栏/导航栏，刘海穿透 */
    private void immersive() {
        getWindow().getDecorView().setSystemUiVisibility(
                View.SYSTEM_UI_FLAG_LAYOUT_STABLE
                        | View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION
                        | View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN
                        | View.SYSTEM_UI_FLAG_HIDE_NAVIGATION
                        | View.SYSTEM_UI_FLAG_FULLSCREEN
                        | View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY);
        if (android.os.Build.VERSION.SDK_INT >= 28) {
            WindowManager.LayoutParams lp = getWindow().getAttributes();
            lp.layoutInDisplayCutoutMode =
                    WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES;
            getWindow().setAttributes(lp);
        }
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        ui.removeCallbacksAndMessages(null);
        // v2.22：播放器已移交浮窗时不得 release / drop 代理（由 FloatVideoService 负责）
        if (!transferred) {
            if (player != null) player.release();
            if (playingLocalUrl != null) LocalProxy.drop(playingLocalUrl);
        }
        io.shutdownNow();
        restoreBrightness();
    }

    // ==================== v2.22 悬浮小窗 ====================

    /** 缩小为悬浮小窗：同进程移交 IjkHolder（零黑屏），Service 兜底重建。 */
    private void goFloat() {
        if (android.os.Build.VERSION.SDK_INT >= 24) {
            try { if (isInPictureInPictureMode()) return; } catch (Throwable ignored) {}
        }
        if (!android.provider.Settings.canDrawOverlays(this)) {
            new android.app.AlertDialog.Builder(this)
                    .setTitle("需要悬浮窗权限")
                    .setMessage("悬浮小窗需要「显示在其他应用上层」权限，去设置里开启后返回重试。")
                    .setPositiveButton("去授权", new android.content.DialogInterface.OnClickListener() {
                        @Override public void onClick(android.content.DialogInterface d, int w) {
                            try {
                                startActivity(new Intent(
                                        android.provider.Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                                        android.net.Uri.parse("package:" + getPackageName())));
                            } catch (Throwable t) {
                                Toast.makeText(VideoActivity.this, "请在系统设置里开启悬浮窗权限",
                                        Toast.LENGTH_SHORT).show();
                            }
                        }
                    })
                    .setNegativeButton("取消", null)
                    .show();
            return;
        }
        if (com.fengxi.fnplayer.player.PlaySession.active()) {
            stopService(new Intent(this, FloatVideoService.class));
        }
        boolean ok = com.fengxi.fnplayer.player.PlaySession.takeover(player, title,
                player == null ? 0 : player.curMs(), false, "video", guid, itemGuid,
                epIndex, currentLive);
        if (!ok) { toast("当前没有可缩小的播放"); return; }
        com.fengxi.fnplayer.player.PlaySession.playUrl = currentUrl;
        com.fengxi.fnplayer.player.PlaySession.headers = currentHeaders;
        transferred = true;
        startService(new Intent(this, FloatVideoService.class)
                .setAction(FloatVideoService.ACTION_SHOW));
        finish();
    }

    @Override
    protected void onPause() {
        super.onPause();
        // v2.22：播放器已移交浮窗 → 不再碰它
        if (transferred) return;
        // 画中画 / 后台播放（设置中心可开）：不暂停
        boolean pip = false;
        if (android.os.Build.VERSION.SDK_INT >= 24) {
            try { pip = isInPictureInPictureMode(); } catch (Throwable ignored) {}
        }
        if (pip || Config.backgroundPlay()) return;
        if (player != null) player.pause();
        syncPlayUi();
    }

    @Override
    protected void onResume() {
        super.onResume();
        if (player != null && !isFinishing()) {
            player.play();
        }
        syncPlayUi();
    }

    @Override
    public void onBackPressed() {
        if (locked) {
            toast("已锁屏，点屏幕解锁");
            return;
        }
        finish();
    }

    private void restoreBrightness() {
        if (savedBrightness >= 0) {
            WindowManager.LayoutParams lp = getWindow().getAttributes();
            lp.screenBrightness = savedBrightness;
            getWindow().setAttributes(lp);
        }
    }
}
