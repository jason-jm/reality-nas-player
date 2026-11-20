# Third-party notices

The MIT license in this repository covers the original software by The Reality Team. The following components carry their upstream licenses and attribution requirements.

- The Android app includes FFmpeg, mpv, libplacebo, FriBidi, libass and other native libraries. The in-app notices list the versions, build options, upstream authors, license texts and source availability. The Android native build scripts and patches are included here. [Component and license list](app/src/main/assets/licenses.txt) · [Build scripts](app/natives/) · [Media3 audio build](docs/ffmpeg-decoder.md)
- The JNI bindings adapted from mpv-android retain their MIT notices. The AndroidX Media3 decoder and the Gradle wrapper retain their upstream licenses. [mpv-android bindings](app/src/main/java/is/xyz/mpv/MPVLib.kt) · [AndroidX Media](https://github.com/androidx/media) · [Gradle](https://github.com/gradle/gradle/blob/master/LICENSE)
- The PICO Spatial SDK and other Maven dependencies are fetched from the repositories configured in Gradle and are subject to their publishers’ terms. [SDK and dependency versions](gradle/libs.versions.toml)
- The cinema environments incorporate CC0 scans and materials from Poly Haven. The Earth texture in the space lounge uses NASA Earth Observatory’s Blue Marble imagery. [Poly Haven license](https://polyhaven.com/license) · [NASA Blue Marble imagery](https://science.nasa.gov/earth/earth-observatory/blue-marble-next-generation/base-topography/)
- TMDB, AniList and other external metadata services have their own terms for API access, posters and metadata. Users supply their own media. This product uses the TMDB API but is not endorsed or certified by TMDB. [TMDB](https://www.themoviedb.org/)


## Public film catalogues

The film detail page links to the catalogue that identified the film. every.film aggregates community and upstream film records. It licenses the metadata it owns under CC BY-SA 4.0. Original rights holders retain their rights in posters and other third-party material. [every.film API terms](https://every.film/api-terms-of-use)

NeoDB provides public catalogue records for noncommercial use. The detail page links to the film’s NeoDB entry. Wikidata provides structured facts under CC0. [NeoDB terms](https://neodb.social/pages/terms/) · [Wikidata licensing](https://www.wikidata.org/wiki/Wikidata:Licensing)

Open Film Database (ofdb.cc) provides factual film records under the Open Database License. Its third-party artwork has separate rights. The player uses its titles, release dates and identifiers. [Open Film Database copyright](https://ofdb.cc/copyright)

TVmaze provides show and episode metadata under CC BY-SA 4.0. The detail page links to the original show. [TVmaze API licensing](https://www.tvmaze.com/api#licensing) · [CC BY-SA 4.0](https://creativecommons.org/licenses/by-sa/4.0/)

The offline animation title index is adapted from **bangumi-data** version 0.3.237 by the bangumi-data contributors under CC BY 4.0. The player selects names, translations, dates, formats and public identifiers and packs them into a compressed table. [Upstream dataset](https://github.com/bangumi-data/bangumi-data) · [CC BY 4.0](https://creativecommons.org/licenses/by/4.0/) · [Index builder](tools/dev/build-anime-title-index.py)

Stremio Cinemeta can verify a series title, release year, genre and IMDb identifier. The player links to the series on Stremio. [Stremio addon protocol](https://stremio.github.io/stremio-addon-sdk/protocol.html)

For music recordings, the player can read factual catalogue information and link to the original Internet Archive item. [Internet Archive metadata API](https://archive.org/developers/md-read.html)

Kamonka’s NHK documentary release notes can supply alternate broadcast titles and dates for matching editions. The player reads the public title index and selected note IDs using the WordPress.com REST API. It compares release filenames on the device and confirms programme information against a film or TV catalogue. [WordPress.com posts API](https://developer.wordpress.com/docs/api/1.1/get/sites/%24site/posts/)
