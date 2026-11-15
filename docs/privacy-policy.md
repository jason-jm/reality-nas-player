# Reality NAS Player 隐私政策 / Privacy Policy

最后更新 / Last updated: 2026-11-15

Reality NAS Player 是一款运行在 PICO 头显上的本地媒体库播放器。我们的原则很简单：**你的媒体数据属于你，Reality NAS Player 不收集、不上传、不分析任何个人信息。**

Reality NAS Player is a local media library player for PICO headsets. The principle is simple: **your media data belongs to you. Reality NAS Player collects, uploads and analyzes no personal information.**

## 我们不收集什么

- **没有账号系统** —— Reality NAS Player 不要求注册，也没有登录。
- **没有数据分析 / 埋点 / 广告 SDK** —— Reality NAS Player 不含任何第三方统计或广告组件。
- **不上传你的文件** —— 你的本地视频、片库索引和观看记录保存在头显。启用同步后，进度和收藏还会写入你选择的 NAS 共享。

## Reality NAS Player 什么时候联网，发送什么

联网只发生在以下几类场景，且都可以关闭或不使用：

### 1. 刮削（获取海报和简介）

扫描媒体库时，Reality NAS Player 会把**从你的文件名中解析出的标题、年份等关键词**（例如 `Interstellar 2014`）用于查询公开元数据服务。海报、简介、演员和评分的来源见下表。

| 服务 | 域名 | 用途 |
|------|------|------|
| The Movie Database (TMDB) | api.themoviedb.org / image.tmdb.org | 电影、剧集元数据与海报 |
| AniList | graphql.anilist.co | 动画元数据 |
| Bangumi 番组计划 | api.bgm.tv | 动画中文元数据 |
| Jikan (MyAnimeList) | api.jikan.moe | 动画元数据补充 |
| OMDb（用你填写的 API Key 查询） | www.omdbapi.com | IMDb / 烂番茄 / Metacritic 评分 |
| IMDb Datasets | datasets.imdbws.com | 评分数据集下载 |

检索请求会使用标题、年份或公开的作品编号。IMDb 评分数据集以整表下载，在本机按编号查找。元数据请求**不包含**完整文件路径、你的媒体服务器地址、登录凭据或任何设备标识。这些服务各自的隐私政策适用于它们收到的请求。

在「设置 → 刮削与元数据」中打开**离线刮削**后，Reality NAS Player 使用本地规则识别文件名，停止向外部元数据及图片服务发起请求。你选择的 NAS 播放和同步仍可使用。

### 2. 你主动触发的下载

- **播放串流**：播放网络来源（SMB / FTP / SFTP / NFS / WebDAV / DLNA / Jellyfin）时，视频数据只在你的头显与**你自己的服务器**之间传输，不经过任何第三方。
- **下载到本机**：你选择「下载到本机」时，文件从**你自己的服务器**复制到头显本地，不经过任何第三方。

### 3. 接收投屏（默认关闭）

在「设置 → 播放 → 接收投屏」打开后，头显会在**你的局域网内**以 DLNA 宣告自己，手机上的视频 App 才能在投屏列表里看到它。手机发来的是视频地址，头显直接从那个地址播放；Reality NAS Player 不经过任何自己的服务器，也不记录投过来的内容。关闭后头显立即从局域网里消失。

### 4. 同步（默认关闭）

在「设置 → 同步」打开后，Reality NAS Player 会在**你选定的 NAS 共享**顶层建一个隐藏文件夹「.aura」，把这台头显的观看进度、是否看过、收藏和你手动修正过的匹配写进去，并读取其他设备写的那部分，让你的几台设备保持一致。数据只在头显与**你自己的 NAS** 之间传输，不经过任何第三方；文件里带有你给这台头显起的名称，方便在其他设备上区分。关闭同步后不再读写这个文件夹；「删除同步文件夹」会把它从 NAS 上删掉。

## 保存在本地的数据

Reality NAS Player 将下列数据保存在头显的应用存储中，卸载时系统会删除这些本地副本。开启 NAS 同步后，观看进度、收藏和手动匹配也会写入你选择的共享文件夹。

- 媒体库索引（识别出的影片信息）与海报缓存
- 你的来源配置，包括 NAS / WebDAV / Jellyfin 的**地址、用户名与密码/令牌**——它们仅用于连接你自己的服务器，绝不上传
- 应用设置（含你自行填入的 API Key）
- 收藏与观看历史
- 「下载到本机」的视频副本——可在「已下载」页或「设置 → 媒体库 → 删除全部下载」中删除

Reality NAS Player 的头显版本关闭了 Android 对应用数据的自动云备份，并从系统的设备迁移中排除应用数据。换头显后需要重新添加媒体来源。你可以在「设置 → 同步」中选择自己的 NAS 来同步观看进度、收藏和手动匹配。

「设置 → 媒体库 → 清空媒体库」会删除片库条目及封面缓存。你手动保存的影片截图位于系统相册 Pictures/Reality NAS Player，卸载后仍可在相册中查看和删除。旧版截图保存在原相册 Pictures/Aura。

## 权限说明

- **网络**：刮削元数据、串流你自己服务器上的视频
- **前台服务**：让大型媒体库的刮削在后台持续完成
- **本地媒体读取（视频）**：仅在你点「扫描本地」时请求，用来读取头显 Movies、Download 里的影片；拒绝也不影响其它功能
- **局域网组播**：仅在打开「接收投屏」时使用，让手机发现头显

## 变更与联系

本政策若有变更，将随应用更新一并发布。问题或建议请联系：madeforyou2025@gmail.com

---

## English

Reality NAS Player is a local video player for PICO headsets. It has no Reality NAS Player account, advertising or analytics. Your library index, server credentials and playback settings are stored on the headset.

- **Film identification.** When enabled, Reality NAS Player uses title and year keywords or public work IDs to look up posters and descriptions from TMDB, AniList, Bangumi and Jikan. OMDb is used when you enter an API key. IMDb ratings are downloaded as a complete dataset and matched locally. Requests to these services do not include your file paths, NAS credentials or device identifiers. Offline identification stops external metadata and artwork requests. You can still play films from your own servers.
- **Playback and downloads.** Reality NAS Player connects directly to the servers you configure, including for streaming and offline downloads. Connection security depends on the protocol and settings you choose.
- **Casting and sync.** Both are off by default. Enabling the cast receiver lets phones on your network discover the headset and send a playback URL. Enabling sync stores viewing progress, favourites and manual matches in a hidden `.aura` directory in the NAS share you select. The records include the device name you set. You can disable either feature or delete the sync folder from settings.
- **Changing headsets.** Reality NAS Player excludes its app data from Android cloud backup and device-to-device transfer. On a new headset, add your media sources again. In Settings → Sync, you can choose your own NAS to carry over watch progress, favourites and manual matches.
- **Permissions and deletion.** A local video scan requests media access. The foreground service supports long-running library indexing, and multicast supports cast discovery. Removing the app clears its local app data. You can remove downloaded media in the app and delete NAS sync data separately. Screenshots you save to the Pictures/Reality NAS Player album remain in the system gallery after uninstallation. You can delete them in the gallery. Screenshots saved by earlier versions remain in Pictures/Aura.

Contact for questions about this policy: madeforyou2025@gmail.com.
