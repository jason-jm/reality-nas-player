# Reality NAS Player 怎样认出 NAS 里的作品

[English](metadata-matching.md) · **简体中文** · [繁體中文](metadata-matching.zh-Hant.md)

一块用了几年的硬盘里，可能有只叫 `video1.mkv` 的电影、用德文标季数的剧集，还有不同字幕组各用一种简称的番剧。在 **PICO Space Pro** 上把共享文件夹加进「来源」并扫描，播放器就能从这些文件和目录里找线索。

## 文件名缺了什么，哪里还能找回来

| 硬盘上看到的名字 | 播放器能读出的信息 |
| --- | --- |
| `Interstellar (2014)/video1.mkv` | 上一级目录给出电影名 *Interstellar* 和上映年份 2014。 |
| `Blade.Runner.2049.2017.2160p.BluRay.x264.mkv` | *2049* 是片名的一部分，*2017* 才是用来查找影片的上映年份。 |
| `Dark Staffel 2 Folge 3.mkv` | 德文的季、集标记对应《暗黑》第二季第三集。解析器也认识法语、西班牙语、韩语等语言的命名习惯。 |
| 某一集只写了 `Seitokai`，旁边另一集写着 `Seitokai no Ichizon` | 查找番剧时优先使用相邻分集提供的全名，并能剔除字幕组名、编码参数和校验码。 |

电影和电视剧可以按原名或不同地区的发行名查找。番剧还会比较日文名、罗马字名、英文名、季数和作品形式。例如，《机动警察剧场版》和《机动警察剧场版 2》同属一个系列。即使续作人气更高，文件名里有没有那个「2」，也会影响最后选中哪一部。[文件名案例](../core/src/test/kotlin/com/therealityteam/videoplayer/ScrapeLogicTest.kt) · [多语言案例](../core/src/test/kotlin/com/therealityteam/videoplayer/FilenameLanguagesTest.kt) · [番剧案例](../core/src/test/kotlin/com/therealityteam/videoplayer/ScrapeQualityTest.kt)

## 扫描完，打开一张卡会看到什么

- **剧集可以按季、按集选。** 数据源有收录时，电视剧用 TMDB 补充分集名称和简介，通过 AniList 识别的番剧则可以从 Bangumi 取得这些资料。
- **正片和特典各有去处。** 没有制作人员署名的片头（NCOP）、片尾（NCED）和预告片可以归入原作的特典。同一套碟里的剧场版，或有独立片名的 OVA，也可以有自己的海报。
- **纪录片和演唱会有各自的识别线索。** 一个电视台目录可以装下几十部不同的纪录片，播放器会看节目的名字，并能根据纪录片分类排除同名电视剧。演唱会自己的目录可以帮助识别曲目，「钢琴」这样的音乐分类则按分类处理。[分类识别案例](../core/src/test/kotlin/com/therealityteam/videoplayer/ShelfRulesTest.kt)
- **同一部电影可以保留多个版本。** 比如《机动战士高达 闪光的哈萨维》在电影目录里由 TMDB 认出，另一份压制在番剧目录里由 AniList 认出。原名和年份相符、两边对应关系明确时，会归到同一张卡，打开可选版本。真正分成上下两碟的电影仍然可以按顺序播放。[跨库去重案例](../core/src/test/kotlin/com/therealityteam/videoplayer/OneFilmTwoDatabasesTest.kt) · [多版本与分碟案例](../core/src/test/kotlin/com/therealityteam/videoplayer/FilmVersionsTest.kt)

## 自己刮好的资料，换个设备也能用

如果一直用 Kodi 或 tinyMediaManager 整理电影和电视剧，把 NFO、海报和背景图留在原处即可。NFO 带有 TMDB ID 时，播放器离线也能认出作品，读出文件里已有的片名、简介和演职员。剧集可以从 `tvshow.nfo` 认出整部剧，再从分集自己的 NFO 取得季集编号。介绍页里的八部演示电影就是在关闭在线查询后，从 WebDAV 共享这样扫出来的。[查看这次扫描生成的海报墙](showcase/features/zh-Hans/03-poster-wall.webp) · [NFO 扫描案例](../core/src/test/kotlin/com/therealityteam/videoplayer/NfoScrapeTest.kt)

电影扫描默认免密钥，先查 every.film，按需用 NeoDB、Wikidata 和 Open Film Database 补查。若要使用 TMDB，也可以填写自己的密钥。番剧可用 AniList 和 Bangumi。有相应数据时，详情页也能并列展示 TMDB、IMDb、AniList、Bangumi 和 MyAnimeList 的评分。整部剧配错时，从详情页「修正匹配」选对作品，这一张卡上的文件就一起改好，重新扫描仍会保留。

开启片库自动更新，后续扫描会收录新放进去的文件。更改「元数据语言」时，已经在线匹配的作品可以按已有 ID 刷新相应语言的片名、简介和演职员。观看进度仍在原卡片上，从本地 NFO 读取的条目继续使用你保存的文字。

## 自己验证这些例子

开源项目附带了这些例子的测试数据。文件名测试核对读出的片名和季集编号，番剧测试核对选中了哪一部作品。NFO 测试会自行建立影片目录和元数据，跨库测试则给同一部动画电影的两份文件分配不同的数据库 ID，再核对最终生成的影片卡片。

下载源码后，可以在项目根目录用 Gradle 运行这些例子。[构建环境说明](build.md)

```sh
./gradlew :core:test --tests '*ScrapeLogicTest' --tests '*FilenameLanguagesTest' --tests '*ScrapeQualityTest' --tests '*OneFilmTwoDatabasesTest' --tests '*NfoScrapeTest'
```

[返回产品介绍](languages/zh-Hans.md) · [选择语言](languages/README.md)
