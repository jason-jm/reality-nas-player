# How Reality NAS Player recognizes your collection

**English** · [简体中文](metadata-matching.zh-Hans.md) · [繁體中文](metadata-matching.zh-Hant.md)

A NAS you've kept for years can hold a movie named `video1.mkv`, a TV season labelled in German and a fansub box set with a different abbreviation on every disc. Add the share under **Sources** on PICO Space Pro and start a scan. The player reads the clues you already have on disk.

## What the player can learn from a name

| What’s on your drive | What the player can tell |
| --- | --- |
| `Interstellar (2014)/video1.mkv` | The folder supplies *Interstellar* and the year 2014. |
| `Blade.Runner.2049.2017.2160p.BluRay.x264.mkv` | *2049* belongs in the title. *2017* is the release year to search for. |
| `Dark Staffel 2 Folge 3.mkv` | The German season and episode markers place it at season 2, episode 3. The parser also handles French, Spanish, Korean and other release conventions. |
| A fansub episode labelled `Seitokai` beside one labelled `Seitokai no Ichizon` | The longer title from a neighboring episode gets searched first. Group names, codecs and checksums can be set aside. |

Film and TV searches can use regional and alternative titles. For anime, the player compares native, romanized and English names, season numbers and the kind of release. For example, *Patlabor: The Movie* and *Patlabor 2* share a franchise. The numeral helps select the right film even when the sequel is the more popular result. [Filename examples](../core/src/test/kotlin/com/therealityteam/videoplayer/ScrapeLogicTest.kt) · [Language examples](../core/src/test/kotlin/com/therealityteam/videoplayer/FilenameLanguagesTest.kt) · [Anime matching examples](../core/src/test/kotlin/com/therealityteam/videoplayer/ScrapeQualityTest.kt)

## What ends up on the shelf

- **A season you can watch in order.** Open a series to pick an episode. When the source has them, the player adds individual episode names and synopses from TVmaze for a show it identified, from Bangumi for anime, or from TMDB when configured.
- **A box set with its extras in place.** Creditless openings (`NCOP`), endings (`NCED`) and trailers can sit with the show. A theatrical film or a separately titled original video animation in the same box can have a card of its own.
- **Documentaries and concert recordings.** A broadcaster’s folder can hold dozens of unrelated programmes. The player looks at each programme’s name and can use the documentary shelf to reject a drama with the same title. A concert’s own folder can identify its tracks, while a broad music category such as “Piano” is treated as a category. [Documentary and music examples](../core/src/test/kotlin/com/therealityteam/videoplayer/ShelfRulesTest.kt)
- **One film with a choice of copies.** One copy of *Mobile Suit Gundam Hathaway* might be in Movies and identified by TMDB, another in Anime and identified by AniList. When the original title and release year line up and the pairing is unambiguous, the two copies can share one card. Pick the version you want to play. A movie split across two discs can still play as two parts in sequence. [A film across two databases](../core/src/test/kotlin/com/therealityteam/videoplayer/OneFilmTwoDatabasesTest.kt) · [Film version examples](../core/src/test/kotlin/com/therealityteam/videoplayer/FilmVersionsTest.kt)

An older TV documentary may have no genre in the catalogue. When the full title agrees, the player can use a documentary label on the file together with a matching station credit in the catalogue. A clip filed under PBS can also match a catalogue entry that describes itself as a PBS documentary.

For a concert in its own folder, the player can check the Internet Archive catalogue for a recording of the same name. The entry must credit a creator, describe music, and contain an original video. An audio-only album leaves the video unidentified.

## Bring your own metadata or look it up online

If you already use Kodi or tinyMediaManager, leave the NFO and images beside the video. For a movie or TV series, an NFO with a TMDB ID can identify it offline and supply the title, synopsis, cast and other fields it contains. An episode can take its series ID from `tvshow.nfo` and its episode number from its own NFO. Local posters and fanart can accompany the entry. The eight films in the illustrated guide were scanned this way from a WebDAV share with online lookup switched off. [See the captured library](showcase/01-library-en.webp) · [NFO scan examples](../core/src/test/kotlin/com/therealityteam/videoplayer/NfoScrapeTest.kt)

Scan a folder of video files and the player looks up films by title and release year, then brings available posters and synopses into your library. No movie-database account or API key is required. Public lookups start with every.film, then use NeoDB, Wikidata and Open Film Database as needed. Your own TMDB key is optional. TV series use TVmaze, NeoDB and every.film. Stremio Cinemeta can confirm a series by its release year, format and IMDb ID. Wikidata distinguishes a film from a miniseries, even when you keep both under Movies. A file labelled “BBC MiniSeries part 2” stays with the second instalment. For documentaries the player also checks the type of programme. A local show NFO can group episodes even without a database ID. For anime, AniList, Bangumi and a small built-in Japanese, Chinese and English title index help distinguish a TV run from its theatrical release. The bundled names work offline. Posters and online episode guides depend on the source. Where ratings are available, a title can show scores from TMDB, IMDb, AniList, Bangumi and MyAnimeList side by side. If an entry is wrong, open **Fix match** on the title page, choose the right film or series from the available catalogues, and apply it to the card. The correction covers the files on that card and persists across ordinary rescans.

Enable automatic library updates to pick up new files on later scans. If you prefer to read descriptions in another language, change **Metadata language**. The player can fetch localized titles, synopses and credits for works already matched online, using the IDs it already knows. Your watch progress follows the same cards, and entries read from your NFO files keep the wording you saved.

## Try the examples yourself

The open-source project includes tests for the title a filename suggests, which work wins a search, and which files end up on the same card. The NFO test creates its own video folder and metadata. The cross-database test gives two copies of an anime film different provider IDs and checks the resulting card. Both use test data included with the project.

From a checkout, you can run these examples with the project’s Gradle wrapper. [Build setup](build.md)

```sh
./gradlew :core:test --tests '*ScrapeLogicTest' --tests '*FilenameLanguagesTest' --tests '*ScrapeQualityTest' --tests '*OneFilmTwoDatabasesTest' --tests '*NfoScrapeTest'
```

[Back to the illustrated product guide](../README.md) · [Choose a language](languages/README.md)
