package com.github.catvod.crawler;

import android.content.Context;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * TVBox spider jar 的基类桩（v2.13）。
 *
 * <p>市面通用爬虫 jar 里的源类都继承 {@code com.github.catvod.crawler.Spider}，
 * 本 App 在同样的包路径下提供这个抽象类，运行时用 DexClassLoader 加载 jar 后，
 * 源类即可向上转型到本类被反射调用。方法签名对齐 FongMi / takagen99 系 TVBox。
 */
public abstract class Spider {

    public void init(Context context) {}

    public void init(Context context, String extend) {}

    /** 首页：{"class":[{type_id,type_name}], "list":[首页推荐]} */
    public String homeContent(boolean filter) { return ""; }

    public String homeVideoContent() { return ""; }

    /** 分类列表：{"list":[{vod_id,vod_name,vod_pic,remarks}], "page":..} */
    public String categoryContent(String tid, String pg, boolean filter,
                                  HashMap<String, String> extend) { return ""; }

    /** 详情：{"list":[{vod_id,vod_name,vod_pic,vod_content,vod_play_from,vod_play_url}]} */
    public String detailContent(List<String> ids) { return ""; }

    public String searchContent(String key, boolean quick) { return ""; }

    public String searchContent(String key, boolean quick, int pg) { return ""; }

    /** 取播放地址：{"parse":0/1,"url":"直链","playUrl"/"play_url":"解析页","header":{}} */
    public String playerContent(String flag, String id, List<String> vipFlags) { return ""; }

    public boolean isVideoFormat(String url) { return true; }

    public boolean manualVideoCheck() { return false; }

    public Object proxy(Map<String, String> params) { return null; }

    public void destroy() {}
}
