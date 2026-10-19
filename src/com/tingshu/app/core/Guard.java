package com.tingshu.app.core;

import android.content.Context;
import android.content.pm.PackageInfo;
import android.content.pm.PackageManager;
import android.widget.Toast;

/**
 * ★ v4.0.0 运行时防盗版（内部模块，不进更新公告）。
 *
 * <p>启动时校验当前安装包的签名指纹是否与正版证书一致：
 * 被人解包改造后必然要用自己的证书重签，指纹就对不上。
 * 不一致时提示并终止进程。
 *
 * <p>实现要点：
 * <ul>
 *   <li>期望指纹以 XOR 编码的 int[] 编译在代码里（防 strings/grep 直接定位）；</li>
 *   <li> PackageManager 环境异常（个别 ROM 抛异常、签名列表为空等）
 *       一律<b>放行</b>——宁可漏过盗版，不能误伤正版；</li>
 *   <li>自包含、无反射，R8 混淆改名不影响；校验只在启动时跑一次（毫秒级），
 *       运行期零开销。</li>
 * </ul>
 *
 * <p><b>换签名证书时必须同步更新</b> {@link #FP}（用新证书指纹逐字符 XOR
 * 0x5A 后的 int 数组），否则正版用户会被误杀。
 */
public final class Guard {

    private static final int K = 0x5A;

    /** 期望的签名 SHA-256 指纹（大写 HEX，逐字符 XOR K 后的密文） */
    private static final int[] FP = {
            108,104,108,108,98,31,106,30,99,105,105,105,107,105,28,111,
            30,99,25,109,28,99,98,28,106,107,99,110,105,31,110,108,
            27,109,30,99,24,31,30,27,30,110,98,108,109,28,106,108,
            99,98,98,25,106,107,25,104,111,104,99,25,110,111,108,98
    };

    /** 拒绝文案（同样 XOR 编码） */
    private static final int[] MSG = {
            24270,30066,31524,21591,24408,24162,65366,35757,23507,34975,27449,29202
    };

    private Guard() {}

    private static String dec(int[] a) {
        StringBuilder sb = new StringBuilder(a.length);
        for (int v : a) sb.append((char) (v ^ K));
        return sb.toString();
    }

    private static byte[] sha256(byte[] in) {
        try {
            return java.security.MessageDigest.getInstance("SHA-256").digest(in);
        } catch (Throwable t) {
            return new byte[0];
        }
    }

    /** true = 签名与正版一致（或环境异常按放行处理） */
    @SuppressWarnings("deprecation")
    public static boolean ok(Context c) {
        try {
            PackageManager pm = c.getPackageManager();
            PackageInfo pi = pm.getPackageInfo(
                    c.getPackageName(), PackageManager.GET_SIGNATURES);
            if (pi == null || pi.signatures == null || pi.signatures.length == 0) {
                return true;    // 环境异常：放行，不误伤
            }
            byte[] h = sha256(pi.signatures[0].toByteArray());
            if (h.length != 32) return true;
            StringBuilder sb = new StringBuilder(h.length * 2);
            for (byte b : h) {
                sb.append(Character.toUpperCase(
                        Character.forDigit((b >> 4) & 0xF, 16)));
                sb.append(Character.toUpperCase(
                        Character.forDigit(b & 0xF, 16)));
            }
            return dec(FP).contentEquals(sb);
        } catch (Throwable t) {
            return true;        // 任何意外都放行
        }
    }

    /**
     * 启动校验入口。不过：主线程弹提示后终止进程。
     * 正版用户永远走不到拒绝分支（整个方法开销 < 5ms）。
     */
    public static void check(final Context c) {
        if (ok(c)) return;
        new android.os.Handler(android.os.Looper.getMainLooper()).post(new Runnable() {
            public void run() {
                try {
                    Toast.makeText(c.getApplicationContext(),
                            dec(MSG), Toast.LENGTH_LONG).show();
                } catch (Throwable ignored) {}
                try {
                    android.os.Process.killProcess(android.os.Process.myPid());
                } catch (Throwable ignored) {}
                System.exit(0);
            }
        });
    }
}
