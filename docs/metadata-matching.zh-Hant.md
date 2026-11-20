# Reality NAS Player 如何辨識 NAS 裡的作品

[English](metadata-matching.md) · [简体中文](metadata-matching.zh-Hans.md) · **繁體中文**

一顆用了好幾年的硬碟裡，可能有只叫 `video1.mkv` 的電影、用德文標記季數的影集，還有不同字幕組各用不同簡稱的動畫。在 **PICO Space Pro** 的「來源」加入共享資料夾並掃描，播放器便能從檔案和資料夾裡找線索。

## 檔名之外，還能從哪裡找到線索

| 硬碟上看到的名稱 | 播放器能讀出的資訊 |
| --- | --- |
| `Interstellar (2014)/video1.mkv` | 上一層資料夾提供片名 *Interstellar* 和上映年份 2014。 |
| `Blade.Runner.2049.2017.2160p.BluRay.x264.mkv` | *2049* 是片名的一部分，*2017* 才是查詢影片所用的上映年份。 |
| `Dark Staffel 2 Folge 3.mkv` | 德文的季、集標記對應《闇》第二季第三集。解析器也認得法文、西班牙文、韓文等語言的命名方式。 |
| 某一集只寫了 `Seitokai`，同資料夾另一集寫著 `Seitokai no Ichizon` | 查詢時可優先使用其他集數提供的完整片名，並辨別字幕組名稱、編碼參數及校驗碼。 |

電影與影集可以用原名或各地發行名稱查詢。動畫還會比對日文名稱、羅馬字、英文名、季數與作品形式。例如《機動警察劇場版》與《機動警察劇場版 2》屬於同一系列。即使續集人氣較高，檔名裡是否有「2」也會影響配對結果。[檔名案例](../core/src/test/kotlin/com/therealityteam/videoplayer/ScrapeLogicTest.kt) · [多語言案例](../core/src/test/kotlin/com/therealityteam/videoplayer/FilenameLanguagesTest.kt) · [動畫案例](../core/src/test/kotlin/com/therealityteam/videoplayer/ScrapeQualityTest.kt)

## 掃描完成後，一張卡片裡有什麼

- **影集可以按季、按集選。** 資料來源有收錄時，影集會從 TMDB 取得各集名稱與簡介。經 AniList 辨識的動畫則可從 Bangumi 取得這些資料。
- **正片和特典各就各位。** 沒有製作人員字幕的片頭（NCOP）、片尾（NCED）和預告片可歸入原作的特典。同一套影碟裡的劇場版，或有獨立片名的 OVA，也可以有自己的海報。
- **紀錄片和演唱會各有線索。** 一個電視台資料夾可以收錄幾十部不同的紀錄片，播放器會先看節目名稱，也能利用紀錄片分類排除同名戲劇。演唱會本身的資料夾可協助辨識曲目，「鋼琴」這類資料夾則視為音樂分類。[分類辨識案例](../core/src/test/kotlin/com/therealityteam/videoplayer/ShelfRulesTest.kt)
- **同一部電影可以保留多個版本。** 例如《機動戰士鋼彈 閃光的哈薩威》在電影資料夾由 TMDB 辨識，另一份放在動畫資料夾的版本則由 AniList 辨識。原名和年份相符、兩邊能明確對應時，打開同一張卡片就可選擇版本。真正拆成上下兩片的電影仍可依序播放。[跨資料庫比對案例](../core/src/test/kotlin/com/therealityteam/videoplayer/OneFilmTwoDatabasesTest.kt) · [多版本與分片案例](../core/src/test/kotlin/com/therealityteam/videoplayer/FilmVersionsTest.kt)

有些電視紀錄片在資料庫裡缺少類型。如果完整片名一致，播放器會核對檔名上的「紀錄片」標示與資料庫裡的電視台署名。例如，放在 PBS 資料夾的節目，也能以條目裡明確的「PBS 紀錄片」說明來辨識。

同名影集有時可以從季數與集數分辨。如果檔名寫著 `S04E12`，播放器會查哪些同名影集確實播到第四季第十二集，再以另一個資料庫核對作品編號。

有些電視紀錄片在 NeoDB 裡只有單季條目。節目名、紀錄片類型和電視台署名都對得上時，播放器仍能取用該季的海報與資料。遇到標有 NHK 的中文字幕版，播放器也會核對字幕發行紀錄上的檔名，再以日文原名及播出年份到另一個資料庫確認節目。

## 繼續使用已經整理好的資料

若平時用 Kodi 或 tinyMediaManager 整理電影和影集，把 NFO、海報和背景圖留在影片旁即可。NFO 含有 TMDB ID 時，播放器可離線辨識作品，讀取檔案內的片名、劇情與演員。影集可以從 `tvshow.nfo` 取得整部作品的 ID，再從單集自己的 NFO 讀取季集編號。介紹頁上的八部示範電影，就是在關閉線上查詢後，從 WebDAV 共享資料夾這樣掃描出來的。[查看實際的海報牆](showcase/01-library-en.webp) · [NFO 掃描案例](../core/src/test/kotlin/com/therealityteam/videoplayer/NfoScrapeTest.kt)

掃描電影預設免金鑰，先查 every.film，再視需要使用 NeoDB、Wikidata 和 Open Film Database。若要使用 TMDB，也可填入自己的金鑰。影集會查 TVmaze、NeoDB 與 every.film。缺少資料時，還可以用 Stremio Cinemeta 核對年份、類型與 IMDb 編號，或用 Wikidata 辨認迷你影集。放在電影資料夾裡、標有「BBC MiniSeries part 2」的檔案也會歸入第 2 集。紀錄節目另會核對類型。TVmaze 可提供它所辨識影集的各集名稱與簡介。本機 tvshow.nfo 即使沒有資料庫編號，也能將各集歸在一起。動畫可透過 AniList、Bangumi 和內建日中英片名索引比對。離線也能用索引核對名稱、年份與劇場版等版本；海報和分集資料依來源而定。各來源有資料時，作品頁也能並列顯示 TMDB、IMDb、AniList、Bangumi 與 MyAnimeList 評分。若整部影集配錯，在詳情頁修正一次，卡片裡的檔案會一起更新，重新掃描也會保留選擇。

啟用片庫自動更新，後續掃描會收錄新加入的檔案。變更中繼資料的語言時，已線上配對的作品可依既有 ID 更新片名、劇情與演職員的顯示語言。觀看進度仍在原卡片上，從本機 NFO 讀入的項目繼續使用你儲存的文字。

演唱會放在獨立資料夾時，播放器可以用演出名稱查詢 Internet Archive。檔案必須註明創作者和音樂主題，而且收錄了原始影片。只有音訊專輯的條目不會被套用到錄影。

## 自己驗證這些例子

開源專案附有這些例子的測試資料。檔名測試核對讀出的片名及季集編號，動畫測試核對最後選到哪部作品。NFO 測試會自行建立影片資料夾與中繼資料，跨資料庫測試則給同一部動畫電影的兩份檔案不同的資料庫 ID，再核對實際產生的作品卡片。

取得原始碼後，可在專案根目錄透過 Gradle 執行這些例子。[建置環境說明](build.md)

```sh
./gradlew :core:test --tests '*ScrapeLogicTest' --tests '*FilenameLanguagesTest' --tests '*ScrapeQualityTest' --tests '*OneFilmTwoDatabasesTest' --tests '*NfoScrapeTest'
```

[返回產品介紹](languages/zh-Hant.md) · [選擇語言](languages/README.md)
