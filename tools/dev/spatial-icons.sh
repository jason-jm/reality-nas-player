#!/bin/sh
# Build the OS 6 launcher resources from the three store layers with ImageMagick (Q16).
set -eu
cd "$(dirname "$0")/../.."
src=docs/store/icon
res=app/src/main/res/drawable-nodpi
tmp=$(mktemp -d)
trap 'rm -rf "$tmp"' EXIT
# The PICO SDK template uses a circular base on a transparent canvas.
magick "$src/icon_bg.png" '(' -size 1024x1024 xc:black -fill white -draw 'circle 511.5,511.5 511.5,0' ')' \
    -alpha off -compose CopyOpacity -composite -depth 8 "PNG32:$res/icon_3d_base.png"
cp "$src/icon_fg2.png" "$res/icon_3d_ring.png"
cp "$src/icon_fg3.png" "$res/icon_3d_play.png"
for layer in base ring play; do
    # Like the 6.2 template, the interior distance ramps to opaque white over 32 pixels.
    magick "$res/icon_3d_$layer.png" -alpha extract -threshold 50% -bordercolor black -border 1 \
        -morphology Distance Euclidean:4,1 -shave 1x1 -evaluate subtract 0.5 \
        -evaluate multiply 2048 -depth 16 "$tmp/distance.miff"
    magick "$tmp/distance.miff" "$tmp/distance.miff" -alpha off -compose CopyOpacity -composite \
        -set colorspace sRGB -depth 8 "PNG32:$res/icon_3d_sdf_$layer.png"
done
