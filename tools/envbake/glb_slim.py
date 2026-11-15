"""
glb_slim.py — a .glb without the data it holds twice or never uses, the picture unchanged:

  python3 tools/envbake/glb_slim.py IN.glb OUT.glb [--keep-normals] [--drop NODE,NODE…]

A baked place draws each part once per light layer (base, house, a screen zone: tools/envbake/bake_layers.py), each copy
its own mesh with its own material — and the exporter writes each copy's vertices and indices again. Accessors whose
bytes and layout match are made one, so the layers share them. Unlit primitives (KHR_materials_unlit: every baked layer)
never read their normals; those go too, unless --keep-normals. Buffer views nothing points at any more are dropped and
the buffer packed again. --drop takes out nodes by name (a layer a bake left dark), with whatever meshes, materials,
textures and images only they used. Prints the sizes before and after.
"""
import argparse, json, os, struct, hashlib

ap = argparse.ArgumentParser()
ap.add_argument("src"); ap.add_argument("out")
ap.add_argument("--keep-normals", action="store_true")
ap.add_argument("--drop", default="", help="node names to take out, comma-separated")
a = ap.parse_args()

data = open(a.src, "rb").read()
magic, ver, _ = struct.unpack_from("<III", data, 0)
jlen, jtype = struct.unpack_from("<II", data, 12)
gltf = json.loads(data[20:20 + jlen])
blen, btype = struct.unpack_from("<II", data, 20 + jlen)
blob = data[28 + jlen:28 + jlen + blen]
accessors, views = gltf.get("accessors", []), gltf["bufferViews"]
assert not any("sparse" in x for x in accessors), "sparse accessors are not handled"
assert not any(k in gltf.get("extensionsUsed", []) for k in ("KHR_draco_mesh_compression", "EXT_meshopt_compression")), \
    "compressed meshes are not handled"

def prune(drop):
    """Takes out the named nodes, then every mesh, material, texture and image nothing still uses, renumbering the rest."""
    nodes = gltf["nodes"]
    keep_n = [i for i, n in enumerate(nodes) if n.get("name") not in drop]
    if len(keep_n) == len(nodes): return
    nmap = {o: i for i, o in enumerate(keep_n)}
    for n in nodes:
        if "children" in n: n["children"] = [nmap[c] for c in n["children"] if c in nmap]
    for sc in gltf.get("scenes", []): sc["nodes"] = [nmap[c] for c in sc["nodes"] if c in nmap]
    gltf["nodes"] = [nodes[i] for i in keep_n]

    def renumber(items, used, refs):
        keep = sorted(used); m = {o: i for i, o in enumerate(keep)}
        for holder, key in refs: holder[key] = m[holder[key]]
        return [items[i] for i in keep]

    refs = [(n, "mesh") for n in gltf["nodes"] if "mesh" in n]
    gltf["meshes"] = renumber(gltf["meshes"], {h[k] for h, k in refs}, refs)
    refs = [(p, "material") for m in gltf["meshes"] for p in m["primitives"] if "material" in p]
    gltf["materials"] = renumber(gltf["materials"], {h[k] for h, k in refs}, refs)
    refs = [(mt["pbrMetallicRoughness"]["baseColorTexture"], "index") for mt in gltf["materials"]
            if "baseColorTexture" in mt.get("pbrMetallicRoughness", {})]
    gltf["textures"] = renumber(gltf.get("textures", []), {h[k] for h, k in refs}, refs)
    refs = [(t, "source") for t in gltf["textures"] if "source" in t] + \
           [(t["extensions"]["KHR_texture_basisu"], "source") for t in gltf["textures"] if "KHR_texture_basisu" in t.get("extensions", {})]
    gltf["images"] = renumber(gltf.get("images", []), {h[k] for h, k in refs}, refs)
    print("dropped", sorted(drop))


if a.drop: prune(set(a.drop.split(",")))
prims = [p for m in gltf.get("meshes", []) for p in m["primitives"]]
dropped = 0
if not a.keep_normals:
    for p in prims:
        mat = gltf["materials"][p["material"]] if "material" in p else {}
        if "KHR_materials_unlit" in mat.get("extensions", {}) and "NORMAL" in p["attributes"]:
            del p["attributes"]["NORMAL"]; dropped += 1


def view_bytes(vi):
    v = views[vi]; o = v.get("byteOffset", 0)
    return blob[o:o + v["byteLength"]]


# one accessor for each distinct (layout, bytes)
first, remap = {}, {}
for ai, x in enumerate(accessors):
    layout = {k: x[k] for k in x if k not in ("name", "min", "max", "bufferView", "byteOffset")}
    has = "bufferView" in x
    key = (json.dumps(layout, sort_keys=True),
           hashlib.sha256(view_bytes(x["bufferView"])[x.get("byteOffset", 0):]).hexdigest() if has else None,
           views[x["bufferView"]].get("byteStride") if has else None, views[x["bufferView"]].get("target") if has else None)
    remap[ai] = first.setdefault(key, ai)

# the accessors still used, in order
used = set()
for p in prims:
    for k in list(p["attributes"]): p["attributes"][k] = remap[p["attributes"][k]]; used.add(p["attributes"][k])
    if "indices" in p: p["indices"] = remap[p["indices"]]; used.add(p["indices"])
    for t in p.get("targets", []):
        for k in t: t[k] = remap[t[k]]; used.add(t[k])
for s in gltf.get("skins", []):
    if "inverseBindMatrices" in s: s["inverseBindMatrices"] = remap[s["inverseBindMatrices"]]; used.add(s["inverseBindMatrices"])
for an in gltf.get("animations", []):
    for s in an["samplers"]:
        for k in ("input", "output"): s[k] = remap[s[k]]; used.add(s[k])
keep = sorted(used); new_ai = {old: i for i, old in enumerate(keep)}


def renumber(i): return new_ai[i]


for p in prims:
    p["attributes"] = {k: renumber(v) for k, v in p["attributes"].items()}
    if "indices" in p: p["indices"] = renumber(p["indices"])
    for t in p.get("targets", []):
        for k in t: t[k] = renumber(t[k])
for s in gltf.get("skins", []):
    if "inverseBindMatrices" in s: s["inverseBindMatrices"] = renumber(s["inverseBindMatrices"])
for an in gltf.get("animations", []):
    for s in an["samplers"]:
        for k in ("input", "output"): s[k] = renumber(s[k])
gltf["accessors"] = [accessors[i] for i in keep]

# the buffer views still pointed at, packed again
live = sorted({x["bufferView"] for x in gltf["accessors"] if "bufferView" in x} |
              {im["bufferView"] for im in gltf.get("images", []) if "bufferView" in im})
new_vi = {old: i for i, old in enumerate(live)}
new_blob, new_views = bytearray(), []
for old in live:
    chunk = view_bytes(old)
    while len(new_blob) % 4: new_blob += b"\0"
    v = dict(views[old]); v["byteOffset"] = len(new_blob); v["byteLength"] = len(chunk)
    new_views.append(v); new_blob += chunk
while len(new_blob) % 4: new_blob += b"\0"
for x in gltf["accessors"]:
    if "bufferView" in x: x["bufferView"] = new_vi[x["bufferView"]]
for im in gltf.get("images", []):
    if "bufferView" in im: im["bufferView"] = new_vi[im["bufferView"]]
gltf["bufferViews"] = new_views
gltf["buffers"][0]["byteLength"] = len(new_blob)

j = json.dumps(gltf, separators=(",", ":")).encode(); j += b" " * ((4 - len(j) % 4) % 4)
with open(a.out, "wb") as f:
    f.write(struct.pack("<III", magic, ver, 12 + 8 + len(j) + 8 + len(new_blob)))
    f.write(struct.pack("<II", len(j), jtype)); f.write(j)
    f.write(struct.pack("<II", len(new_blob), btype)); f.write(new_blob)
print(f"normals dropped from {dropped} primitives; accessors {len(accessors)} -> {len(keep)}, "
      f"buffer {blen} -> {len(new_blob)} bytes; wrote {a.out} {os.path.getsize(a.out)} bytes")
