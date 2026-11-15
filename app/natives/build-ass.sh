#!/bin/bash
# libaurass.so: the JNI layer that draws the .ass subtitles Media3 plays with libass
# (app/natives/ass/aura_ass.c → AssNative in the app). libass itself is the one in libmpv.so,
# which exports it (app/natives/build-mpv.sh): this links against that, so nothing of libass is
# added — build-mpv.sh first, then this. The headers in ass/include are libass 0.17.5's (ISC).
#
#   app/natives/build-ass.sh     # → app/src/main/jniLibs/arm64-v8a/libaurass.so
set -euo pipefail

NDK_VERSION=30.0.16248370
API=35

here="$(cd "$(dirname "$0")" && pwd)"
app="$(cd "$here/.." && pwd)"
sdk="${ANDROID_HOME:-$HOME/Library/Android/sdk}"
out="$app/src/main/jniLibs/arm64-v8a"
bin=$(ls -d "$sdk/ndk/$NDK_VERSION/toolchains/llvm/prebuilt/"*/bin | head -1)
[ -f "$out/libmpv.so" ] || { echo "no libmpv.so in $out: run build-mpv.sh first" >&2; exit 1; }

"$bin/aarch64-linux-android$API-clang" -shared -fPIC -O2 -Wall -Wextra -Werror \
  -I"$here/ass/include" -o "$out/libaurass.so" "$here/ass/aura_ass.c" \
  -L"$out" -lmpv -ljnigraphics -llog -Wl,-soname,libaurass.so -Wl,--no-undefined
"$bin/llvm-strip" --strip-unneeded "$out/libaurass.so"
ls -la "$out/libaurass.so"
