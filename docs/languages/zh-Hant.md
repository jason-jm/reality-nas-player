# Reality NAS Player

**專為 PICO Space Pro 打造的私人電影院。**

**繁體中文** · [English](../../README.md) · [简体中文](zh-Hans.md) · [日本語](ja.md) · [Español](es.md) · [Deutsch](de.md) · [Français](fr.md)

<details>
<summary>語言 (23)</summary>

**繁體中文** · [English](../../README.md) · [简体中文](zh-Hans.md) · [日本語](ja.md) · [Español](es.md) · [Deutsch](de.md) · [Français](fr.md) · [Čeština](cs.md) · [Dansk](da.md) · [Ελληνικά](el.md) · [Suomi](fi.md) · [Italiano](it.md) · [한국어](ko.md) · [Bahasa Melayu](ms.md) · [Norsk bokmål](nb.md) · [Nederlands](nl.md) · [Polski](pl.md) · [Português (Brasil)](pt-BR.md) · [Română](ro.md) · [Русский](ru.md) · [Svenska](sv.md) · [ไทย](th.md) · [Türkçe](tr.md)

[語言 (23) →](README.md)

</details>

![專為 PICO Space Pro 打造的私人電影院。 — Reality NAS Player](../showcase/00-screening-room-en.webp)

把 NAS 裡多年累積的電影、影集和動畫資料夾接進來。Reality NAS Player 能從檔名以外的線索認出作品，把正片、各集與特典分好。戴上 **PICO Space Pro**，從海報牆選一部，到太空觀影廳入座。

**[下載頭戴版 APK](https://github.com/jason-jm/reality-nas-player/releases/download/v1.1.0/reality-nas-player-1.1.0-6.apk)** · [版本資訊與校驗碼](https://github.com/jason-jm/reality-nas-player/releases/tag/v1.1.0) · [建置桌面版](../build.md)

## 自動為 NAS 片庫刮削影片資料

![左側以《星際效應》和動畫簡稱為例，說明資料夾及相鄰各集提供的線索。右側是頭顯從 WebDAV 共享的 NFO 辨識出的八部示範影片中的四部。](../showcase/01-auto-scraping-en.webp)

**檔名寫不下的線索，資料夾裡找得到。** 在 `Interstellar (2014)/video1.mkv` 裡，上一層資料夾就交代了片名和年份。影集寫成德文的 `Dark Staffel 2 Folge 3.mkv`，也能排進《闇》第二季第三集。查詢時還能比對原名與各地譯名，帶回簡介、演員與海報。

**動畫收藏也能各就各位。** 某集只寫了 `Seitokai`，同一個資料夾裡另一集寫了全名 `Seitokai no Ichizon`，播放器就能用全名到 AniList 查詢。劇場版可自成一張海報，無字幕片頭與片尾則收在原作的特典裡。同一部動畫電影若有兩個版本，分別由 TMDB 與 AniList 認出，也能依原名和年份歸在同一張卡片，兩版都能播放。

電影或影集的 Kodi NFO 若有 TMDB ID，離線也能讀取裡面的劇情和演員，沿用自己存的海報及背景圖。NAS 裡只有影片也沒問題。掃描時會依片名與年份自動尋找電影、海報和劇情簡介，無須註冊或輸入 API 金鑰。若整部影集配錯，只要在詳情頁修正一次，卡片裡的各集都會跟著更正，重新掃描也會保留。

[查看辨識案例，以及如何用開源測試自行驗證](../metadata-matching.zh-Hant.md)

## 用封面逛片庫

<p align="center">
  <a href="../showcase/01-library-en.webp?raw=true"><img src="../showcase/01-library-en.webp" alt="PICO Space Pro 片庫的完整海報牆。八部電影分成上下兩排，每排四張，左側是分類，上方可篩選與排序。"></a>
</p>

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

macOS 與 Windows 桌面版使用相同的片庫整理工具，可用滑鼠和鍵盤看片。空間影院為頭戴版功能。v1.1.0 提供頭戴版 APK，桌面版需從原始碼建置。

## 第一次播放

將 APK 側載至 PICO Space Pro，從檔案管理員開啟影片，或在來源頁面新增共享資料夾。再從播放控制選擇觀影空間。頭戴畫面擷取自 PICO OS 6 模擬器，示範影片不隨程式提供。

[隱私權政策](../privacy-policy.md) · [畫面說明](../showcase/README.md) · [MIT](../../LICENSE) · [語言 (23)](README.md)
