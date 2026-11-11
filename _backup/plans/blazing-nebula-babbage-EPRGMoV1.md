# NAS 影视库 → EasyBox 源（fnOS 转 TVBox CMS 服务）

## Context

用户对自研枫兮影视 App 不满意，改用 EasyBox（TVBox 手机竖屏魔改版，兼容 TVBox 配置接口）。
目标：把飞牛 NAS（fnOS 影视 trimemedia）的影视库包装成 **TVBox CMS type=1 标准 JSON API**，
让 EasyBox 订阅后像刷资源站一样刷 NAS 影视。

**部署决策**（已与用户确认）：
- 服务部署在飞牛 NAS 上（Docker 容器），转换服务与 NAS 同机/同内网
- 内网 + 外网远程都要用 → 播放/列表 URL 全部基于请求的 `Host` 头动态生成（服务地址内外网自适应）

**核心资产**（可直接移植，均已实测可用）：
- `/workspace/fengxi-tingshu/fnplayer/src/com/fengxi/fnplayer/fnos/FnClient.java`：
  飞牛 API 全链路 —— 登录（POST /v/api/v1/login, app_name=trimemedia-web）、
  前缀探测（/v 或空）、mdb/list 媒体库、item/list（ancestor_guid 分页 48 条）、
  item/{guid} 详情、episode/list、season/list、
  播取链四步（play/info → stream/list → play/quality → play/play → play_link）
- `Authx.java`：authx 签名算法
  `nonce=<6位>&timestamp=<ms>&sign=md5(secret_path_nonce_ts_bodyHash_apiKey)`，
  GET 用排序 query 的 md5，POST 用 sort_keys+紧凑 JSON 的 md5；
  内置公开常量 secret=`NDzZTVxnRKP8Z0jXg1VAMonaG8akvh`、api_key=`16CCEB3D-AB42-077D-36A1-F355324E4237`
- 播放请求头：Cookie 含 authorization/Trim-MC-token/lastLoginUsername + Referer + Authorization

## 方案

### 交付物（`/workspace/easybox-fnos/`）

1. **`server.py`** —— Flask 单文件服务（依赖仅 flask + requests）：
   - 飞牛客户端（移植 FnClient/Authx）：login、probe、libraries、items、detail、episodes、seasons、playUrl 四步取链；token 缓存 + 失效自动重登
   - TVBox CMS 接口（挂在 `/api.php/provide/vod`）：
     - `ac=list`：class = mdb/list 媒体库（type_id=mdbGuid）；list = 分页影片（vod_id/vod_name/vod_pic）
     - `ac=detail&ids=`：详情 + 剧集——TV 用 seasons+episodes 拼成
       `第01集$URL#第02集$URL`（多季用 `$$$` 分组）；电影直接单集
     - `ac=videolist`：同 detail（兼容不同 TVBox 变体）
     - `ac=search&wd=`：用 item/list 拉列表缓存后本地名称过滤（searchable=1，尽力而为）
   - **流代理**（解决 EasyBox 无法带 Cookie/Referer 鉴权头的根本问题）：
     - `GET /stream/<guid>.m3u8`：服务端跑四步取链拿到 play_link（NAS 的 m3u8），
       带鉴权头取回 m3u8，把内部分段地址改写成 `/seg?u=<b64url>`
     - `GET /seg?u=`：带鉴权头转发分段（透传 Range/Content-Type，支持拖进度条）
     - 非 m3u8 直链（mp4）走 `/file/<guid>` 代理，同样透传 Range
   - `GET /tvbox.json`：动态生成订阅配置（api 地址按请求 Host 拼），EasyBox 订阅这一条 URL 即可，内外网通用
   - `GET /`：状态页（登录态、媒体库数、简易诊断）
   - 配置：环境变量 `FN_BASE / FN_USER / FN_PASS / PORT`（NAS 地址、账号密码）

2. **`Dockerfile` + `docker-compose.yml`**：python:3.12-slim，暴露 8095 端口，环境变量注入

3. **`README.md`**：飞牛 fnOS Docker 界面部署步骤（上传文件夹 → Docker Compose 启动 → EasyBox 订阅管理填 `http://<nas地址>:8095/tvbox.json`）；外网访问说明（沿用用户现有的 NAS 公网入口：IPv6/DDNS/中继，服务端口跟着暴露即可）

### 关键设计点

- **URL 自适应**：所有返回给 EasyBox 的链接（api 地址、剧集播放地址）基于当前请求的 Host 头生成 → 局域网用 IP 就得内网地址，外网走域名就得公网地址，无需两套配置
- **鉴权隔离**：EasyBox 只接触转换服务，飞牛凭据只存在 NAS 上的配置里；流代理统一带签名头，规避播放器无法自定义 header 的限制
- **Token 管理**：启动/401 时自动原生登录刷新；请求级缓存避免每段都取链（m3u8 URL 内存缓存 guid→link，TTL 30 分钟）

## 验证

1. **沙箱内自测**（用户 NAS 不可达）：写一个 mock fnOS 服务器（实现 login/mdb/list/item/list/episode/list/play 四步 + 一个假 m3u8/mp4），跑真 server.py 对接，curl 验证：
   - `ac=list` 分类与分页正确、`ac=detail` 剧集 URL 格式正确（`$`/`#`/`$$$`）
   - `/stream/*.m3u8` 返回的 m3u8 内部分段已改写为 `/seg?u=`，`/seg` 带鉴权头转发成功
   - `/tvbox.json` 内容合法、地址与请求 Host 一致
2. mock 全绿后交付部署包 + README；用户在 NAS 上跑起来后，让其在 EasyBox 订阅并实测播放，有问题按诊断页日志排查
