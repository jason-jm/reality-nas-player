"""Check the contents of the actual store APK against the current app sources."""
from pathlib import Path
import hashlib
import re
import struct
import subprocess
import sys
import zipfile
from collections import Counter

root = Path(__file__).resolve().parents[2]
app = root / "app/src/main"
package = app / "java/com/therealityteam/videoplayer"
launcher = (package / "platform/LaunchActivity.kt").read_text()
extras = set(re.findall(rb'"(aura\.[a-zA-Z0-9_]+)"', launcher.encode()))
if not extras:
    sys.exit("No launch extras found in LaunchActivity")


def dex_strings(data):
    if not data.startswith(b"dex\n"):
        sys.exit("Invalid dex entry")
    count, table = struct.unpack_from("<II", data, 0x38)
    for index in range(count):
        offset, = struct.unpack_from("<I", data, table + index * 4)
        while data[offset] & 0x80:
            offset += 1
        start = offset + 1  # skip the encoded UTF-16 length
        yield data[start:data.index(b"\0", start)]


def checksum(stream):
    digest = hashlib.sha256()
    for chunk in iter(lambda: stream.read(1024 * 1024), b""):
        digest.update(chunk)
    return digest.digest()


def verify_backup_rules(apk_path, aapt2, manifest):
    reference = re.search(r'android:dataExtractionRules\([^)]*\)=@(0x[0-9a-f]+)', manifest)
    if not reference:
        sys.exit("APK manifest has no data extraction rules")
    table = subprocess.check_output([aapt2, "dump", "resources", apk_path], text=True)
    rule_files = []
    selected = False
    for line in table.splitlines():
        fields = line.split()
        if fields[:1] == ["resource"]:
            selected = fields[1] == reference[1]
        elif selected and "(file)" in fields and fields[-1] == "type=XML":
            rule_files.append(fields[fields.index("(file)") + 1])
    if not rule_files:
        sys.exit("APK data extraction rules do not resolve to XML")

    domains = {"root", "file", "database", "sharedpref", "external",
               "device_root", "device_file", "device_database", "device_sharedpref"}
    for path in set(rule_files):
        tree = subprocess.check_output([aapt2, "dump", "xmltree", "--file", path, apk_path], text=True)
        sections = re.split(r"(?m)^    E: (\S+) [^\n]*\n", tree)
        if "E: data-extraction-rules " not in sections[0] or sorted(sections[1::2]) != ["cloud-backup", "device-transfer"]:
            sys.exit(f"{path}: expected cloud-backup and device-transfer rules")
        for section, contents in zip(sections[1::2], sections[2::2]):
            entries = re.split(r"(?m)^\s*E: (\S+) [^\n]*\n", contents)
            exclusions = Counter()
            for tag, attributes in zip(entries[1::2], entries[2::2]):
                values = dict(re.findall(r'(?m)^\s*A: (domain|path)="([^"]*)"', attributes))
                if tag != "exclude" or values.get("path") != ".":
                    sys.exit(f"{path}: {section} must exclude whole storage domains")
                exclusions[values.get("domain")] += 1
            if exclusions != Counter(domains):
                sys.exit(f"{path}: incomplete storage exclusions for {section}")
    print("Android cloud backup and device transfer exclude all nine app storage domains.")


def verify_native_library(apk, name):
    if apk.getinfo(name).compress_type != zipfile.ZIP_STORED:
        sys.exit(f"{name}: native library must be stored uncompressed")
    with apk.open(name) as stream:
        header = stream.read(64)
        if len(header) != 64 or header[:6] != b"\x7fELF\x02\x01" or struct.unpack_from("<H", header, 18)[0] != 183:
            sys.exit(f"{name}: expected a little-endian arm64 ELF")
        offset, = struct.unpack_from("<Q", header, 32)
        entry_size, count = struct.unpack_from("<HH", header, 54)
        if entry_size < 56 or offset + entry_size * count > apk.getinfo(name).file_size:
            sys.exit(f"{name}: invalid program header table")
        stream.seek(offset)
        loads = 0
        for _ in range(count):
            entry = stream.read(entry_size)
            kind, _, start, address, _, _, _, alignment = struct.unpack_from("<IIQQQQQQ", entry)
            if kind == 1:  # PT_LOAD
                loads += 1
                if alignment < 16384 or alignment & (alignment - 1) or (address - start) % 16384:
                    sys.exit(f"{name}: load segment is incompatible with 16 KB pages (p_align={alignment})")
        if not loads:
            sys.exit(f"{name}: no loadable segments")


if len(sys.argv) == 4:
    verify_backup_rules(sys.argv[1], sys.argv[2], Path(sys.argv[3]).read_text())

with zipfile.ZipFile(sys.argv[1]) as apk:
    entries = set(apk.namelist())
    native_abis = {name.split("/")[1] for name in entries if name.startswith("lib/")}
    if native_abis != {"arm64-v8a"}:
        sys.exit(f"Unexpected native ABIs: {native_abis}")
    libraries = sorted(n for n in entries if n.startswith("lib/") and n.endswith(".so"))
    for name in libraries:
        verify_native_library(apk, name)
    decoder = "arm64-v8a/libffmpegJNI.so"
    with zipfile.ZipFile(root / "app/libs/media3-decoder-ffmpeg-1.8.0-arm64.aar") as aar:
        with aar.open("jni/" + decoder) as source, apk.open("lib/" + decoder) as packed:
            if checksum(source) != checksum(packed):
                sys.exit("APK audio decoder differs from the tested Media3 AAR")
    dex = sorted(n for n in entries if re.fullmatch(r"classes\d*\.dex", n))
    if not dex:
        sys.exit("APK contains no dex")
    leftover = set().union(*(extras.intersection(dex_strings(apk.read(n))) for n in dex))
    if leftover:
        sys.exit("Debug launch extras in release dex: " + ", ".join(n.decode() for n in sorted(leftover)))

    catalogue = (package / "player/Environments.kt").read_text()
    rooms = set(re.findall(r'EnvSource\.Asset\("(env/[^"]+\.glb)"\)', catalogue))
    bundled = {n.removeprefix("assets/") for n in entries if n.startswith("assets/env/") and n.endswith(".glb")}
    if not rooms or rooms != bundled:
        sys.exit(f"Room catalogue and APK differ: {sorted(rooms ^ bundled)}")
    if apk.getinfo("assets/cinema-glow.bundle").compress_type != zipfile.ZIP_STORED:
        sys.exit("SDK requires the cinema material bundle to be stored uncompressed")
    for asset in rooms | {"cinema-glow.bundle", "licenses.txt", "ibl.ktx"}:
        with (app / "assets" / asset).open("rb") as source, apk.open("assets/" + asset) as packed:
            if checksum(source) != checksum(packed):
                sys.exit(f"Stale APK asset: {asset}")
    print(f"{len(libraries)} arm64 libraries have 16 KB-compatible load segments. "
          f"{len(rooms)} room models, screen material, IBL, audio decoder and notices match the source. "
          f"Checked {len(extras)} launch extras across {len(dex)} dex files.")
