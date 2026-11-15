"""
glb_tessellate.py — a baked place with more triangles and the same look, for finding how many the headset carries:

  Blender --background --python tools/envbake/glb_tessellate.py -- IN.glb OUT.glb --target 400000

Every mesh's triangles are cut in four (flat: the new corners lie on the old edges, the UVs and so the baked light
interpolated along them) until the place holds about --target triangles — all of them as often as that fits, then a
share chosen by face index, the same faces in a base_ mesh and its house_ twin (one geometry, drawn twice) so the
layers stay exactly on one another. The materials stay unlit and single-sided, as bake_layers.py made them.
"""
import bpy, bmesh, sys, argparse, json, struct, os

argv = sys.argv[sys.argv.index("--") + 1:]
ap = argparse.ArgumentParser()
ap.add_argument("src"); ap.add_argument("out"); ap.add_argument("--target", type=int, required=True)
a = ap.parse_args(argv)

bpy.ops.wm.read_factory_settings(use_empty=True)
bpy.ops.import_scene.gltf(filepath=a.src)
meshes = [o for o in bpy.context.scene.objects if o.type == 'MESH']


def tris(o):
    return sum(len(p.vertices) - 2 for p in o.data.polygons)


total = sum(tris(o) for o in meshes)
print("TESS from", total, "to about", a.target)


def cut(o, share):
    """Cuts `share` of the mesh's faces (by index, the same in its twin) in four."""
    bm = bmesh.new(); bm.from_mesh(o.data); bm.faces.ensure_lookup_table()
    pick = [f for f in bm.faces if (f.index * 2654435761) % 10000 < share * 10000]
    edges = list({e for f in pick for e in f.edges})
    if edges:
        bmesh.ops.subdivide_edges(bm, edges=edges, cuts=1, use_grid_fill=True, smooth=0.0)
    bmesh.ops.triangulate(bm, faces=bm.faces[:])
    bm.to_mesh(o.data); bm.free()


# whole passes while a full one still fits, then the share that reaches the target
while total * 4 <= a.target:
    for o in meshes: cut(o, 1.0)
    total = sum(tris(o) for o in meshes)
if total < a.target:
    share = (a.target - total) / (3 * total)
    for o in meshes: cut(o, share)
    total = sum(tris(o) for o in meshes)
print("TESS now", total)

bpy.ops.export_scene.gltf(filepath=a.out, export_format='GLB', export_image_format='JPEG', export_jpeg_quality=92)
with open(a.out, "rb") as f: data = f.read()
magic, ver, _ = struct.unpack_from("<III", data, 0)
jlen, jtype = struct.unpack_from("<II", data, 12)
gltf = json.loads(data[20:20 + jlen]); rest = data[20 + jlen:]
for m in gltf.get("materials", []):
    if m.get("name", "").startswith(("base_", "house_")):
        m.setdefault("extensions", {})["KHR_materials_unlit"] = {}
        pbr = m.setdefault("pbrMetallicRoughness", {}); pbr["metallicFactor"] = 0.0; pbr["roughnessFactor"] = 1.0
    m["doubleSided"] = False
gltf["extensionsUsed"] = sorted(set(gltf.get("extensionsUsed", [])) | {"KHR_materials_unlit"})
j = json.dumps(gltf, separators=(",", ":")).encode(); j += b" " * ((4 - len(j) % 4) % 4)
with open(a.out, "wb") as f:
    f.write(struct.pack("<III", magic, ver, 12 + 8 + len(j) + len(rest)))
    f.write(struct.pack("<II", len(j), jtype)); f.write(j); f.write(rest)
print("TESS wrote", a.out, os.path.getsize(a.out), "bytes,", total, "triangles")
