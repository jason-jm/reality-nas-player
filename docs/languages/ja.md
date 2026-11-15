# Reality NAS Player

**PICO Space Pro のための、自分専用の映画館。**

**日本語** · [English](../../README.md) · [简体中文](zh-Hans.md) · [Español](es.md) · [Deutsch](de.md) · [Français](fr.md)

<details>
<summary>言語 (23)</summary>

**日本語** · [English](../../README.md) · [简体中文](zh-Hans.md) · [Español](es.md) · [Deutsch](de.md) · [Français](fr.md) · [Čeština](cs.md) · [Dansk](da.md) · [Ελληνικά](el.md) · [Suomi](fi.md) · [Italiano](it.md) · [한국어](ko.md) · [Bahasa Melayu](ms.md) · [Norsk bokmål](nb.md) · [Nederlands](nl.md) · [Polski](pl.md) · [Português (Brasil)](pt-BR.md) · [Română](ro.md) · [Русский](ru.md) · [Svenska](sv.md) · [ไทย](th.md) · [Türkçe](tr.md) · [繁體中文](zh-Hant.md)

[言語 (23) →](README.md)

</details>

![PICO Space Pro のための、自分専用の映画館。 — Reality NAS Player](../showcase/00-screening-room-en.webp)

NAS の映画やドラマのフォルダーを PICO Space Pro につなぐと、Reality NAS Player が作品を識別してライブラリを整えます。ポスターから今夜の一本を選んで、地球の見える席へ。

**[ヘッドセット用 APK](https://github.com/jason-jm/reality-nas-player/releases/download/v1.0/reality-nas-player-1.0-4.apk)** · [リリース情報とチェックサム](https://github.com/jason-jm/reality-nas-player/releases/tag/v1.0) · [デスクトップ版のビルド](../build.md)

## NAS のファイルから作品情報を自動取得

![NAS のファイルから作品情報を自動取得 — Reality NAS Player](../showcase/01-auto-scraping-en.webp)

共有フォルダーを追加してスキャンすると、ファイル名や公開年、シーズン・話数を手がかりに作品を照合します。Kodi 形式の NFO に映画やシリーズの ID があれば、それを読み、同じフォルダーのポスターや背景画像を使えます。画面の『Polar Letter』のあらすじと出演者は WebDAV 上の NFO からオフラインで読み込んだものです。オンライン検索には自分の TMDB API キーを設定でき、誤った照合は手動で直せます。

## ポスターをめくって次の一本を選ぶ

![ポスターをめくって次の一本を選ぶ — Reality NAS Player](../showcase/01-library-en.webp)

題名は忘れても、青い表紙なら覚えている。そんなときはポスターが並ぶライブラリへ。作品を開くと背景画像、あらすじ、出演者が見られ、ドラマならシーズンと話数を選べます。ジャンルや年代で絞り込んだり、前回の続きから再生したりできます。

## いま使っている保存先から再生

![いま使っている保存先から再生 — Reality NAS Player](../showcase/02-connect-en.webp)

本体のファイルはシステムのファイル管理から開けます。PC の共有フォルダーには SMB、NAS には WebDAV、NFS、FTP、SFTP も利用できます。Jellyfin、Emby、Plex、DLNA にも対応。スマートフォンから送りたいときは DLNA 受信をオンにします。

## 今夜の上映場所を決める

![今夜の上映場所を決める — Reality NAS Player](../showcase/03-cinemas-en.webp)

宇宙を望むスペースラウンジには、中央と窓際を含む五つの席があります。プライベートシネマ、村の広場、砂漠のキャンプ場も選べます。座席は記憶され、一時停止すると照明が戻ります。周囲も見たいときはウィンドウ表示へ。

## カメラの後ろを振り返る

![カメラの後ろを振り返る — Reality NAS Player](../showcase/04-panorama-en.webp)

360° 動画は振り向いて、180°・VR180 動画は正面に広がる景色として楽しめます。ウィンドウ内ではドラッグで見回せます。左右分割・上下分割の 3D 映画は両眼に別々の映像を表示し、形式は手動でも選べます。

## 字幕も音声も好みの組み合わせで

![字幕も音声も好みの組み合わせで — Reality NAS Player](../showcase/05-playback-en.webp)

内蔵・外部字幕のほか、ASS/SSA の装飾や PGS・VobSub の画像字幕に対応。吹き替えへの切り替えや音ずれの調整も可能です。MKV、MP4、旧形式の動画、暗号化されていない Blu-ray・DVD バックアップの本編を再生できます。ディスクメニューには非対応。4K・HDR の再生結果は機器とファイルによります。

## 旅の前にダウンロード

![旅の前にダウンロード — Reality NAS Player](../showcase/06-offline-en.webp)

Wi-Fi を離れる前に本体に保存しておけば、移動中も再生できます。自分の SMB 共有を指定すれば、再生位置、お気に入り、手動で修正した作品情報をヘッドセット間で同期できます。広告や利用状況の解析はなく、オフラインでの作品判別も選べます。

## デスクでも同じライブラリ操作を

![デスクでも同じライブラリ操作を — Reality NAS Player](../showcase/07-desktop-en.webp)

macOS・Windows ではマウスとキーボードで作品を探して再生できます。空間シネマはヘッドセット専用です。v1.0 はヘッドセット用 APK を配布しています。デスクトップ版はソースからビルドしてください。

## 最初の上映

PICO Space Pro に APK をサイドロードし、自分の動画を開くか共有フォルダーを追加します。再生コントロールから視聴空間を選んでください。画像は PICO OS 6 エミュレーターで撮影したもので、作例の映画は同梱されません。

[プライバシー](../privacy-policy.md) · [画像について](../showcase/README.md) · [MIT](../../LICENSE) · [言語 (23)](README.md)
