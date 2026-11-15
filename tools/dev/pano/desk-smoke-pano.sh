#!/bin/bash
# desk-smoke-pano.sh CLIPS_DIR [OUT_DIR] — the desktop player's panorama views checked against the CPU: each test
# clip of make-pano-tests.sh (CLIPS_DIR, with the source frames it made them from) played headless by
# PlaybackProbeTest, three views each (ahead, then two drags), every view compared with the same view worked out
# on the CPU from the clip's source frame (core PanoFlatten — the headset's mapping). One line per clip:
#   PASS when every view's mean difference is within LIMIT (default 12, per channel 0–255: H.264's loss is 6–10)
#   and below the "3° off" control by MARGIN (default 4) — else FAIL, with the numbers.
# Needs no window (a locked screen is fine). Shots land in OUT_DIR (default $TMPDIR/aura-desk-pano).
set -u
clips="${1:?usage: desk-smoke-pano.sh CLIPS_DIR [OUT_DIR]}"
out="${2:-${TMPDIR:-/tmp}/aura-desk-pano}"
LIMIT=${LIMIT:-12}
MARGIN=${MARGIN:-4}
mkdir -p "$out"
cd "$(dirname "$0")/../../../desktop" || exit 2
# file | the source frame it was made from (the left eye's for stereo; the right-eye-first clip's whole frame)
cases=(
  "Morning walk.mp4|eq.png"
  "city_360_tb.mp4|tb.png"
  "Garden tour.mp4|vr180.png"
  "lake_fisheye190.mp4|fish.png"
  "stage_eac.mp4|eac.png"
  "Concert hall.mp4|eac.png"
  "Lobby.mp4|c3x2.png"
  "hall_c6x1.mp4|c6x1.png"
  "hall_c1x6.mp4|c1x6.png"
  "camera_dfisheye.mp4|dfish.png"
  "Harbour right first.mkv|rl.png"
  "Sound walk.mp4|eq.png"
  "Canyon spatial 180.mov|hL.png"
  "Atrium padded.mp4|c3x2_pad.png"
)
fails=0
for c in "${cases[@]}"; do
  f="${c%%|*}"; ref="${c##*|}"
  if [ ! -f "$clips/$f" ] || [ ! -f "$clips/$ref" ]; then echo "SKIP | $f (not in $clips)"; continue; fi
  name=$(echo "$f" | tr ' ' '_')
  lines=$(../gradlew -p . test --tests '*PlaybackProbeTest*' "-Daura.probe.play=$clips/$f" -Daura.probe.start=1000 \
    "-Daura.probe.look=0.3,0.1;0.35,-0.25" "-Daura.probe.shot=$out/${name%.*}.png" "-Daura.probe.ref=$clips/$ref" \
    -Daura.mpv.opts=ao=null -i 2>&1 | grep -E "PLAYPROBE ref look|sphere " | sed 's/^ *//')
  sphere=$(echo "$lines" | grep -o 'sphere [A-Z_0-9]* [A-Z]*.*' | tail -1 | sed 's/sphere //')
  verdict=$(echo "$lines" | grep "PLAYPROBE ref look" | awk -v lim="$LIMIT" -v m="$MARGIN" '
    { d = $(NF-5) + 0; ctl = $NF; sub(/\)/, "", ctl); ctl += 0
      out = out sprintf(" %.1f/%.1f", d, ctl); n++
      if (d > lim || d > ctl - m) bad = 1 }
    END { if (n < 3) bad = 1; printf "%s|%s", (bad ? "FAIL" : "PASS"), out }')
  echo "${verdict%%|*} | $f | ${sphere:-flat} |${verdict#*|}"
  [ "${verdict%%|*}" = "PASS" ] || fails=$((fails + 1))
done
echo "failures: $fails"
exit $fails
