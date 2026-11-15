#!/bin/bash
# perf_place.sh NAME GLB LIGHTS — the headset's GPU cost of a place file: the long test film playing on the private
# theater's screen in it (seat and screen of the catalogue entry), room lights LIGHTS (hold|off); 30 s to settle, then a
# 20 s Perfetto trace with a live GPU report beside it. Prints frames and their kinds (Miss/Late), GPU ms, and GPU load =
# use × clock (healthy ≈ 420, ceiling ≈ 600), and the app's graphics memory. Traces go to ./traces/.
SP=$(cd "$(dirname "$0")" && pwd); D=${DEVICE:-PB3310PGL7200077B}; PKG=com.therealityteam.videoplayer.dev; P=/sdcard/Android/data/$PKG/files
export ADB_LIBUSB=1
name=$1; env=$2; lights=$3
adb -s $D shell am force-stop $PKG; sleep 1
# POSE / SCREEN: the private theater's seat and screen by default (for a place file); empty for a catalogue entry's own
POSE=${POSE-0,0.36,5.37,0}; SCREEN=${SCREEN-0,2.0798,0.07,5.4,0,2.39,1}
adb -s $D shell am start -n $PKG/com.therealityteam.videoplayer.platform.LaunchActivity --es aura.play_uri "'$P/Long film.mp4'" \
  --es aura.env "'$env'" --es aura.env_lights "$lights" ${POSE:+--es aura.env_pose $POSE} ${SCREEN:+--es aura.env_screen $SCREEN} >/dev/null
sleep 30
mkdir -p traces
live=$(pico-cli perf live start --package $PKG --device $D -o traces/$name.live.json 2>&1 | grep -oE '[0-9a-f]{8}-[0-9a-f-]{27}' | head -1)
pico-cli perf trace record -d $D -a $PKG -t 20 -o traces/$name.perfetto-trace >/dev/null 2>&1
[ -n "$live" ] && pico-cli perf live stop $live >/dev/null 2>&1
sleep 2
python3 $SP/framestats.py traces/$name.perfetto-trace | python3 -c "
import json,sys; j=json.loads(sys.stdin.read()); print('$name', 'frames', j['frames'], 'gpu_ms', j['gpu_ms']['avg'], 'p95', j['gpu_ms']['p95'], 'kinds', j.get('frame_kinds'), 'triangles', j.get('max_triangles'))"
python3 - "traces/$name.live.json" "$name" <<'PY'
import json, sys
s = json.load(open(sys.argv[1]))['summary']
u, f = s['gpuUsage']['avg'], s['gpuFreq']['avg']
print(sys.argv[2], 'gpu use', u, '% at', f, 'MHz: load', round(u * f / 100), '; system fps', s['sysFps']['avg'])
PY
adb -s $D shell dumpsys meminfo $PKG | grep -E "Graphics:" | sed "s/^/$name /"
