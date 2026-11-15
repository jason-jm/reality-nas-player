"""
export_hybrid.py — a layered place (bake_layers.py) with its room lights drawn by the headset instead of baked,
to compare the two ways (look and GPU load):

  Blender --background --python tools/envbake/export_hybrid.py -- SCENE.blend BAKE_DIR OUT_DIR [--spacing 1.0]

The screen's glow stays baked: the place's base_<g> meshes as they are (unlit). The room's own lights become the
headset's real-time lights shining on the surfaces' own colour: house_<g> keeps its geometry but takes a PBR
material on the albedo atlas (<g>_albedo.jpg) — the app draws it added on top, scaled by the lights' level, as it
does the baked house layer — and the LED strips, which glow rather than take light, become one unlit white mesh,
glow_led, added the same way.

OUT_DIR gets hybrid.glb and lights.json — the scene's "house" lights for the app to make (`-e aura.env_rt_lights`),
in glTF coordinates (x, z, -y), as the SDK's two kinds: a spot light as a spot; an area light, which the headset
does not have, as a row of spots along its long side, two metres apart (--spacing), each facing the area's way with
a wide cone, sharing its power. Power stays in Blender's watts: the app scales it to its lumens by one factor,
found by matching the two versions on the headset. Each reaches --radius metres: 102 spots reaching 10 m (all of them
on every part of the room) faulted the B3110's GPU (2026-10-22).
"""
import bpy, sys, math, argparse, json, os, struct
from mathutils import Vector, Matrix

argv = sys.argv[sys.argv.index("--") + 1:]
ap = argparse.ArgumentParser()
ap.add_argument("blend"); ap.add_argument("bake"); ap.add_argument("out")
ap.add_argument("--spacing", type=float, default=2.0)
ap.add_argument("--radius", type=float, default=3.5, help="how far each light reaches, metres")
ap.add_argument("--roughness", type=float, default=0.75)
a = ap.parse_args(argv)
os.makedirs(a.out, exist_ok=True)


def gl(v):
    """Blender (x, y, z) → glTF (x, z, -y)."""
    return [round(v.x, 4), round(v.z, 4), round(-v.y, 4)]


# ---- the lights, from the scene as built
bpy.ops.wm.open_mainfile(filepath=a.blend)
lights = []
for o in bpy.data.collections["house"].objects:
    if o.type != 'LIGHT':
        continue
    l = o.data; mw = o.matrix_world
    facing = (mw.to_3x3() @ Vector((0, 0, -1))).normalized()  # a Blender light shines down its -z
    col = [round(c, 4) for c in l.color]
    if l.type == 'SPOT':
        lights.append({"name": o.name, "kind": "spot", "pos": gl(mw.translation), "dir": gl(facing), "color": col,
                       "watts": l.energy, "outer": math.degrees(l.spot_size) / 2,
                       "inner": math.degrees(l.spot_size) / 2 * (1 - l.spot_blend)})
    elif l.type == 'AREA':
        sx, sy = l.size, (l.size_y if l.shape in ('RECTANGLE', 'ELLIPSE') else l.size)
        axis = mw.to_3x3() @ (Vector((1, 0, 0)) if sx >= sy else Vector((0, 1, 0)))
        length = max(sx, sy) * axis.length; axis.normalize()
        n = max(1, round(length / a.spacing))
        for i in range(n):
            p = mw.translation + axis * ((i + 0.5) / n - 0.5) * length
            # an area light lights its half-space, brightest straight out: a wide spot facing out
            lights.append({"name": f"{o.name}_{i}", "kind": "spot", "pos": gl(p), "dir": gl(facing), "color": col,
                           "watts": l.energy / n, "outer": 80.0, "inner": 30.0})
    elif l.type == 'POINT':
        lights.append({"name": o.name, "kind": "point", "pos": gl(mw.translation), "color": col, "watts": l.energy})
leds = [o.name for o in bpy.data.objects if o.type == 'MESH' and "_led" in o.name]
print("HYBRID lights", len(lights), "from", len(bpy.data.collections["house"].objects), "; led strips", len(leds))

# the LED strips, joined into one mesh, for the glow
bpy.ops.object.select_all(action='DESELECT')
for name in leds: bpy.data.objects[name].select_set(True)
bpy.context.view_layer.objects.active = bpy.data.objects[leds[0]]
bpy.ops.object.make_single_user(object=True, obdata=True)
bpy.ops.object.transform_apply(location=True, rotation=True, scale=True)
bpy.ops.object.join()
glow = bpy.context.view_layer.objects.active; glow.name = "glow_led"
glow_path = os.path.join(a.out, "glow_led.blend")
bpy.data.libraries.write(glow_path, {glow.data}, fake_user=True)
glow_mesh_name = glow.data.name

# ---- the place: the layered bake's meshes, house_ re-dressed, the glow added
bpy.ops.wm.read_factory_settings(use_empty=True)
bpy.ops.import_scene.gltf(filepath=os.path.join(a.bake, "place.glb"))
unlit = set()


def white_unlit(name):
    m = bpy.data.materials.new(name); m.use_nodes = True
    nt = m.node_tree; b = nt.nodes["Principled BSDF"]; b.inputs["Base Color"].default_value = (1, 1, 1, 1)
    unlit.add(name)
    return m


for o in [o for o in bpy.context.scene.objects if o.type == 'MESH']:
    if not o.name.startswith("house_"):
        continue
    g = o.name[len("house_"):]
    for old in o.data.materials:  # the baked house material: its name goes to the new one
        if old: old.name = "baked_" + old.name
    m = bpy.data.materials.new(f"house_{g}"); m.use_nodes = True
    nt = m.node_tree; b = nt.nodes["Principled BSDF"]
    tex = nt.nodes.new("ShaderNodeTexImage"); tex.image = bpy.data.images.load(os.path.join(a.bake, f"{g}_albedo.jpg"))
    nt.links.new(tex.outputs[0], b.inputs["Base Color"])
    b.inputs["Roughness"].default_value = a.roughness; b.inputs["Metallic"].default_value = 0.0
    o.data.materials.clear(); o.data.materials.append(m)
with bpy.data.libraries.load(glow_path) as (src, dst):
    dst.meshes = [glow_mesh_name]
go = bpy.data.objects.new("glow_led", dst.meshes[0]); bpy.context.scene.collection.objects.link(go)
go.data.materials.clear(); go.data.materials.append(white_unlit("glow_led"))

glb = os.path.join(a.out, "hybrid.glb")
bpy.ops.export_scene.gltf(filepath=glb, export_format='GLB', export_image_format='JPEG', export_jpeg_quality=92)

# the base_ atlases stay unlit (the importer read them back as PBR), the glow is unlit; all double-sided
with open(glb, "rb") as f: data = f.read()
magic, ver, _ = struct.unpack_from("<III", data, 0)
jlen, jtype = struct.unpack_from("<II", data, 12)
gltf = json.loads(data[20:20 + jlen]); rest = data[20 + jlen:]
for m in gltf.get("materials", []):
    name = m.get("name", "")
    if name.startswith("base_") or name in unlit:
        m.setdefault("extensions", {})["KHR_materials_unlit"] = {}
        pbr = m.setdefault("pbrMetallicRoughness", {}); pbr["metallicFactor"] = 0.0; pbr["roughnessFactor"] = 1.0
    m["doubleSided"] = True
gltf["extensionsUsed"] = sorted(set(gltf.get("extensionsUsed", [])) | {"KHR_materials_unlit"})
j = json.dumps(gltf, separators=(",", ":")).encode(); j += b" " * ((4 - len(j) % 4) % 4)
with open(glb, "wb") as f:
    f.write(struct.pack("<III", magic, ver, 12 + 8 + len(j) + len(rest)))
    f.write(struct.pack("<II", len(j), jtype)); f.write(j); f.write(rest)
for l in lights: l["radius"] = a.radius
json.dump({"lights": lights}, open(os.path.join(a.out, "lights.json"), "w"), indent=1)
os.remove(glow_path)
print("HYBRID wrote", glb, os.path.getsize(glb), "bytes;", len(lights), "lights")
