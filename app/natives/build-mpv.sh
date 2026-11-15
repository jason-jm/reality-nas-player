#!/bin/bash
# libmpv for the headset: the engine for the files Media3 cannot read — RMVB and RM (RealVideo), WMV
# and ASF, FLV's old codecs, XviD/DivX AVIs and their AC-3 — a quarter of a real home library (see
# docs/legacy-formats.md). Media3 stays the player for everything else; this is only ever loaded for
# a file it cannot play.
#
# Built with mpv-android's buildscripts at the revision pinned below, for arm64 only, and LGPL
# throughout, as the app's other FFmpeg is (docs/ffmpeg-decoder.md): FFmpeg without --enable-gpl or
# --enable-version3 — so no mbedtls, which mpv does not need: it reads the files from the app's own
# LocalStreamServer at http://127.0.0.1 — mpv with -Dgpl=false, and no lua or libcurl. FFmpeg is the
# desktop's 8.1.1 (desktop/natives/build-ffmpeg.sh) with every LGPL decoder, demuxer and parser.
#
#   app/natives/build-mpv.sh     # → app/src/main/jniLibs/arm64-v8a/*.so
#
# Needs the NDK below (sdkmanager "ndk;30.0.16248370") and, from Homebrew: meson ninja nasm gnu-sed
# coreutils wget pkg-config autoconf automake. Sources go to $WORK_DIR (default ~/aura-native/mpv).
set -euo pipefail

NDK_VERSION=30.0.16248370
MPV_ANDROID_REV=c2e02fe111541e4063ab8f868bf629649ed937a3
MPV_TAG=v0.41.0
LIBPLACEBO_TAG=v7.360.1
LIBASS_TAG=0.17.5
FFMPEG_VERSION=8.1.1
FFMPEG_SHA256=b6863adde98898f42602017462871b5f6333e65aec803fdd7a6308639c52edf3

here="$(cd "$(dirname "$0")" && pwd)"
app="$(cd "$here/.." && pwd)"
work="${WORK_DIR:-$HOME/aura-native/mpv}"
sdk="${ANDROID_HOME:-$HOME/Library/Android/sdk}"
out="$app/src/main/jniLibs/arm64-v8a"
mkdir -p "$work"

# 1. mpv-android, at the pinned revision: its buildscripts, and the JNI layer (libplayer) the app's
#    is.xyz.mpv.MPVLib binds to.
if [ ! -d "$work/mpv-android/.git" ]; then
  git clone --quiet https://github.com/mpv-android/mpv-android "$work/mpv-android"
fi
git -C "$work/mpv-android" fetch --quiet origin
git -C "$work/mpv-android" checkout --quiet --force "$MPV_ANDROID_REV"
bs="$work/mpv-android/buildscripts"

# 2. Its sdk/ layout, over this machine's SDK and NDK.
[ -f "$sdk/ndk/$NDK_VERSION/source.properties" ] || { echo "NDK $NDK_VERSION is not installed under $sdk" >&2; exit 1; }
mkdir -p "$bs/sdk/bin"
ln -sfn "$sdk/ndk/$NDK_VERSION" "$bs/sdk/android-ndk-r30"
ln -sfn "$sdk" "$bs/sdk/android-sdk-mac"

# Their buildall.sh means to run on macOS's bash 3, but marks what it has built with `declare -g`,
# which bash 3 lacks; a plain assignment in a function is global there all the same.
sed -i '' 's/declare -g "\$varname=0"/eval "$varname=0"/' "$bs/buildall.sh"

# 3. The LGPL build: our ffmpeg.sh and mpv.sh over theirs, and dependency lists without mbedtls,
#    dav1d (Media3 plays AV1), lua and curl.
cp "$here/mpv-scripts/ffmpeg.sh" "$here/mpv-scripts/mpv.sh" "$bs/scripts/"
cat >>"$bs/include/depinfo.sh" <<'DEPS'

# Aura: LGPL only (app/natives/build-mpv.sh).
dep_ffmpeg=()
dep_mpv=(ffmpeg libass libplacebo)
DEPS

# 4. Sources: the libraries mpv-android fetches, at their versions; mpv, libplacebo and libass at the
#    tags above; FFmpeg from ffmpeg.org, checked against its hash.
. "$bs/include/depinfo.sh"
deps="$bs/deps"
mkdir -p "$deps"
untar() { # url dir [tar flags]
  [ -d "$deps/$2" ] && return 0
  local file="$work/$(basename "$1")"
  if [ ! -f "$file" ]; then
    curl -sSfL --retry 5 --retry-all-errors -o "$file.part" "$1"
    mv "$file.part" "$file"
  fi
  rm -rf "$deps/$2.tmp" && mkdir -p "$deps/$2.tmp"
  tar -x"${3:-z}" -C "$deps/$2.tmp" --strip-components=1 -f "$file"
  mv "$deps/$2.tmp" "$deps/$2"
}
shallow() { # url tag dir
  [ -d "$deps/$3" ] && return 0
  git clone --quiet --depth 1 --recurse-submodules --shallow-submodules -b "$2" "$1" "$deps/$3"
}
if [ ! -d "$deps/ffmpeg" ]; then
  tarball="$work/ffmpeg-$FFMPEG_VERSION.tar.xz"
  # The desktop build's copy, when it has one; else ffmpeg.org — into a temporary name, so a cut-off
  # download is never taken for the tarball.
  desktop="$app/../desktop/natives/work/ffmpeg-$FFMPEG_VERSION.tar.xz"
  if [ ! -f "$tarball" ] && [ -f "$desktop" ]; then cp "$desktop" "$tarball"; fi
  if [ ! -f "$tarball" ]; then
    curl -sSfL --retry 5 --retry-all-errors -o "$tarball.part" "https://ffmpeg.org/releases/ffmpeg-$FFMPEG_VERSION.tar.xz"
    mv "$tarball.part" "$tarball"
  fi
  echo "$FFMPEG_SHA256  $tarball" | shasum -a 256 -c - >&2
  mkdir -p "$deps/ffmpeg.tmp" && tar -xJ -C "$deps/ffmpeg.tmp" --strip-components=1 -f "$tarball"
  mv "$deps/ffmpeg.tmp" "$deps/ffmpeg"
fi
# Our changes to FFmpeg, each applied once — the patches are what differs from the release (LGPL):
# mpv-patches/ffmpeg-mediacodec-stall.patch lets a hardware decoder that gives nothing back fail
# instead of holding mpv up for good, so that mpv decodes in software.
for patch_file in "$here"/mpv-patches/ffmpeg-*.patch; do
  patch -d "$deps/ffmpeg" -p1 -R --dry-run -s -f <"$patch_file" >/dev/null 2>&1 ||
    patch -d "$deps/ffmpeg" -p1 -s -f <"$patch_file"
done
shallow https://gitlab.freedesktop.org/freetype/freetype.git "VER-${v_freetype//./-}" freetype2
untar "https://github.com/fribidi/fribidi/releases/download/v$v_fribidi/fribidi-$v_fribidi.tar.xz" fribidi J
untar "https://github.com/harfbuzz/harfbuzz/releases/download/$v_harfbuzz/harfbuzz-$v_harfbuzz.tar.xz" harfbuzz J
untar "https://github.com/adah1972/libunibreak/releases/download/libunibreak_${v_unibreak//./_}/libunibreak-$v_unibreak.tar.gz" unibreak
untar "https://gitlab.gnome.org/GNOME/libxml2/-/archive/v$v_libxml2/libxml2-v$v_libxml2.tar.gz" libxml2
untar "https://gitlab.freedesktop.org/fontconfig/fontconfig/-/archive/$v_fontconfig/fontconfig-$v_fontconfig.tar.gz" fontconfig
shallow https://github.com/libass/libass "$LIBASS_TAG" libass
shallow https://github.com/haasn/libplacebo "$LIBPLACEBO_TAG" libplacebo
shallow https://github.com/mpv-player/mpv "$MPV_TAG" mpv

# 5. Build mpv and what it needs, for arm64.
(cd "$bs" && ./buildall.sh --arch arm64 mpv)

# 6. The JNI layer, and everything it links, as the app packages them.
prefix="$bs/prefix/arm64"
(cd "$work/mpv-android" && PATH="$sdk/ndk/$NDK_VERSION:$PATH" PREFIX64="$prefix" \
  ndk-build -C app/src/main -j"$(sysctl -n hw.ncpu)")
mkdir -p "$out"
rm -f "$out"/*.so
cp "$work/mpv-android/app/src/main/libs/arm64-v8a/"*.so "$out/"
# Stripped here, as AGP does not strip prebuilt libraries without the project's own NDK: libc++_shared
# alone is 9 MB with its symbols and 1 MB without.
strip_tool=$(ls -d "$sdk/ndk/$NDK_VERSION/toolchains/llvm/prebuilt/"*/bin/llvm-strip | head -1)
for so in "$out"/*.so; do "$strip_tool" --strip-unneeded "$so"; done
ls -la "$out"
