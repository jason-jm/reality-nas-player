#!/bin/bash
# make-disc-tests.sh OUT_DIR [SECONDS] [SIZE] — disc images (原盘 .iso) to test DiscTitle and the player with,
# made the way discs are: UDF by macOS's own newfs_udf (2.50 with its metadata partition for a Blu-ray,
# 1.02 for a DVD), mounted out of sight (-nobrowse), filled, and detached.
#   Test BD.iso        BDMV: 00000.mpls plays 00800 + 00801 (the film, SECONDS each), 00001.mpls plays
#                      00001 (an extra, 2 s), 00002.mpls names a clip the disc lacks (skipped)
#   Test DVD.iso       VIDEO_TS: VTS_01_0 (menu), VTS_01_1 + VTS_01_2 (the film), VTS_02_1 (an extra)
#   Marked BD.iso      the BD with every 6144-byte unit's copy-permission bits set, its packets plain (a decrypted backup)
#   Encrypted BD.iso   the same with each unit past its first 16 bytes noise, as AACS leaves it
#   Software.iso       a UDF image of files, no film
# SIZE is the picture (default 320x180). Needs ffmpeg and python3.
set -euo pipefail
out="${1:?usage: make-disc-tests.sh OUT_DIR [SECONDS] [SIZE]}"
secs="${2:-2}"
size="${3:-320x180}"
mkdir -p "$out"
out="$(cd "$out" && pwd)"
work="$(mktemp -d)"
trap 'rm -rf "$work"' EXIT

ff() { ffmpeg -hide_banner -loglevel error -y "$@"; }

# ---- Blu-ray -------------------------------------------------------------------------------
bd="$work/bd"
mkdir -p "$bd/BDMV/PLAYLIST" "$bd/BDMV/STREAM" "$bd/BDMV/CLIPINF"
clip() { # name seconds source
  ff -f lavfi -i "$3=s=$size:r=24:d=$2" -f lavfi -i "sine=f=440:d=$2" -shortest \
    -c:v libx264 -preset veryfast -pix_fmt yuv420p -g 24 -c:a ac3 -b:a 96k \
    -f mpegts -mpegts_m2ts_mode 1 "$bd/BDMV/STREAM/$1.m2ts"
}
clip 00800 "$secs" testsrc2
clip 00801 "$secs" smptehdbars
clip 00001 2 rgbtestsrc
python3 - "$bd/BDMV/PLAYLIST" "$secs" <<'EOF'
import struct, sys, os
d, secs = sys.argv[1], float(sys.argv[2])
def mpls(items):
    body = b""
    for clip, length in items:
        item = clip.encode() + b"M2TS" + struct.pack(">HB", 0, 0) + struct.pack(">II", 0, int(length * 45000))
        item += b"\0" * 8 + b"\0\0\0\0"            # UO mask, random access flag, still mode / time
        item += struct.pack(">H", 14) + b"\0" * 14   # an empty STN table
        body += struct.pack(">H", len(item)) + item
    playlist = struct.pack(">IHHH", 6 + len(body), 0, len(items), 0) + body
    app = struct.pack(">I", 14) + b"\0" * 14
    start = 40 + len(app)
    mark = struct.pack(">IH", 2, 0)
    head = b"MPLS0200" + struct.pack(">III", start, start + len(playlist), 0) + b"\0" * 20
    return head + app + playlist + mark
open(os.path.join(d, "00000.mpls"), "wb").write(mpls([("00800", secs), ("00801", secs)]))
open(os.path.join(d, "00001.mpls"), "wb").write(mpls([("00001", 2)]))
open(os.path.join(d, "00002.mpls"), "wb").write(mpls([("00999", 900)]))
EOF

# A UDF image holding the folder $2, as $1: formatted by newfs_udf at revision $3, filled while mounted.
image() {
  local iso="$1" src="$2" rev="$3" extra="${4:-}"
  local kb blocks
  kb=$(du -sk "$src" | cut -f1)
  blocks=$(( (kb * 1024 / 2048) * 12 / 10 + 1024 ))
  rm -f "$iso"
  dd if=/dev/zero of="$iso" bs=2048 count="$blocks" 2>/dev/null
  /sbin/newfs_udf -r "$rev" $extra -v "AURA_DISC_$$" "$iso" >/dev/null
  local line dev mnt
  # (hdiutil's columns are tab-separated; a second volume of the same name is "DISC 1".)
  line=$(hdiutil attach -nobrowse -imagekey diskimage-class=CRawDiskImage "$iso" 2>/dev/null | grep '/Volumes/')
  dev=$(echo "$line" | awk '{print $1}')
  mnt=$(echo "$line" | awk -F'\t' '{print $NF}' | sed 's/^[[:space:]]*//')
  cp -R "$src"/. "$mnt"/
  # (macOS keeps its ._ and .DS_Store files off a disc image: none were made by cp -R from a plain folder.)
  hdiutil detach "$dev" >/dev/null
}
image "$out/Test BD.iso" "$bd" 2.50 -e

# AACS's mark: each aligned unit's first packet header with its copy-permission bits set — as a decrypted
# backup can keep it (Marked BD, which plays) — then each unit past its first 16 bytes made noise, as AACS
# leaves it (Encrypted BD).
aacs() { # mark | scramble
python3 - "$bd/BDMV/STREAM" "$1" <<'EOF'
import os, sys, random
d, how = sys.argv[1], sys.argv[2]
random.seed(7)
for n in os.listdir(d):
    p = os.path.join(d, n); b = bytearray(open(p, "rb").read())
    for u in range(0, len(b), 6144):
        b[u] |= 0xC0
        if how == "scramble":
            for i in range(u + 16, min(u + 6144, len(b))): b[i] ^= random.randrange(1, 256)
    open(p, "wb").write(b)
EOF
}
aacs mark
image "$out/Marked BD.iso" "$bd" 2.50 -e
aacs scramble
image "$out/Encrypted BD.iso" "$bd" 2.50 -e

# ---- DVD -----------------------------------------------------------------------------------
dvd="$work/dvd/VIDEO_TS"
mkdir -p "$dvd"
vob() { # name seconds source
  ff -f lavfi -i "$3=s=720x480:r=30000/1001:d=$2" -f lavfi -i "sine=f=660:d=$2" -shortest \
    -c:v mpeg2video -b:v 400k -c:a ac3 -b:a 128k -f vob "$dvd/$1"
}
vob VTS_01_0.VOB 1 rgbtestsrc
vob VTS_01_1.VOB "$secs" testsrc2
vob VTS_01_2.VOB "$secs" smptebars
vob VTS_02_1.VOB 1 rgbtestsrc
image "$out/Test DVD.iso" "$work/dvd" 1.02

# ---- Not a film ----------------------------------------------------------------------------
soft="$work/soft"
mkdir -p "$soft/setup"
head -c 20000 /dev/urandom > "$soft/setup/install.bin"
echo "readme" > "$soft/README.txt"
image "$out/Software.iso" "$soft" 2.50 -e

ls -la "$out"
