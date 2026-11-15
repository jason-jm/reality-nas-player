#!/bin/bash
# make-pano-tests.sh [OUT_DIR] — the 360°/180° test clips for the immersive player, from one labelled
# equirectangular frame per eye (test-pano.swift), converted by FFmpeg's v360 into each projection, 60 s of
# H.264 each. Some carry Google's spherical metadata (inject-spherical.py) and plain names — the file's own
# word must be read; others only say it in their names, as downloads do. Push them to the emulator with
#   adb push OUT_DIR/*.mp4 /sdcard/Android/data/com.therealityteam.videoplayer/files/
set -euo pipefail
here="$(cd "$(dirname "$0")" && pwd)"
out="${1:-${TMPDIR:-/tmp}/aura-pano}"
mkdir -p "$out"
cd "$out"

swift "$here/test-pano.swift" eq.png 2048 1024
swift "$here/test-pano.swift" eqL.png 2048 1024 L
swift "$here/test-pano.swift" eqR.png 2048 1024 R

conv() { ffmpeg -hide_banner -loglevel error -y -i "$1" -vf "v360=$2,format=rgb24" -frames:v 1 "$3"; }
clip() { ffmpeg -hide_banner -loglevel error -y -loop 1 -i "$1" -t 60 -r 30 -c:v libx264 -pix_fmt yuv420p -preset veryfast -crf 22 "$2"; }

# Whole sphere, mono: the file's metadata says so (a name with no marks).
clip eq.png plain.mp4
python3 "$here/inject-spherical.py" plain.mp4 "Morning walk.mp4"
# Whole sphere, top-and-bottom stereo, by name only.
ffmpeg -hide_banner -loglevel error -y -i eqL.png -i eqR.png -filter_complex "[0][1]vstack" -frames:v 1 tb.png
clip tb.png "city_360_tb.mp4"
# VR180, side by side, metadata (st3d left-right + equi bounds) and a plain name.
conv eqL.png "e:hequirect:interp=linear:w=1024:h=1024" hL.png
conv eqR.png "e:hequirect:interp=linear:w=1024:h=1024" hR.png
ffmpeg -hide_banner -loglevel error -y -i hL.png -i hR.png -filter_complex "[0][1]hstack" -frames:v 1 vr180.png
clip vr180.png plain180.mp4
python3 "$here/inject-spherical.py" plain180.mp4 "Garden tour.mp4" --stereo left-right --bounds 0,0,0.25,0.25
rm -f plain.mp4 plain180.mp4
# A 190-degree fisheye, by name.
conv eq.png "e:fisheye:interp=linear:h_fov=190:v_fov=190:w=1440:h=1440" fish.png
clip fish.png "lake_fisheye190.mp4"
# YouTube's equi-angular cubemap, by name.
conv eq.png "e:eac:interp=linear:w=1536:h=1024" eac.png
clip eac.png "stage_eac.mp4"
# YouTube's way: the EAC frame with a mesh projection (mshp, deflated) — FFmpeg cannot read this one.
clip eac.png plaineac.mp4
python3 "$here/inject-spherical.py" plaineac.mp4 "Concert hall.mp4" --projection mesh-eac
rm -f plaineac.mp4
# Google's 3x2 cubemap, metadata (cbmp).
conv eq.png "e:c3x2:interp=linear:w=1536:h=1024" c3x2.png
clip c3x2.png plaincube.mp4
python3 "$here/inject-spherical.py" plaincube.mp4 "Lobby.mp4" --projection cubemap
rm -f plaincube.mp4
# A padded cubemap (cbmp padding 8: each face's picture 8 pixels in from its edges — FFmpeg's fout_pad 16).
conv eq.png "e:c3x2:fout_pad=16:interp=linear:w=1536:h=1024" c3x2_pad.png
clip c3x2_pad.png plainpad.mp4
python3 "$here/inject-spherical.py" plainpad.mp4 "Atrium padded.mp4" --projection cubemap --padding 8
rm -f plainpad.mp4
# Cubemap strips, 6x1 and 1x6, by name: the same faces in one row or one column.
conv eq.png "e:c6x1:interp=linear:w=1536:h=256" c6x1.png
clip c6x1.png "hall_c6x1.mp4"
conv eq.png "e:c1x6:interp=linear:w=256:h=1536" c1x6.png
clip c1x6.png "hall_c1x6.mp4"
# A 360 camera's two fisheye circles, by name.
conv eq.png "e:dfisheye:interp=linear:h_fov=190:v_fov=190:w=2048:h=1024" dfish.png
clip dfish.png "camera_dfisheye.mp4"
# Stereo with the RIGHT eye's picture first (Matroska StereoMode 11): shown flat, its left eye's picture to
# both eyes — the screenshot (left eye) must read "EYE L".
ffmpeg -hide_banner -loglevel error -y -i eqR.png -i eqL.png -filter_complex "[0][1]hstack" -frames:v 1 rl.png
clip rl.png plainrl.mp4
python3 "$here/inject-spherical.py" plainrl.mp4 metarl.mp4 --stereo left-right
ffmpeg -hide_banner -loglevel error -y -i metarl.mp4 -c copy -metadata:s:v stereo_mode=right_left "Harbour right first.mkv"
rm -f plainrl.mp4 metarl.mp4
# Apple's MV-HEVC (make-spatial.swift: VideoToolbox, macOS 26+, as an iPhone's spatial video and Apple's projected
# media are written): the eyes coded as two layers, with Apple's vexu. A VR180 (hequ) and a flat spatial video
# (rect, 64° across, 64 mm apart). A decoder that takes HEVC but not its second layer may give no picture (the
# emulator's): the player then goes on in mpv, whose FFmpeg decodes the base layer — the left eye.
swift "$here/make-spatial.swift" hL.png hR.png "Canyon spatial 180.mov" 60 hequ
for e in L R; do conv eq$e.png "e:flat:interp=linear:h_fov=64:v_fov=38:w=1280:h=720" flat$e.png; done
swift "$here/make-spatial.swift" flatL.png flatR.png "IMG_0042 spatial.mov" 60 rect
# 全景声: first-order ambisonics (ACN/SN3D: W, Y, Z, X) of pink-noise bursts walking round — ahead, left,
# behind, right, above, ahead again, 10 s each — as 4-channel AAC with SA3D (and the 360 metadata), then
# as Opus channel mapping family 2 in WebM (a flat VP9 picture: the sound alone).
walk="val(0)*lt(mod(t\,1)\,0.6)"
ffmpeg -hide_banner -loglevel error -y -loop 1 -i eq.png -f lavfi -i "anoisesrc=c=pink:a=0.35:d=60:r=48000" \
  -filter_complex "[1]aeval=exprs='$walk|$walk*(gte(t\,10)*lt(t\,20)-gte(t\,30)*lt(t\,40))|$walk*gte(t\,40)*lt(t\,50)|$walk*(lt(t\,10)+gte(t\,50)-gte(t\,20)*lt(t\,30))':c=4.0[a]" \
  -map 0:v -map "[a]" -t 60 -r 30 -c:v libx264 -pix_fmt yuv420p -preset veryfast -crf 22 -c:a aac -b:a 256k -ac 4 -movflags +faststart plainfield.mp4
python3 "$here/inject-spherical.py" plainfield.mp4 "Sound walk.mp4" --ambisonic 1
ffmpeg -hide_banner -loglevel error -y -i plainfield.mp4 -t 30 -vf scale=1024:512 -c:v libvpx-vp9 -b:v 600k -deadline realtime -cpu-used 8 \
  -c:a libopus -mapping_family 2 -b:a 256k "Sound walk opus.webm"
rm -f plainfield.mp4
ls -la "$out"/*.mp4 "$out"/*.webm "$out"/*.mkv "$out"/*.mov
