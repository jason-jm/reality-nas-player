#!/bin/bash
# smoke-pano.sh [OUT_DIR] — plays each panorama test clip (make-pano-tests.sh, pushed to the app's files
# folder) from a cold start, checks the sphere the log says was made against what the clip is, and
# lays a screenshot of each (left eye) into OUT_DIR/sheet.png. Prints one line per clip, PASS or FAIL.
#   DELAY seconds after the sphere before the shot (default 10: the emulator's screenshots lag the stage)
#   DEVICE  adb serial (default emulator-5554); a headset's is shot with `capture screenshot --spatial` (pico-cli
#           0.6.1+: the compositor's view from the head pose — it shows the sphere, plain screencap does not)
#   PKG     the app (default com.therealityteam.realityplayer; the headset's side-by-side build is …realityplayer.dev)
# The clips' saved positions are forgotten after (aura.forget_progress since the start; debug builds).
set -u
here="$(cd "$(dirname "$0")" && pwd)"
out="${1:-${TMPDIR:-/tmp}/aura-pano-smoke}"
mkdir -p "$out"
PKG=${PKG:-com.therealityteam.realityplayer}
DIRP=/sdcard/Android/data/$PKG/files
DEVICE=${DEVICE:-emulator-5554}
cap=(); [[ $DEVICE == emulator-* ]] || cap=(--spatial --size 1920x1080)
t0=$(($(date +%s) * 1000))
# file | the sphere's projection and stereo as the log names them ("-" for a film that must stay flat)
cases=(
  "Morning walk.mp4|EQ360 MONO"
  "city_360_tb.mp4|EQ360 OU"
  "Garden tour.mp4|EQ180 SBS"
  "lake_fisheye190.mp4|FISHEYE MONO"
  "stage_eac.mp4|EAC MONO"
  "Concert hall.mp4|EQ360 MONO"
  "Lobby.mp4|CUBEMAP MONO"
  "camera_dfisheye.mp4|DUAL_FISHEYE MONO"
  "hall_c6x1.mp4|CUBEMAP MONO"
  "hall_c1x6.mp4|CUBEMAP MONO"
  "Harbour right first.mkv|EQ360 SBS"
  "Sound walk.mp4|EQ360 MONO"
  "Canyon spatial 180.mov|EQ180 MONO"
  "Atrium padded.mp4|CUBEMAP MONO"
)
fails=0
shots=()
for c in "${cases[@]}"; do
  file="${c%%|*}"
  want="${c##*|}"
  if ! adb -s "$DEVICE" shell ls "'$DIRP/$file'" >/dev/null 2>&1; then echo "SKIP | $file (not on the device)"; continue; fi
  adb -s "$DEVICE" shell am force-stop $PKG
  adb -s "$DEVICE" logcat -c
  adb -s "$DEVICE" shell am start -n $PKG/com.therealityteam.videoplayer.platform.LaunchActivity --es aura.play_uri "'$DIRP/$file'" >/dev/null
  got=""
  for _ in $(seq 1 40); do
    sleep 1
    got=$(adb -s "$DEVICE" logcat -d -s 'AuraPano:*' | grep -o 'sphere: [A-Z_0-9]* [A-Z]*' | tail -1 | sed 's/sphere: //')
    [ -n "$got" ] && break
  done
  sleep "${DELAY:-10}"
  shot="$out/$(echo "$file" | tr ' ' '_').png"
  pico-cli capture screenshot --device "$DEVICE" "${cap[@]}" --out "$shot" --replace >/dev/null 2>&1 && shots+=("$shot")
  crash=$(adb -s "$DEVICE" logcat -d | grep -c "FATAL EXCEPTION")
  if [ "$got" = "$want" ] && [ "$crash" = "0" ]; then echo "PASS | $file | $got"
  else echo "FAIL | $file | got '${got:-nothing}', wanted '$want', crashes $crash"; fails=$((fails + 1)); fi
done
# A contact sheet, four across.
if [ ${#shots[@]} -gt 0 ]; then
  inputs=(); filters=""; i=0
  for s in "${shots[@]}"; do inputs+=(-i "$s"); filters+="[$i]scale=480:-1[s$i];"; i=$((i + 1)); done
  # Blanks the shots' own shape: square on the emulator, 16:9 from a headset.
  h=$(ffprobe -v error -select_streams v -show_entries stream=width,height -of csv=p=0 "${shots[0]}" | awk -F, '{printf "%d", 480 * $2 / $1 / 2 * 2}')
  while [ $((i % 4)) -ne 0 ]; do inputs+=(-f lavfi -i "color=c=black:s=480x$h"); filters+="[$i]scale=480:$h[s$i];"; i=$((i + 1)); done
  rows=""; for r in $(seq 0 $((i / 4 - 1))); do
    filters+="[s$((4*r))][s$((4*r+1))][s$((4*r+2))][s$((4*r+3))]hstack=4[r$r];"; rows+="[r$r]"; done
  ffmpeg -hide_banner -loglevel error -y "${inputs[@]}" -filter_complex "${filters}${rows}vstack=$((i / 4))" -frames:v 1 "$out/sheet.png" && echo "sheet: $out/sheet.png"
fi
adb -s "$DEVICE" shell am force-stop $PKG
adb -s "$DEVICE" shell am start -n $PKG/com.therealityteam.videoplayer.platform.LaunchActivity -e aura.forget_progress "since:$t0" >/dev/null
echo "failures: $fails"
exit $fails
