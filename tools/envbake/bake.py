"""
bake.py — a place's light baked into its textures, for drawing unlit on the headset (Blender, headless):

WORK IN PROGRESS (2026-10-21): the bedroom baked with --uv original came out washed out on the headset (walls near
white and flat, the bed dark) while the atlas itself looks right — the cause (texture/UV mapping in the exported glb,
the run-time dimming of "unlit" materials, or the tone mapping) is not found yet.

  Blender --background --factory-startup --python tools/envbake/bake.py -- SRC.gltf OUT.glb \
      [--uv original|atlas] [--sizes Mat=8192,Other=2048] [--size 4096] [--sun ELEV,AZIM,STRENGTH] [--sky STRENGTH]
      [--samples 256] [--exposure 0]

Cycles bakes each opaque surface's diffuse light — direct and bounced, times its own colour — and the result is saved
through the scene's view transform (AgX), as preview.py shows it; the .glb's baked materials are KHR_materials_unlit:
no lighting at run time. Transparent and glass parts are kept as they came. The light is preview.py's: Blender's
physical sky and a sun lamp in its direction.
  --uv original (when the model's own UVs do not overlap — a downloaded scene's usually do not): each material baked
      into a new image on its own UVs, SIZES per material (SIZE for the rest); the meshes stay as they came.
  --uv atlas: every opaque mesh joined, unwrapped onto a new UV map and baked into one SIZE atlas — one draw, but a
      high-poly curved mesh breaks into thousands of islands whose margins eat the atlas.
"""
import bpy, sys, math, argparse, json, struct, os

argv = sys.argv[sys.argv.index("--") + 1:]
ap = argparse.ArgumentParser()
ap.add_argument("src"); ap.add_argument("out")
ap.add_argument("--sun", default="35,200,3.0"); ap.add_argument("--sky", type=float, default=0.6)
ap.add_argument("--size", type=int, default=4096); ap.add_argument("--samples", type=int, default=256)
ap.add_argument("--exposure", type=float, default=0.0); ap.add_argument("--margin", type=int, default=16)
ap.add_argument("--uv", choices=["original", "atlas"], default="original"); ap.add_argument("--sizes", default="")
a = ap.parse_args(argv)

bpy.ops.wm.read_factory_settings(use_empty=True)
bpy.ops.import_scene.gltf(filepath=a.src)
scene = bpy.context.scene

# ---- light: the sky and its sun (as preview.py)
world = bpy.data.worlds.new("sky"); scene.world = world; world.use_nodes = True
nt = world.node_tree; nt.nodes.clear()
sky = nt.nodes.new("ShaderNodeTexSky"); bg = nt.nodes.new("ShaderNodeBackground"); wo = nt.nodes.new("ShaderNodeOutputWorld")
elev, azim, sun_strength = (float(v) for v in a.sun.split(","))
try:
    sky.sun_elevation = math.radians(elev); sky.sun_rotation = math.radians(azim); sky.sun_intensity = sun_strength / 3.0
except Exception:
    pass
bg.inputs["Strength"].default_value = a.sky
nt.links.new(sky.outputs[0], bg.inputs[0]); nt.links.new(bg.outputs[0], wo.inputs[0])
sun = bpy.data.lights.new("sun", 'SUN'); sun.energy = sun_strength; sun.angle = math.radians(1.0)
so = bpy.data.objects.new("sun", sun); scene.collection.objects.link(so)
so.rotation_euler = (math.radians(90 - elev), 0, math.radians(azim))

scene.render.engine = 'CYCLES'
prefs = bpy.context.preferences.addons['cycles'].preferences
try:
    prefs.compute_device_type = 'METAL'; prefs.get_devices()
    for d in prefs.devices: d.use = True
    scene.cycles.device = 'GPU'
except Exception as e:
    print("GPU unavailable:", e)
scene.cycles.samples = a.samples
scene.view_settings.view_transform = 'AgX'
scene.view_settings.exposure = a.exposure


def see_through(mat):
    """Glass, alpha-blended or transmissive: kept as it came, not baked."""
    if mat is None:
        return False
    if getattr(mat, "surface_render_method", "") == 'BLENDED' or getattr(mat, "blend_method", "OPAQUE") in ('BLEND',):
        return True
    if mat.use_nodes:
        for n in mat.node_tree.nodes:
            if n.type == 'BSDF_PRINCIPLED':
                t = n.inputs.get("Transmission Weight") or n.inputs.get("Transmission")
                al = n.inputs.get("Alpha")
                if (t is not None and not t.is_linked and t.default_value > 0.5) or (al is not None and not al.is_linked and al.default_value < 0.99):
                    return True
    return False


meshes = [o for o in scene.objects if o.type == 'MESH']
opaque = [o for o in meshes if not any(see_through(s.material) for s in o.material_slots)]
kept = [o for o in meshes if o not in opaque]
print(f"BAKE meshes {len(meshes)}: {len(opaque)} opaque, {len(kept)} kept as they are; uv {a.uv}")
out_dir = os.path.dirname(os.path.abspath(a.out)); stem = os.path.splitext(os.path.basename(a.out))[0]
scene.render.image_settings.file_format = 'PNG'; scene.render.image_settings.color_depth = '8'
unlit_names = set()


def unlit_material(name, image, uv_name=None):
    """A material that draws IMAGE on UV_NAME (the mesh's first UVs when None) — marked unlit after export."""
    m = bpy.data.materials.new(name); m.use_nodes = True
    nodes = m.node_tree.nodes; nodes.clear()
    tex = nodes.new("ShaderNodeTexImage"); tex.image = image
    if uv_name:
        uvn = nodes.new("ShaderNodeUVMap"); uvn.uv_map = uv_name; m.node_tree.links.new(uvn.outputs[0], tex.inputs[0])
    bsdf = nodes.new("ShaderNodeBsdfPrincipled"); outn = nodes.new("ShaderNodeOutputMaterial")
    m.node_tree.links.new(tex.outputs[0], bsdf.inputs["Base Color"]); m.node_tree.links.new(bsdf.outputs[0], outn.inputs[0])
    unlit_names.add(name)
    return m


if a.uv == "original":
    sizes = dict((k, int(v)) for k, v in (kv.split("=") for kv in a.sizes.split(",") if kv))
    images = {}
    for o in opaque:
        for slot in o.material_slots:
            m = slot.material
            if m is None or not m.use_nodes or m.name in images:
                continue
            size = sizes.get(m.name, a.size)
            images[m.name] = bpy.data.images.new("bake_" + m.name, size, size, alpha=False, float_buffer=True)
            n = m.node_tree.nodes.new("ShaderNodeTexImage"); n.image = images[m.name]; m.node_tree.nodes.active = n
    for o in scene.objects:
        o.select_set(False)
    for o in opaque:
        o.select_set(True)
    bpy.context.view_layer.objects.active = opaque[0]
    bpy.ops.object.bake(type='DIFFUSE', pass_filter={'DIRECT', 'INDIRECT', 'COLOR'}, margin=a.margin, use_clear=True)
    print("BAKE baked", {k: v.size[0] for k, v in images.items()}, "at", a.samples, "samples")
    baked_mats = {}
    for name, img in images.items():
        png = os.path.join(out_dir, f"{stem}_{name}.png")
        img.save_render(png, scene=scene)
        baked_mats[name] = unlit_material("baked_" + name, bpy.data.images.load(png))
    for o in opaque:
        for slot in o.material_slots:
            if slot.material and slot.material.name in baked_mats:
                slot.material = baked_mats[slot.material.name]
    finish = True
else:
    finish = False

# ---- --uv atlas: the opaque meshes, joined (their transforms applied)
if not finish:
    bpy.ops.object.select_all(action='DESELECT')
    for o in opaque:
        o.select_set(True)
    bpy.context.view_layer.objects.active = opaque[0]
    bpy.ops.object.make_single_user(object=True, obdata=True)
    bpy.ops.object.transform_apply(location=True, rotation=True, scale=True)
    bpy.ops.object.join()
    baked = bpy.context.view_layer.objects.active
    baked.name = "baked"
    # Unparent so the joined mesh keeps its place in the world when its old parents are gone.
    if baked.parent:
        mw = baked.matrix_world.copy(); baked.parent = None; baked.matrix_world = mw

    # ---- the lightmap UVs: every face its own share of one atlas
    me = baked.data
    lm = me.uv_layers.new(name="lightmap")
    me.uv_layers.active = lm
    bpy.ops.object.mode_set(mode='EDIT')
    bpy.ops.mesh.select_all(action='SELECT')
    bpy.ops.uv.smart_project(angle_limit=math.radians(66), island_margin=a.margin / a.size * 2, area_weight=0.0, scale_to_bounds=False)
    bpy.ops.object.mode_set(mode='OBJECT')

    # ---- bake: diffuse, direct + indirect + its colour, into one image through every material
    img = bpy.data.images.new("lightmap", a.size, a.size, alpha=False, float_buffer=True)
    for slot in baked.material_slots:
        m = slot.material
        if m is None or not m.use_nodes:
            continue
        n = m.node_tree.nodes.new("ShaderNodeTexImage"); n.image = img
        m.node_tree.nodes.active = n
    for o in scene.objects:
        o.select_set(False)
    baked.select_set(True); bpy.context.view_layer.objects.active = baked
    bpy.ops.object.bake(type='DIFFUSE', pass_filter={'DIRECT', 'INDIRECT', 'COLOR'}, uv_layer="lightmap", margin=a.margin, use_clear=True)
    print("BAKE baked", a.size, "x", a.size, "at", a.samples, "samples")

    # ---- the atlas through the view transform (as the preview shows it), as 8-bit sRGB
    png = os.path.join(out_dir, stem + "_lightmap.png")
    img.save_render(png, scene=scene)
    shown = bpy.data.images.load(png)

    # ---- one material: the atlas on the lightmap UVs
    mat = unlit_material("baked_lightmap", shown, "lightmap")
    me.materials.clear(); me.materials.append(mat)
    # Only the lightmap UVs go out with it.
    for layer in [l for l in me.uv_layers if l.name != "lightmap"]:
        me.uv_layers.remove(layer)

for o in [o for o in scene.objects if o.type in ('LIGHT', 'CAMERA')]:
    bpy.data.objects.remove(o)
bpy.ops.export_scene.gltf(filepath=a.out, export_format='GLB', export_image_format='JPEG', export_jpeg_quality=92)

# ---- KHR_materials_unlit on the baked material (the exporter writes it as PBR)
with open(a.out, "rb") as f:
    data = f.read()
magic, ver, length = struct.unpack_from("<III", data, 0)
jlen, jtype = struct.unpack_from("<II", data, 12)
gltf = json.loads(data[20:20 + jlen])
rest = data[20 + jlen:]
for m in gltf.get("materials", []):
    if m.get("name") in unlit_names:
        m.setdefault("extensions", {})["KHR_materials_unlit"] = {}
        pbr = m.setdefault("pbrMetallicRoughness", {}); pbr["metallicFactor"] = 0.0; pbr["roughnessFactor"] = 1.0
gltf["extensionsUsed"] = sorted(set(gltf.get("extensionsUsed", [])) | {"KHR_materials_unlit"})
j = json.dumps(gltf, separators=(",", ":")).encode()
j += b" " * ((4 - len(j) % 4) % 4)
with open(a.out, "wb") as f:
    f.write(struct.pack("<III", magic, ver, 12 + 8 + len(j) + len(rest)))
    f.write(struct.pack("<II", len(j), jtype)); f.write(j); f.write(rest)
print("BAKE wrote", a.out, os.path.getsize(a.out), "bytes; atlas", png)
