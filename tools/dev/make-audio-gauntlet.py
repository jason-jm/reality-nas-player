"""Generate the synthetic multi-track MKV used by AudioCodecGauntletTest."""
import argparse
import subprocess
from pathlib import Path

parser = argparse.ArgumentParser(description=__doc__)
parser.add_argument("output", type=Path)
output = parser.parse_args().output
tracks = [
    ("AC3", "ac3"), ("EAC3", "eac3"), ("DTS", "dca"), ("TrueHD", "truehd"),
    ("FLAC", "flac"), ("Opus", "libopus"), ("AAC", "aac"), ("Vorbis", "vorbis"),
]
command = [
    "ffmpeg", "-nostdin", "-hide_banner", "-loglevel", "error", "-n",
    "-f", "lavfi", "-i", "sine=frequency=392:sample_rate=48000:duration=48",
]
for _ in tracks:
    command.extend(["-map", "0:a"])
for index, (name, encoder) in enumerate(tracks):
    command.extend([f"-c:a:{index}", encoder, f"-metadata:s:a:{index}", f"title={name}"])
command.extend(["-strict", "experimental", "-ac", "2", "-threads", "3", "-f", "matroska", str(output)])
subprocess.run(command, check=True)
