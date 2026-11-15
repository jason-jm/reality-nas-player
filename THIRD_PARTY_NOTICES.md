# Third-party notices

The MIT license in this repository covers the original software by The Reality Team. The following components carry their upstream licenses and attribution requirements.

- The Android app includes FFmpeg, mpv, libplacebo, FriBidi, libass and other native libraries. The in-app notices list the versions, build options, upstream authors, license texts and source availability. The Android native build scripts and patches are included here. [Component and license list](app/src/main/assets/licenses.txt) · [Build scripts](app/natives/) · [Media3 audio build](docs/ffmpeg-decoder.md)
- The JNI bindings adapted from mpv-android retain their MIT notices. The AndroidX Media3 decoder and the Gradle wrapper retain their upstream licenses. [mpv-android bindings](app/src/main/java/is/xyz/mpv/MPVLib.kt) · [AndroidX Media](https://github.com/androidx/media) · [Gradle](https://github.com/gradle/gradle/blob/master/LICENSE)
- The PICO Spatial SDK and other Maven dependencies are fetched from the repositories configured in Gradle and are subject to their publishers’ terms. [SDK and dependency versions](gradle/libs.versions.toml)
- The cinema environments incorporate CC0 scans and materials from Poly Haven. The Earth texture in the space lounge uses NASA Earth Observatory’s Blue Marble imagery. [Poly Haven license](https://polyhaven.com/license) · [NASA Blue Marble imagery](https://science.nasa.gov/earth/earth-observatory/blue-marble-next-generation/base-topography/)
- TMDB, AniList and other external metadata services have their own terms for API access, posters and metadata. Users supply their own media. This product uses the TMDB API but is not endorsed or certified by TMDB. [TMDB](https://www.themoviedb.org/)
