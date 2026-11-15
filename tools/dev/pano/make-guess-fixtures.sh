#!/bin/bash
# make-guess-fixtures.sh OUT_DIR — the frames ProjectionGuessTest reads: panoramas built the way cameras'
# stitchers make them (six textured cube faces → FFmpeg v360 → each projection) and flat pictures of the
# same textures, all FFmpeg-generated (fractals, cellular automata, test cards: nothing anyone owns).
set -euo pipefail
out="${1:?out dir}"
mkdir -p "$out"
cd "$out"
q() { ffmpeg -hide_banner -loglevel error -y "$@"; }
# Six different faces, 512 px each, laid out 6x1 (FFmpeg's c6x1: right left up down front back): busy
# walls, a smooth sky above, a fractal ground below — nothing sharp across the up and down faces' middle
# column, where the back seam runs (a test card's bar edge there would break the seam on its own).
q -f lavfi -i "cellauto=s=512x512:rule=110:scroll=0" -f lavfi -i "testsrc2=s=512x512" \
  -f lavfi -i "gradients=s=512x512:c0=0x6080b0:c1=0xa0c0e0:n=2:type=linear" \
  -f lavfi -i "mandelbrot=s=512x512:start_x=-0.75:start_y=0.1:start_scale=0.4" -f lavfi -i "mandelbrot=s=512x512:start_scale=2.5" \
  -f lavfi -i "life=s=512x512:mold=10:r=1:ratio=0.3:death_color=#202848:life_color=#d0c080:mold_color=#406020" \
  -filter_complex "[0][1][2][3][4][5]hstack=6,trim=end_frame=1" -frames:v 1 cube.png
v() { q -i "$1" -vf "v360=$2,format=rgb24" -frames:v 1 "$3"; }
v cube.png "c6x1:e:w=1024:h=512" eq.png
v cube.png "c6x1:e:w=1024:h=512:yaw=2" eq_r.png
# Positives.
cp eq.png pos_eq360.png
q -i eq.png -i eq_r.png -filter_complex "[0][1]vstack" -frames:v 1 pos_eq360_tb.png
v eq.png "e:hequirect:w=512:h=512" h_l.png
v eq_r.png "e:hequirect:w=512:h=512" h_r.png
q -i h_l.png -i h_r.png -filter_complex "[0][1]hstack" -frames:v 1 pos_vr180_sbs.png
# A lens draws a circle: outside it the sensor is black (v360 fills it in, so it is masked here).
circle="if(lte(hypot(mod(X\,H)-H/2\,Y-H/2)\,H/2)\,1\,0)"
mask() { q -i "$1" -vf "format=rgb24,geq=r='r(X\,Y)*$circle':g='g(X\,Y)*$circle':b='b(X\,Y)*$circle'" -frames:v 1 "$2"; }
v eq.png "e:fisheye:h_fov=190:v_fov=190:w=512:h=512" f_l.png
v eq_r.png "e:fisheye:h_fov=190:v_fov=190:w=512:h=512" f_r.png
mask f_l.png pos_fisheye.png
q -i f_l.png -i f_r.png -filter_complex "[0][1]hstack" -frames:v 1 f_lr.png
mask f_lr.png pos_fisheye_sbs.png
v eq.png "e:dfisheye:h_fov=190:v_fov=190:w=1024:h=512" df.png
mask df.png pos_dfisheye.png
# Negatives: flat pictures of the same textures, at the panoramas' shapes.
v eq.png "e:flat:h_fov=110:v_fov=70:w=1024:h=512" neg_flat_2x1.png
v eq.png "e:flat:h_fov=80:v_fov=80:w=512:h=512:yaw=60" neg_flat_1x1.png
q -f lavfi -i "testsrc2=s=1024x512" -frames:v 1 neg_card_2x1.png
q -f lavfi -i "mandelbrot=s=1024x400:start_scale=1.2" -vf "pad=1024:512:0:56:black,trim=end_frame=1" -frames:v 1 neg_letterbox_2x1.png
# A flat picture with dark corners (a vignette), and two flat pictures side by side (a 3D film's halves are not 1:1).
q -i neg_flat_1x1.png -vf "vignette=angle=PI/3" -frames:v 1 neg_vignette_1x1.png
rm -f cube.png eq.png eq_r.png h_l.png h_r.png f_l.png f_r.png f_lr.png df.png
# Small as a video's frames are read for the guess (512 wide), and coded as video is (JPEG's blocks).
for f in *.png; do
  ffmpeg -hide_banner -loglevel error -y -i "$f" -vf "scale='min(512,iw)':-2:flags=area" -q:v 3 "${f%.png}.jpg" && rm -f "$f"
done
ls -la
