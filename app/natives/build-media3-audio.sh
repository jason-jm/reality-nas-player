#!/bin/bash
# Rebuild the arm64 JNI library in Aura's Media3 1.8.0 AAR. Its Java classes and metadata stay intact.
# A source delivery can be used offline: AURA_DECODER_SOURCES points at the two files below.
set -euo pipefail
here=$(cd "$(dirname "$0")" && pwd)
app=$(cd "$here/.." && pwd)
ndk="${ANDROID_NDK_HOME:-${ANDROID_HOME:-$HOME/Library/Android/sdk}/ndk/30.0.16248370}"
grep -Eq '^Pkg.Revision[[:space:]]*=[[:space:]]*30\.0\.16248370[[:space:]]*$' "$ndk/source.properties"
tools=("$ndk"/toolchains/llvm/prebuilt/*/bin)
bin=${tools[0]}
cc="$bin/aarch64-linux-android35-clang"
work=$(mktemp -d)
trap 'rm -rf -- "$work"' EXIT
sources=${AURA_DECODER_SOURCES:-$work}
ffmpeg_rev=573f2b81ebd97da81def2ba762e81b0328547004
media_rev=b7bbc6e2bc3e45ff3ed99884c114c50f03bba5c9
ffmpeg="ffmpeg-$ffmpeg_rev.tar.gz"
if [[ -z ${AURA_DECODER_SOURCES:-} ]]; then
    curl -fsSL "https://codeload.github.com/FFmpeg/FFmpeg/tar.gz/$ffmpeg_rev" -o "$sources/$ffmpeg"
    curl -fsSL "https://raw.githubusercontent.com/androidx/media/$media_rev/libraries/decoder_ffmpeg/src/main/jni/ffmpeg_jni.cc" -o "$sources/ffmpeg_jni.cc"
fi
(cd "$sources" && printf '%s  %s\n' \
    b2e888da05e52ac7c23fef4955bc28d6da948b6e2843d4f6cd421ac13fea804d "$ffmpeg" \
    d60f46f794883a16cb200f454b004b224d9ec230ff73cb3aa9ee6a8c913fa407 ffmpeg_jni.cc | shasum -a 256 -c -)
mkdir "$work/ffmpeg"
tar -xzf "$sources/$ffmpeg" -C "$work/ffmpeg" --strip-components=1
cd "$work/ffmpeg"
export LC_ALL=C
# Backport TrueHD substream layout detection from FFmpeg 8.1.1. Media3 supplies packets without
# an already-populated AVCodecContext channel layout, including after a seek.
patch -p1 < "$here/ffmpeg-truehd-layout.patch"
decoders=(ac3 eac3 dca truehd mlp flac vorbis opus alac aac)
options=()
for decoder in "${decoders[@]}"; do options+=("--enable-decoder=$decoder"); done
./configure --target-os=android --arch=aarch64 --cpu=armv8-a --enable-cross-compile \
    --cc="$cc" --nm="$bin/llvm-nm" --ar="$bin/llvm-ar" --ranlib="$bin/llvm-ranlib" --strip="$bin/llvm-strip" \
    --enable-static --enable-pic --disable-shared --disable-everything --disable-autodetect \
    --disable-doc --disable-programs --disable-avdevice --disable-avformat --disable-swscale \
    --disable-postproc --disable-avfilter --disable-symver --disable-debug --disable-v4l2-m2m \
    --disable-vulkan --enable-swresample "${options[@]}"
make -j"${AURA_BUILD_JOBS:-6}"
"$bin/aarch64-linux-android35-clang++" -std=c++11 -O2 -fPIC -fvisibility=hidden -shared \
    -I. "$sources/ffmpeg_jni.cc" -Wl,--start-group \
    libavcodec/libavcodec.a libswresample/libswresample.a libavutil/libavutil.a -Wl,--end-group \
    -landroid -llog -lm -Wl,-Bsymbolic -Wl,--no-undefined -Wl,--exclude-libs,ALL \
    -Wl,-soname,libffmpegJNI.so -Wl,-z,max-page-size=16384 -o "$work/libffmpegJNI.so"
"$bin/llvm-strip" --strip-unneeded "$work/libffmpegJNI.so"
aar="${AURA_DECODER_AAR:-$app/libs/media3-decoder-ffmpeg-1.8.0-arm64.aar}"
python3 - "$aar" "$work/libffmpegJNI.so" <<'PY'
from pathlib import Path
import hashlib
import os
import sys
import tempfile
import zipfile

aar, so = map(Path, sys.argv[1:])
jni = "jni/arm64-v8a/libffmpegJNI.so"
with zipfile.ZipFile(aar) as original:
    entries = original.infolist()
    digest = hashlib.sha256()
    for name in sorted(original.namelist()):
        if not name.endswith("/") and name != jni:
            digest.update(name.encode() + b"\0")
            digest.update(hashlib.sha256(original.read(name)).digest())
    if digest.hexdigest() != "2bd4b0a692ab244f64711447afd0d8281a6569ae4af859d6468386d749011905" or jni not in original.namelist():
        sys.exit("Unexpected base AAR; check the Media3 Java wrapper and integration metadata")
    # Replace only after every entry has been read and the new archive closed.
    temp = tempfile.NamedTemporaryFile(dir=aar.parent, delete=False)
    try:
        with temp, zipfile.ZipFile(temp, "w") as updated:
            for item in entries:
                updated.writestr(item, so.read_bytes() if item.filename == jni else original.read(item))
        os.chmod(temp.name, aar.stat().st_mode & 0o777)
        os.replace(temp.name, aar)
    finally:
        Path(temp.name).unlink(missing_ok=True)
print(f"{aar}: libffmpegJNI.so SHA-256 {hashlib.sha256(so.read_bytes()).hexdigest()}")
PY
