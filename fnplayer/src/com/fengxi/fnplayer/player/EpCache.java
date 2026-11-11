package com.fengxi.fnplayer.player;

import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;

/**
 * v2.22 短剧集直链缓存：预加载下一集，上滑零等待。
 * <ul>
 *   <li>键 = 集下标（同一部剧会话内稳定），值 = 已解析、已 probe 的可播条目；</li>
 *   <li>容量 2（覆盖 i+1 / i+2），超限按插入时间淘汰最旧；</li>
 *   <li>INFLIGHT 单飞防抖：快速连滑不堆线程；</li>
 *   <li>换剧时 clear()，避免下标错位。</li>
 * </ul>
 */
public final class EpCache {

    /** 一集的可播结果（url 为原始直链/代理地址，startPlayer 内统一再走 LocalProxy）。 */
    public static final class Ent {
        public final String url;
        public final Map<String, String> headers;
        public final boolean probedOk;
        final long ts;
        public Ent(String url, Map<String, String> headers, boolean probedOk) {
            this.url = url;
            this.headers = headers;
            this.probedOk = probedOk;
            this.ts = System.currentTimeMillis();
        }
    }

    private static final int CAP = 2;
    private static final Map<Integer, Ent> MAP = new HashMap<>();
    private static final Set<Integer> INFLIGHT = new HashSet<>();
    private static final Object LOCK = new Object();

    private EpCache() {}

    public static Ent get(int i) {
        synchronized (LOCK) { return MAP.get(i); }
    }

    public static void put(int i, Ent e) {
        synchronized (LOCK) {
            MAP.put(i, e);
            while (MAP.size() > CAP) {
                int oldest = -1;
                long ts = Long.MAX_VALUE;
                for (Integer k : MAP.keySet()) {
                    Ent v = MAP.get(k);
                    if (v != null && v.ts < ts) { ts = v.ts; oldest = k; }
                }
                if (oldest < 0) break;
                MAP.remove(oldest);
            }
        }
    }

    public static void remove(int i) { synchronized (LOCK) { MAP.remove(i); } }

    public static boolean inflight(int i) { synchronized (LOCK) { return INFLIGHT.contains(i); } }

    public static void markInflight(int i, boolean on) {
        synchronized (LOCK) { if (on) INFLIGHT.add(i); else INFLIGHT.remove(i); }
    }

    public static void clear() { synchronized (LOCK) { MAP.clear(); INFLIGHT.clear(); } }
}
