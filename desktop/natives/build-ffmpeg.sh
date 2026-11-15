#!/bin/bash
# FFmpeg for the desktop player, built to play what a home library holds.
#
# mediamp's mpv runtime carries an FFmpeg cut down to what a streaming app needs: 40 decoders, a
# few containers. A library on a NAS holds more than that — FLV (no demuxer: the files do not
# open), MPEG-1/2 program streams and VOBs, MJPEG and DV from cameras, VP8, ProRes, DVD LPCM… —
# and those played as sound over black, or not at all. This builds the same FFmpeg major (libavcodec
# 62, so mediamp's libmpv takes it unchanged) with every LGPL decoder, demuxer, parser and filter,
# VideoToolbox, and dav1d for AV1 on Macs without AV1 hardware. No GPL parts; shared libraries,
# replaced into mediamp's runtime jar by the build (TrimNativeJar in build.gradle.kts).
#
#   desktop/natives/build-ffmpeg.sh          # → desktop/natives/out/macos-<arch>/*.dylib
#
# Sources are fetched from ffmpeg.org and videolan.org and checked against the hashes below, or
# taken from FFMPEG_TARBALL / DAV1D_TARBALL when set. Needs: clang (Xcode tools), python3, ninja.
set -euo pipefail

FFMPEG_VERSION=8.1.1
FFMPEG_SHA256=b6863adde98898f42602017462871b5f6333e65aec803fdd7a6308639c52edf3
DAV1D_VERSION=1.5.3
DAV1D_SHA256=732010aa5ef461fa93355ed2c6c5fedb48ddc4b74e697eaabe8907eaeb943011
MACOS_MIN=12.0

here="$(cd "$(dirname "$0")" && pwd)"
case "$(uname -s)" in
  Darwin) platform=macos ;;
  *) echo "only macOS is built here so far" >&2; exit 1 ;;
esac
arch="$(uname -m)"; [ "$arch" = "x86_64" ] && arch=x64
out="$here/out/$platform-$arch"
work="${WORK_DIR:-$here/work}"
mkdir -p "$work" "$out"

fetch() { # url sha256 local-override → path
  local url=$1 sum=$2 local=$3 file="$work/$(basename "$1")"
  if [ -n "$local" ]; then file="$local"; elif [ ! -f "$file" ]; then curl -sSfL -o "$file" "$url"; fi
  echo "$sum  $file" | shasum -a 256 -c - >&2
  echo "$file"
}

ff_tar=$(fetch "https://ffmpeg.org/releases/ffmpeg-$FFMPEG_VERSION.tar.xz" "$FFMPEG_SHA256" "${FFMPEG_TARBALL:-}")
dav1d_tar=$(fetch "https://downloads.videolan.org/pub/videolan/dav1d/$DAV1D_VERSION/dav1d-$DAV1D_VERSION.tar.xz" "$DAV1D_SHA256" "${DAV1D_TARBALL:-}")

flags="-arch $(uname -m) -mmacosx-version-min=$MACOS_MIN"

# dav1d, static, through meson in a private virtualenv.
if [ ! -f "$work/dav1d/lib/libdav1d.a" ]; then
  rm -rf "$work/dav1d-$DAV1D_VERSION" && tar xf "$dav1d_tar" -C "$work"
  [ -x "$work/venv/bin/meson" ] || { python3 -m venv "$work/venv" && "$work/venv/bin/pip" install -q meson; }
  (cd "$work/dav1d-$DAV1D_VERSION" && PATH="$work/venv/bin:$PATH" CFLAGS="$flags" LDFLAGS="$flags" \
    meson setup build --buildtype=release --default-library=static -Denable_tools=false -Denable_tests=false \
      -Denable_examples=false --prefix="$work/dav1d" >/dev/null && ninja -C build install >/dev/null)
fi

# Just enough pkg-config for FFmpeg's configure to find that dav1d.
cat > "$work/pkg-config" <<EOF
#!/bin/sh
case " \$* " in
  *" --version "*) echo 2.0.0 ;;
  *" --exists "*) case "\$*" in *dav1d*) exit 0 ;; *) exit 1 ;; esac ;;
  *" --cflags "*) case "\$*" in *dav1d*) echo "-I$work/dav1d/include" ;; esac ;;
  *" --libs "*) case "\$*" in *dav1d*) echo "$work/dav1d/lib/libdav1d.a" ;; esac ;;
  *" --modversion "*) echo $DAV1D_VERSION ;;
  *) exit 1 ;;
esac
EOF
chmod +x "$work/pkg-config"

rm -rf "$work/ffmpeg-$FFMPEG_VERSION" "$work/ffmpeg-install" && tar xf "$ff_tar" -C "$work"
cd "$work/ffmpeg-$FFMPEG_VERSION"
./configure --prefix="$work/ffmpeg-install" --install-name-dir=@loader_path \
  --disable-static --enable-shared --disable-programs --disable-doc --disable-debug --disable-autodetect \
  --arch="$(uname -m)" --target-os=darwin --cc=clang --cxx=clang++ \
  --extra-cflags="$flags" --extra-ldflags="$flags" --extra-libs=-liconv \
  --enable-securetransport --enable-videotoolbox --enable-zlib --enable-bzlib --enable-iconv \
  --enable-libdav1d --pkg-config="$work/pkg-config" \
  --disable-encoders --enable-encoder=mpeg4,aac,srt,png,mjpeg \
  --disable-muxers --enable-muxer=mp4,matroska,mpegts \
  --disable-devices --enable-indev=lavfi >/dev/null
make -j"$(sysctl -n hw.ncpu)" >/dev/null
make install >/dev/null

# The names everything links by (@loader_path/libavcodec.62.dylib), nothing else.
rm -f "$out"/*.dylib
for lib in libavcodec libavdevice libavfilter libavformat libavutil libswresample libswscale; do
  cp "$(ls "$work/ffmpeg-install/lib/$lib".*.dylib | grep -E "/$lib\.[0-9]+\.dylib$")" "$out/"
done
echo "FFmpeg $FFMPEG_VERSION (LGPL, dav1d $DAV1D_VERSION) → $out:"
ls -l "$out"
