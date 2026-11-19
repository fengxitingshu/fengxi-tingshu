# FX NAS 锚点

让**飞牛 NAS 主动来拉** APK，自己生成下载页。你用固定地址（公网域名 / 局域网 IP）下载，不用再让我每回构建公网链接。

## 为什么是这样

我这边（沙箱）实测过：

- 你的域名 `[更新域名]` 能解析，但**只有 AAAA（纯 IPv6）**：`2408:821b:8a22:ab31:62be:b4ff:fe02:f790`
- 沙箱**没有 IPv6 出口**，连 443 都是 `Network is unreachable` → 我没法从外面「推」进你的 NAS
- 你没有公网 IPv4，端口转发 / frp 那条路也走不通

但 **NAS 出方向能访问公网**。所以反过来：在 NAS 上跑一个常驻小程序（锚点），每隔 60 秒主动去固定源检查一下 `manifest.json`，发现 MD5 变了就下载新 APK、校验、替换、刷新下载页。

> 不需要公网 IPv4、不需要端口转发、不需要内网穿透、不需要给我任何账号密码。

```
[沙箱] 构建 APK → 更新 manifest.json（固定源）
                        ↑ 每 60s 主动 GET
[NAS]  锚点容器 → 校验 md5 → 写入 nginx 静态目录 → 生成 index.html
                        ↓
[手机/车机] https://你的域名/apk/  或  http://局域网IP:9080/apk/
```

## 部署（Docker，约 3 分钟）

### 0. 拿到文件

从下载页点「下载锚点部署包」即可。注意：**WAF 会拦 `.zip` 直链**，所以部署包是以 `.bin` 传的：

- 页面按钮已指定保存名 `fx-nas-anchor.zip`，多数浏览器会直接存成 zip；
- 若存成了 `fx-nas-anchor.bin`，**手动把后缀改成 `.zip`** 再解压；
- 也可以逐个下载 `anchor.py` / `Dockerfile` / `docker-compose.yml` / `README.md` 四个文件放同一目录（`Dockerfile.txt` 下载后改名为 `Dockerfile`，无扩展名）。

### 1. 上传

把 `anchor.py`、`Dockerfile`、`docker-compose.yml` 三个文件传到 NAS，例如：

```
/vol1/1000/docker/fx-nas-anchor/
```

### 2. 确认挂载目录（关键）

按你现有的拓扑，**nginx(:9080) 挂载 `/vol1/1000/miyue` → Lucky 整域名反代**。所以锚点输出目录挂到它的子目录即可：

```yaml
volumes:
  - /vol1/1000/miyue/apk:/data
```

路径按你实际情况改。锚点会把 `apk` 文件和 `index.html` 写进这个目录。

### 3. 启动

fnOS 界面：**Docker → Compose → 新增**，选这个目录，启动。或 SSH：

```bash
cd /vol1/1000/docker/fx-nas-anchor && docker compose up -d
```

看日志确认首次同步：

```bash
docker logs -f fx-nas-anchor
# 期望看到：发现新版本 v2.13 → 同步完成：…（5141434 字节，md5 0954…）
```

## 访问下载页

| 场景 | 地址 |
|---|---|
| 公网（你选的） | `https://[更新域名]/apk/` |
| 局域网备用 | `http://<NAS内网IP>:9080/apk/` |

- Lucky 是整域名反代，`/apk/` 一般会自动透传给 nginx；若 404，检查 nginx 静态根目录里是否真有 `apk/` 这一层。
- ⚠️ 你的域名是**纯 IPv6**，外网（尤其车机 / 部分移动网络）可能连不上；局域网 IP 那条最稳，建议两个都留着。
- 页面里的下载按钮是**相对路径**，用哪个地址打开都能正确下载。

## 以后的更新流程

**我这边**：每次构建完 APK，跑一次 `sync.py` 刷新 `manifest.json`（自动算版本、大小、MD5）。**不用重新发布任何链接**。

**你那边**：什么都不用做。锚点 60 秒内发现 MD5 变化 → 自动下载替换 → 刷新页面就是最新版。

也可以**完全手动**：直接把新的 apk 丢进 `apk/` 目录，锚点源不可达时会扫描本机 APK 并照常生成下载页（已实测降级可用）。

想立刻手动同步一次：

```bash
docker exec fx-nas-anchor python3 -c "import os;os.environ['ONCE']='1';exec(open('/app/anchor.py').read())"
```

或在 compose 里临时加 `ONCE=1` 跑一次。

## 换源（建议尽早做）

`docker-compose.yml` 里只有一行需要关心：

```yaml
- SRC_MANIFEST=https://[REDACTED]/
```

> ⚠️ **当前这个源是会话级的发布应用，不是永久基础设施**。沙箱会话回收后它可能失效。失效的后果只有「不再自动更新」——**NAS 上已下载的 APK 和下载页照常可用**，不会变砖（降级逻辑已实测）。

想长期稳定，把 `manifest.json` 和 APK 放到你自己的地方，然后把这一行改成新地址即可，锚点代码一行都不用动：

| 自建源 | 做法 | 备注 |
|---|---|---|
| GitHub / Gitee 仓库 | 两个文件传上去，用 raw 地址 | 最简单，5MB 没问题 |
| 对象存储（COS/OSS/R2） | 开公有读，填文件 URL | 最稳 |
| 飞牛自己的静态站 | 直接放，源和下载端合一 | 那其实连锚点都不用了 |
| Cloudflare Pages / 任意静态 HTTP | 同上 | — |

`manifest.json` 格式（字段说明）：

```json
{
  "app": "枫兮影视",
  "package": "com.fengxi.fnplayer",
  "version": "2.13",
  "versionCode": 24,
  "file": "fengxi-video-v2.13.apk",
  "size": 5141434,
  "md5": "0954b4a6e3c40c374926471b45130b52",
  "updated": "2026-11-09 17:08:35",
  "notes": "版本说明"
}
```

锚点以 **md5 + size** 判断新旧，版本号只用于显示；也可以加 `url` 字段直接给 APK 完整直链（此时忽略 `file` 的拼接规则）。

## 不用 Docker 的跑法

纯标准库，任何有 python3 的机器都行：

```bash
mkdir -p /vol1/1000/miyue/apk
export SRC_MANIFEST='https://…/manifest.json?sharecode=…'
export OUT_DIR=/vol1/1000/miyue/apk
export INTERVAL=60
nohup python3 -u anchor.py >> /var/log/fx-anchor.log 2>&1 &
```

或用 crontab 每分钟跑一次 `ONCE=1 python3 anchor.py`。

## 排错

| 现象 | 查什么 |
|---|---|
| 日志一直「拉取 manifest 失败」 | NAS 能否出公网；源地址整条复制（含 `?sharecode=`）；需要代理就开 compose 里的 `HTTP_PROXY` |
| 日志「MD5 不匹配」 | 源上的 APK 正在被覆盖，等下一轮；或 CDN 缓存了旧 manifest |
| 页面 404 | nginx 静态目录里有没有 `apk/`；Lucky 是否把 `/apk/` 转给 nginx:9080 |
| 页面显示「源暂不可达」 | 正常降级，APK 仍可下载；恢复联网后自动补同步 |
| 点 apk 被别的程序抢开 | 设置 → 应用管理 → 找到抢注 `.apk` 的 App → 清除默认值 |
| 一直是旧版本 | `docker logs fx-nas-anchor` 看是否「已是最新」；确认我这边跑过 `sync.py` 且 md5 变了 |

## 边界

- 锚点**只做三件事**：读 manifest、下载并校验 APK、生成静态页。不执行源端任何代码，文件名有白名单校验（`[A-Za-z0-9_.-]+`），下载先写 `.part` 再校验 MD5 和大小，通过才原子替换——源被劫持也不会写入坏文件。
- 它不需要 root、不碰你的影视库、不改 nginx / Lucky 配置，只往你指定的一个目录写两个文件。
- 公网源带 `sharecode` 只用于访问控制，不是敏感凭据；换自己的源后就没有这个参数了。
