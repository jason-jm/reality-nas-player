"""Create a 4:3 test movie with Blu-ray bitmap subtitles and a frame marking their authored position."""
import argparse
import itertools
import struct
import subprocess
from pathlib import Path
from tempfile import TemporaryDirectory


def encode_image(font, width, height, label="PLATFORM 7"):
    pixels = subprocess.check_output([
        "magick", "-size", f"{width}x{height}", "xc:black", "-font", font,
        "-pointsize", "35", "-fill", "white", "-gravity", "center", "-annotate", "0", label,
        "-threshold", "50%", "-depth", "8", "gray:-",
    ])
    if len(pixels) != width * height:
        raise ValueError("Unexpected grayscale bitmap size")
    data = bytearray()
    for row in range(height):
        for pixel, run in itertools.groupby(pixels[row * width:(row + 1) * width]):
            length = sum(1 for _ in run)
            data.extend((0, 0xc0 | (length >> 8), length & 255, 2 if pixel else 1))
        data.extend((0, 0))  # End of scanline.
    return data


def write_display(stream, seconds, sequence, position, size, image, color=(210, 16, 146)):
    pts = int(seconds * 90000)

    def segment(kind, data=b""):
        stream.write(b"PG" + struct.pack(">IIBH", pts, pts, kind, len(data)) + data)

    visible = position is not None
    composition = struct.pack(">HHBHBBBB", 1024, 768, 0x10, sequence, 0x80 if visible else 0, 0, 0, int(visible))
    if visible:
        composition += struct.pack(">HBBHH", 0, 0, 0, *position)
    segment(0x16, composition)
    if visible:
        segment(0x17, struct.pack(">BBHHHH", 1, 0, *position, *size))
        # Palette entries are transparent, the plate colour, and dark lettering, in Y/Cr/Cb/alpha order.
        segment(0x14, bytes([0, 0, 0, 16, 128, 128, 0, 1, *color, 255, 2, 24, 128, 128, 255]))
        segment(0x15, struct.pack(">HBB", 0, 0, 0xc0) + (len(image) + 4).to_bytes(3, "big")
                + struct.pack(">HH", *size) + image)
    segment(0x80)


if __name__ == "__main__":
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("output", type=Path)
    parser.add_argument("--font", help="ImageMagick font name or path")
    parser.add_argument("--omit-block-duration", action="store_true",
                        help="Leave out the optional Matroska PGS durations to test seeking between subtitle updates")
    parser.add_argument("--alternate-track", action="store_true",
                        help="Add a French PGS track with an orange PLATFORM 2 plate and an empty interval from 35 to 72 seconds")
    args = parser.parse_args()
    helvetica = Path("/System/Library/Fonts/Helvetica.ttc")
    font = args.font or (str(helvetica) if helvetica.is_file() else "DejaVu-Sans")
    size = (224, 56)
    image = encode_image(font, *size)
    with TemporaryDirectory() as tmp:
        sup = Path(tmp) / "marks.sup"
        with sup.open("wb") as stream:
            write_display(stream, 0.5, 0, (80, 80), size, image)
            write_display(stream, 72, 1, (720, 616), size, image)
            write_display(stream, 139, 2, None, size, image)
        second_input, second_output = [], []
        if args.alternate_track:
            alt = Path(tmp) / "alternate.sup"
            plate = encode_image(font, *size, "PLATFORM 2")
            with alt.open("wb") as stream:
                for seq, (at, position) in enumerate(((0.5, (720, 80)), (35, None), (72, (80, 616)), (139, None))):
                    write_display(stream, at, seq, position, size, plate, color=(166, 187, 67))
            second_input = ["-f", "sup", "-i", str(alt)]
            second_output = ["-map", "3:s"]
        guides = ",".join((
            r"drawbox=x=76:y=76:w=232:h=64:c=yellow:t=4:enable=lt(t\,72)",
            r"drawbox=x=716:y=612:w=232:h=64:c=yellow:t=4:enable=gte(t\,72)",
            "drawbox=x=2:y=2:w=1020:h=764:c=0xf840c0:t=4",
            "drawbox=x=8:y=8:w=1008:h=752:c=0xf840c0:t=3",
        ))
        subtitle_filter = "pgs_frame_merge"
        if not args.omit_block_duration:
            subtitle_filter += r",setts=duration=if(eq(N\,0)\,71.5/TB_OUT\,if(eq(N\,1)\,67/TB_OUT\,1/TB_OUT))"
        alternate_options = []
        if args.alternate_track:
            filter_alt = "pgs_frame_merge"
            if not args.omit_block_duration:
                filter_alt += r",setts=duration=if(eq(N\,0)\,34.5/TB_OUT\,if(eq(N\,1)\,37/TB_OUT\,if(eq(N\,2)\,67/TB_OUT\,1/TB_OUT)))"
            alternate_options = ["-bsf:s:1", filter_alt, "-metadata:s:s:1", "language=fra", "-disposition:s:1", "0"]
        subprocess.run([
            "ffmpeg", "-nostdin", "-hide_banner", "-loglevel", "error", "-n",
            "-f", "lavfi", "-i", "color=c=0x20384e:s=1024x768:r=10:d=140",
            "-f", "lavfi", "-i", "sine=frequency=260:duration=140", "-f", "sup", "-i", str(sup), *second_input,
            "-map", "0:v", "-map", "1:a", "-map", "2:s", *second_output, "-vf", guides, "-c:v", "libx264",
            "-preset", "ultrafast", "-crf", "17", "-g", "10", "-pix_fmt", "yuv420p",
            "-c:a", "aac", "-af", "volume=0.012", "-b:a", "48k", "-c:s", "copy",
            "-bsf:s:0", subtitle_filter, *alternate_options,
            "-metadata:s:s:0", "language=eng", "-disposition:s:0", "default",
            "-t", "140", "-f", "matroska", str(args.output),
        ], check=True)
