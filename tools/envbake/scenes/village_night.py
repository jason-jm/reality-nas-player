"""
village_night.py — an open-air cinema in a Mediterranean hill village at night (the user's reference, 2026-10-23): a cobbled
square closed by stone houses with terracotta roofs and green shutters, stone stairs climbing to the left, an alley going
down to the right, a screen on a timber frame against a low stone wall, and beyond the roofs a hillside of houses and
cypresses with a windmill on its crest, mountains behind, a starry sky. The viewer sits in a timber armchair on a jute
rug, a round table with a candle lantern and a potted plant to the left, another armchair to the right.

  Blender --background --factory-startup --python tools/envbake/scenes/village_night.py -- OUT.blend

Blender axes: x right, y toward the screen, z up (glTF: x, z, -y). `SEAT` is the ground under the viewer's eye, `EYE` their
seated eye height. Lights, as tools/envbake/bake_layers.py bakes them apart:
  base  — the moon (and the world's night sky, which bake_layers counts in this layer alone);
  house — the village's lights: wall lanterns, lit windows, the candle on the table, and the warm wash the reference's lit
          state has over all of it; its glowing glass ("house_emission") lights with it;
  ambc  — the screen's light, in white (动态氛围光: the app tints it with the film).
Near things — the chairs, the table and what stands on it, the rug — are modelled finely: the viewer looks at them from a
hand's length. Every size is in metres.

The stone, the cobbles, the roof tiles and the shutters' planks are photographed scans, and a few props too (Poly Haven, CC0
— no credit owed): rustic_stone_wall, plaster_stone_wall_02, cobblestone_square, clay_roof_tiles_02, old_planks_02,
planter_pot_clay, wine_barrel_01, painted_wooden_bench, watering_can_metal_01; and for the near furniture (2026-10-25) teak_veneer,
rough_linen, fabric_pattern_05 and hessian_380, over real-size UVs (uv_box: the grain along each part). ASSETS is their folder, as
tools/envbake/polyhaven_get.py fetches it:

  Blender --background --factory-startup --python tools/envbake/scenes/village_night.py -- OUT.blend ASSETS

baked with --groups "near=near_,gnear=gnear_,hnear=hnear_,plaza=plaza_|terrace|stair|alley_floor,leaves@2048/2:base+house=leaf_,
far@2048:base+house=far_,sky@2048:base=sky_" — the square's floor in tiles (gnear) and the walls that face the seat (hnear)
on atlases of their own, sharpest near the seat; the plants' leaves, thousands of little islands, on theirs.

2026-10-24, the user: "欧洲小镇的那个能够提高逼真度吗" — the first version's stone, cobbles and tiles were procedural patterns,
its floor one sheet and its houses one box each, all sharing atlases: clean, regular, soft. Now the materials are scans and
the surfaces tiles.
"""
import bpy, bmesh, math, sys, random, os
from mathutils import Vector, Matrix, noise

ARGS = sys.argv[sys.argv.index("--") + 1:] if "--" in sys.argv else []
OUT = ARGS[0] if ARGS else None
ASSETS = ARGS[1] if len(ARGS) > 1 else os.environ.get("AURA_ENV_ASSETS", "")
assert os.path.isdir(os.path.join(ASSETS, "rustic_stone_wall")), "ASSETS: the Poly Haven scans (tools/envbake/polyhaven_get.py)"
random.seed(23)

SEAT = (0.0, 0.0, 0.0)
EYE = 1.1
SCREEN_W, SCREEN_ASPECT = 5.4, 2.0
SCREEN_H = SCREEN_W / SCREEN_ASPECT
SCREEN_Y = 8.6            # the screen's face
SCREEN_FOOT = 0.85
SCREEN_CENTER = (0.0, SCREEN_Y - 0.01, SCREEN_FOOT + SCREEN_H / 2)
TERRACE_Z = 1.5           # the ground behind the screen's wall, up the slope

bpy.ops.wm.read_factory_settings(use_empty=True)
scene = bpy.context.scene
house = bpy.data.collections.new("house"); scene.collection.children.link(house)
base = bpy.data.collections.new("base"); scene.collection.children.link(base)
room = bpy.data.collections.new("room"); scene.collection.children.link(room)
ambc = bpy.data.collections.new("ambc"); scene.collection.children.link(ambc)
fx = bpy.data.collections.new("fx"); scene.collection.children.link(fx)
WARM = (1.0, 0.62, 0.30)


# ---------------------------------------------------------------- materials
def _nodes(m):
    m.use_nodes = True
    nt = m.node_tree
    return nt, nt.nodes["Principled BSDF"]


def _coords(nt, scale=(1, 1, 1)):
    tc = nt.nodes.new("ShaderNodeTexCoord"); mp = nt.nodes.new("ShaderNodeMapping")
    mp.inputs["Scale"].default_value = scale
    nt.links.new(tc.outputs["Object"], mp.inputs[0])
    return mp.outputs[0]


def _ramp(nt, pairs):
    r = nt.nodes.new("ShaderNodeValToRGB")
    r.color_ramp.elements[0].position, r.color_ramp.elements[0].color = pairs[0][0], (*pairs[0][1], 1)
    r.color_ramp.elements[1].position, r.color_ramp.elements[1].color = pairs[-1][0], (*pairs[-1][1], 1)
    for pos, col in pairs[1:-1]:
        e = r.color_ramp.elements.new(pos); e.color = (*col, 1)
    return r


def _bump(nt, b, height, strength, distance=0.02):
    bp = nt.nodes.new("ShaderNodeBump"); bp.inputs["Strength"].default_value = strength
    bp.inputs["Distance"].default_value = distance
    nt.links.new(height, bp.inputs["Height"]); nt.links.new(bp.outputs[0], b.inputs["Normal"])


def _math(nt, op, a, b=None, c=None):
    n = nt.nodes.new("ShaderNodeMath"); n.operation = op
    for i, v in enumerate((a, b, c)):
        if v is None: continue
        if isinstance(v, (int, float)): n.inputs[i].default_value = v
        else: nt.links.new(v, n.inputs[i])
    return n.outputs[0]


def _mix(nt, fac, a, b, blend='MIX'):
    n = nt.nodes.new("ShaderNodeMix"); n.data_type = 'RGBA'; n.blend_type = blend
    for sock, v in ((n.inputs["Factor"], fac), (n.inputs[6], a), (n.inputs[7], b)):
        if isinstance(v, (int, float)): sock.default_value = v
        elif isinstance(v, tuple): sock.default_value = (*v, 1) if len(v) == 3 else v
        else: nt.links.new(v, sock)
    return n.outputs[2]


def scan_mat(name, tex, tile, tint=None, sat=1.0, rot=0.0, bump=0.7, dirt=False, tone=0.12):
    """A material from a Poly Haven scan in ASSETS: its colour, roughness and height, box-projected in the world — the
    projection takes the plane each face looks along, so a wall's courses lie level whichever way it faces and a floor is
    read from above — `tile` metres a repeat, turned `rot` degrees about z (a ridge-along-y roof's channels down its
    slope). The height is a bump: a normal map would want UVs the walls do not have. `tint` multiplies the colour (green
    paint on grey planks); `tone` varies it slowly, so no two stretches of wall are alike; `dirt` darkens a wall's foot,
    where rain splashes and feet pass."""
    d = os.path.join(ASSETS, tex)

    def img(kind, colour):
        f = next(x for x in sorted(os.listdir(d)) if f"_{kind}_" in x)
        i = bpy.data.images.load(os.path.join(d, f), check_existing=True)
        if not colour: i.colorspace_settings.name = "Non-Color"
        return i
    m = bpy.data.materials.new(name); nt, b = _nodes(m)
    tc = nt.nodes.new("ShaderNodeTexCoord"); mp = nt.nodes.new("ShaderNodeMapping")
    mp.inputs["Scale"].default_value = (1 / tile, 1 / tile, 1 / tile); mp.inputs["Rotation"].default_value = (0, 0, math.radians(rot))
    nt.links.new(tc.outputs["Object"], mp.inputs[0])
    out = {}
    for kind, colour in (("diff", True), ("rough", False), ("disp", False)):
        t = nt.nodes.new("ShaderNodeTexImage"); t.image = img(kind, colour); t.projection = 'BOX'; t.projection_blend = 0.2
        nt.links.new(mp.outputs[0], t.inputs[0]); out[kind] = t.outputs[0]
    col = out["diff"]
    if sat != 1.0:
        hs = nt.nodes.new("ShaderNodeHueSaturation"); hs.inputs["Saturation"].default_value = sat
        nt.links.new(col, hs.inputs["Color"]); col = hs.outputs[0]
    if tint: col = _mix(nt, 1.0, col, tint, 'MULTIPLY')
    co = _coords(nt)
    nz = nt.nodes.new("ShaderNodeTexNoise"); nz.inputs["Scale"].default_value = 0.22; nz.inputs["Detail"].default_value = 4.0
    nt.links.new(co, nz.inputs["Vector"])
    tn = _ramp(nt, [(0.3, (1 - tone,) * 3), (0.7, (1 + tone * 0.6,) * 3)]); nt.links.new(nz.outputs["Fac"], tn.inputs[0])
    col = _mix(nt, 1.0, col, tn.outputs[0], 'MULTIPLY')
    if dirt:
        sep = nt.nodes.new("ShaderNodeSeparateXYZ"); nt.links.new(tc.outputs["Object"], sep.inputs[0])
        dz = nt.nodes.new("ShaderNodeTexNoise"); dz.inputs["Scale"].default_value = 3.0; nt.links.new(co, dz.inputs["Vector"])
        zz = _math(nt, 'ADD', sep.outputs["Z"], _math(nt, 'MULTIPLY', dz.outputs["Fac"], 0.35))
        foot = _ramp(nt, [(0.0, (0.55, 0.52, 0.50)), (0.45, (0.85, 0.84, 0.83)), (0.75, (1, 1, 1))])
        mr = nt.nodes.new("ShaderNodeMapRange"); mr.inputs["From Min"].default_value = 0.0; mr.inputs["From Max"].default_value = 1.2
        nt.links.new(zz, mr.inputs["Value"]); nt.links.new(mr.outputs[0], foot.inputs[0])
        col = _mix(nt, 1.0, col, foot.outputs[0], 'MULTIPLY')
    nt.links.new(col, b.inputs["Base Color"]); nt.links.new(out["rough"], b.inputs["Roughness"])
    _bump(nt, b, out["disp"], bump, 0.03)
    return m


def uv_box(o):
    """UVs in metres on each face of a box-like mesh, u along the longer of the face's two in-plane axes: a part's grain or
    weave runs along its length — an arm's along the arm, a leg's up the leg, a pillow's checks square to its edges."""
    me = o.data
    if not me.uv_layers: me.uv_layers.new(name="UVMap")
    uv = me.uv_layers[0].data
    ext = [max(v.co[i] for v in me.vertices) - min(v.co[i] for v in me.vertices) for i in range(3)]
    for f in me.polygons:
        k = max(range(3), key=lambda i: abs(f.normal[i]))
        a, b = [i for i in range(3) if i != k]
        if ext[b] > ext[a]: a, b = b, a
        for li in f.loop_indices:
            co = me.vertices[me.loops[li].vertex_index].co
            uv[li].uv = (co[a], co[b])
    return o


def scan_uv(name, tex, tile, tint=None, sat=1.0, col="diff", rough=None, normal=1.0):
    """A material from a Poly Haven scan in ASSETS read through the mesh's own UVs ("UVMap", in metres: uv_box), `tile`
    metres a repeat — for near things with UVs, so the grain and weave follow each part and the normal map is right.
    `col` picks a colourway (col_01 …); `tint` multiplies the colour back to the part's own (after `sat`)."""
    d = os.path.join(ASSETS, tex)
    if not os.path.isdir(d): d = os.path.join(ASSETS, tex.split("/")[0], "textures")

    def img(kind, colour):
        f = next((x for x in sorted(os.listdir(d)) if f"_{kind}_" in x), None)
        if f is None: return None
        i = bpy.data.images.load(os.path.join(d, f), check_existing=True)
        if not colour: i.colorspace_settings.name = "Non-Color"
        return i
    m = bpy.data.materials.new(name); nt, b = _nodes(m)
    uvn = nt.nodes.new("ShaderNodeUVMap"); uvn.uv_map = "UVMap"
    mp = nt.nodes.new("ShaderNodeMapping"); mp.inputs["Scale"].default_value = (1 / tile, 1 / tile, 1 / tile)
    nt.links.new(uvn.outputs[0], mp.inputs[0])

    def tex_node(image):
        t = nt.nodes.new("ShaderNodeTexImage"); t.image = image; nt.links.new(mp.outputs[0], t.inputs[0]); return t.outputs[0]
    c = tex_node(img(col, True))
    if sat != 1.0:
        hs = nt.nodes.new("ShaderNodeHueSaturation"); hs.inputs["Saturation"].default_value = sat
        nt.links.new(c, hs.inputs["Color"]); c = hs.outputs[0]
    if tint: c = _mix(nt, 1.0, c, tint, 'MULTIPLY')
    nt.links.new(c, b.inputs["Base Color"])
    r = img("rough", False)
    if r is not None and rough is None: nt.links.new(tex_node(r), b.inputs["Roughness"])
    else: b.inputs["Roughness"].default_value = rough if rough is not None else 0.8
    n = img("nor_gl", False)
    if n is not None and normal:
        nm = nt.nodes.new("ShaderNodeNormalMap"); nm.uv_map = "UVMap"; nm.inputs["Strength"].default_value = normal
        nt.links.new(tex_node(n), nm.inputs["Color"]); nt.links.new(nm.outputs[0], b.inputs["Normal"])
    return m


def plain(name, color, rough=0.7, metal=0.0, emission=None, house_emission=None, base_emission=None):
    m = bpy.data.materials.new(name); nt, b = _nodes(m)
    b.inputs["Base Color"].default_value = (*color, 1); b.inputs["Roughness"].default_value = rough
    b.inputs["Metallic"].default_value = metal
    if emission is not None:
        b.inputs["Emission Color"].default_value = (*emission, 1)
        b.inputs["Emission Strength"].default_value = 0.0
        if house_emission is not None: m["house_emission"] = house_emission
        if base_emission is not None: m["base_emission"] = base_emission
    return m


def lit_glass(name, strength):
    """A lit window, glowing in the house layer alone: lamplight behind it, amber, a little brighter and paler in places
    (a lamp, a curtain), not one flat glare (at 4 it baked as a white blaze — the user, 2026-10-23: 太亮了)."""
    m = bpy.data.materials.new(name); nt, b = _nodes(m)
    b.inputs["Base Color"].default_value = (0.02, 0.012, 0.006, 1); b.inputs["Roughness"].default_value = 0.3
    co = _coords(nt)
    nz = nt.nodes.new("ShaderNodeTexNoise"); nz.inputs["Scale"].default_value = 3.5; nz.inputs["Detail"].default_value = 2.0
    nt.links.new(co, nz.inputs["Vector"])
    r = _ramp(nt, [(0.3, (0.85, 0.38, 0.11)), (0.55, (1.0, 0.52, 0.18)), (0.75, (1.0, 0.66, 0.33))])
    nt.links.new(nz.outputs["Fac"], r.inputs[0]); nt.links.new(r.outputs[0], b.inputs["Emission Color"])
    b.inputs["Emission Strength"].default_value = 0.0
    m["house_emission"] = strength
    return m


def stone(name, light, dark, scale=4.2, rough=0.85):
    """Rubble walling: irregular stones (Voronoi cells, wider than tall), each its own shade and mottled, pillowed — rounded
    off toward deep, thin mortar joints."""
    m = bpy.data.materials.new(name); nt, b = _nodes(m)
    co = _coords(nt, (1.0, 1.0, 1.6))
    nzw = nt.nodes.new("ShaderNodeTexNoise"); nzw.inputs["Scale"].default_value = 3.0; nt.links.new(co, nzw.inputs["Vector"])
    warp = nt.nodes.new("ShaderNodeVectorMath"); warp.operation = 'MULTIPLY_ADD'; warp.inputs[1].default_value = (0.06, 0.06, 0.06)
    nt.links.new(nzw.outputs["Color"], warp.inputs[0]); nt.links.new(co, warp.inputs[2])
    v = nt.nodes.new("ShaderNodeTexVoronoi"); v.feature = 'DISTANCE_TO_EDGE'
    v.inputs["Scale"].default_value = scale; v.inputs["Randomness"].default_value = 0.9
    v2 = nt.nodes.new("ShaderNodeTexVoronoi"); v2.inputs["Scale"].default_value = scale; v2.inputs["Randomness"].default_value = 0.9
    nt.links.new(warp.outputs[0], v.inputs["Vector"]); nt.links.new(warp.outputs[0], v2.inputs["Vector"])
    joint = _ramp(nt, [(0.0, (0, 0, 0)), (0.035, (1, 1, 1))]); nt.links.new(v.outputs["Distance"], joint.inputs[0])
    pillow = _ramp(nt, [(0.0, (0, 0, 0)), (0.22, (1, 1, 1))]); pillow.color_ramp.interpolation = 'EASE'
    nt.links.new(v.outputs["Distance"], pillow.inputs[0])
    shade = _ramp(nt, [(0.0, dark), (0.55, tuple((a + c) / 2 for a, c in zip(light, dark))), (1.0, light)])
    sep = nt.nodes.new("ShaderNodeSeparateColor"); nt.links.new(v2.outputs["Color"], sep.inputs[0])
    nt.links.new(sep.outputs[0], shade.inputs[0])
    nz = nt.nodes.new("ShaderNodeTexNoise"); nz.inputs["Scale"].default_value = 30.0; nz.inputs["Detail"].default_value = 10.0
    nz.inputs["Roughness"].default_value = 0.65
    nt.links.new(co, nz.inputs["Vector"])
    mott = _ramp(nt, [(0.3, (0.72, 0.72, 0.72)), (0.7, (1.12, 1.12, 1.12))]); nt.links.new(nz.outputs["Fac"], mott.inputs[0])
    grain = nt.nodes.new("ShaderNodeMix"); grain.data_type = 'RGBA'; grain.blend_type = 'MULTIPLY'; grain.inputs["Factor"].default_value = 1.0
    nt.links.new(shade.outputs[0], grain.inputs[6]); nt.links.new(mott.outputs[0], grain.inputs[7])
    mix = nt.nodes.new("ShaderNodeMix"); mix.data_type = 'RGBA'
    nt.links.new(joint.outputs[0], mix.inputs["Factor"])
    mix.inputs[6].default_value = (*[c * 0.28 for c in dark], 1); nt.links.new(grain.outputs[2], mix.inputs[7])
    nt.links.new(mix.outputs[2], b.inputs["Base Color"])
    h = nt.nodes.new("ShaderNodeMath"); h.operation = 'MULTIPLY_ADD'; h.inputs[1].default_value = 0.12
    nt.links.new(nz.outputs["Fac"], h.inputs[0]); nt.links.new(pillow.outputs[0], h.inputs[2])
    _bump(nt, b, h.outputs[0], 1.0, 0.045)
    b.inputs["Roughness"].default_value = rough
    return m


def wood(name, color, scale=3.0, rough=0.55, grain_dir=(1, 18, 18), planks=None):
    """Timber: a long grain along the object's x, darker streaks; `planks` (spacing m) cuts grooves across it."""
    m = bpy.data.materials.new(name); nt, b = _nodes(m)
    co = _coords(nt, grain_dir)
    nz = nt.nodes.new("ShaderNodeTexNoise"); nz.inputs["Scale"].default_value = scale
    nz.inputs["Detail"].default_value = 9.0; nz.inputs["Distortion"].default_value = 3.0
    nt.links.new(co, nz.inputs["Vector"])
    r = _ramp(nt, [(0.25, tuple(c * 0.55 for c in color)), (0.6, color), (0.85, tuple(min(1, c * 1.25) for c in color))])
    nt.links.new(nz.outputs["Fac"], r.inputs[0])
    height = nz.outputs["Fac"]
    col = r.outputs[0]
    if planks:
        co2 = _coords(nt)
        wv = nt.nodes.new("ShaderNodeTexWave"); wv.wave_type = 'BANDS'; wv.bands_direction = 'Z'
        wv.wave_profile = 'SAW'; wv.inputs["Scale"].default_value = 1.0 / planks / 1.0
        nt.links.new(co2, wv.inputs["Vector"])
        groove = _ramp(nt, [(0.0, (0, 0, 0)), (0.04, (1, 1, 1))]); nt.links.new(wv.outputs["Fac"], groove.inputs[0])
        mm = nt.nodes.new("ShaderNodeMix"); mm.data_type = 'RGBA'; mm.blend_type = 'MULTIPLY'; mm.inputs["Factor"].default_value = 0.7
        nt.links.new(col, mm.inputs[6]); nt.links.new(groove.outputs[0], mm.inputs[7]); col = mm.outputs[2]
        ad = nt.nodes.new("ShaderNodeMath"); ad.operation = 'MULTIPLY_ADD'; ad.inputs[1].default_value = 0.2
        nt.links.new(nz.outputs["Fac"], ad.inputs[0]); nt.links.new(groove.outputs[0], ad.inputs[2]); height = ad.outputs[0]
    nt.links.new(col, b.inputs["Base Color"])
    _bump(nt, b, height, 0.25, 0.01)
    b.inputs["Roughness"].default_value = rough
    return m


def fabric(name, color, scale=260.0, stripe=None):
    """Upholstery: a fine weave; `stripe` = (colour, period m, share) for the throw pillows' bands."""
    m = bpy.data.materials.new(name); nt, b = _nodes(m)
    co = _coords(nt)
    nz = nt.nodes.new("ShaderNodeTexNoise"); nz.inputs["Scale"].default_value = scale; nz.inputs["Detail"].default_value = 6.0
    nt.links.new(co, nz.inputs["Vector"])
    r = _ramp(nt, [(0.3, tuple(c * 0.8 for c in color)), (0.7, tuple(min(1, c * 1.1) for c in color))])
    nt.links.new(nz.outputs["Fac"], r.inputs[0]); col = r.outputs[0]
    if stripe:
        sc, period, share = stripe
        wv = nt.nodes.new("ShaderNodeTexWave"); wv.wave_type = 'BANDS'; wv.bands_direction = 'X'; wv.wave_profile = 'SAW'
        wv.inputs["Scale"].default_value = 1.0 / period
        nt.links.new(co, wv.inputs["Vector"])
        cut = _ramp(nt, [(share - 0.01, (0, 0, 0)), (share, (1, 1, 1))]); cut.color_ramp.interpolation = 'CONSTANT'
        nt.links.new(wv.outputs["Fac"], cut.inputs[0])
        mm = nt.nodes.new("ShaderNodeMix"); mm.data_type = 'RGBA'
        nt.links.new(cut.outputs[0], mm.inputs["Factor"]); mm.inputs[6].default_value = (*sc, 1); nt.links.new(col, mm.inputs[7])
        col = mm.outputs[2]
    nt.links.new(col, b.inputs["Base Color"])
    _bump(nt, b, nz.outputs["Fac"], 0.2, 0.004)
    b.inputs["Roughness"].default_value = 0.92
    b.inputs["Sheen Weight"].default_value = 0.3
    return m


def jute(name):
    """A chunky jute rug: knots in rows (two crossed waves) and a darker border cord."""
    m = bpy.data.materials.new(name); nt, b = _nodes(m)
    co = _coords(nt)
    w1 = nt.nodes.new("ShaderNodeTexWave"); w1.wave_type = 'BANDS'; w1.bands_direction = 'X'; w1.inputs["Scale"].default_value = 1 / 0.018 / 6.28
    w2 = nt.nodes.new("ShaderNodeTexWave"); w2.wave_type = 'BANDS'; w2.bands_direction = 'Y'; w2.inputs["Scale"].default_value = 1 / 0.022 / 6.28
    for w in (w1, w2):
        w.wave_profile = 'SIN'; w.inputs["Distortion"].default_value = 0.6; nt.links.new(co, w.inputs["Vector"])
    knot = nt.nodes.new("ShaderNodeMath"); knot.operation = 'MULTIPLY'
    nt.links.new(w1.outputs["Fac"], knot.inputs[0]); nt.links.new(w2.outputs["Fac"], knot.inputs[1])
    nz = nt.nodes.new("ShaderNodeTexNoise"); nz.inputs["Scale"].default_value = 90.0; nt.links.new(co, nz.inputs["Vector"])
    r = _ramp(nt, [(0.0, (0.10, 0.065, 0.035)), (0.5, (0.26, 0.17, 0.09)), (1.0, (0.40, 0.28, 0.16))])
    mix = nt.nodes.new("ShaderNodeMath"); mix.operation = 'MULTIPLY_ADD'; mix.inputs[1].default_value = 0.7
    nt.links.new(knot.outputs[0], mix.inputs[0]); nt.links.new(nz.outputs["Fac"], mix.inputs[2])
    nt.links.new(mix.outputs[0], r.inputs[0]); nt.links.new(r.outputs[0], b.inputs["Base Color"])
    _bump(nt, b, knot.outputs[0], 0.9, 0.006)
    b.inputs["Roughness"].default_value = 0.95
    return m


def leaves(name, color):
    m = bpy.data.materials.new(name); nt, b = _nodes(m)
    co = _coords(nt)
    nz = nt.nodes.new("ShaderNodeTexNoise"); nz.inputs["Scale"].default_value = 25.0; nz.inputs["Detail"].default_value = 4.0
    nt.links.new(co, nz.inputs["Vector"])
    r = _ramp(nt, [(0.3, tuple(c * 0.5 for c in color)), (0.7, tuple(min(1, c * 1.35) for c in color))])
    nt.links.new(nz.outputs["Fac"], r.inputs[0]); nt.links.new(r.outputs[0], b.inputs["Base Color"])
    _bump(nt, b, nz.outputs["Fac"], 0.5, 0.02)
    b.inputs["Roughness"].default_value = 0.6
    return m


def hillside(name):
    """The far slope: dark scrub with grey rock showing through, terraced in bands."""
    m = bpy.data.materials.new(name); nt, b = _nodes(m)
    co = _coords(nt)
    nz = nt.nodes.new("ShaderNodeTexNoise"); nz.inputs["Scale"].default_value = 0.09; nz.inputs["Detail"].default_value = 10.0
    nt.links.new(co, nz.inputs["Vector"])
    r = _ramp(nt, [(0.35, (0.035, 0.05, 0.03)), (0.5, (0.06, 0.08, 0.045)), (0.62, (0.30, 0.29, 0.27)), (0.75, (0.42, 0.41, 0.38))])
    nt.links.new(nz.outputs["Fac"], r.inputs[0]); nt.links.new(r.outputs[0], b.inputs["Base Color"])
    _bump(nt, b, nz.outputs["Fac"], 0.6, 0.5)
    b.inputs["Roughness"].default_value = 0.95
    return m


def night_sky(name):
    """The dome's face, emitting (base layer only): deep navy, lighter toward the horizon, wisps of cloud, stars."""
    m = bpy.data.materials.new(name); nt, b = _nodes(m)
    b.inputs["Base Color"].default_value = (0, 0, 0, 1)
    tc = nt.nodes.new("ShaderNodeTexCoord")
    dirn = nt.nodes.new("ShaderNodeVectorMath"); dirn.operation = 'NORMALIZE'; nt.links.new(tc.outputs["Object"], dirn.inputs[0])
    sep = nt.nodes.new("ShaderNodeSeparateXYZ"); nt.links.new(dirn.outputs[0], sep.inputs[0])
    # the dome's height, −1…1, onto 0…1: dark under the horizon (seen through any gap low down), lightest at it
    half = nt.nodes.new("ShaderNodeMath"); half.operation = 'MULTIPLY_ADD'
    half.inputs[1].default_value = 0.5; half.inputs[2].default_value = 0.5
    nt.links.new(sep.outputs["Z"], half.inputs[0])
    grad = _ramp(nt, [(0.0, (0.003, 0.004, 0.008)), (0.47, (0.010, 0.016, 0.040)), (0.5, (0.030, 0.055, 0.150)),
                      (0.56, (0.016, 0.030, 0.105)), (0.725, (0.007, 0.014, 0.060)), (1.0, (0.004, 0.008, 0.038))])
    nt.links.new(half.outputs[0], grad.inputs[0])
    cl = nt.nodes.new("ShaderNodeTexNoise"); cl.inputs["Scale"].default_value = 2.6; cl.inputs["Detail"].default_value = 9.0
    cl.inputs["Roughness"].default_value = 0.62
    stretch = nt.nodes.new("ShaderNodeVectorMath"); stretch.operation = 'MULTIPLY'; stretch.inputs[1].default_value = (1.0, 2.2, 3.0)
    nt.links.new(dirn.outputs[0], stretch.inputs[0]); nt.links.new(stretch.outputs[0], cl.inputs["Vector"])
    cmask = _ramp(nt, [(0.52, (0, 0, 0)), (0.72, (1, 1, 1))]); nt.links.new(cl.outputs["Fac"], cmask.inputs[0])
    cloud = nt.nodes.new("ShaderNodeMix"); cloud.data_type = 'RGBA'
    nt.links.new(cmask.outputs[0], cloud.inputs["Factor"]); nt.links.new(grad.outputs[0], cloud.inputs[6])
    cloud.inputs[7].default_value = (0.032, 0.048, 0.110, 1)
    st = nt.nodes.new("ShaderNodeTexVoronoi"); st.inputs["Scale"].default_value = 260.0; st.inputs["Randomness"].default_value = 1.0
    nt.links.new(dirn.outputs[0], st.inputs["Vector"])
    dot = _ramp(nt, [(0.0, (1, 1, 1)), (0.06, (0, 0, 0))]); nt.links.new(st.outputs["Distance"], dot.inputs[0])
    sepc = nt.nodes.new("ShaderNodeSeparateColor"); nt.links.new(st.outputs["Color"], sepc.inputs[0])
    keep = _ramp(nt, [(0.80, (0, 0, 0)), (0.81, (0.35, 0.35, 0.35)), (0.97, (0.9, 0.9, 0.95))])
    nt.links.new(sepc.outputs[0], keep.inputs[0])
    starlit = nt.nodes.new("ShaderNodeMix"); starlit.data_type = 'RGBA'; starlit.blend_type = 'MULTIPLY'; starlit.inputs["Factor"].default_value = 1.0
    nt.links.new(dot.outputs[0], starlit.inputs[6]); nt.links.new(keep.outputs[0], starlit.inputs[7])
    veil = nt.nodes.new("ShaderNodeMath"); veil.operation = 'SUBTRACT'; veil.inputs[0].default_value = 1.0
    nt.links.new(cmask.outputs[0], veil.inputs[1])
    starv = nt.nodes.new("ShaderNodeMix"); starv.data_type = 'RGBA'; starv.blend_type = 'MULTIPLY'
    starv.inputs["Factor"].default_value = 1.0
    nt.links.new(starlit.outputs[2], starv.inputs[6]); nt.links.new(veil.outputs[0], starv.inputs[7])
    add = nt.nodes.new("ShaderNodeMix"); add.data_type = 'RGBA'; add.blend_type = 'ADD'; add.inputs["Factor"].default_value = 1.0
    nt.links.new(cloud.outputs[2], add.inputs[6]); nt.links.new(starv.outputs[2], add.inputs[7])
    nt.links.new(add.outputs[2], b.inputs["Emission Color"])
    b.inputs["Emission Strength"].default_value = 0.0
    m["base_emission"] = 1.0
    return m


M = {
    # The scans' colours are true (the rubble reflects 14 %); the first version's were near 45 % and its lights, which the user
    # set by eye (windows "太亮" … "太暗"), were set against them: the scans are raised toward that brightness, the lights kept.
    "wall": scan_mat("stone_wall", "rustic_stone_wall", 1.52, tint=(2.1, 2.0, 1.95), dirt=True),
    "wall_pale": scan_mat("stone_wall_pale", "plaster_stone_wall_02", 2.12, tint=(1.45, 1.45, 1.45), dirt=True),
    # quoins, sills, steps, voussoirs: the rubble scan laid huge, so a block shows one stone's face (its grain, no joints),
    # paler and greyer — dressed limestone beside the rubble (the procedural blocks read as white blotches next to the scans)
    "dressed": scan_mat("stone_dressed", "rustic_stone_wall", 5.0, tint=(2.9, 2.8, 2.6), sat=0.45, bump=0.35, tone=0.08),
    "cobbles": scan_mat("cobbles", "cobblestone_square", 1.0, tint=(1.55, 1.55, 1.55), bump=0.8, tone=0.15),
    "roof": scan_mat("roof_tiles", "clay_roof_tiles_02", 2.5, sat=0.85, bump=0.8),            # a ridge along x: channels along y
    "roof_y": scan_mat("roof_tiles_y", "clay_roof_tiles_02", 2.5, sat=0.85, bump=0.8, rot=90.0),
    "timber": wood("timber_frame", (0.20, 0.11, 0.055), scale=2.5),
    # the near furniture in scanned wood and cloth (2026-10-25), each tinted back to its colour before
    "teak": scan_uv("teak", "teak_veneer", 0.8, tint=(0.84, 0.85, 0.84)),
    "table": scan_uv("table_top", "teak_veneer", 0.8, tint=(0.70, 0.72, 0.74)),
    "door": scan_mat("door_planks", "old_planks_02", 1.6, tint=(2.07, 1.44, 0.99)),
    "shutter": scan_mat("shutter", "old_planks_02", 1.2, tint=(1.24, 1.9, 1.56), sat=0.6),
    "door_green": scan_mat("door_green", "old_planks_02", 1.4, tint=(1.24, 1.9, 1.6), sat=0.6),
    "frame": plain("window_frame", (0.16, 0.10, 0.06), 0.5),
    "iron": plain("iron", (0.02, 0.02, 0.022), 0.45, metal=0.7),
    "gutter": plain("gutter", (0.12, 0.11, 0.10), 0.5, metal=0.5),
    # unlit panes: dull, as an unlit room behind glass reads at night — glossy, the sky's sheen baked from the seat made
    # them white boards (2026-10-23)
    "glass_dark": plain("glass_dark", (0.006, 0.007, 0.011), 0.65),
    "glass_lit": lit_glass("glass_lit", 1.1),
    "lamp_glass": plain("lamp_glass", (0.10, 0.09, 0.08), 0.1, emission=(1.0, 0.58, 0.22), house_emission=1.8),
    "clear_glass": plain("clear_glass", (0.035, 0.035, 0.04), 0.05, emission=(1.0, 0.55, 0.2), house_emission=0.6),
    "flame": plain("flame", (0.9, 0.85, 0.7), 0.5, emission=(1.0, 0.62, 0.25), house_emission=30.0),
    "candle": plain("candle", (0.86, 0.83, 0.76), 0.6),
    "terracotta": plain("terracotta", (0.42, 0.17, 0.08), 0.8),
    "earth": plain("earth", (0.06, 0.04, 0.025), 0.95),
    "leaf": leaves("leaf", (0.055, 0.10, 0.04)),
    "olive": leaves("olive_leaf", (0.09, 0.12, 0.07)),
    "cypress": leaves("cypress", (0.02, 0.05, 0.025)),
    "bark": wood("bark", (0.12, 0.09, 0.06), scale=6.0, grain_dir=(18, 18, 1)),
    "cushion": scan_uv("cushion_beige", "rough_linen", 0.27, sat=0.0, tint=(1.01, 0.93, 0.78)),
    "seat_teal": scan_uv("cushion_teal", "rough_linen", 0.27, sat=0.0, tint=(0.114, 0.277, 0.245)),
    "pillow": scan_uv("pillow_check", "fabric_pattern_05", 0.5, col="col_01", tint=(1.6, 1.6, 1.6)),
    "pillow_green": scan_uv("pillow_green", "fabric_pattern_05", 0.5, col="col_03", tint=(0.8, 0.9, 0.85)),
    # a jute rug's weave is coarse — the hessian scan at its own scale (2 mm threads) read as a smooth pad: laid ×3.6
    "jute": scan_uv("jute", "hessian_380", 1.0, normal=1.5),
    "screen": plain("screen", (0.8, 0.8, 0.8), 0.9, emission=(0.62, 0.70, 0.85)),
    "hill": hillside("hillside"),
    "mountain": plain("mountain", (0.05, 0.06, 0.08), 0.95),
    "plaster": stone("stone_far", (0.60, 0.55, 0.47), (0.48, 0.43, 0.36), scale=1.6),
    "sky": night_sky("night_sky"),
}
M["screen"]["house_emission"] = 0.0  # the bare screen does not glow; render_state.py's dusk film paints it


# ---------------------------------------------------------------- geometry helpers
def _link(o, coll):
    for c in list(o.users_collection): c.objects.unlink(o)
    coll.objects.link(o)
    return o


def _apply(o, mods):
    bpy.context.view_layer.objects.active = o; o.select_set(True)
    for md in mods: bpy.ops.object.modifier_apply(modifier=md.name)
    o.select_set(False)


def box(name, c, s, material, coll=room, bevel=0.0, segs=2, soft=0, rot=(0, 0, 0), smooth_angle=35):
    """A box centred at c sized s, turned by rot (degrees, XYZ), bevelled and (soft > 0) subdivided that many levels."""
    bpy.ops.mesh.primitive_cube_add(size=1, location=c)
    o = bpy.context.active_object; o.name = name; o.scale = s
    bpy.ops.object.transform_apply(location=False, rotation=False, scale=True)
    uv_box(o)
    mods = []
    if bevel > 0:
        md = o.modifiers.new("bevel", 'BEVEL'); md.width = bevel; md.segments = 3 if soft else segs
        md.limit_method = 'ANGLE'; mods.append(md)
    if soft:
        md = o.modifiers.new("soft", 'SUBSURF'); md.levels = soft; md.render_levels = soft; mods.append(md)
    if mods: _apply(o, mods)
    o.rotation_euler = tuple(math.radians(r) for r in rot)
    bpy.ops.object.transform_apply(location=False, rotation=True, scale=False)
    o.data.materials.append(material)
    if bevel > 0 or soft:
        for p in o.data.polygons: p.use_smooth = True
        if not soft:
            o.data.set_sharp_from_angle(angle=math.radians(smooth_angle))
    return _link(o, coll)


def cyl(name, c, r, depth, material, coll=room, verts=16, rot=(0, 0, 0), r2=None, smooth=True, cap=True):
    bpy.ops.mesh.primitive_cone_add(vertices=verts, radius1=r, radius2=r if r2 is None else r2, depth=depth, location=c,
                                    rotation=tuple(math.radians(x) for x in rot), end_fill_type='NGON' if cap else 'NOTHING')
    o = bpy.context.active_object; o.name = name; o.data.materials.append(material)
    if smooth:
        for p in o.data.polygons: p.use_smooth = True
        o.data.set_sharp_from_angle(angle=math.radians(40))
    return _link(o, coll)


def sphere(name, c, s, material, coll=room, subdiv=2, wobble=0.0, seed=0, rot=(0, 0, 0)):
    """An icosphere scaled to s, its surface pushed in and out by noise (`wobble`, a share of its size): leaves, a bush."""
    bpy.ops.mesh.primitive_ico_sphere_add(subdivisions=subdiv, radius=1.0, location=(0, 0, 0))
    o = bpy.context.active_object; o.name = name
    if wobble:
        off = Vector((seed * 13.1, seed * 7.7, seed * 3.3))
        for v in o.data.vertices:
            v.co *= 1.0 + wobble * noise.noise(v.co * 1.8 + off)
    for v in o.data.vertices:
        v.co = Vector((v.co.x * s[0], v.co.y * s[1], v.co.z * s[2]))
    o.rotation_euler = tuple(math.radians(r) for r in rot)
    bpy.ops.object.transform_apply(location=False, rotation=True, scale=False)
    o.location = c
    o.data.materials.append(material)
    for p in o.data.polygons: p.use_smooth = True
    return _link(o, coll)


def mesh_from(name, verts, faces, material, coll=room, smooth=False):
    me = bpy.data.meshes.new(name); me.from_pydata(verts, [], faces); me.update()
    o = bpy.data.objects.new(name, me); coll.objects.link(o); me.materials.append(material)
    if smooth:
        for p in me.polygons: p.use_smooth = True
    return o


def join(name, objs):
    bpy.ops.object.select_all(action='DESELECT')
    for o in objs: o.select_set(True)
    bpy.context.view_layer.objects.active = objs[0]
    bpy.ops.object.join()
    o = bpy.context.view_layer.objects.active; o.name = name
    o.select_set(False)
    return o


def area_light(name, loc, size, rot, color, energy, coll):
    l = bpy.data.lights.new(name, 'AREA'); l.shape = 'RECTANGLE'; l.size, l.size_y = size
    l.color = color; l.energy = energy
    o = bpy.data.objects.new(name, l); o.location = loc; o.rotation_euler = tuple(math.radians(r) for r in rot)
    coll.objects.link(o)
    return o


def point_light(name, loc, color, energy, radius, coll):
    l = bpy.data.lights.new(name, 'POINT'); l.color = color; l.energy = energy; l.shadow_soft_size = radius
    o = bpy.data.objects.new(name, l); o.location = loc; coll.objects.link(o)
    return o


def facing(nrm):
    """The rotation (degrees, XYZ) that turns +y onto the horizontal direction `nrm`."""
    return (0, 0, math.degrees(math.atan2(-nrm[0], nrm[1])))


# ---------------------------------------------------------------- building parts
def window(name, p, nrm, w, h, lit, shutters=True, coll=room, light=True):
    """A window on a wall: `p` the middle of its foot on the wall's face, `nrm` the face's outward normal (horizontal).
    Dressed-stone surround and sill, a timber frame with a cross, glass (lit: warm, glowing in the house layer, with a
    little light thrown out of it), and a pair of plank shutters folded back against the wall."""
    n = Vector(nrm).normalized(); t = Vector((n.y, -n.x, 0))  # along the wall, to the right seen from outside
    rz = facing(n)[2]
    P = Vector(p)
    parts = []
    mid = P + Vector((0, 0, h / 2))
    parts.append(box(f"{name}_glass", mid + n * 0.004, (w, 0.01, h), M["glass_lit" if lit else "glass_dark"], coll, rot=(0, 0, rz)))
    fw = 0.05
    for (du, dz, sw, sh) in [(0, h / 2 - fw / 2, w, fw), (0, -h / 2 + fw / 2, w, fw), (-w / 2 + fw / 2, 0, fw, h),
                             (w / 2 - fw / 2, 0, fw, h), (0, 0.08, w, 0.035), (0, 0, 0.035, h)]:
        parts.append(box(f"{name}_frame", mid + t * du + Vector((0, 0, dz)) + n * 0.02, (sw, 0.04, sh), M["frame"], coll, rot=(0, 0, rz)))
    s = 0.12
    for (du, dz, sw, sh) in [(0, h / 2 + s / 2, w + 2 * s, s), (-w / 2 - s / 2, 0, s, h), (w / 2 + s / 2, 0, s, h)]:
        parts.append(box(f"{name}_surround", mid + t * du + Vector((0, 0, dz)) + n * 0.03, (sw, 0.06, sh), M["dressed"], coll,
                         bevel=0.01, segs=1, rot=(0, 0, rz)))
    parts.append(box(f"{name}_sill", P + Vector((0, 0, -0.04)) + n * 0.06, (w + 0.3, 0.14, 0.08), M["dressed"], coll, bevel=0.012,
                     segs=1, rot=(0, 0, rz)))
    if shutters:
        for side in (-1, 1):
            parts.append(box(f"{name}_shutter", mid + t * side * (w / 2 + s + w / 4 + 0.02) + n * 0.035, (w / 2, 0.035, h + 0.04),
                             M["shutter"], coll, bevel=0.006, segs=1, rot=(0, 0, rz)))
    if lit and light:
        area_light(f"{name}_glow", mid + n * 0.12, (w * 0.9, h * 0.9), (90, 0, rz + 180), WARM, 10.0 * w * h, house)
    return parts


def wall_lantern(name, p, nrm, coll=room, energy=55.0):
    """A wrought-iron wall lantern: a scrolled bracket out from the wall, a lantern hanging from it, glass that glows."""
    n = Vector(nrm).normalized(); rz = facing(n)[2]
    P = Vector(p)
    parts = [box(f"{name}_plate", P + n * 0.01, (0.10, 0.02, 0.16), M["iron"], coll, bevel=0.004, segs=1, rot=(0, 0, rz)),
             box(f"{name}_arm", P + n * 0.2 + Vector((0, 0, 0.06)), (0.025, 0.4, 0.025), M["iron"], coll, rot=(0, 0, rz))]
    bpy.ops.mesh.primitive_torus_add(major_radius=0.09, minor_radius=0.010, major_segments=20, minor_segments=6,
                                     location=P + n * 0.14 + Vector((0, 0, -0.03)), rotation=(math.radians(90), 0, math.radians(rz + 90)))
    scroll = bpy.context.active_object; scroll.name = f"{name}_scroll"; scroll.data.materials.append(M["iron"]); _link(scroll, coll)
    parts.append(scroll)
    L = P + n * 0.36 + Vector((0, 0, -0.16))
    body = cyl(f"{name}_body", L, 0.085, 0.26, M["lamp_glass"], coll, verts=6, r2=0.11)
    body.visible_shadow = False  # glass: its light goes out through it
    parts.append(body)
    parts.append(cyl(f"{name}_cap", L + Vector((0, 0, 0.17)), 0.13, 0.08, M["iron"], coll, verts=6, r2=0.03))
    parts.append(cyl(f"{name}_foot", L + Vector((0, 0, -0.15)), 0.06, 0.04, M["iron"], coll, verts=6, r2=0.09))
    for k in range(6):
        a = math.radians(30 + 60 * k)
        parts.append(box(f"{name}_rib", L + Vector((math.cos(a) * 0.098, math.sin(a) * 0.098, 0)), (0.012, 0.012, 0.27), M["iron"], coll))
    point_light(f"{name}_light", L, WARM, energy, 0.05, house)
    return parts


SCANS, LODS = {}, {}


def scan_pieces(name):
    """The meshes of a scanned model in ASSETS (glTF, 2k maps), upright, all of them at the middle of their common foot."""
    if name not in SCANS:
        before = set(bpy.data.objects)
        bpy.ops.import_scene.gltf(filepath=os.path.join(ASSETS, name, f"{name}_2k.gltf"))
        new = [o for o in bpy.data.objects if o not in before]
        pieces = []
        for o in new:
            if o.type != 'MESH': continue
            me = o.data.copy(); me.transform(o.matrix_world); pieces.append(me)
        for o in new: bpy.data.objects.remove(o)
        xs, ys, zs = ([v.co[i] for me in pieces for v in me.vertices] for i in range(3))
        for me in pieces: me.transform(Matrix.Translation(Vector((-(min(xs) + max(xs)) / 2, -(min(ys) + max(ys)) / 2, -min(zs)))))
        SCANS[name] = pieces
    return SCANS[name]


def scan_lod(name, piece, tris):
    key = (name, piece, tris)
    if key not in LODS:
        src = scan_pieces(name)[piece]
        o = bpy.data.objects.new("lod", src); scene.collection.objects.link(o)
        n = sum(len(p_.vertices) - 2 for p_ in src.polygons)
        if tris < n: o.modifiers.new("lod", 'DECIMATE').ratio = tris / n
        dg = bpy.context.evaluated_depsgraph_get(); dg.update()
        LODS[key] = bpy.data.meshes.new_from_object(o.evaluated_get(dg))
        bpy.data.objects.remove(o)
    return LODS[key]


def prop(name, scan, foot, yaw=0.0, scale=1.0, tris=None, coll=room):
    """A scanned prop, its pieces in their places, decimated to about `tris` triangles in all, turned `yaw` degrees and
    scaled (a number, or x, y, z), the middle of its foot at `foot`. Transmission off (a renderer's glass; the bake would
    see through it)."""
    pieces = scan_pieces(scan)
    counts = [sum(len(p_.vertices) - 2 for p_ in me.polygons) for me in pieces]
    sc = scale if isinstance(scale, tuple) else (scale, scale, scale)
    S = Matrix.Diagonal((*sc, 1.0))
    objs = []
    for k, n in enumerate(counts):
        me = scan_lod(scan, k, max(60, int((tris or sum(counts)) * n / sum(counts)))).copy()
        me.transform(Matrix.Translation(Vector(foot)) @ Matrix.Rotation(math.radians(yaw), 4, 'Z') @ S)
        o = bpy.data.objects.new(f"{name}_{k}", me); coll.objects.link(o); objs.append(o)
    o = join(name, objs) if len(objs) > 1 else objs[0]
    o.name = name
    for m in o.data.materials:
        b = m.node_tree.nodes.get("Principled BSDF") if m and m.use_nodes else None
        if b is not None and "Transmission Weight" in b.inputs: b.inputs["Transmission Weight"].default_value = 0.0
    return o


EYE_P = Vector((SEAT[0], SEAT[1], SEAT[2] + EYE))


def near_face(c, n, reach=18.0):
    """Whether a face at c looking along n is seen from the seat, near it: then it goes on the near facades' atlas."""
    return n.dot(EYE_P - c) > 0 and (EYE_P - c).length < reach


def wall_tiles(name, x0, x1, y0, y1, zb, zt, mat, coll=room, size=2.0):
    """A house's four walls as tiles about `size` m square, each its own object, so bake_layers gives each its share of
    the atlas by its nearness: those the seat sees ("hnear_") on the near facades' atlas, the backs on the room's."""
    parts = []
    for (ax, ay), (bx, by), (nx, ny) in (((x0, y0), (x1, y0), (0, -1)), ((x1, y0), (x1, y1), (1, 0)), ((x1, y1), (x0, y1), (0, 1)),
                                         ((x0, y1), (x0, y0), (-1, 0))):
        nu = max(1, round(math.hypot(bx - ax, by - ay) / size)); nv = max(1, round((zt - zb) / size))
        n = Vector((nx, ny, 0))
        for i in range(nu):
            for j in range(nv):
                u0, u1 = i / nu, (i + 1) / nu
                za, zc = zb + (zt - zb) * j / nv, zb + (zt - zb) * (j + 1) / nv
                pa = (ax + (bx - ax) * u0, ay + (by - ay) * u0); pb = (ax + (bx - ax) * u1, ay + (by - ay) * u1)
                c = Vector(((pa[0] + pb[0]) / 2, (pa[1] + pb[1]) / 2, (za + zc) / 2))
                pre = "hnear_" if near_face(c, n) else ""
                parts.append(mesh_from(f"{pre}{name}_wall_{nx}_{ny}_{i}_{j}", [(pa[0], pa[1], za), (pb[0], pb[1], za), (pb[0], pb[1], zc),
                                                                              (pa[0], pa[1], zc)], [(0, 1, 2, 3)], mat, coll))
    return parts


def gable_house(name, x0, x1, y0, y1, z0, h, ridge="x", pitch=0.42, over=0.32, mat=None, chimney=None, coll=room,
                rafters=False, foot=None, tiles=True):
    """A stone block with a terracotta gable roof (ridge along x or y), its eaves overhanging, a ridge of round tiles, a
    gutter along each eave. Returns the parts."""
    mat = mat or M["wall"]
    W, D = x1 - x0, y1 - y0
    cx, cy = (x0 + x1) / 2, (y0 + y1) / 2
    zb = z0 if foot is None else min(foot, z0)  # the walls' foot: lower than the floor where the ground falls away
    parts = wall_tiles(name, x0, x1, y0, y1, zb, z0 + h, mat, coll) if tiles else \
        [box(f"{name}_walls", (cx, cy, (zb + z0 + h) / 2), (W, D, z0 + h - zb), mat, coll)]
    span = (D if ridge == "x" else W) / 2 + over
    rise = span * pitch
    zt = z0 + h
    # gable ends: a triangle of wall under the roof on the two ends
    if ridge == "x":
        for xe in (x0, x1):
            pre = "hnear_" if near_face(Vector((xe, cy, zt + D * pitch / 6)), Vector((1 if xe == x1 else -1, 0, 0))) else ""
            parts.append(mesh_from(f"{pre}{name}_gable", [(xe, y0, zt), (xe, y1, zt), (xe, cy, zt + (D / 2) * pitch)],
                                   [(0, 1, 2) if xe == x1 else (0, 2, 1)], mat, coll))
    else:
        for ye in (y0, y1):
            pre = "hnear_" if near_face(Vector((cx, ye, zt + W * pitch / 6)), Vector((0, 1 if ye == y1 else -1, 0))) else ""
            parts.append(mesh_from(f"{pre}{name}_gable", [(x0, ye, zt), (x1, ye, zt), (cx, ye, zt + (W / 2) * pitch)],
                                   [(0, 2, 1) if ye == y1 else (0, 1, 2)], mat, coll))
    zr = zt + (D / 2 if ridge == "x" else W / 2) * pitch      # the ridge
    L = math.hypot(span, rise)
    ang = math.degrees(math.atan(pitch))
    length = (W if ridge == "x" else D) + 2 * over * 0.6
    zc = zr - rise / 2 + 0.05                                # a slab's middle, resting on the walls
    for side in (-1, 1):
        if ridge == "x":
            parts.append(box(f"{name}_roof", (cx, cy + side * span / 2, zc), (length, L, 0.09), M["roof"], coll, rot=(side * -ang, 0, 0)))
            parts.append(cyl(f"{name}_gutter", (cx, cy + side * (span - 0.02), zr - rise - 0.05), 0.05, length, M["gutter"], coll,
                             verts=10, rot=(0, 90, 0)))
        else:
            parts.append(box(f"{name}_roof", (cx + side * span / 2, cy, zc), (L, length, 0.09), M["roof_y"], coll, rot=(0, side * ang, 0)))
            parts.append(cyl(f"{name}_gutter", (cx + side * (span - 0.02), cy, zr - rise - 0.05), 0.05, length, M["gutter"], coll,
                             verts=10, rot=(90, 0, 0)))
    parts.append(cyl(f"{name}_ridge", (cx, cy, zr + 0.1), 0.09, length, M["roof"], coll, verts=12,
                     rot=(0, 90, 0) if ridge == "x" else (90, 0, 0)))
    if chimney:
        chx, chy = chimney
        parts.append(box(f"{name}_chimney", (chx, chy, zr + 0.3), (0.55, 0.55, 1.6), mat, coll))
        parts.append(box(f"{name}_chimcap", (chx, chy, zr + 1.15), (0.7, 0.7, 0.1), M["dressed"], coll))
        parts.append(box(f"{name}_chimtop", (chx, chy, zr + 1.36), (0.5, 0.5, 0.3), M["roof"], coll))
    if rafters:  # the rafters' tails under the eaves, as the near houses show them
        n = int((W if ridge == "x" else D) / 0.45)
        for k in range(n + 1):
            u = (x0 if ridge == "x" else y0) + k * 0.45
            for side in (-1, 1):
                z = zt - over / 2 * pitch - 0.08
                if ridge == "x":
                    parts.append(box(f"{name}_rafter", (u, cy + side * (D / 2 + over / 2), z), (0.07, over, 0.1), M["timber"], coll,
                                     rot=(side * -ang, 0, 0)))
                else:
                    parts.append(box(f"{name}_rafter", (cx + side * (W / 2 + over / 2), u, z), (over, 0.07, 0.1), M["timber"], coll,
                                     rot=(0, side * ang, 0)))
    return parts


def arched_door(name, p, nrm, w, spring, coll=room, mat=None, ring=True):
    """A plank door with a round head on a wall: `p` the middle of its foot on the wall's face, `nrm` the face's outward
    normal, `w` its width, `spring` the height where the arch begins; the head a half-disc, the arch's voussoirs round it."""
    n = Vector(nrm).normalized(); t = Vector((n.y, -n.x, 0)); rz = facing(n)[2]
    P = Vector(p) + n * 0.03
    mat = mat or M["door"]; r = w / 2
    parts = [box(f"{name}_leaf", P + Vector((0, 0, spring / 2)), (w, 0.05, spring), mat, coll, bevel=0.006, segs=1, rot=(0, 0, rz))]
    k_ = 16
    head = [P + Vector((0, 0, spring))] + [P + t * (r * math.cos(math.pi * k / k_)) + Vector((0, 0, spring + r * math.sin(math.pi * k / k_)))
                                          for k in range(k_ + 1)]
    o = mesh_from(f"{name}_head", [tuple(v + n * 0.025) for v in head], [tuple(range(k_ + 2))], mat, coll)
    if o.data.polygons[0].normal.dot(n) < 0: o.data.flip_normals()
    parts.append(o)
    if ring:
        for k in range(9):
            a = math.pi * (k + 0.5) / 9
            parts.append(box(f"{name}_voussoir", P + t * ((r + 0.11) * math.cos(a)) + Vector((0, 0, spring + (r + 0.11) * math.sin(a))),
                             (0.2, 0.12, 0.17), M["dressed"], coll, bevel=0.012, segs=1, rot=(0, math.degrees(a) - 90, rz)))
        for side in (-1, 1):
            parts.append(box(f"{name}_jamb", P + t * (side * (r + 0.1)) + Vector((0, 0, spring / 2)), (0.2, 0.12, spring), M["dressed"],
                             coll, bevel=0.01, segs=1, rot=(0, 0, rz)))
    return parts


def downpipe(name, x, y, z0, z1, coll=room):
    return cyl(name, (x, y, (z0 + z1) / 2), 0.045, z1 - z0, M["gutter"], coll, verts=10)


def plant_pot(name, c, r, h, coll=room, leaf_size=0.06, n_leaves=60, height=0.35, mat="leaf", tall=False):
    """A terracotta pot (a rolled rim), earth, and a leafy plant: leaves as small soft blades fanning up and out."""
    C = Vector(c)
    # Poly Haven's planter_pot_clay (26.6 cm wide, 22.2 high), scaled to the pot wanted
    parts = [prop(f"{name}_pot", "planter_pot_clay", tuple(C), yaw=sum(map(ord, name)) % 360, scale=(2 * r / 0.266, 2 * r / 0.266, h / 0.222),
                  tris=1500)]
    parts.append(cyl(f"{name}_earth", C + Vector((0, 0, h - 0.04)), r * 0.88, 0.02, M["earth"], coll, verts=20))
    leaves_ = []
    for k in range(n_leaves):
        a = random.uniform(0, 2 * math.pi); up = random.uniform(0.15, 1.0)
        rr = random.uniform(0.0, r * 0.9) * (1 - up * 0.5)
        hh = h + 0.03 + up * height
        tilt = (1 - up) * 70 + random.uniform(-10, 10)
        o = sphere(f"{name}_leaf", C + Vector((math.cos(a) * rr, math.sin(a) * rr, hh)),
                   (leaf_size * 0.38, leaf_size * 0.12, leaf_size), M[mat], coll, subdiv=1,
                   rot=(tilt * math.cos(a + 1.57), tilt * math.sin(a + 1.57), math.degrees(a)))
        leaves_.append(o)
    if leaves_: parts.append(join(f"leaf_{name}_foliage", leaves_))   # the leaves' own atlas: little islands
    return parts


def olive_tree(name, c, height, coll=room, pot=True):
    C = Vector(c)
    parts = []
    if pot:
        parts += plant_pot(f"{name}_tub", c, 0.26, 0.5, coll, n_leaves=0)
        C = C + Vector((0, 0, 0.48))
    parts.append(cyl(f"{name}_trunk", C + Vector((0, 0, height * 0.3)), 0.035, height * 0.6, M["bark"], coll, verts=8, r2=0.025))
    blobs = []
    for k in range(16):
        a = random.uniform(0, 2 * math.pi); rr = random.uniform(0, 0.32)
        blobs.append(sphere(f"{name}_crown", C + Vector((math.cos(a) * rr, math.sin(a) * rr, height * random.uniform(0.6, 1.0))),
                            [random.uniform(0.12, 0.2)] * 3, M["olive"], coll, subdiv=2, wobble=0.35, seed=k + hash(name) % 97))
    parts.append(join(f"leaf_{name}_canopy", blobs))
    return parts


def cypress(name, c, height, coll=room, subdiv=3, tiers=1):
    """A cypress: a dark flame — wider low, drawn to a point — its outline broken by tufts (`tiers` stacked bodies)."""
    C = Vector(c)
    if tiers <= 1:
        return [sphere(name, C + Vector((0, 0, height * 0.5)), (height * 0.11, height * 0.11, height * 0.5), M["cypress"], coll,
                       subdiv=subdiv, wobble=0.22, seed=hash(name) % 101)]
    blobs = []
    for k in range(tiers):
        t = (k + 0.5) / tiers
        r = height * 0.13 * (1.0 - 0.75 * t) + 0.05
        blobs.append(sphere(f"{name}_tuft", C + Vector((random.uniform(-0.1, 0.1), random.uniform(-0.1, 0.1), height * t)),
                            (r, r, height / tiers * 0.85), M["cypress"], coll, subdiv=subdiv, wobble=0.3, seed=k * 7 + hash(name) % 31))
    return [join(name, blobs)]


# ---------------------------------------------------------------- the square
PX0, PX1, PY0, PY1 = -5.2, 5.5, -4.6, 9.6
# in 2 m tiles, each its own object: bake_layers gives each its share of the floor's atlas by its nearness, so the setts at
# the viewer's feet are sharp (one sheet was 6 mm a texel everywhere); each subdivided, for the bake's light to have
# vertices to work with
nx_, ny_ = math.ceil((PX1 - PX0) / 2.0), math.ceil((PY1 - PY0) / 2.0)
for i in range(nx_):
    for j in range(ny_):
        x0_, x1_ = PX0 + (PX1 - PX0) * i / nx_, PX0 + (PX1 - PX0) * (i + 1) / nx_
        y0_, y1_ = PY0 + (PY1 - PY0) * j / ny_, PY0 + (PY1 - PY0) * (j + 1) / ny_
        t_ = mesh_from(f"gnear_{i}_{j}", [(x0_, y0_, 0), (x1_, y0_, 0), (x1_, y1_, 0), (x0_, y1_, 0)], [(0, 1, 2, 3)], M["cobbles"])
        bpy.context.view_layer.objects.active = t_; t_.select_set(True)
        bpy.ops.object.mode_set(mode='EDIT'); bpy.ops.mesh.subdivide(number_cuts=4); bpy.ops.object.mode_set(mode='OBJECT')
        t_.select_set(False)

# the screen: a stretched canvas in a timber frame — two posts with braces, a top beam running past them, a bottom rail
sx = SCREEN_W / 2 + 0.16
box("plaza_screen_face", (0, SCREEN_Y + 0.01, SCREEN_FOOT + SCREEN_H / 2), (SCREEN_W, 0.02, SCREEN_H), M["screen"])
box("plaza_screen_back", (0, SCREEN_Y + 0.04, SCREEN_FOOT + SCREEN_H / 2), (SCREEN_W + 0.1, 0.03, SCREEN_H + 0.1), M["timber"])
# its back as the stairs' landing sees it: a stretcher frame of battens, a rail across, two braces
for bx in (-2.0, -0.7, 0.7, 2.0):
    box("plaza_screen_batten", (bx, SCREEN_Y + 0.09, SCREEN_FOOT + SCREEN_H / 2), (0.08, 0.06, SCREEN_H), M["timber"], bevel=0.006, segs=1)
box("plaza_screen_rail", (0, SCREEN_Y + 0.13, SCREEN_FOOT + SCREEN_H / 2), (SCREEN_W - 0.1, 0.06, 0.09), M["timber"], bevel=0.006, segs=1)
brace = math.degrees(math.atan2(SCREEN_H, SCREEN_W / 2))
for sxn in (-1, 1):
    box("plaza_screen_xbrace", (sxn * SCREEN_W / 4, SCREEN_Y + 0.17, SCREEN_FOOT + SCREEN_H / 2),
        (math.hypot(SCREEN_W / 2, SCREEN_H) - 0.2, 0.05, 0.07), M["timber"], rot=(0, sxn * brace, 0))
for side in (-1, 1):
    box(f"plaza_post_{side}", (side * sx, SCREEN_Y + 0.05, 1.85), (0.16, 0.16, 3.7), M["timber"], bevel=0.012, segs=2)
    box(f"plaza_post_foot_{side}", (side * sx, SCREEN_Y + 0.05, 0.08), (0.34, 0.34, 0.16), M["timber"], bevel=0.01, segs=1)
    box(f"plaza_brace_{side}", (side * sx, SCREEN_Y + 0.42, 0.62), (0.09, 0.09, 1.2), M["timber"], bevel=0.006, segs=1, rot=(-38, 0, 0))
    for zb in (0.9, 1.9, 2.9):
        box(f"plaza_strap_{side}", (side * sx, SCREEN_Y - 0.035, zb), (0.18, 0.015, 0.06), M["iron"])
box("plaza_beam_top", (0, SCREEN_Y + 0.05, SCREEN_FOOT + SCREEN_H + 0.1), (SCREEN_W + 0.9, 0.17, 0.17), M["timber"], bevel=0.012, segs=2)
box("plaza_beam_low", (0, SCREEN_Y + 0.05, SCREEN_FOOT - 0.09), (SCREEN_W + 0.32, 0.14, 0.14), M["timber"], bevel=0.01, segs=2)
for side in (-1, 1):
    box(f"plaza_rail_{side}", (side * (SCREEN_W / 2 + 0.03), SCREEN_Y + 0.0, SCREEN_FOOT + SCREEN_H / 2), (0.07, 0.07, SCREEN_H),
        M["timber"])
box("plaza_stand", (0, SCREEN_Y + 0.05, 0.38), (0.12, 0.12, 0.76), M["timber"], bevel=0.008, segs=1)
# the low stone wall behind it, and the terrace it holds up
box("plaza_wall_low", (0.45, 9.85, 0.75), (7.7, 0.5, 1.5), M["wall_pale"], bevel=0.02, segs=1)
box("plaza_wall_cope", (0.45, 9.85, 1.54), (7.8, 0.56, 0.1), M["dressed"], bevel=0.015, segs=1)
mesh_from("terrace", [(-6, 10.1, TERRACE_Z), (6, 10.1, TERRACE_Z), (6, 26, TERRACE_Z), (-6, 26, TERRACE_Z)], [(0, 1, 2, 3)], M["cobbles"])
for side, (x, y) in ((-1, (-2.5, 8.05)), (1, (3.3, 8.45))):
    pot = plant_pot(f"plaza_pot_{side}", (x, y, 0), 0.24, 0.45, n_leaves=70, leaf_size=0.09, height=0.35, mat="olive")
    if side == -1:
        # Keep the crown below the canvas for viewers seated low in the centre and left chairs.
        foliage = next(part for part in pot if part.name.startswith("leaf_"))
        foliage.location.z = 0.45 + 0.70 * (foliage.location.z - 0.45)
        foliage.scale.z *= 0.70
        bpy.context.view_layer.update()
        crown = max((foliage.matrix_world @ vertex.co).z for vertex in foliage.data.vertices)
        assert crown < SCREEN_FOOT - 0.05, f"Left olive reaches the screen: {crown:.3f} m"

# ---------------------------------------------------------------- the left side: a tall house, the stairs going up
gable_house("left_house", -9.0, -5.2, -5.0, 6.0, 0.0, 8.2, ridge="y", pitch=0.45, over=0.55, rafters=True)
box("left_house_plinth", (-5.15, 0.5, 0.25), (0.1, 11.0, 0.5), M["dressed"], bevel=0.01, segs=1)
# Fewer lit windows round the screen (the user, 2026-10-23): a lit pane by the picture pulls the eye from it — those over
# and beside the screen are dark; the lit ones are low, at the sides, behind and far off.
for y, lit in ((-1.6, True), (3.8, False)):
    window(f"left_house_win_up_{y}", (-5.2, y, 4.9), (1, 0, 0), 0.9, 1.35, lit=lit)
window("left_house_win_low", (-5.2, 4.8, 1.2), (1, 0, 0), 0.8, 1.2, lit=False)
window("left_house_win_low2", (-5.2, -2.6, 1.2), (1, 0, 0), 0.8, 1.2, lit=True)
prop("left_house_bench", "painted_wooden_bench", (-4.92, 0.6, 0.0), yaw=-90.0, tris=630)
# a green plank door in a dressed surround
box("left_house_door", (-5.17, 2.3, 1.2), (0.06, 1.1, 2.4), M["door_green"], bevel=0.008, segs=1)
for dz, sy, sz in ((2.5, 1.5, 0.22), ):
    box("left_house_lintel", (-5.15, 2.3, dz), (0.1, sy, sz), M["dressed"], bevel=0.01, segs=1)
for dy in (-0.65, 0.65):
    box("left_house_jamb", (-5.15, 2.3 + dy, 1.2), (0.1, 0.18, 2.4), M["dressed"], bevel=0.01, segs=1)
wall_lantern("left_lantern_a", (-5.2, 0.6, 2.9), (1, 0, 0))
wall_lantern("left_lantern_b", (-5.2, 5.6, 3.0), (1, 0, 0))
downpipe("left_house_pipe", -5.12, -4.6, 0.0, 8.2)
downpipe("left_house_pipe2", -5.12, 5.85, 0.0, 8.2)

gable_house("left_house2", -9.5, -5.2, 6.0, 10.4, 0.0, 9.0, ridge="y", pitch=0.45, over=0.45)
for y, z, lit in ((7.2, 5.6, False), (9.3, 5.6, False), (8.4, 2.6, True)):
    window(f"left_house2_win_{y}_{z}", (-5.2, y, z), (1, 0, 0), 0.8, 1.2, lit=lit)
wall_lantern("left_lantern_c", (-5.2, 8.0, 4.0), (1, 0, 0), energy=40.0)
olive_tree("left_house2_olive", (-4.7, 4.9, 0.0), 1.7)

# the stairs: from the square's floor up the left of the screen to the terrace and beyond, a stone parapet on their right.
# They keep clear of the screen: the parapet stands outside its left post and under its beam, and from the seat it is seen
# beside the frame, not across the picture (2026-10-23: 2.4 m wide, it ran into the screen's corner).
STAIR_X0, STAIR_X1 = -5.2, -3.4
STAIR_W, STAIR_C = STAIR_X1 - STAIR_X0, (STAIR_X0 + STAIR_X1) / 2
STEP_R, STEP_D, N_STEPS = 0.17, 0.42, 9
for k in range(N_STEPS):
    y = 5.4 + k * STEP_D
    wide = (3 - k) * 0.45 if k < 3 else 0.0  # the bottom steps spread out onto the square, to the right
    box(f"stair_{k}", (STAIR_C + wide / 2, y + STEP_D / 2, STEP_R * (k + 0.5)), (STAIR_W + wide, STEP_D + 0.02, STEP_R),
        M["dressed"], bevel=0.02, segs=2)
# what the steps stand on, and the parapet
mesh_from("stair_side", [(STAIR_X1 + 0.02, 5.4, 0), (STAIR_X1 + 0.02, 5.4 + N_STEPS * STEP_D, 0),
                         (STAIR_X1 + 0.02, 5.4 + N_STEPS * STEP_D, STEP_R * N_STEPS)],
          [(0, 1, 2)], M["wall"])
for k in range(3, N_STEPS):
    y = 5.4 + (k + 0.5) * STEP_D
    box(f"stair_parapet_{k}", (STAIR_X1 + 0.15, y, STEP_R * k + 0.36), (0.34, STEP_D + 0.01, 0.72), M["wall_pale"], bevel=0.015,
        segs=1)
PAR0, PAR1 = 5.4 + 3 * STEP_D, 5.4 + N_STEPS * STEP_D
box("stair_parapet_top", (STAIR_X1 + 0.15, (PAR0 + PAR1) / 2, STEP_R * (3 + N_STEPS) / 2 + 0.74), (0.4, PAR1 - PAR0 + 0.2, 0.08),
    M["dressed"], rot=(math.degrees(math.atan2(STEP_R, STEP_D)), 0, 0))
LAND_Z = STEP_R * N_STEPS
box("stair_landing", (STAIR_C, (PAR1 + 12.5) / 2, LAND_Z / 2), (STAIR_W, 12.5 - PAR1, LAND_Z), M["wall"])
for k in range(7):  # the upper flight, turning left between the houses
    box(f"stair_up_{k}", (-5.4 - k * STEP_D, 11.5, LAND_Z + STEP_R * (k + 0.5)), (STEP_D + 0.02, 1.8, STEP_R), M["dressed"],
        bevel=0.02, segs=1)
olive_tree("stair_olive", (-4.6, 11.6, LAND_Z), 1.5)
plant_pot("stair_pot", (STAIR_X1 + 0.5, 4.9, 0.0), 0.16, 0.32, n_leaves=40, leaf_size=0.07, height=0.25)
# houses up the slope behind the stairs
gable_house("left_upper_a", -11.0, -6.2, 8.5, 13.5, LAND_Z, 6.0, ridge="x", pitch=0.45, over=0.4)
window("left_upper_a_win", (-7.2, 8.5, LAND_Z + 3.4), (0, -1, 0), 0.8, 1.1, lit=True)
window("left_upper_a_win2", (-9.4, 8.5, LAND_Z + 3.4), (0, -1, 0), 0.8, 1.1, lit=False)
gable_house("left_upper_b", -12.5, -7.0, 15.0, 20.0, LAND_Z + 1.4, 6.0, ridge="y", pitch=0.45, over=0.4)
window("left_upper_b_win", (-9.6, 15.0, LAND_Z + 4.6), (0, -1, 0), 0.7, 1.0, lit=True)
olive_tree("left_upper_bush", (-6.6, 14.2, LAND_Z), 2.4, pot=False)

# ---------------------------------------------------------------- the house behind the screen
gable_house("back_house", -4.4, 4.1, 11.6, 18.5, TERRACE_Z, 7.2, ridge="x", pitch=0.46, over=0.5, chimney=(-0.6, 15.2))
for x in (-2.3, 1.9):  # over the screen: dark
    window(f"back_house_win_{x}", (x, 11.6, TERRACE_Z + 4.1), (0, -1, 0), 1.0, 1.5, lit=False)
window("back_house_attic", (-0.2, 11.6, TERRACE_Z + 6.2), (0, -1, 0), 0.45, 0.55, lit=False, shutters=False)
window("back_house_low", (2.6, 11.6, TERRACE_Z + 0.9), (0, -1, 0), 0.8, 1.1, lit=False)
window("back_house_low2", (-2.8, 11.6, TERRACE_Z + 0.9), (0, -1, 0), 0.8, 1.1, lit=False)
downpipe("back_house_pipe", 4.0, 11.5, TERRACE_Z, TERRACE_Z + 7.2)
cypress("back_cypress", (4.3, 14.6, TERRACE_Z), 10.5, subdiv=2, tiers=7)

# ---------------------------------------------------------------- the right side: houses, an arched door, an alley going down
gable_house("right_house", 5.5, 9.5, -5.0, 5.2, 0.0, 8.6, ridge="y", pitch=0.45, over=0.5, rafters=True)
box("right_house_plinth", (5.45, 0.1, 0.3), (0.1, 10.2, 0.6), M["dressed"], bevel=0.01, segs=1)
for y, lit in ((-2.8, True), (3.6, False)):
    window(f"right_house_win_{y}", (5.5, y, 4.8), (-1, 0, 0), 0.9, 1.35, lit=lit)
window("right_house_win_low", (5.5, -1.0, 1.4), (-1, 0, 0), 0.9, 1.3, lit=True)
# the arched door: planks under a round head, in a dressed arch
box("right_door", (5.47, 1.6, 1.15), (0.06, 1.15, 2.3), M["door"], bevel=0.008, segs=1)
n_ = 16
head = [(5.44, 1.6, 2.3)] + [(5.44, 1.6 + 0.575 * math.cos(math.pi * k / n_), 2.3 + 0.575 * math.sin(math.pi * k / n_))
                            for k in range(n_ + 1)]
mesh_from("right_door_head", head, [tuple(range(n_ + 2))[::-1]], M["door"])
for k in range(9):  # the voussoirs
    a = math.pi * (k + 0.5) / 9
    box("right_door_voussoir", (5.45, 1.6 + 0.69 * math.cos(a), 2.3 + 0.69 * math.sin(a)), (0.12, 0.2, 0.17), M["dressed"],
        bevel=0.012, segs=1, rot=(math.degrees(a) - 90, 0, 0))
for dy in (-0.68, 0.68):
    box("right_door_jamb", (5.45, 1.6 + dy, 1.15), (0.12, 0.2, 2.3), M["dressed"], bevel=0.01, segs=1)
for k in range(2):
    box(f"right_door_step_{k}", (5.25 - k * 0.2, 1.6, 0.08 + k * 0.0), (0.4 - k * 0.0, 1.5 - k * 0.2, 0.16 - k * 0.0), M["dressed"],
        bevel=0.015, segs=1)
wall_lantern("right_lantern", (5.5, 0.55, 2.6), (-1, 0, 0))
olive_tree("right_olive", (5.0, 3.2, 0.0), 1.5)
prop("right_barrel", "wine_barrel_01", (5.0, 0.45, 0.0), yaw=20.0, tris=3000)
box("right_planter", (4.95, -3.0, 0.4), (0.9, 1.8, 0.8), M["wall_pale"], bevel=0.02, segs=1)
plant_pot("right_planter_bush", (4.95, -3.0, 0.6), 0.5, 0.2, n_leaves=110, leaf_size=0.1, height=0.45, mat="olive")
downpipe("right_house_pipe", 5.42, 5.0, 0.0, 8.6)
# a vine climbing beside the door
vine = []
for k in range(120):
    t = k / 120
    vine.append(sphere("right_vine_leaf", (5.43, 2.6 + 0.3 * math.sin(t * 9) + random.uniform(-0.15, 0.15), 0.6 + t * 4.2),
                       (0.03, 0.07, 0.06), M["leaf"], subdiv=1, rot=(random.uniform(-30, 30), 0, random.uniform(-40, 40))))
join("leaf_right_vine", vine)
# the alley going down to the right, behind the screen's right post
gable_house("alley_house_a", 3.7, 5.5, 9.0, 12.0, 0.0, 3.4, ridge="y", pitch=0.5, over=0.25, foot=-3.0)
window("alley_house_a_win", (3.7, 10.4, 1.4), (-1, 0, 0), 0.6, 0.9, lit=False)  # beside the screen's right edge: dark
gable_house("alley_house_b", 6.6, 10.5, 5.6, 14.0, -0.6, 7.0, ridge="y", pitch=0.45, over=0.4, foot=-3.0)
for y, lit in ((7.5, True), (11.0, False)):
    window(f"alley_house_b_win_{y}", (6.6, y, 3.6), (-1, 0, 0), 0.7, 1.1, lit=lit)
arched_door("alley_door", (6.6, 9.4, -0.75), (-1, 0, 0), 0.9, 1.75)
gable_house("alley_house_c", 3.2, 5.5, 13.0, 22.0, -1.2, 6.5, ridge="y", pitch=0.45, over=0.35, foot=-3.5)
window("alley_house_c_win", (5.5, 16.0, 2.6), (1, 0, 0), 0.6, 1.0, lit=True)
mesh_from("alley_floor", [(5.5, 5.2, 0), (6.6, 5.2, 0), (6.6, 24, -2.4), (5.5, 24, -2.4)], [(0, 1, 2, 3)], M["cobbles"])
wall_lantern("alley_lantern", (6.6, 12.5, 2.4), (-1, 0, 0), energy=18.0)
olive_tree("alley_olive", (6.1, 13.2, -1.2), 2.2, pot=False)

# ---------------------------------------------------------------- behind the viewer: the square's fourth side
# Three houses close it — the references look one way, the headset all ways round. The middle one has an arched way through
# to a lane beyond (dark), the others doors and windows, a lantern each side of the arch.
gable_house("rear_house_a", -5.4, -1.3, -11.0, -4.6, 0.0, 8.0, ridge="x", pitch=0.45, over=0.45)
window("rear_house_a_win_1", (-4.2, -4.6, 4.6), (0, 1, 0), 0.85, 1.3, lit=False)
window("rear_house_a_win_2", (-2.4, -4.6, 4.6), (0, 1, 0), 0.85, 1.3, lit=False)
window("rear_house_a_win_3", (-2.4, -4.6, 1.2), (0, 1, 0), 0.8, 1.15, lit=True)
box("rear_house_a_door", (-4.0, -4.57, 1.15), (1.05, 0.06, 2.3), M["door_green"], bevel=0.008, segs=1)
box("rear_house_a_lintel", (-4.0, -4.55, 2.4), (1.4, 0.1, 0.2), M["dressed"], bevel=0.01, segs=1)
gable_house("rear_house_b", -1.3, 2.4, -9.5, -4.6, 0.0, 6.6, ridge="x", pitch=0.48, over=0.4, chimney=(1.4, -7.0), tiles=False)
# the arched way through: cut into the house (a boolean), a vaulted passage 3 m deep, cobbled, a lantern on its far wall
# over a door — a way out of the square, lit, not a black board (2026-10-23)
WAY_X, WAY_R, WAY_SPRING, WAY_END = 0.55, 1.0, 2.2, -7.7
bpy.ops.mesh.primitive_cube_add(size=1, location=(WAY_X, (WAY_END - 4.0) / 2, (0.03 + WAY_SPRING) / 2))
cut = bpy.context.active_object; cut.scale = (2 * WAY_R, -4.0 - WAY_END, WAY_SPRING - 0.03)
bpy.ops.object.transform_apply(scale=True)
bpy.ops.mesh.primitive_cylinder_add(vertices=32, radius=WAY_R, depth=-4.0 - WAY_END, location=(WAY_X, (WAY_END - 4.0) / 2, WAY_SPRING),
                                    rotation=(math.radians(90), 0, 0))
vault = bpy.context.active_object
walls = bpy.data.objects["rear_house_b_walls"]
for c_ in (cut, vault):  # one after the other: joined, the two overlapping solids were no solid to cut with
    c_.select_set(False)
    md = walls.modifiers.new("way", 'BOOLEAN'); md.operation = 'DIFFERENCE'; md.object = c_; md.solver = 'EXACT'
    _apply(walls, [md])
    bpy.data.objects.remove(c_)
for p_ in walls.data.polygons: p_.use_smooth = False
walls.name = "hnear_rear_house_b_walls"
mesh_from("rear_way_floor", [(WAY_X - WAY_R, WAY_END, 0.031), (WAY_X + WAY_R, WAY_END, 0.031), (WAY_X + WAY_R, -4.6, 0.031),
                             (WAY_X - WAY_R, -4.6, 0.031)], [(0, 1, 2, 3)], M["cobbles"])
arched_door("rear_way_door", (WAY_X, WAY_END, 0.03), (0, 1, 0), 0.95, 1.6, mat=M["door_green"])
wall_lantern("rear_way_lantern", (WAY_X - 0.75, WAY_END, 2.3), (0, 1, 0), energy=30.0)
for k in range(11):
    a = math.pi * (k + 0.5) / 11
    box("rear_way_voussoir", (0.55 + 1.12 * math.cos(a), -4.55, 2.2 + 1.12 * math.sin(a)), (0.24, 0.12, 0.2), M["dressed"],
        bevel=0.012, segs=1, rot=(0, 90 - math.degrees(a), 0))
for dx in (-1.12, 1.12):
    box("rear_way_jamb", (0.55 + dx, -4.55, 1.1), (0.24, 0.12, 2.2), M["dressed"], bevel=0.01, segs=1)
window("rear_house_b_win", (0.55, -4.6, 4.2), (0, 1, 0), 0.9, 1.2, lit=True)
wall_lantern("rear_lantern_l", (-1.0, -4.6, 2.7), (0, 1, 0), energy=40.0)
wall_lantern("rear_lantern_r", (2.1, -4.6, 2.7), (0, 1, 0), energy=40.0)
gable_house("rear_house_c", 2.4, 5.6, -10.5, -4.6, 0.0, 7.6, ridge="y", pitch=0.45, over=0.45)
window("rear_house_c_win_1", (4.0, -4.6, 4.4), (0, 1, 0), 0.85, 1.3, lit=False)
window("rear_house_c_win_2", (4.0, -4.6, 1.3), (0, 1, 0), 0.8, 1.15, lit=True)
downpipe("rear_house_c_pipe", 2.5, -4.52, 0.0, 7.6)
plant_pot("rear_pot_a", (-1.6, -4.25, 0.0), 0.22, 0.42, n_leaves=60, leaf_size=0.09, height=0.32, mat="olive")
plant_pot("rear_pot_b", (2.7, -4.25, 0.0), 0.22, 0.42, n_leaves=60, leaf_size=0.09, height=0.32, mat="olive")
prop("rear_wateringcan", "watering_can_metal_01", (3.25, -4.25, 0.0), yaw=-30.0, tris=2500)
olive_tree("rear_olive", (-4.9, -4.0, 0.0), 1.6)

# ---------------------------------------------------------------- near: the viewer's chair, the rug, the table, the other chairs
def armchair(name, c, yaw, coll=room, fine=True, pillows=True, seat_mat="cushion"):
    """A timber lounge chair: thick flat arms on square legs, slatted sides, a low back, a deep cushion, a back cushion,
    throw pillows against the arms. `c` is the middle of the seat on the ground; it faces +y turned by `yaw` degrees."""
    parts = []
    lv = 3 if fine else 1
    W, D = 0.84, 0.86
    def P(x, y, z): return Vector((x, y, z))
    T = Matrix.Translation(Vector(c)) @ Matrix.Rotation(math.radians(yaw), 4, 'Z')
    local = []
    for sx in (-1, 1):
        x = sx * (W / 2 - 0.05)
        local.append(("arm", (x, -0.02, 0.62), (0.11, D + 0.08, 0.045), "teak", 0.012))
        local.append(("leg_f", (x, D / 2 - 0.06, 0.30), (0.07, 0.07, 0.62), "teak", 0.008))
        local.append(("leg_b", (x, -D / 2 + 0.06, 0.30), (0.07, 0.07, 0.62), "teak", 0.008))
        for k in range(4):
            local.append(("slat", (x, -D / 2 + 0.16 + k * 0.18, 0.42), (0.03, 0.08, 0.30), "teak", 0.006))
        local.append(("rail", (x, 0.0, 0.25), (0.05, D - 0.1, 0.06), "teak", 0.006))
    local.append(("rail_f", (0, D / 2 - 0.06, 0.20), (W - 0.12, 0.05, 0.07), "teak", 0.006))
    local.append(("rail_b", (0, -D / 2 + 0.06, 0.20), (W - 0.12, 0.05, 0.07), "teak", 0.006))
    local.append(("back_post", (0, -D / 2 + 0.04, 0.5), (W - 0.12, 0.05, 0.62), "teak", 0.006))
    for (nm, p, s, mt, bv) in local:
        q = T @ P(*p)
        parts.append(box(f"{name}_{nm}", tuple(q), s, M[mt], coll, bevel=bv, segs=4 if fine else 2, rot=(0, 0, yaw)))
    # cushions: soft boxes, subdivided
    q = T @ P(0, 0.02, 0.33)
    parts.append(box(f"{name}_seat", tuple(q), (W - 0.2, D - 0.12, 0.17), M[seat_mat], coll, bevel=0.05, soft=lv, rot=(0, 0, yaw)))
    q = T @ P(0, -D / 2 + 0.13, 0.66)
    parts.append(box(f"{name}_backrest", tuple(q), (W - 0.22, 0.16, 0.5), M["cushion"], coll, bevel=0.06, soft=lv, rot=(-8, 0, yaw)))
    if pillows:
        for sx in (-1, 1):
            q = T @ P(sx * (W / 2 - 0.2), -D / 2 + 0.26, 0.6)
            parts.append(box(f"{name}_pillow", tuple(q), (0.14, 0.38, 0.36), M["pillow" if sx < 0 else "pillow_green"], coll,
                             bevel=0.06, soft=lv, rot=(-12, sx * 8, yaw + sx * 12)))
    return parts


# the viewer's own: they sit in it — its arms either side at the foot of the view, a teal seat, striped pillows by the arms
armchair("near_chair_mine", (0.0, -0.15, 0.0), 0, seat_mat="seat_teal")
# the rug before it, a cord round its edge
box("near_rug", (0.0, 1.35, 0.008), (1.85, 1.3, 0.016), M["jute"], bevel=0.006, segs=2)
for (c, s) in [((0, 2.0, 0.014), (1.88, 0.03, 0.022)), ((0, 0.70, 0.014), (1.88, 0.03, 0.022)),
               ((-0.93, 1.35, 0.014), (0.03, 1.32, 0.022)), ((0.93, 1.35, 0.014), (0.03, 1.32, 0.022))]:
    box("near_rug_cord", c, s, M["jute"], bevel=0.01, segs=3)
# the other chairs: one to the right, turned to the screen; one to the far left, half out of view
armchair("near_chair_right", (1.75, 1.3, 0.0), 72)
armchair("near_chair_left", (-2.1, 0.7, 0.0), -70, pillows=False)

# the round table: plank top with a rounded edge, four splayed legs, an apron
TC = Vector((-1.15, 1.25, 0.0))
top = uv_box(cyl("near_table_top", TC + Vector((0, 0, 0.52)), 0.38, 0.045, M["table"], verts=64))
md = top.modifiers.new("bevel", 'BEVEL'); md.width = 0.012; md.segments = 3; md.limit_method = 'ANGLE'; _apply(top, [md])
for p in top.data.polygons: p.use_smooth = True
top.data.set_sharp_from_angle(angle=math.radians(40))
for k in range(5):  # the planks' joints, faint grooves on top
    box("near_table_groove", TC + Vector((-0.30 + k * 0.15, 0, 0.5435)), (0.006, 0.7 - abs(k - 2) * 0.14, 0.002), M["timber"])
uv_box(cyl("near_table_apron", TC + Vector((0, 0, 0.47)), 0.3, 0.06, M["table"], verts=48))
for k in range(4):
    a = math.radians(45 + 90 * k)
    box("near_table_leg", TC + Vector((math.cos(a) * 0.2, math.sin(a) * 0.2, 0.24)), (0.05, 0.05, 0.5), M["table"], bevel=0.008,
        segs=3, rot=(math.degrees(math.sin(a)) * -0.16, math.degrees(math.cos(a)) * 0.16, math.degrees(a)))
# the lantern on it: a square iron lantern, glass sides, a pyramid cap and a ring, a candle inside
LC = TC + Vector((0.13, 0.06, 0.545))
box("near_lantern_base", LC + Vector((0, 0, 0.02)), (0.15, 0.15, 0.04), M["iron"], bevel=0.006, segs=2)
# no glass: the headset draws no clear glass, and a pane would hide the candle — the frame, and the candle in it
for zb in (0.065, 0.235):
    for (dx, dy, sx_, sy_) in ((0, 0.066, 0.13, 0.01), (0, -0.066, 0.13, 0.01), (0.066, 0, 0.01, 0.13), (-0.066, 0, 0.01, 0.13)):
        box("near_lantern_bar", LC + Vector((dx, dy, zb)), (sx_, sy_, 0.012), M["iron"])
for dx in (-1, 1):
    for dy in (-1, 1):
        box("near_lantern_post", LC + Vector((dx * 0.066, dy * 0.066, 0.15)), (0.014, 0.014, 0.24), M["iron"], bevel=0.003, segs=2)
box("near_lantern_band", LC + Vector((0, 0, 0.27)), (0.16, 0.16, 0.03), M["iron"], bevel=0.004, segs=2)
bpy.ops.mesh.primitive_cone_add(vertices=4, radius1=0.115, radius2=0.03, depth=0.09, location=LC + Vector((0, 0, 0.33)),
                                rotation=(0, 0, math.radians(45)))
cap = bpy.context.active_object; cap.name = "near_lantern_cap"; cap.data.materials.append(M["iron"]); _link(cap, room)
bpy.ops.mesh.primitive_torus_add(major_radius=0.04, minor_radius=0.007, major_segments=24, minor_segments=8,
                                 location=LC + Vector((0, 0, 0.415)), rotation=(math.radians(90), 0, 0))
ring = bpy.context.active_object; ring.name = "near_lantern_ring"; ring.data.materials.append(M["iron"]); _link(ring, room)
cyl("near_lantern_candle", LC + Vector((0, 0, 0.1)), 0.026, 0.11, M["candle"], verts=24)
sphere("near_lantern_flame", LC + Vector((0, 0, 0.175)), (0.009, 0.009, 0.022), M["flame"], subdiv=2)
point_light("near_lantern_light", LC + Vector((0, 0, 0.18)), (1.0, 0.58, 0.25), 9.0, 0.02, house)
plant_pot("near_table_plant", TC + Vector((-0.12, -0.08, 0.545)), 0.085, 0.13, n_leaves=220, leaf_size=0.04, height=0.17)

# ---------------------------------------------------------------- far: the hillside village, cypresses, the windmill, mountains
def hill_height(x, y):
    """The slope beyond the roofs: rising away from the square, a crest to the right where the windmill stands."""
    d = max(0.0, y - 22.0)
    h = d * 0.42 + 9.0 * math.exp(-((x - 30) / 16) ** 2) * min(1, d / 20)
    h += 3.0 * noise.noise(Vector((x * 0.06, y * 0.06, 0.5)))
    return h - 2.0


verts, faces = [], []
NX, NY = 64, 40
for j in range(NY + 1):
    for i in range(NX + 1):
        x = -70 + 150 * i / NX; y = 20 + 75 * j / NY
        verts.append((x, y, hill_height(x, y)))
for j in range(NY):
    for i in range(NX):
        a = j * (NX + 1) + i
        faces.append((a, a + 1, a + NX + 2, a + NX + 1))
mesh_from("far_hill", verts, faces, M["hill"], smooth=True)
far = []
random.seed(5)
for k in range(150):  # small houses up the slope, most windows dark, some lit
    x = random.uniform(-45, 45); y = random.uniform(26, 70)
    if abs(x - 30) < 7 and y > 48: continue
    z = hill_height(x, y)
    w, d, hgt = random.uniform(3, 5), random.uniform(3, 5), random.uniform(3, 5)
    far.append(box("far_house", (x, y, z + hgt / 2 - 0.3), (w, d, hgt), M["plaster"]))
    far.append(box("far_roof", (x, y, z + hgt), (w + 0.4, d + 0.4, 0.5), M["roof"], rot=(0, 0, 0)))
    for wi in range(random.randint(1, 3)):
        lit = random.random() < 0.45
        far.append(box("far_window", (x + random.uniform(-w / 3, w / 3), y - d / 2 - 0.03, z + random.uniform(1.0, hgt - 1.0)),
                       (0.6, 0.06, 0.8), M["glass_lit" if lit else "glass_dark"]))
for k in range(80):
    x = random.uniform(-50, 50); y = random.uniform(24, 75)
    far += cypress("far_cypress", (x, y, hill_height(x, y) - 0.5), random.uniform(6, 11), subdiv=2)
for k in range(30):
    x = random.uniform(-50, 50); y = random.uniform(24, 75)
    far.append(sphere("far_tree", (x, y, hill_height(x, y) + 1.5), (2.2, 2.2, 1.8), M["olive"], subdiv=2, wobble=0.3, seed=k))
# the windmill on the crest: a white round tower, a conical cap, four lattice sails; its small windows lit
WM = Vector((30.0, 56.0, hill_height(30.0, 56.0)))
far.append(cyl("far_mill_tower", WM + Vector((0, 0, 4.0)), 2.6, 8.0, M["wall_pale"], verts=24, r2=2.1))
far.append(cyl("far_mill_cap", WM + Vector((0, 0, 8.9)), 2.4, 1.8, M["roof"], verts=24, r2=0.3))
for zz in (3.0, 5.6):
    far.append(box("far_mill_win", WM + Vector((0, -2.45, zz)), (0.6, 0.1, 0.9), M["glass_lit"]))
hub = WM + Vector((0, -2.6, 8.3))
for k in range(4):
    a = math.radians(20 + 90 * k)
    far.append(box("far_mill_sail", hub + Vector((math.cos(a) * 3.2, 0, math.sin(a) * 3.2)), (6.0, 0.08, 1.2), M["timber"],
                   rot=(0, -math.degrees(a), 0)))
# mountains: two ragged ridges, far off, in the night's haze
for (yy, top, low, col) in ((330, 95, 20, "mountain"), (520, 160, 40, "mountain")):
    vs, fs = [], []
    n = 90
    for i in range(n + 1):
        x = -900 + 1800 * i / n
        h = low + (top - low) * (0.5 + 0.5 * noise.noise(Vector((x * 0.004, yy * 0.01, 0.0)))) + 12 * noise.noise(Vector((x * 0.03, 1, 0)))
        vs += [(x, yy, -20), (x, yy + 30, h)]
    for i in range(n):
        a = 2 * i
        fs.append((a, a + 2, a + 3, a + 1))
    mesh_from("far_mountains", vs, fs, M[col])

mesh_from("far_ground", [(-300, -300, -3.5), (300, -300, -3.5), (300, 300, -3.5), (-300, 300, -3.5)], [(0, 1, 2, 3)], M["earth"])

# the night sky: a dome far beyond everything, emitting (base layer); it neither lights nor shadows the place
bpy.ops.mesh.primitive_uv_sphere_add(segments=48, ring_count=24, radius=900.0, location=(0, 0, 0))
sky = bpy.context.active_object; sky.name = "sky_dome"
bpy.ops.object.mode_set(mode='EDIT'); bpy.ops.mesh.select_all(action='SELECT'); bpy.ops.mesh.flip_normals()
bpy.ops.object.mode_set(mode='OBJECT')
sky.data.materials.append(M["sky"]); _link(sky, room)
for p in sky.data.polygons: p.use_smooth = True
sky.visible_diffuse = False; sky.visible_glossy = False; sky.visible_shadow = False; sky.visible_transmission = False

# ---------------------------------------------------------------- lights
# base: the moon, low and cool, from behind the viewer's right; the world: the night sky's dim blue all round
moon = bpy.data.lights.new("moon", 'SUN'); moon.energy = 1.1; moon.color = (0.55, 0.66, 1.0); moon.angle = math.radians(12.0)
mo = bpy.data.objects.new("moon", moon); mo.rotation_euler = (math.radians(52), 0, math.radians(200)); base.objects.link(mo)
world = bpy.data.worlds.new("night"); world.use_nodes = True
bg = world.node_tree.nodes["Background"]; bg.inputs["Color"].default_value = (0.020, 0.032, 0.075, 1); bg.inputs["Strength"].default_value = 2.6
scene.world = world
# house: the warm wash the reference's lit square has — the light of lanterns and windows all round it, out of view — and
# the lanterns and windows themselves (above)
area_light("house_wash_back", (0, -7, 7), (12, 3), (55, 0, 0), WARM, 260.0, house)
area_light("house_wash_left", (-2, 4, 9), (6, 10), (0, -35, 0), WARM, 120.0, house)
area_light("house_wash_right", (3, 4, 9), (6, 10), (0, 35, 0), WARM, 120.0, house)
area_light("house_wash_far", (0, 30, 25), (60, 20), (-20, 0, 0), WARM, 900.0, house)
# ambc: the screen's light, as if it were white (the app turns it down by the film's frame)
area_light("screen_glow_ambc", (0, SCREEN_Y - 0.03, SCREEN_FOOT + SCREEN_H / 2), (SCREEN_W, SCREEN_H), (-90, 0, 0),
           (1.0, 1.0, 1.0), math.pi * SCREEN_W * SCREEN_H, ambc)
SCREEN_MEAN = 0.35
scene["film_tint"] = (0.62, 0.70, 0.85)
scene["film_mean"] = SCREEN_MEAN

# ---------------------------------------------------------------- effects: the lights' halos (not baked; drawn added, unlit)
def fx_texture(name, w, h, fn):
    img = bpy.data.images.new(name, w, h, alpha=False, float_buffer=False)
    px = []
    for j in range(h):
        for i in range(w):
            r, g, b = fn((i + 0.5) / w, (j + 0.5) / h); px += [r, g, b, 1.0]
    img.pixels[:] = px
    img.pack()
    return img


def fx_material(name, img):
    m = bpy.data.materials.new(name); m.use_nodes = True
    nt = m.node_tree; b = nt.nodes["Principled BSDF"]
    t = nt.nodes.new("ShaderNodeTexImage"); t.image = img
    nt.links.new(t.outputs[0], b.inputs["Base Color"])
    return m


def _glow(u, v):
    r = math.hypot(u - 0.5, v - 0.5) * 2
    k = max(0.0, 1.0 - r) ** 2.4 * 0.9
    return (k, k * 0.66, k * 0.36)


halo_mat = fx_material("fxh_halo", fx_texture("fx_halo_tex", 64, 64, _glow))
hbm = bmesh.new(); huv = hbm.loops.layers.uv.new("UVMap")
EYE_AT = Vector((SEAT[0], SEAT[1], SEAT[2] + EYE))


def halo(p, size):
    """A soft glow round a light, a square turned to face the seat."""
    P = Vector(p); f = (EYE_AT - P); f.z = 0; f.normalize()
    side = Vector((f.y, -f.x, 0)) * size / 2; up = Vector((0, 0, size / 2))
    vs = [hbm.verts.new(P - side - up), hbm.verts.new(P + side - up), hbm.verts.new(P + side + up), hbm.verts.new(P - side + up)]
    face = hbm.faces.new(vs)
    for loop, uv in zip(face.loops, [(0, 0), (1, 0), (1, 1), (0, 1)]): loop[huv].uv = uv
    if face.normal.dot(EYE_AT - P) < 0: face.normal_flip()


for o in [o for o in house.objects if o.type == 'LIGHT' and o.data.type == 'POINT']:
    halo(o.location, 0.9 if "near_" not in o.name else 0.28)
hme = bpy.data.meshes.new("fxh_halo"); hbm.to_mesh(hme); hbm.free()
hob = bpy.data.objects.new("fxh_halo", hme); fx.objects.link(hob); hme.materials.append(halo_mat)

# ---------------------------------------------------------------- the seat's camera
cam_data = bpy.data.cameras.new("seat"); cam = bpy.data.objects.new("seat", cam_data); scene.collection.objects.link(cam)
cam.location = (SEAT[0], SEAT[1], SEAT[2] + EYE)
look = Vector(SCREEN_CENTER) - cam.location
cam.rotation_euler = (math.radians(90) + math.atan2(look.z, math.hypot(look.x, look.y)), 0, 0)
cam_data.sensor_fit = 'HORIZONTAL'; cam_data.angle = math.radians(100); cam_data.clip_start = 0.05; cam_data.clip_end = 2000
scene.camera = cam
scene["seat"] = SEAT; scene["eye"] = EYE; scene["step_base"] = 0.0
scene["screen"] = (SCREEN_CENTER[0], SCREEN_CENTER[1], SCREEN_CENTER[2], SCREEN_W, SCREEN_ASPECT)

if OUT:
    bpy.ops.wm.save_as_mainfile(filepath=OUT)
    meshes = [o for o in scene.objects if o.type == 'MESH']
    tris = lambda o: sum(len(p.vertices) - 2 for p in o.data.polygons)
    by = {}
    for o in meshes:
        g = o.name.split("_")[0]
        by[g] = by.get(g, 0) + tris(o)
    print(f"VILLAGE saved {OUT}: {len(meshes)} meshes, {sum(by.values())} triangles {by}, "
          f"{len(house.objects)} house lights, {len(base.objects)} base lights")
