# Reality NAS Player

**为 PICO Space Pro 打造的私人影院。**

**简体中文** · [English](../../README.md) · [日本語](ja.md) · [Español](es.md) · [Deutsch](de.md) · [Français](fr.md)

<details>
<summary>语言 (23)</summary>

**简体中文** · [English](../../README.md) · [日本語](ja.md) · [Español](es.md) · [Deutsch](de.md) · [Français](fr.md) · [Čeština](cs.md) · [Dansk](da.md) · [Ελληνικά](el.md) · [Suomi](fi.md) · [Italiano](it.md) · [한국어](ko.md) · [Bahasa Melayu](ms.md) · [Norsk bokmål](nb.md) · [Nederlands](nl.md) · [Polski](pl.md) · [Português (Brasil)](pt-BR.md) · [Română](ro.md) · [Русский](ru.md) · [Svenska](sv.md) · [ไทย](th.md) · [Türkçe](tr.md) · [繁體中文](zh-Hant.md)

[语言 (23) →](README.md)

</details>

![为 PICO Space Pro 打造的私人影院。 — Reality NAS Player](../showcase/00-screening-room-en.webp)

把 NAS 上的电影和剧集文件夹接入 PICO Space Pro。Reality NAS Player 会识别影片、整理资料，你可以按封面挑片，再到太空观景厅里开场。

**[下载头显 APK](https://github.com/jason-jm/reality-nas-player/releases/download/v1.0/reality-nas-player-1.0-4.apk)** · [版本说明与校验值](https://github.com/jason-jm/reality-nas-player/releases/tag/v1.0) · [桌面构建](../build.md)

## 让播放器替你的 NAS 片库刮削资料

![WebDAV 共享文件夹里《Polar Letter》的文件，以及扫描后出现在片库第一排的四张海报。](../showcase/01-auto-scraping-en.webp)

在「来源」里添加 NAS 共享并扫描。播放器会从文件名、年份和季集编号识别电影或剧集。已有 Kodi 风格的 NFO、海报和背景图？它能读取 NFO 里的影片或剧集 ID，沿用同目录的配图。图中的《Polar Letter》就是在关闭在线查询后，从 WebDAV 共享刮削出来的，连简介和演员都来自 NFO。需要在线补全时可填写自己的 TMDB API Key，匹配错了也能手动纠正。

## 按封面逛海报墙

![PICO Space Pro 片库的完整海报墙。八部影片按四列两行排列，左侧是分类，上方可以筛选和排序。](../showcase/01-library-en.webp)

记得蓝色封面，却想不起片名？在海报墙上一眼就能认出来。点开封面可看背景图、简介和演职员，打开剧集还能按季选集。按类型、年代或观看进度筛选，「继续观看」会带你回到上次停下的那一幕。

## 家里的片源，直接连

![家里的片源，直接连 — Reality NAS Player](../showcase/02-connect-en.webp)

本机文件可从系统文件管理器打开。电脑共享文件夹选 SMB；NAS 也可用 WebDAV、NFS、FTP 或 SFTP。已经在用 Jellyfin、Emby、Plex 或 DLNA 的话，直接选择对应入口。需要手机投放时，可自行开启 DLNA 接收。

## 为今晚挑个座位

![为今晚挑个座位 — Reality NAS Player](../showcase/03-cinemas-en.webp)

太空观景厅设有五个座位，窗边能见到地球。私人影院、小镇广场和沙漠营地各有不同的光线与景致。影院会记住座位；暂停时厅内灯光亮起，也可随时切回浮动窗口。

## 转身看另一面湖岸

![转身看另一面湖岸 — Reality NAS Player](../showcase/04-panorama-en.webp)

360° 影片可以转身环顾，180° 和 VR180 影片在眼前展开。在窗口里可以拖动视角，格式判断有误时也能手动指定。左右或上下排列的 3D 影片会分别送到左右眼。

## 听原声，也能换配音

![听原声，也能换配音 — Reality NAS Player](../showcase/05-playback-en.webp)

支持内封与外挂字幕、ASS/SSA 排版以及 PGS、VobSub 图形字幕。可切换音轨、外挂配音并微调音画延迟。除 MKV、MP4 外，还可播放多种旧格式及未加密蓝光、DVD 备份的正片；不含光盘交互菜单。4K、HDR 效果取决于片源与设备。

## 出门前存一部，回家接着看

![出门前存一部，回家接着看 — Reality NAS Player](../showcase/06-offline-en.webp)

提前下载到头显，离开 Wi-Fi 也能看；来源支持时可断点续传。可选的 SMB 同步只把进度、收藏和手动匹配写到你选的共享。没有广告或分析服务；离线识别使用本地文件信息。

## 坐回电脑前

![坐回电脑前 — Reality NAS Player](../showcase/07-desktop-en.webp)

macOS 和 Windows 桌面端可用鼠标、键盘浏览片库和播放文件。空间影院属于头显版。v1.0 提供已签名的头显 APK，桌面端从源码构建。

## 第一次放映

将 APK 侧载到 PICO Space Pro，打开一部自己的影片或在「来源」添加共享，然后在播放控件的「观影空间」选择窗口或影院。这里的头显画面来自 PICO OS 6 模拟器，演示影片不随播放器提供。

[隐私政策](../privacy-policy.md) · [关于这些画面](../showcase/README.md) · [MIT](../../LICENSE) · [语言 (23)](README.md)
