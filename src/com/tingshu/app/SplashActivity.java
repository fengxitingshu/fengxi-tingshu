package com.tingshu.app;

import android.app.Activity;
import android.graphics.Color;
import android.os.Bundle;
import android.os.Handler;
import android.view.Gravity;
import android.view.View;
import android.view.animation.AlphaAnimation;
import android.view.animation.Animation;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;

/**
 * ★ v4.10.2：开屏页。
 *
 * <p>没有接任何第三方广告 SDK —— 穿山甲/优量汇等正规广告平台都要求
 * 企业开发者资质 + App 备案，个人应用接不了。这里做的是<b>自有开屏位</b>：
 * Logo + 应用名 + 可配置公告文案，2.5 秒自动进入主界面，随时可跳过。
 * 附带收益：冷启动时的白屏窗口变成了品牌页，观感更完整。
 *
 * <p>公告文案优先读 assets/splash_notice.txt（运营时改文件重新打包即可
 * 更新开屏公告），没有就显示默认文案。
 */
public class SplashActivity extends Activity {

    private static final long SHOW_MS = 2500;

    private final Handler h = new Handler();
    private boolean jumped = false;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        FrameLayout root = new FrameLayout(this);
        root.setBackgroundColor(Color.parseColor("#0f1220"));

        LinearLayout center = new LinearLayout(this);
        center.setOrientation(LinearLayout.VERTICAL);
        center.setGravity(Gravity.CENTER);
        FrameLayout.LayoutParams clp = new FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.WRAP_CONTENT,
                FrameLayout.LayoutParams.WRAP_CONTENT, Gravity.CENTER);
        root.addView(center, clp);

        ImageView logo = new ImageView(this);
        logo.setImageResource(R.mipmap.ic_launcher);
        // ★ v4.10.3：用户反馈「换成软件图标」——图标放大到 128dp 更醒目
        int px = (int) (128 * getResources().getDisplayMetrics().density);
        LinearLayout.LayoutParams llp = new LinearLayout.LayoutParams(px, px);
        center.addView(logo, llp);

        AlphaAnimation fade = new AlphaAnimation(0.2f, 1f);
        fade.setDuration(600);
        logo.startAnimation(fade);

        TextView name = new TextView(this);
        name.setText("枫兮听书");
        name.setTextColor(Color.WHITE);
        name.setTextSize(28);   // ★ v4.10.3：应用名加大
        name.setTypeface(null, android.graphics.Typeface.BOLD);
        LinearLayout.LayoutParams nlp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT,
                LinearLayout.LayoutParams.WRAP_CONTENT);
        nlp.topMargin = (int) (22 * getResources().getDisplayMetrics().density);
        center.addView(name, nlp);

        // 公告文案（assets/splash_notice.txt 可配置，读不到用默认）
        // ★ v4.15.2：常驻单行 + 超宽省略。用户反馈跑马灯"晃眼"——
        //   一行放不下就截尾，保持静止常驻，不再滚动。
        TextView notice = new TextView(this);
        notice.setText(readNotice().replace("\n", "  ·  "));
        notice.setTextColor(Color.parseColor("#c7cddb"));
        notice.setTextSize(14);
        notice.setSingleLine(true);
        notice.setEllipsize(android.text.TextUtils.TruncateAt.END);
        notice.setGravity(Gravity.CENTER_HORIZONTAL);
        notice.setPadding(0, (int) (14 * getResources().getDisplayMetrics().density), 0, 0);
        LinearLayout.LayoutParams ntp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT);
        ntp.leftMargin = ntp.rightMargin =
                (int) (24 * getResources().getDisplayMetrics().density);
        center.addView(notice, ntp);

        // 右下角跳过按钮
        TextView skip = new TextView(this);
        skip.setText("跳过");
        skip.setTextColor(Color.parseColor("#9aa3b2"));
        skip.setTextSize(14);
        skip.setPadding((int) (18 * getResources().getDisplayMetrics().density),
                (int) (10 * getResources().getDisplayMetrics().density),
                (int) (18 * getResources().getDisplayMetrics().density),
                (int) (10 * getResources().getDisplayMetrics().density));
        FrameLayout.LayoutParams slp = new FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.WRAP_CONTENT,
                FrameLayout.LayoutParams.WRAP_CONTENT,
                Gravity.BOTTOM | Gravity.END);
        int m = (int) (24 * getResources().getDisplayMetrics().density);
        slp.bottomMargin = m;
        slp.rightMargin = m;
        skip.setOnClickListener(new View.OnClickListener() {
            public void onClick(View v) { enterMain(); }
        });
        root.addView(skip, slp);

        // ★ v4.15.2：加载条改为**真实推进**——白色填充从 0 匀速涨到满，
        //   到头正好进入主界面。原来是滑块来回滚（用户反馈像"卡住不动"）。
        int barW = (int) (168 * getResources().getDisplayMetrics().density);
        int barH = (int) (3 * getResources().getDisplayMetrics().density);
        FrameLayout.LayoutParams blp = new FrameLayout.LayoutParams(barW, barH,
                Gravity.BOTTOM | Gravity.CENTER_HORIZONTAL);
        blp.bottomMargin = (int) (72 * getResources().getDisplayMetrics().density);
        final FrameLayout track = new FrameLayout(this);
        android.graphics.drawable.GradientDrawable trackBg =
                new android.graphics.drawable.GradientDrawable();
        trackBg.setColor(Color.parseColor("#33ffffff"));
        trackBg.setCornerRadius(barH / 2f);
        track.setBackground(trackBg);
        final View fill = new View(this);
        android.graphics.drawable.GradientDrawable fillBg =
                new android.graphics.drawable.GradientDrawable();
        fillBg.setColor(Color.WHITE);
        fillBg.setCornerRadius(barH / 2f);
        fill.setBackground(fillBg);
        track.addView(fill, new FrameLayout.LayoutParams(0, barH));
        root.addView(track, blp);
        // 0 → 100% 单向推进（SHOW_MS 走完 = 加载完成 = 进 App）
        android.animation.ValueAnimator prog =
                android.animation.ValueAnimator.ofFloat(0f, 1f);
        prog.setDuration(SHOW_MS);
        prog.setInterpolator(new android.view.animation.LinearInterpolator());
        prog.addUpdateListener(new android.animation.ValueAnimator.AnimatorUpdateListener() {
            public void onAnimationUpdate(android.animation.ValueAnimator a) {
                int tw = track.getWidth();
                if (tw <= 0) tw = barW;
                int w = (int) (tw * ((Float) a.getAnimatedValue()).floatValue());
                FrameLayout.LayoutParams lp =
                        (FrameLayout.LayoutParams) fill.getLayoutParams();
                if (lp.width != w) { lp.width = w; fill.setLayoutParams(lp); }
            }
        });
        prog.addListener(new android.animation.AnimatorListenerAdapter() {
            @Override public void onAnimationEnd(android.animation.Animator a) {
                enterMain();
            }
        });
        prog.start();

        setContentView(root);
    }

    private String readNotice() {
        try {
            java.io.InputStream is = getAssets().open("splash_notice.txt");
            java.io.ByteArrayOutputStream bos = new java.io.ByteArrayOutputStream();
            byte[] buf = new byte[1024];
            int n;
            while ((n = is.read(buf)) > 0) bos.write(buf, 0, n);
            is.close();
            String s = new String(bos.toByteArray(), "UTF-8").trim();
            if (s.length() > 0) return s;
        } catch (Throwable ignored) {}
        return "本地听书 · 无需注册";
    }

    private void enterMain() {
        if (jumped) return;
        jumped = true;
        h.removeCallbacksAndMessages(null);
        startActivity(new android.content.Intent(this, MainActivity.class));
        finish();
        // 不做跨页动画：开屏到主页越自然越好
        overridePendingTransition(0, 0);
    }

    @Override
    public void onBackPressed() {
        // 开屏阶段按返回 = 直接退出（不再进主页）
        jumped = true;
        h.removeCallbacksAndMessages(null);
        super.onBackPressed();
    }
}
