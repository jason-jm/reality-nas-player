#!/bin/bash -e
# FFmpeg for the headset's libmpv (app/natives/build-mpv.sh): mpv-android's script, LGPL. No
# --enable-gpl or --enable-version3, so no mbedtls (mpv reads http://127.0.0.1) and no dav1d (Media3
# plays AV1); every LGPL decoder, demuxer and parser stays on — the point is to read old files —
# and the PNG encoder, for mpv's screenshots.

. ../../include/path.sh

if [ "$1" == "build" ]; then
	true
elif [ "$1" == "clean" ]; then
	rm -rf _build$ndk_suffix
	exit 0
else
	exit 255
fi

mkdir -p _build$ndk_suffix
cd _build$ndk_suffix

cpu=armv8-a
[[ "$ndk_triple" == "aarch64"* ]] || { echo "arm64 only" >&2; exit 1; }

args=(
	--target-os=android --enable-cross-compile
	--cross-prefix=$ndk_triple- --cc=$CC --pkg-config=pkg-config --nm=llvm-nm
	--arch=${ndk_triple%%-*} --cpu=$cpu
	--extra-cflags="-I$prefix_dir/include" --extra-ldflags="-L$prefix_dir/lib"

	--enable-{jni,mediacodec} --disable-vulkan
	--disable-static --enable-shared
	--disable-{stripping,doc,programs}
	--disable-{muxers,encoders,devices}
	# 截图: mpv writes the frame as a PNG with FFmpeg's encoder — the only encoder built.
	--enable-encoder=png
)
../configure "${args[@]}"

# LGPL, as configured: refuse to install anything else.
grep -q '^#define CONFIG_GPL 0' config.h
grep -q '^#define CONFIG_VERSION3 0' config.h

make -j$cores
make DESTDIR="$prefix_dir" install
