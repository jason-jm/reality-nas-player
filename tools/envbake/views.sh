#!/bin/bash
# views.sh DEVICE OUTPREFIX GLB [LIGHTS] — a place file on the device seen from four vantage points, for comparing versions
# on the headset and on the emulator (whose view is steadier and wider): the seat; the back-right corner, high, over the
# whole room; the stage looking back at the seats; the right aisle looking across the rows. The poses are the private
# theater's (seat coordinates, as aura.env_pose takes them). The film is paused and the window hidden (aura.env_capture).
#   LIGHTS on|off|hold (hold: on while the film plays). FILM=<name in files/> (default "Long film.mp4");
#   NOPAUSE=1 to leave it playing. On the emulator play a silent track (FILM=Silence.m4a NOPAUSE=1): its video sampling
#   crashes MoltenVK. There the lights want LIGHTS=hold: with the user's Aura installed beside the dev one, the pause key
#   can reach the other's session, the film plays on, and 观影灯光 takes the room lights down.
export ADB_LIBUSB=1; D=$1; pre=$2; glb=$3; lights=${4:-on}; P=com.therealityteam.realitynasplayer.dev
F=/sdcard/Android/data/$P/files
# VIEWS="name:x,y,z,yaw …" for another place's vantage points (aura.env_pose's figures); the private theater's by default
VIEWS=${VIEWS:-"seat:0,0.36,5.37,0 overview:2.9,1.3,9.2,-22 stage:0,0.38,0.9,180 side:3.1,0.36,5.6,-90"}
for v in $(echo $VIEWS); do
  n=${v%%:*}; pose=${v#*:}
  adb -s $D shell am force-stop $P
  adb -s $D shell "am start -n $P/com.therealityteam.videoplayer.platform.LaunchActivity --es aura.play_uri '$F/${FILM:-Long film.mp4}' --es aura.env_file $glb --es aura.env_pose $pose --es aura.env_capture 1 --es aura.env_lights $lights" >/dev/null
  sleep ${WAIT:-20}
  [ -z "$NOPAUSE" ] && adb -s $D shell cmd media_session dispatch pause; sleep 4
  case $D in emulator-*) size="" ;; *) size="--spatial --size 3840x2160" ;; esac
  pico-cli capture screenshot --device $D $size --out "${pre}_$n.png" --replace >/dev/null 2>&1
  echo "$n $(ls -la ${pre}_$n.png 2>/dev/null | awk '{print $5}')"
done
adb -s $D shell am force-stop $P
