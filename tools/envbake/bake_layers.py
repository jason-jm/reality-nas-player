"""
bake_layers.py — a scenes/ place baked in light layers, for the headset to mix while it draws (Blender, headless):

  Blender --background --python tools/envbake/bake_layers.py -- SCENE.blend OUT_DIR [--size 4096] [--samples 128]
      [--groups seats=seat_,room_b=floor|riser_|stage|screen|front_wall|ceiling|ceil_|back_wall,leaves@2048/2=leaf_]

Every mesh goes into one of a few groups (by name prefix; the rest into "room"); each group is joined, unwrapped
onto its own atlas, and Cycles bakes, into that atlas. A group named `name@SIZE/MARGIN` gets an atlas of its own size
and its islands packed MARGIN pixels apart: for thousands of tiny islands (a bush's leaves) the usual margin leaves them no
room, and their gaps are filled by the bake's own margin from the leaves round them anyway. `name…:base+house` bakes and
exports that group in those layers only ("amb" standing for the screen's zones): the far plain and the mountains need no
layer for a lamp or the film that cannot reach them, and the headset draws a group again for every layer it has. With --density view (the default) a part's share of the atlas
follows its nearness to the seat's eye — its islands scaled by 2.5 m / its distance (within 0.3–3) before they are
packed — so the armrest beside the viewer is sharp and the far wall not wasteful: about even texels per degree of view.
The layers — each a collection of lights in the scene, baked alone:
  base   — what stays when the room's lights are off ("base": the step lines' share), bounced;
  house  — the room's lights alone ("house" collection, the LED material glowing), bounced;
  ambl, ambc, ambr — the screen's light in three zones (left, centre, right), in white, when the scene has them;
  albedo — the surface's own colour, no light (for drawing it lit by the headset's own lights instead).
The "fx" collection (effects — a projector's beam, halos) is not baked and casts nothing; it goes out as it is.
Light is diffuse and glossy; the gloss — the sheen a dark fabric ceiling or a leather back takes at a grazing angle,
which Cycles shows from the seat and a diffuse-only bake lost — is baked as seen from the scene's camera, the seat's
eye (--view seat), where the viewer always is. Each layer is saved through the Standard view transform — the
headset's unlit tone mapping is turned off for these, so the texture is shown as it is and adds up linearly:
base + k · house is the room with its lights at k. The albedo has no light and no view.

OUT_DIR gets place.glb — per group a mesh "base_<g>" (its base atlas, unlit) and a mesh "house_<g>" (the same
geometry, its house atlas, unlit; the app draws it additively, scaled by the lights' level) — the atlases as
<g>_<layer>.jpg, and place.json (groups, the seat, the eye and the screen from the scene). With screen zones, place.glb's
base holds them too (summed, in the scene's film_tint: a still film's light), and two more files carry them apart for the
app to tint with the film as it plays (动态氛围光): place_amb1.glb (one layer "amb_", the whole screen) and
place_amb3.glb ("ambl_", "ambc_", "ambr_"). The effects go out in all of them, unlit and double-sided.
"""
import bpy, sys, math, argparse, json, os, struct
import numpy as np

argv = sys.argv[sys.argv.index("--") + 1:]
ap = argparse.ArgumentParser()
ap.add_argument("blend"); ap.add_argument("out")
ap.add_argument("--size", type=int, default=4096); ap.add_argument("--samples", type=int, default=128)
ap.add_argument("--margin", type=int, default=6)
ap.add_argument("--groups", default="seats=seat_,room_b=floor|riser_|stage|screen|front_wall|ceiling|ceil_|back_wall")
ap.add_argument("--layers", default=None, help="default: base, house, the screen zones if any, albedo")
ap.add_argument("--zone-size", type=int, default=2048, help="the screen zones' atlases: their light is soft")
ap.add_argument("--zone-samples", type=int, default=512, help="…and noise in them shows on the dark ceiling")
ap.add_argument("--density", choices=["view", "area"], default="view",
                help="atlas space by nearness to the seat's eye (view) or by surface area alone (area)")
ap.add_argument("--pack", default="CONVEX", help="island shape for packing: CONCAVE (tightest, slow), CONVEX, AABB")
ap.add_argument("--sided", choices=["single", "double"], default="single",
                help="single: back faces culled (B3110, high level: 4.68 → 3.89 ms GPU, 52 → 2 missed frames)")
ap.add_argument("--view", choices=["seat", "surface"], default="seat",
                help="gloss as seen from the scene's camera (the seat) or from straight above each surface")
a = ap.parse_args(argv)
os.makedirs(a.out, exist_ok=True)

bpy.ops.wm.open_mainfile(filepath=a.blend)
scene = bpy.context.scene
LED_STRENGTH = 12.0

scene.render.engine = 'CYCLES'
prefs = bpy.context.preferences.addons['cycles'].preferences
try:
    prefs.compute_device_type = 'METAL'; prefs.get_devices()
    for d in prefs.devices: d.use = True
    scene.cycles.device = 'GPU'
except Exception as ex:
    print("GPU unavailable:", ex)
scene.cycles.samples = a.samples
scene.view_settings.view_transform = 'Standard'; scene.view_settings.look = 'None'; scene.view_settings.exposure = 0.0
scene.render.image_settings.file_format = 'JPEG'; scene.render.image_settings.quality = 92
scene.render.bake.view_from = 'ACTIVE_CAMERA' if a.view == "seat" else 'ABOVE_SURFACE'


ZONES = [z for z in ("ambl", "ambc", "ambr") if z in bpy.data.collections]
LIGHTS = [c for c in ("base", "house") if c in bpy.data.collections] + ZONES
LAYERS = a.layers.split(",") if a.layers else LIGHTS + ["albedo"]
FX = list(bpy.data.collections["fx"].objects) if "fx" in bpy.data.collections else []
for o in FX: o.hide_render = True  # not in the bake: no shadow, no occlusion, no light


# The world (an outdoor place's night sky, its ambient light) belongs to the base layer alone: added again under the house
# layer it would be counted twice. So does a material with a "base_emission" (a sky dome); one with a "house_emission"
# (a lit window, a lantern's glass) glows in the house layer alone. Their strengths are those properties.
WORLD = scene.world.node_tree.nodes.get("Background") if scene.world and scene.world.use_nodes else None
WORLD_STRENGTH = WORLD.inputs["Strength"].default_value if WORLD else 0.0


def set_layer(layer):
    on_house = layer == "house"
    on_base = layer == "base"
    for c in LIGHTS:
        for o in bpy.data.collections[c].objects: o.hide_render = c != layer
    if WORLD: WORLD.inputs["Strength"].default_value = WORLD_STRENGTH if on_base else 0.0
    for m in bpy.data.materials:
        b = m.node_tree.nodes.get("Principled BSDF") if m.use_nodes else None
        if b is not None and ("base_emission" in m or "house_emission" in m):
            b.inputs["Emission Strength"].default_value = \
                m.get("base_emission", 0.0) if on_base else m.get("house_emission", 0.0) if on_house else 0.0
        if m.name == "led_strip":
            m.node_tree.nodes["Principled BSDF"].inputs["Emission Strength"].default_value = LED_STRENGTH if on_house else 0.0
        if m.name == "led_step":  # the step lines: their base share with the lights off, the rest with them on
            sb = scene.get("step_base", 0.0)
            m.node_tree.nodes["Principled BSDF"].inputs["Emission Strength"].default_value = \
                LED_STRENGTH * ((1 - sb) if on_house else sb if on_base else 0.0)


# ---- groups by name prefix
rules = []
gsize, gmargin, glayers = {}, {}, {}
for g in a.groups.split(","):
    name, prefixes = g.split("=")
    if ":" in name:
        name, only = name.split(":")
        glayers[name.split("@")[0]] = set(only.split("+"))
    if "@" in name:
        name, spec = name.split("@")
        size, _, margin = spec.partition("/")
        gsize[name] = int(size)
        if margin: gmargin[name] = int(margin)
    rules.append((name, prefixes.split("|")))
G_SIZE = lambda g: gsize.get(g, a.size)
G_MARGIN = lambda g: gmargin.get(g, a.margin) / G_SIZE(g)  # the packing gap, as a fraction of the atlas
G_HAS = lambda g, layer: g not in glayers or ("amb" if layer.startswith("amb") else layer) in glayers[g]
meshes = [o for o in scene.objects if o.type == 'MESH' and o not in FX]
groups = {}
for o in meshes:
    g = next((name for name, pre in rules if any(o.name.startswith(p) for p in pre)), "room")
    groups.setdefault(g, []).append(o)
print("BAKE groups", {g: len(v) for g, v in groups.items()})

joined = {}
from mathutils import Vector
EYE_POS = Vector(scene["seat"]) + Vector((0, 0, scene["eye"]))


def nearness(o):
    """2.5 m over the part's distance from the eye (half its box's nearest point, half its centre), within 0.3–3."""
    bb = [Vector(c) for c in o.bound_box]  # transforms applied: world coordinates
    mn = Vector((min(v.x for v in bb), min(v.y for v in bb), min(v.z for v in bb)))
    mx = Vector((max(v.x for v in bb), max(v.y for v in bb), max(v.z for v in bb)))
    near = Vector((min(max(EYE_POS.x, mn.x), mx.x), min(max(EYE_POS.y, mn.y), mx.y), min(max(EYE_POS.z, mn.z), mx.z)))
    d = 0.5 * (near - EYE_POS).length + 0.5 * ((mn + mx) / 2 - EYE_POS).length
    return min(3.0, max(0.3, 2.5 / max(d, 0.1)))


for g, objs in groups.items():
    bpy.ops.object.select_all(action='DESELECT')
    for o in objs: o.select_set(True)
    bpy.context.view_layer.objects.active = objs[0]
    bpy.ops.object.make_single_user(object=True, obdata=True)
    bpy.ops.object.transform_apply(location=True, rotation=True, scale=True)
    for o in objs:
        lm = o.data.uv_layers.new(name="lightmap"); o.data.uv_layers.active = lm
    bpy.ops.object.mode_set(mode='EDIT'); bpy.ops.mesh.select_all(action='SELECT')
    bpy.ops.uv.smart_project(angle_limit=math.radians(66), island_margin=G_MARGIN(g), area_weight=0.0, scale_to_bounds=False)
    bpy.ops.object.mode_set(mode='OBJECT')
    if a.density == "view":
        for o in objs:
            w = nearness(o)
            for d in o.data.uv_layers["lightmap"].data: d.uv = d.uv * w
    if len(objs) > 1:
        bpy.ops.object.join()
    o = bpy.context.view_layer.objects.active; o.name = g
    me = o.data
    if a.density == "view":
        bpy.ops.object.mode_set(mode='EDIT'); bpy.ops.mesh.select_all(action='SELECT')
        bpy.ops.uv.select_all(action='SELECT')
        bpy.ops.uv.pack_islands(rotate=True, scale=True, margin_method='FRACTION', margin=G_MARGIN(g),
                                shape_method=a.pack)
        bpy.ops.object.mode_set(mode='OBJECT')
    joined[g] = o
    print("BAKE group", g, "faces", len(me.polygons))

# ---- bake each layer of each group into its atlas
images = {}
baked = {}
for layer in LAYERS:
    set_layer(layer)
    scene.cycles.samples = a.zone_samples if layer in ZONES else a.samples
    for g, o in joined.items():
        if not G_HAS(g, layer): continue
        size = min(a.zone_size, G_SIZE(g)) if layer in ZONES else G_SIZE(g)
        img = bpy.data.images.new(f"{g}_{layer}", size, size, alpha=False, float_buffer=True)
        for slot in o.material_slots:
            m = slot.material
            if m is None or not m.use_nodes: continue
            for n in [n for n in m.node_tree.nodes if n.name == "bake_target"]: m.node_tree.nodes.remove(n)
            n = m.node_tree.nodes.new("ShaderNodeTexImage"); n.name = "bake_target"; n.image = img
            m.node_tree.nodes.active = n
        for x in scene.objects: x.select_set(False)
        o.select_set(True); bpy.context.view_layer.objects.active = o
        if layer == "albedo":
            bpy.ops.object.bake(type='DIFFUSE', pass_filter={'COLOR'}, uv_layer="lightmap", margin=a.margin, use_clear=True)
        else:
            bpy.ops.object.bake(type='COMBINED', pass_filter={'EMIT', 'DIRECT', 'INDIRECT', 'DIFFUSE', 'GLOSSY'},
                                uv_layer="lightmap", margin=a.margin, use_clear=True)
        path = os.path.join(a.out, f"{g}_{layer}.jpg")
        img.save_render(path, scene=scene)
        baked[(g, layer)] = img
        # a group this layer leaves dark (the sky under the house lights) goes out without it: the headset would draw
        # the whole of it again, added, for nothing
        px = np.empty(len(img.pixels), np.float32); img.pixels.foreach_get(px)
        if layer != "albedo" and px.reshape(-1, 4)[:, :3].max() < 1.5 / 255:
            print("BAKE dark", g, layer, "— not exported")
        else:
            images[(g, layer)] = path
        print("BAKE baked", g, layer, path)

# ---- with screen zones: the whole screen (their sum) and the still place's base (base + that, in the film's tint)
import numpy as np


def pixels(img, size=None):
    if size and img.size[0] != size:
        img = img.copy(); img.scale(size, size)
    arr = np.empty(len(img.pixels), np.float32); img.pixels.foreach_get(arr); return arr


def derived(g, name, arr, size):
    arr.reshape(-1, 4)[:, 3] = 1.0
    img = bpy.data.images.new(f"{g}_{name}", size, size, alpha=False, float_buffer=True); img.pixels.foreach_set(arr)
    path = os.path.join(a.out, f"{g}_{name}.jpg"); img.save_render(path, scene=scene)
    if arr.reshape(-1, 4)[:, :3].max() < 1.5 / 255:  # as a dark baked layer: not drawn for nothing
        print("BAKE dark", g, name, "— not exported")
    else:
        images[(g, name)] = path


if ZONES:
    tint = np.array(list(scene.get("film_tint", (1.0, 1.0, 1.0))), np.float32) * scene.get("film_mean", 1.0)
    for g in joined:
        if not all((g, z) in baked for z in ZONES):  # a group out of the film's reach: its still place is its base
            if (g, "base") in images: images[(g, "still")] = images[(g, "base")]
            continue
        derived(g, "amb", sum(pixels(baked[(g, z)]) for z in ZONES), min(a.zone_size, G_SIZE(g)))
        still = sum(pixels(baked[(g, z)], G_SIZE(g)) for z in ZONES).reshape(-1, 4); still[:, :3] *= tint
        derived(g, "still", pixels(baked[(g, "base")]) + still.ravel(), G_SIZE(g))
        print("BAKE derived", g, "amb, still")

# ---- the places: per group a mesh per layer on the same geometry, each its atlas, unlit; the effects as they are
for o in [o for o in scene.objects if o.type in ('LIGHT', 'CAMERA')]: bpy.data.objects.remove(o)
unlit = set()


def atlas_material(name, path):
    m = bpy.data.materials.new(name); m.use_nodes = True
    nt = m.node_tree; nt.nodes.clear()
    uv = nt.nodes.new("ShaderNodeUVMap"); uv.uv_map = "lightmap"
    tex = nt.nodes.new("ShaderNodeTexImage"); tex.image = bpy.data.images.load(path)
    bsdf = nt.nodes.new("ShaderNodeBsdfPrincipled"); out = nt.nodes.new("ShaderNodeOutputMaterial")
    nt.links.new(uv.outputs[0], tex.inputs[0]); nt.links.new(tex.outputs[0], bsdf.inputs["Base Color"])
    nt.links.new(bsdf.outputs[0], out.inputs[0])
    unlit.add(m.name)  # its real name: a second export's "base_room" would be "base_room.001", and went out double-sided
    return m


for g, o in joined.items():
    for layer_uv in [l for l in o.data.uv_layers if l.name != "lightmap"]: o.data.uv_layers.remove(layer_uv)
    o.hide_render = True
for o in FX: o.hide_render = False


def export(path, layers):
    """`layers`: (the name its meshes and materials go out under, the layer whose atlas they show)."""
    made = []
    for g, o in joined.items():
        for name, layer in layers:
            if (g, layer) not in images: continue
            c = o.copy(); c.data = o.data.copy(); c.name = f"{name}_{g}"; c.hide_render = False
            c.data.materials.clear(); c.data.materials.append(atlas_material(f"{name}_{g}", images[(g, layer)]))
            scene.collection.objects.link(c); made.append(c)
    bpy.ops.object.select_all(action='DESELECT')
    for c in made + FX: c.select_set(True)
    bpy.ops.export_scene.gltf(filepath=path, export_format='GLB', export_image_format='JPEG', export_jpeg_quality=92,
                              export_apply=True, use_selection=True)
    for c in made:
        me = c.data; mats = list(me.materials)
        bpy.data.objects.remove(c); bpy.data.meshes.remove(me)
        for m in mats:
            if m: bpy.data.materials.remove(m)  # so the next export's materials keep their own names
    # KHR_materials_unlit on every material; the atlases single-sided by default — every face of a scenes/ room faces the
    # viewer's side (its planes are wound inward), and culling the backs halves what is rasterised; the effects
    # double-sided (a beam is seen from inside and out)
    with open(path, "rb") as f: data = f.read()
    magic, ver, _ = struct.unpack_from("<III", data, 0)
    jlen, jtype = struct.unpack_from("<II", data, 12)
    gltf = json.loads(data[20:20 + jlen]); rest = data[20 + jlen:]
    for m in gltf.get("materials", []):
        m.setdefault("extensions", {})["KHR_materials_unlit"] = {}
        pbr = m.setdefault("pbrMetallicRoughness", {}); pbr["metallicFactor"] = 0.0; pbr["roughnessFactor"] = 1.0
        m["doubleSided"] = (a.sided == "double") if m.get("name") in unlit else True
    gltf["extensionsUsed"] = sorted(set(gltf.get("extensionsUsed", [])) | {"KHR_materials_unlit"})
    j = json.dumps(gltf, separators=(",", ":")).encode(); j += b" " * ((4 - len(j) % 4) % 4)
    with open(path, "wb") as f:
        f.write(struct.pack("<III", magic, ver, 12 + 8 + len(j) + len(rest)))
        f.write(struct.pack("<II", len(j), jtype)); f.write(j); f.write(rest)
    print("BAKE wrote", path, os.path.getsize(path), "bytes:", [n for n, _ in layers], "+", [o.name for o in FX])


glb = os.path.join(a.out, "place.glb")
if any((g, "still") in images for g in joined):
    export(glb, [("base", "still"), ("house", "house")])
    export(os.path.join(a.out, "place_amb1.glb"), [("base", "base"), ("house", "house"), ("amb", "amb")])
    export(os.path.join(a.out, "place_amb3.glb"), [("base", "base"), ("house", "house")] + [(z, z) for z in ZONES])
else:
    export(glb, [("base", "base"), ("house", "house")])

info = {"groups": sorted(joined), "layers": LAYERS, "size": a.size, "samples": a.samples}
for k in ("seat", "eye", "screen"):
    if k in scene: info[k] = list(scene[k]) if hasattr(scene[k], "__len__") else scene[k]
json.dump(info, open(os.path.join(a.out, "place.json"), "w"), indent=1)
