"""
glb_textures.py — a .glb with its textures resized (ImageMagick), for trying a place at several texture sizes from one bake:

  python3 tools/envbake/glb_textures.py IN.glb OUT.glb [--max 2048] [--quality 92] [--format jpg|png] [--sided single|double]
      [--replace IMAGE_NAME=FILE ...]

Every image held in the .glb's buffer is cut to at most --max pixels a side (Lanczos; never enlarged) and written back;
the meshes and UVs stay as they are. --sided sets every material's doubleSided (single: the back faces culled — half
the faces rasterised in a closed room). Prints the sizes before and after.
"""
import argparse, json, os, struct, subprocess, tempfile

ap = argparse.ArgumentParser()
ap.add_argument("src"); ap.add_argument("out")
ap.add_argument("--max", type=int, default=0); ap.add_argument("--quality", type=int, default=92)
ap.add_argument("--format", choices=["jpg", "png"], default=None, help="re-encode as (default: as it came)")
ap.add_argument("--sided", choices=["single", "double"], default=None)
ap.add_argument("--replace", action="append", default=[], help="IMAGE_NAME=FILE: that image's bytes from FILE (its type by extension)")
a = ap.parse_args()

data = open(a.src, "rb").read()
magic, ver, _ = struct.unpack_from("<III", data, 0)
jlen, jtype = struct.unpack_from("<II", data, 12)
gltf = json.loads(data[20:20 + jlen])
blen, btype = struct.unpack_from("<II", data, 20 + jlen)
blob = data[28 + jlen:28 + jlen + blen]

images = {img["bufferView"]: i for i, img in enumerate(gltf.get("images", [])) if "bufferView" in img}
views = gltf["bufferViews"]
new_blob = bytearray()
with tempfile.TemporaryDirectory() as tmp:
    for vi, v in enumerate(views):
        chunk = blob[v.get("byteOffset", 0):v.get("byteOffset", 0) + v["byteLength"]]
        repl = dict(r.split("=", 1) for r in a.replace)
        if vi in images and gltf["images"][images[vi]].get("name") in repl:
            img = gltf["images"][images[vi]]
            path = repl[img["name"]]; chunk = open(path, "rb").read()
            img["mimeType"] = "image/png" if path.endswith(".png") else "image/jpeg"
            print(f"image {img['name']}: replaced from {path}, {len(chunk)} bytes")
        elif vi in images and a.max:
            img = gltf["images"][images[vi]]
            ext = "png" if img.get("mimeType") == "image/png" else "jpg"
            out_ext = a.format or ext
            src = os.path.join(tmp, f"i{vi}.{ext}"); dst = os.path.join(tmp, f"o{vi}.{out_ext}")
            open(src, "wb").write(chunk)
            subprocess.run(["magick", src, "-filter", "Lanczos", "-resize", f"{a.max}x{a.max}>", "-quality", str(a.quality),
                            dst], check=True)
            size = subprocess.run(["magick", "identify", "-format", "%wx%h", dst], capture_output=True, text=True).stdout
            chunk = open(dst, "rb").read()
            img["mimeType"] = "image/png" if out_ext == "png" else "image/jpeg"
            print(f"image {img.get('name', vi)}: {v['byteLength']} -> {len(chunk)} bytes, {size}")
        while len(new_blob) % 4: new_blob += b"\0"
        v["byteOffset"] = len(new_blob); v["byteLength"] = len(chunk)
        new_blob += chunk
while len(new_blob) % 4: new_blob += b"\0"
gltf["buffers"][0]["byteLength"] = len(new_blob)
if a.sided:  # effects ("fx…": the LED lines' halos, seen from either side) stay double-sided
    for m in gltf.get("materials", []):
        if not m.get("name", "").startswith("fx"): m["doubleSided"] = a.sided == "double"
j = json.dumps(gltf, separators=(",", ":")).encode(); j += b" " * ((4 - len(j) % 4) % 4)
with open(a.out, "wb") as f:
    f.write(struct.pack("<III", magic, ver, 12 + 8 + len(j) + 8 + len(new_blob)))
    f.write(struct.pack("<II", len(j), jtype)); f.write(j)
    f.write(struct.pack("<II", len(new_blob), btype)); f.write(new_blob)
print("wrote", a.out, os.path.getsize(a.out), "bytes")
