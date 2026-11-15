#!/bin/bash
# libauraframes.so: the JNI layer that takes keyframes out of a video with the FFmpeg inside the app
# (app/natives/frames/aura_frames.c → FrameNative in the app) — 章节 pictures for what the platform's
# software decoders cannot read. FFmpeg itself is the libav*.so build-mpv.sh puts beside libmpv.so:
# this links against those, so nothing of FFmpeg is added. Its headers are that build's own
# (AURA_FFMPEG_INCLUDE, by default the mpv-android build tree's prefix). build-mpv.sh first, then this.
#
#   app/natives/build-frames.sh     # → app/src/main/jniLibs/arm64-v8a/libauraframes.so
set -euo pipefail

NDK_VERSION=30.0.16248370
API=35

here="$(cd "$(dirname "$0")" && pwd)"
app="$(cd "$here/.." && pwd)"
sdk="${ANDROID_HOME:-$HOME/Library/Android/sdk}"
out="$app/src/main/jniLibs/arm64-v8a"
include="${AURA_FFMPEG_INCLUDE:-$HOME/aura-native/mpv/mpv-android/buildscripts/prefix/arm64/include}"
bin=$(ls -d "$sdk/ndk/$NDK_VERSION/toolchains/llvm/prebuilt/"*/bin | head -1)
for lib in avformat avcodec avutil swscale; do
  [ -f "$out/lib$lib.so" ] || { echo "no lib$lib.so in $out: run build-mpv.sh first" >&2; exit 1; }
done
[ -f "$include/libavformat/avformat.h" ] || { echo "no FFmpeg headers in $include (set AURA_FFMPEG_INCLUDE)" >&2; exit 1; }

"$bin/aarch64-linux-android$API-clang" -shared -fPIC -O2 -Wall -Wextra -Werror \
  -I"$include" -o "$out/libauraframes.so" "$here/frames/aura_frames.c" \
  -L"$out" -lavformat -lavcodec -lavutil -lswscale -llog -Wl,-soname,libauraframes.so -Wl,--no-undefined
"$bin/llvm-strip" --strip-unneeded "$out/libauraframes.so"
ls -la "$out/libauraframes.so"
