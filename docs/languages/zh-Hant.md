# Reality NAS Player

**專為 PICO Space Pro 打造的私人電影院。**

**繁體中文** · [English](../../README.md) · [简体中文](zh-Hans.md) · [日本語](ja.md) · [Español](es.md) · [Deutsch](de.md) · [Français](fr.md)

<details>
<summary>語言 (23)</summary>

**繁體中文** · [English](../../README.md) · [简体中文](zh-Hans.md) · [日本語](ja.md) · [Español](es.md) · [Deutsch](de.md) · [Français](fr.md) · [Čeština](cs.md) · [Dansk](da.md) · [Ελληνικά](el.md) · [Suomi](fi.md) · [Italiano](it.md) · [한국어](ko.md) · [Bahasa Melayu](ms.md) · [Norsk bokmål](nb.md) · [Nederlands](nl.md) · [Polski](pl.md) · [Português (Brasil)](pt-BR.md) · [Română](ro.md) · [Русский](ru.md) · [Svenska](sv.md) · [ไทย](th.md) · [Türkçe](tr.md)

[語言 (23) →](README.md)

</details>

![專為 PICO Space Pro 打造的私人電影院。 — Reality NAS Player](../showcase/00-screening-room-en.webp)

把 NAS 裡的電影和影集資料夾連上 PICO Space Pro。Reality NAS Player 會辨識片名、整理資料，讓你從海報牆挑一部，再到太空觀景廳入座。

**[下載頭戴版 APK](https://github.com/jason-jm/reality-nas-player/releases/download/v1.0/reality-nas-player-1.0-4.apk)** · [版本資訊與校驗碼](https://github.com/jason-jm/reality-nas-player/releases/tag/v1.0) · [建置桌面版](../build.md)

## 自動為 NAS 片庫刮削影片資料

![WebDAV 共用資料夾裡《Polar Letter》的檔案，以及掃描後出現在片庫第一排的四張海報。](../showcase/01-auto-scraping-en.webp)

在「來源」加入 NAS 共享資料夾並掃描。播放器會從檔名、年份和季集編號辨識電影與影集。若已有 Kodi 格式的 NFO、海報和背景圖，便能讀取 NFO 內的電影或影集 ID，並沿用隨檔圖片。圖中的《Polar Letter》就是在關閉線上查詢後從 WebDAV 共享讀入的，連劇情簡介和演員都來自 NFO。需要線上資料時可填入自己的 TMDB API 金鑰，也能手動修正配對。

## 用封面逛片庫

![PICO Space Pro 片庫的完整海報牆。八部電影分成上下兩排，每排四張，左側是分類，上方可篩選與排序。](../showcase/01-library-en.webp)

只記得那張藍色海報？從海報牆挑片，點開就能看背景圖、劇情和演職員。影集依季、集整理，也能依類型、年代和觀看狀態篩選；「繼續觀看」會回到上次停下的場景。

## 從現有的硬碟開播

![從現有的硬碟開播 — Reality NAS Player](../showcase/02-connect-en.webp)

本機影片可從系統檔案管理員開啟。電腦的共享資料夾可用 SMB，NAS 也支援 WebDAV、NFS、FTP 和 SFTP。Jellyfin、Emby、Plex 與 DLNA 皆有對應入口；若要接受手機投放，可自行開啟 DLNA 接收功能。

## 今晚坐哪裡？

![今晚坐哪裡？ — Reality NAS Player](../showcase/03-cinemas-en.webp)

太空觀景廳有五個座位，靠窗便能看見地球。也能選擇私人影院、小鎮廣場或夜裡的沙漠營地。影院會記住座位，暫停時燈光會亮起；若想留意周遭，也可以改用浮動視窗。

## 轉身看看鏡頭另一側

![轉身看看鏡頭另一側 — Reality NAS Player](../showcase/04-panorama-en.webp)

置身 360° 影片中環顧四周，或觀看眼前的 180°／VR180 畫面。在視窗中則用拖曳改變視角。左右並排或上下排列的 3D 影片可分眼顯示，必要時可手動指定格式。

## 保留原來的字幕與聲音

![保留原來的字幕與聲音 — Reality NAS Player](../showcase/05-playback-en.webp)

支援內嵌、外掛字幕、ASS/SSA 排版及 PGS、VobSub 圖形字幕。可換配音、調整字幕時間或藍牙音訊延遲。除了 MKV、MP4 和多種舊格式，也能播放未加密藍光或 DVD 備份的正片；不含光碟互動選單。4K、HDR 表現依片源與裝置而定。

## 把電影帶出門

![把電影帶出門 — Reality NAS Player](../showcase/06-offline-en.webp)

先下載到頭戴裝置，離開 Wi-Fi 仍能看。可選用自己的 SMB 共享位置，在兩部裝置間同步進度、收藏及手動配對。沒有廣告或分析服務；離線辨識只使用本機檔案資訊。

## 在書桌前繼續逛片庫

![在書桌前繼續逛片庫 — Reality NAS Player](../showcase/07-desktop-en.webp)

macOS 與 Windows 桌面版使用相同的片庫整理工具，可用滑鼠和鍵盤看片。空間影院為頭戴版功能。v1.0 提供頭戴版 APK，桌面版需從原始碼建置。

## 第一次播放

將 APK 側載至 PICO Space Pro，從檔案管理員開啟影片，或在來源頁面新增共享資料夾。再從播放控制選擇觀影空間。頭戴畫面擷取自 PICO OS 6 模擬器，示範影片不隨程式提供。

[隱私權政策](../privacy-policy.md) · [畫面說明](../showcase/README.md) · [MIT](../../LICENSE) · [語言 (23)](README.md)
