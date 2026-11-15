"""
glb_ktx2.py — a .glb with its large textures compressed to KTX2 (Basis Universal UASTC, KHR_texture_basisu):

  python3 tools/envbake/glb_ktx2.py IN.glb OUT.glb [--min 1024] [--level 2] [--rdo 0] [--zstd 18] [--jobs 3]
      [--etc1s PATTERN,…] [--etc1s-q 255]

A GPU keeps a JPEG or PNG texture as 4 bytes a pixel (a 4K atlas with its mips: 89 MB); UASTC transcodes on load to the
headset's ASTC 4x4, 1 byte a pixel (22 MB) — so a place's atlases take a quarter of the memory, and 8K ones fit at all.
Every image held in the .glb's buffer whose longer side is at least --min is encoded (sRGB, its mips made here in linear
light, Zstandard on top) and its textures point at it through the extension, which becomes required; smaller ones
(effects' strips) stay as they are. --rdo > 0 trades quality for size (the baked light's smooth gradients show it first
as blocks: 0 keeps the source's ~52 dB). --etc1s encodes the images whose names match (fnmatch) as ETC1S instead: about a
sixth of UASTC's size, ~40 dB — fine stone and cobbles hide it, a smooth gradient (a night sky) does not. Needs basisu (brew install basis_universal). Prints the sizes before and after.
"""
import argparse, fnmatch, json, os, struct, subprocess, tempfile
from concurrent.futures import ThreadPoolExecutor

ap = argparse.ArgumentParser()
ap.add_argument("src"); ap.add_argument("out")
ap.add_argument("--min", type=int, default=1024, help="encode images at least this many pixels on their longer side")
ap.add_argument("--level", type=int, default=2, help="UASTC effort 0-4")
ap.add_argument("--rdo", type=float, default=0, help="UASTC RDO lambda (0: off)")
ap.add_argument("--zstd", type=int, default=18)
ap.add_argument("--jobs", type=int, default=3)
ap.add_argument("--no-mips", action="store_true", help="level 0 only (for checking how the loader treats mips)")
ap.add_argument("--etc1s", default="", help="image-name patterns to encode as ETC1S, comma-separated")
ap.add_argument("--etc1s-q", type=int, default=255)
a = ap.parse_args()

data = open(a.src, "rb").read()
magic, ver, _ = struct.unpack_from("<III", data, 0)
jlen, jtype = struct.unpack_from("<II", data, 12)
gltf = json.loads(data[20:20 + jlen])
blen, btype = struct.unpack_from("<II", data, 20 + jlen)
blob = data[28 + jlen:28 + jlen + blen]
views = gltf["bufferViews"]
chunks = [blob[v.get("byteOffset", 0):v.get("byteOffset", 0) + v["byteLength"]] for v in views]


def size(path):
    w, h = subprocess.run(["magick", "identify", "-format", "%w %h", path], capture_output=True, text=True, check=True).stdout.split()
    return int(w), int(h)


ETC1S = [p for p in a.etc1s.split(",") if p]


def encode(job):
    ii, src, dst = job
    args = ["basisu", "-ktx2", "-uastc", "-uastc_level", str(a.level), "-mipmap", "-mip_srgb", "-srgb",
            "-ktx2_zstandard_level", str(a.zstd), "-file", src, "-output_file", dst]
    if a.rdo > 0: args[3:3] = ["-uastc_rdo_l", str(a.rdo)]
    if any(fnmatch.fnmatch(gltf["images"][ii].get("name", ""), p) for p in ETC1S):
        args = ["basisu", "-ktx2", "-etc1s", "-q", str(a.etc1s_q), "-mipmap", "-mip_srgb", "-srgb", "-file", src, "-output_file", dst]
    if a.no_mips: args = [x for x in args if x not in ("-mipmap", "-mip_srgb")]
    r = subprocess.run(args, capture_output=True, text=True)
    if r.returncode or not os.path.exists(dst): raise SystemExit(f"basisu failed on image {ii}:\n{r.stdout[-2000:]}{r.stderr[-2000:]}")
    return ii


done = {}
with tempfile.TemporaryDirectory() as tmp:
    jobs = []
    for ii, img in enumerate(gltf.get("images", [])):
        if "bufferView" not in img or img.get("mimeType") == "image/ktx2": continue
        ext = "png" if img.get("mimeType") == "image/png" else "jpg"
        src = os.path.join(tmp, f"i{ii}.{ext}"); open(src, "wb").write(chunks[img["bufferView"]])
        if max(size(src)) < a.min: continue
        jobs.append((ii, src, os.path.join(tmp, f"o{ii}.ktx2")))
    with ThreadPoolExecutor(a.jobs) as pool:
        for ii in pool.map(encode, jobs):
            img = gltf["images"][ii]; vi = img["bufferView"]
            before = len(chunks[vi]); chunks[vi] = open(os.path.join(tmp, f"o{ii}.ktx2"), "rb").read()
            img["mimeType"] = "image/ktx2"; done[ii] = True
            codec = "ETC1S" if any(fnmatch.fnmatch(img.get("name", ""), p) for p in ETC1S) else "UASTC"
            print(f"image {img.get('name', ii)}: {before} -> {len(chunks[vi])} bytes ({codec})")

for t in gltf.get("textures", []):
    if t.get("source") in done:  # required, so no fallback: the source moves into the extension
        t.setdefault("extensions", {})["KHR_texture_basisu"] = {"source": t.pop("source")}
if done:
    for key in ("extensionsUsed", "extensionsRequired"):
        names = gltf.setdefault(key, [])
        if "KHR_texture_basisu" not in names: names.append("KHR_texture_basisu")

new_blob = bytearray()
for v, chunk in zip(views, chunks):
    while len(new_blob) % 4: new_blob += b"\0"
    v["byteOffset"] = len(new_blob); v["byteLength"] = len(chunk)
    new_blob += chunk
while len(new_blob) % 4: new_blob += b"\0"
gltf["buffers"][0]["byteLength"] = len(new_blob)
j = json.dumps(gltf, separators=(",", ":")).encode(); j += b" " * ((4 - len(j) % 4) % 4)
with open(a.out, "wb") as f:
    f.write(struct.pack("<III", magic, ver, 12 + 8 + len(j) + 8 + len(new_blob)))
    f.write(struct.pack("<II", len(j), jtype)); f.write(j)
    f.write(struct.pack("<II", len(new_blob), btype)); f.write(new_blob)
print("wrote", a.out, os.path.getsize(a.out), "bytes")
