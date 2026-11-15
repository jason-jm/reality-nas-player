"""
space_lounge.py — an observation lounge on a space station (the user's references, lit and dark, 2026-10-23): a long hall
under curved ribs, its walls and the middle of its ceiling all window — the Earth filling the left ones, its limb bright
with air, the Milky Way across the ceiling and the right ones — warm light lines on the soffits between the ribs, a lamp
on each rib, planters of uplit plants along both walls, a polished grey marble floor with a dark inlay running down it,
terracotta velvet club chairs on round green rugs either side with small bronze tables and candles, and at the far end
the screen in a dark fluted wall, a lit corridor beside it. The viewer sits in a chair of their own in the middle.

  Blender --background --factory-startup --python tools/envbake/scenes/space_lounge.py -- OUT.blend ASSETS

ASSETS holds Poly Haven scans (CC0), fetched with tools/envbake/polyhaven_get.py: the plants anthurium_botany_01,
calathea_orbifolia_01 and pachira_aquatica_01 (2k glTF, and the Alpha maps of the first two), the chairs' velour_velvet and
the rugs' poly_wool_herringbone; and in nasa_bmng/ NASA's Blue Marble: Next Generation, August, land and topography
(world.topo.200408.3x5400x2700.jpg, from science.nasa.gov/earth/earth-observatory/blue-marble-next-generation — credit
"NASA Earth Observatory") for the Earth.

Blender axes: x right, y toward the screen, z up (glTF: x, z, -y). `SEAT` is the floor under the viewer, `EYE` their seated
eye height. Lights, as tools/envbake/bake_layers.py bakes them apart:
  base  — the Earth's light through the windows, the stars' (the world), the windows' thin blue edge lines, and the sky
          dome itself (emitting, "base_emission"): the dark reference;
  house — everything the lit reference adds: the soffit lines, the rib lamps, the planters' uplights, the candles, the
          corridor, the lines under the screen and round the rear door ("house_emission" glows with them);
  ambc  — the screen's light, in white (动态氛围光).
Near things — the viewer's chair (its arms a hand's length off), the other chairs, the tables and candles, the rugs — are
modelled finely. Every size is in metres.
"""
import bpy, bmesh, math, os, sys, random
import numpy as np
from mathutils import Vector, Matrix, noise

ARGS = sys.argv[sys.argv.index("--") + 1:] if "--" in sys.argv else []
OUT = ARGS[0] if ARGS else None
ASSETS = ARGS[1] if len(ARGS) > 1 else os.path.expanduser("~/aura-eval/polyhaven")
random.seed(11)

SEAT = (0.0, 0.0, 0.0)
EYE = 1.1
HALL_Y0, HALL_Y1 = -7.2, 11.6        # the rear wall's face, the screen wall's face
RIBS = [-5.0 + 2.4 * k for k in range(7)]
RIB_W, RIB_D = 0.30, 0.28            # a rib's breadth along the hall, its depth into it
SCREEN_W, SCREEN_ASPECT = 6.0, 2.0
SCREEN_H = SCREEN_W / SCREEN_ASPECT
SCREEN_FOOT = 0.95
SCREEN_FACE_Y = HALL_Y1 - 0.13
SCREEN_CENTER = (0.0, SCREEN_FACE_Y - 0.01, SCREEN_FOOT + SCREEN_H / 2)
# the hall's cross-section, right half, floor to the ceiling glass's edge: a low wall, the window leaning out, the soffit
# curving in to the flat glass strip over the middle
PROFILE = [(5.6, 0.0), (5.6, 0.75), (6.1, 4.3), (5.85, 5.0), (5.2, 5.7), (4.2, 6.25), (3.0, 6.55), (2.6, 6.6)]
GLASS_X, CEIL_Z = 2.6, 6.6
PLANTER_X0, PLANTER_X1, PLANTER_H = 3.95, 5.55, 0.62
PLANTER_Y0, PLANTER_Y1 = -6.9, 9.8
DOOR_X0, DOOR_X1 = 3.55, 4.55        # the corridor beside the screen
# the Earth, as the sky dome draws it: where its middle lies from the viewer and how wide it looks
EARTH_DIR = Vector((-1500.0, 700.0, -600.0)).normalized()
EARTH_R = math.radians(43.0)
EARTH_SUB = (-10.0, 25.0)            # the latitude and longitude under the station, degrees

bpy.ops.wm.read_factory_settings(use_empty=True)
scene = bpy.context.scene
house = bpy.data.collections.new("house"); scene.collection.children.link(house)
base = bpy.data.collections.new("base"); scene.collection.children.link(base)
room = bpy.data.collections.new("room"); scene.collection.children.link(room)
ambc = bpy.data.collections.new("ambc"); scene.collection.children.link(ambc)
fx = bpy.data.collections.new("fx"); scene.collection.children.link(fx)
WARM = (1.0, 0.66, 0.36)


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


def marble(name):
    """Polished grey marble in large slabs, 1.2 × 0.6 m, their length down the hall, half-bonded, thin dark joints: a
    cloudy grey ground with soft drifts of paler stone, long thin pale veins that wander and fork (ridged noise, two
    sizes), each slab cut from its own part of the block (its own offset, so no vein runs on across a joint); glossy (the
    bake keeps the reflections as the seat sees them)."""
    m = bpy.data.materials.new(name); nt, b = _nodes(m)
    tc = nt.nodes.new("ShaderNodeTexCoord"); mp = nt.nodes.new("ShaderNodeMapping")
    mp.inputs["Rotation"].default_value = (0, 0, math.radians(90)); nt.links.new(tc.outputs["Object"], mp.inputs[0])
    co = mp.outputs[0]
    br = nt.nodes.new("ShaderNodeTexBrick"); br.inputs["Scale"].default_value = 1.0
    br.inputs["Brick Width"].default_value = 1.2; br.inputs["Row Height"].default_value = 0.6
    br.inputs["Mortar Size"].default_value = 0.0025; br.inputs["Mortar Smooth"].default_value = 0.3; br.offset = 0.5
    br.inputs["Color1"].default_value = (0, 0, 0, 1); br.inputs["Color2"].default_value = (1, 1, 1, 1)
    br.inputs["Bias"].default_value = 0.0
    nt.links.new(co, br.inputs["Vector"])
    rnd = nt.nodes.new("ShaderNodeSeparateColor"); nt.links.new(br.outputs["Color"], rnd.inputs[0])
    sh = nt.nodes.new("ShaderNodeVectorMath"); sh.operation = 'SCALE'; sh.inputs[0].default_value = (37.1, 23.7, 11.3)
    nt.links.new(rnd.outputs[0], sh.inputs["Scale"])
    pv = nt.nodes.new("ShaderNodeVectorMath"); pv.operation = 'ADD'
    nt.links.new(co, pv.inputs[0]); nt.links.new(sh.outputs[0], pv.inputs[1])
    P = pv.outputs[0]                                   # the slab's own place in the block

    def nz(scale, detail, rough=0.55, dist=0.0, vec=None):
        n = nt.nodes.new("ShaderNodeTexNoise"); n.inputs["Scale"].default_value = scale; n.inputs["Detail"].default_value = detail
        n.inputs["Roughness"].default_value = rough; n.inputs["Distortion"].default_value = dist
        nt.links.new(vec if vec is not None else P, n.inputs["Vector"]); return n
    ground = nz(1.6, 6.0)
    shade = _ramp(nt, [(0.3, (0.21, 0.215, 0.23)), (0.55, (0.27, 0.275, 0.29)), (0.8, (0.33, 0.335, 0.35))])
    nt.links.new(ground.outputs["Fac"], shade.inputs[0])
    drift = nz(0.45, 4.0)
    dr = _ramp(nt, [(0.5, (0, 0, 0)), (0.72, (1, 1, 1))]); nt.links.new(drift.outputs["Fac"], dr.inputs[0])
    c = _mix(nt, _math(nt, 'MULTIPLY', dr.outputs[0], 0.45), shade.outputs[0], (0.46, 0.465, 0.48))

    def veins(scale, width, warp):
        w = nz(scale * 0.7, 3.0)
        wv = nt.nodes.new("ShaderNodeVectorMath"); wv.operation = 'MULTIPLY_ADD'; wv.inputs[1].default_value = (warp, warp, warp)
        nt.links.new(w.outputs["Color"], wv.inputs[0]); nt.links.new(P, wv.inputs[2])
        n = nz(scale, 7.0, 0.5, vec=wv.outputs[0])
        ridge = _math(nt, 'SUBTRACT', 1.0, _math(nt, 'ABSOLUTE', _math(nt, 'SUBTRACT', _math(nt, 'MULTIPLY', n.outputs["Fac"], 2.0), 1.0)))
        line = _ramp(nt, [(1.0 - width, (0, 0, 0)), (1.0 - width * 0.35, (1, 1, 1))]); nt.links.new(ridge, line.inputs[0])
        return line.outputs[0]
    # few, long, crisp: the ridge's top few hundredths only, a finer set fainter
    v = _math(nt, 'ADD', _math(nt, 'MULTIPLY', veins(0.32, 0.016, 0.5), 0.8), _math(nt, 'MULTIPLY', veins(1.1, 0.012, 0.35), 0.35))
    c = _mix(nt, _math(nt, 'MINIMUM', v, 1.0), c, (0.66, 0.665, 0.68))
    c = _mix(nt, br.outputs["Fac"], c, (0.07, 0.07, 0.075))
    nt.links.new(c, b.inputs["Base Color"])
    b.inputs["Roughness"].default_value = 0.24
    _bump(nt, b, br.outputs["Fac"], 0.12, 0.002)
    return m


def fluted(name, color, period=0.045):
    """A dark wall of vertical flutes (the screen wall, the console)."""
    m = bpy.data.materials.new(name); nt, b = _nodes(m)
    co = _coords(nt)
    wv = nt.nodes.new("ShaderNodeTexWave"); wv.wave_type = 'BANDS'; wv.bands_direction = 'X'; wv.wave_profile = 'SIN'
    wv.inputs["Scale"].default_value = 1.0 / period / 6.283; nt.links.new(co, wv.inputs["Vector"])
    b.inputs["Base Color"].default_value = (*color, 1)
    b.inputs["Metallic"].default_value = 0.5; b.inputs["Roughness"].default_value = 0.45
    _bump(nt, b, wv.outputs["Fac"], 0.6, 0.01)
    return m


def panels(name, color):
    """The soffits' lining: brushed metal in long slats along the hall."""
    m = bpy.data.materials.new(name); nt, b = _nodes(m)
    co = _coords(nt)
    wv = nt.nodes.new("ShaderNodeTexWave"); wv.wave_type = 'BANDS'; wv.bands_direction = 'X'; wv.wave_profile = 'SAW'
    wv.inputs["Scale"].default_value = 1.0 / 0.32 / 1.0; nt.links.new(co, wv.inputs["Vector"])
    seam = _ramp(nt, [(0.0, (0, 0, 0)), (0.03, (1, 1, 1))]); nt.links.new(wv.outputs["Fac"], seam.inputs[0])
    brush = nt.nodes.new("ShaderNodeTexNoise"); brush.inputs["Scale"].default_value = 120.0
    mp = nt.nodes.new("ShaderNodeMapping"); mp.inputs["Scale"].default_value = (8.0, 0.2, 8.0)
    tc = nt.nodes.new("ShaderNodeTexCoord"); nt.links.new(tc.outputs["Object"], mp.inputs[0]); nt.links.new(mp.outputs[0], brush.inputs["Vector"])
    tone = _ramp(nt, [(0.3, tuple(c * 0.85 for c in color)), (0.7, tuple(min(1, c * 1.1) for c in color))])
    nt.links.new(brush.outputs["Fac"], tone.inputs[0])
    c = _mix(nt, seam.outputs[0], (0.03, 0.03, 0.035), tone.outputs[0])
    nt.links.new(c, b.inputs["Base Color"])
    b.inputs["Metallic"].default_value = 0.65; b.inputs["Roughness"].default_value = 0.38
    _bump(nt, b, seam.outputs[0], 0.4, 0.004)
    return m


def uv_box(o):
    """UVs in metres on each face of a box-like mesh, u along the longer of the face's two in-plane axes: the pile of the
    velvet runs along each part's length, as upholstery's does."""
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


def scan_uv(name, tex, tile, tint=None, sat=1.0, rough=None, normal=1.0, sheen=0.0):
    """A material from a Poly Haven scan in ASSETS read through the mesh's own UVs ("UVMap", in metres: uv_box), `tile`
    metres a repeat; `tint` multiplies the colour back to the part's own (after `sat`), `sheen` the velvet's at grazing."""
    d = os.path.join(ASSETS, tex)

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
    c = tex_node(img("diff", True))
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
    if sheen:
        b.inputs["Sheen Weight"].default_value = sheen; b.inputs["Sheen Roughness"].default_value = 0.35
        b.inputs["Sheen Tint"].default_value = (*[min(1.0, 0.25 * t) for t in (tint or (1, 1, 1))], 1)
    return m


def space_sky(name):
    """The dome's face, emitting (base layer alone): black space with stars, the Milky Way across it — clouds of blue,
    violet and rose cut by dark dust, denser stars in it — and the Earth: oceans, land, white weather, darkening toward
    its limb under a band of blue air, and a thin glow above the limb."""
    m = bpy.data.materials.new(name); nt, b = _nodes(m)
    b.inputs["Base Color"].default_value = (0, 0, 0, 1)
    tc = nt.nodes.new("ShaderNodeTexCoord")
    d = nt.nodes.new("ShaderNodeVectorMath"); d.operation = 'NORMALIZE'; nt.links.new(tc.outputs["Object"], d.inputs[0])
    D = d.outputs[0]

    def vdot(v):
        n = nt.nodes.new("ShaderNodeVectorMath"); n.operation = 'DOT_PRODUCT'
        nt.links.new(D, n.inputs[0]); n.inputs[1].default_value = tuple(v)
        return n.outputs["Value"]

    def noise(scale, detail=8.0, rough=0.6, stretch=(1, 1, 1), dist=0.0):
        sv = nt.nodes.new("ShaderNodeVectorMath"); sv.operation = 'MULTIPLY'; sv.inputs[1].default_value = stretch
        nt.links.new(D, sv.inputs[0])
        nz = nt.nodes.new("ShaderNodeTexNoise"); nz.inputs["Scale"].default_value = scale
        nz.inputs["Detail"].default_value = detail; nz.inputs["Roughness"].default_value = rough
        nz.inputs["Distortion"].default_value = dist
        nt.links.new(sv.outputs[0], nz.inputs["Vector"])
        return nz

    # the Milky Way: a band about a great circle through the ceiling's front and the right-hand windows
    N = Vector((0.05, 0.55, 0.83)).cross(Vector((0.75, 0.0, 0.66))).normalized()
    off = _math(nt, 'ABSOLUTE', vdot(N))
    band = _math(nt, 'EXPONENT', _math(nt, 'MULTIPLY', _math(nt, 'POWER', _math(nt, 'DIVIDE', off, 0.16), 2.0), -1.0))
    clouds = noise(3.4, 12.0, 0.66, dist=0.6)
    ctone = _ramp(nt, [(0.25, (0.01, 0.015, 0.05)), (0.40, (0.06, 0.08, 0.26)), (0.52, (0.28, 0.16, 0.45)),
                       (0.62, (0.70, 0.42, 0.68)), (0.74, (1.4, 1.1, 1.2))])
    nt.links.new(clouds.outputs["Fac"], ctone.inputs[0])
    dust = noise(9.0, 10.0, 0.6)
    lane = _ramp(nt, [(0.42, (0.15, 0.15, 0.15)), (0.58, (1, 1, 1))]); nt.links.new(dust.outputs["Fac"], lane.inputs[0])
    mw = _mix(nt, 1.0, ctone.outputs[0], lane.outputs[0], 'MULTIPLY')
    mw = _mix(nt, 1.0, mw, _math(nt, 'MULTIPLY', band, 2.6), 'MULTIPLY')
    sky = _mix(nt, 1.0, (0.002, 0.003, 0.010), mw, 'ADD')
    sky = _mix(nt, 1.0, sky, _mix(nt, band, (0.004, 0.008, 0.03), (0.03, 0.04, 0.12)), 'ADD')

    def stars(scale, keep_from, brightness, extra=None):
        st = nt.nodes.new("ShaderNodeTexVoronoi"); st.inputs["Scale"].default_value = scale; st.inputs["Randomness"].default_value = 1.0
        nt.links.new(D, st.inputs["Vector"])
        dot = _ramp(nt, [(0.0, (1, 1, 1)), (0.04, (0.6, 0.6, 0.6)), (0.15, (0, 0, 0))])
        nt.links.new(st.outputs["Distance"], dot.inputs[0])
        sp = nt.nodes.new("ShaderNodeSeparateColor"); nt.links.new(st.outputs["Color"], sp.inputs[0])
        keep = _ramp(nt, [(keep_from, (0, 0, 0)), (keep_from + 0.01, (0.4, 0.45, 0.6)), (1.0, (1.0, 0.95, 0.9))])
        nt.links.new(sp.outputs[0], keep.inputs[0])
        s = _mix(nt, 1.0, dot.outputs[0], keep.outputs[0], 'MULTIPLY')
        s = _mix(nt, 1.0, s, (brightness, brightness, brightness), 'MULTIPLY')
        if extra is not None: s = _mix(nt, 1.0, s, extra, 'MULTIPLY')
        return s

    sky = _mix(nt, 1.0, sky, stars(140.0, 0.92, 5.0), 'ADD')   # a few bright ones
    sky = _mix(nt, 1.0, sky, stars(320.0, 0.72, 2.0), 'ADD')
    sky = _mix(nt, 1.0, sky, stars(600.0, 0.50, 1.0, _math(nt, 'ADD', _math(nt, 'MULTIPLY', band, 2.5), 0.4)), 'ADD')
    # the Earth: a sphere seen from the station — the point the ray meets on it, turned into latitude and longitude, reads
    # NASA's map; the sub-station point at EARTH_SUB (Africa, the Sahara and the Mediterranean up toward the limb, north up)
    E = EARTH_DIR; r = math.sin(EARTH_R)
    up = (Vector((0, 0, 1)) - E * E.z).normalized(); right = E.cross(up)
    la0, lo0 = (math.radians(x) for x in EARTH_SUB)
    sub = Vector((math.cos(la0) * math.cos(lo0), math.cos(la0) * math.sin(lo0), math.sin(la0)))
    north = (Vector((0, 0, 1)) - sub * sub.z).normalized(); east = north.cross(sub)
    rows = [-E * sub[i] + up * north[i] + right * east[i] for i in range(3)]   # world point on the unit sphere → geo
    bdot = vdot(E)
    disc = _math(nt, 'SUBTRACT', _math(nt, 'MULTIPLY', bdot, bdot), 1.0 - r * r)
    inside = _math(nt, 'GREATER_THAN', disc, 0.0)
    t = _math(nt, 'SUBTRACT', bdot, _math(nt, 'SQRT', _math(nt, 'MAXIMUM', disc, 0.0)))
    hit = nt.nodes.new("ShaderNodeVectorMath"); hit.operation = 'SCALE'; nt.links.new(D, hit.inputs[0]); nt.links.new(t, hit.inputs["Scale"])
    rel = nt.nodes.new("ShaderNodeVectorMath"); rel.operation = 'SUBTRACT'; nt.links.new(hit.outputs[0], rel.inputs[0])
    rel.inputs[1].default_value = tuple(E)
    nrm = nt.nodes.new("ShaderNodeVectorMath"); nrm.operation = 'SCALE'; nt.links.new(rel.outputs[0], nrm.inputs[0])
    nrm.inputs["Scale"].default_value = 1.0 / r
    Nn = nrm.outputs[0]

    def ndot(v):
        n = nt.nodes.new("ShaderNodeVectorMath"); n.operation = 'DOT_PRODUCT'
        nt.links.new(Nn, n.inputs[0]); n.inputs[1].default_value = tuple(v)
        return n.outputs["Value"]
    gx, gy, gz = (ndot(v) for v in rows)
    geo = nt.nodes.new("ShaderNodeCombineXYZ")
    for i, g in enumerate((gx, gy, gz)): nt.links.new(g, geo.inputs[i])
    lat = _math(nt, 'ARCSINE', _math(nt, 'MINIMUM', _math(nt, 'MAXIMUM', gz, -1.0), 1.0))
    lon = _math(nt, 'ARCTAN2', gy, gx)
    uvc = nt.nodes.new("ShaderNodeCombineXYZ")
    nt.links.new(_math(nt, 'ADD', _math(nt, 'MULTIPLY', lon, 1 / (2 * math.pi)), 0.5), uvc.inputs[0])
    nt.links.new(_math(nt, 'ADD', _math(nt, 'MULTIPLY', lat, 1 / math.pi), 0.5), uvc.inputs[1])
    mapimg = nt.nodes.new("ShaderNodeTexImage"); mapimg.interpolation = 'Cubic'; mapimg.extension = 'REPEAT'
    mapimg.image = bpy.data.images.load(os.path.join(ASSETS, "nasa_bmng", "world.topo.200408.3x5400x2700.jpg"), check_existing=True)
    nt.links.new(uvc.outputs[0], mapimg.inputs["Vector"])
    surf = _mix(nt, 1.0, mapimg.outputs["Color"], (1.15, 1.15, 1.15), 'MULTIPLY')
    surf = _mix(nt, 1.0, surf, (0.010, 0.030, 0.075), 'ADD')                 # the air's blue over the dark sea
    # the weather: clouds in bands along the latitudes (the geo point squeezed north–south), swirled; a finer set
    sq = nt.nodes.new("ShaderNodeVectorMath"); sq.operation = 'MULTIPLY'; sq.inputs[1].default_value = (1.0, 1.0, 2.2)
    nt.links.new(geo.outputs[0], sq.inputs[0])

    def gnoise(scale, detail, rough, dist):
        n = nt.nodes.new("ShaderNodeTexNoise"); n.inputs["Scale"].default_value = scale; n.inputs["Detail"].default_value = detail
        n.inputs["Roughness"].default_value = rough; n.inputs["Distortion"].default_value = dist
        nt.links.new(sq.outputs[0], n.inputs["Vector"]); return n
    big = gnoise(2.6, 12.0, 0.62, 1.4)
    cb = _ramp(nt, [(0.49, (0, 0, 0)), (0.60, (1, 1, 1))]); nt.links.new(big.outputs["Fac"], cb.inputs[0])
    fine = gnoise(9.0, 10.0, 0.6, 0.8)
    cf = _ramp(nt, [(0.55, (0, 0, 0)), (0.68, (0.7, 0.7, 0.7))]); nt.links.new(fine.outputs["Fac"], cf.inputs[0])
    cloud = _math(nt, 'MINIMUM', _math(nt, 'ADD', cb.outputs[0], _math(nt, 'MULTIPLY', cf.outputs[0], 0.6)), 1.0)
    surf = _mix(nt, cloud, surf, (1.05, 1.07, 1.12))
    # toward the limb: darker (seen slant, lit slant), the air thicker and bluer
    mu = _math(nt, 'MAXIMUM', _math(nt, 'MULTIPLY', ndot(E), -1.0), 0.0)     # cos of the view's slant at the ground
    surf = _mix(nt, 1.0, surf, _math(nt, 'ADD', _math(nt, 'MULTIPLY', _math(nt, 'SQRT', mu), 0.6), 0.4), 'MULTIPLY')
    haze = _math(nt, 'POWER', _math(nt, 'SUBTRACT', 1.0, mu), 4.0)
    surf = _mix(nt, _math(nt, 'MULTIPLY', haze, 0.8), surf, (0.30, 0.55, 1.0))
    earth = _mix(nt, inside, sky, surf)
    ang = _math(nt, 'ARCCOSINE', bdot)
    above = _math(nt, 'MAXIMUM', _math(nt, 'SUBTRACT', ang, EARTH_R), 0.0)
    glow = _math(nt, 'EXPONENT', _math(nt, 'MULTIPLY', _math(nt, 'POWER', _math(nt, 'DIVIDE', above, math.radians(1.6)), 2.0), -1.0))
    glow = _math(nt, 'MULTIPLY', glow, _math(nt, 'SUBTRACT', 1.0, inside))
    out = _mix(nt, 1.0, earth, _mix(nt, glow, (0, 0, 0), (0.25, 0.50, 1.0)), 'ADD')
    nt.links.new(out, b.inputs["Emission Color"])
    b.inputs["Emission Strength"].default_value = 0.0
    m["base_emission"] = 1.0
    return m


M = {
    "floor": marble("marble"),
    "rib": plain("gunmetal", (0.13, 0.13, 0.14), 0.32, metal=0.8),
    "frame": plain("window_frame", (0.09, 0.09, 0.10), 0.3, metal=0.8),
    "soffit": panels("soffit_panels", (0.26, 0.26, 0.26)),
    "wall": fluted("wall_fluted", (0.06, 0.065, 0.075)),
    "lowwall": plain("low_wall", (0.07, 0.07, 0.08), 0.5, metal=0.5),
    "planter": plain("planter", (0.06, 0.06, 0.065), 0.4, metal=0.6),
    "soil": plain("soil", (0.025, 0.02, 0.015), 0.95),
    "inlay": plain("inlay", (0.05, 0.05, 0.055), 0.25, metal=0.8),
    # the chairs in scanned velvet (2026-10-26): its red taken to grey (its brightest channel, ~0.44), then to their colours
    "velvet": scan_uv("velvet_terracotta", "velour_velvet", 0.28, sat=0.0, tint=(1.14, 0.34, 0.20), sheen=0.8),
    "pillow": scan_uv("velvet_taupe", "velour_velvet", 0.28, sat=0.0, tint=(0.75, 0.61, 0.57), sheen=0.8),
    # the round rugs (2026-10-26): scanned herringbone wool, grey (~0.19) dyed a deep green
    "rug": scan_uv("rug_herringbone", "poly_wool_herringbone", 0.5, sat=0.0, tint=(0.30, 0.56, 0.36), rough=0.95),
    "bronze": plain("bronze", (0.32, 0.25, 0.17), 0.3, metal=0.9),
    "steel": plain("dark_steel", (0.12, 0.12, 0.13), 0.28, metal=0.9),
    "tabletop": plain("smoked_glass", (0.02, 0.02, 0.025), 0.08, metal=0.3),
    "brass": plain("brass", (0.55, 0.40, 0.18), 0.25, metal=1.0),
    "wax": plain("wax", (0.80, 0.74, 0.62), 0.5),
    "flame": plain("flame", (0.9, 0.85, 0.7), 0.5, emission=(1.0, 0.62, 0.25), house_emission=30.0),
    "led": plain("led_line", (0.9, 0.8, 0.6), 0.4, emission=(1.0, 0.62, 0.30), house_emission=3.2),
    "sconce": plain("sconce_glass", (0.3, 0.28, 0.25), 0.2, emission=(1.0, 0.70, 0.42), house_emission=6.0),
    "edge": plain("window_edge", (0.2, 0.25, 0.3), 0.3, emission=(0.45, 0.65, 1.0), base_emission=1.6),
    "screen": plain("screen", (0.8, 0.8, 0.8), 0.9, emission=(0.62, 0.70, 0.85)),
    "black": plain("matte_black", (0.01, 0.01, 0.012), 0.9),
    "sky": space_sky("space_sky"),
}
M["screen"]["house_emission"] = 0.0


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


def mesh_from(name, verts, faces, material, coll=room, smooth=False):
    me = bpy.data.meshes.new(name); me.from_pydata([tuple(v) for v in verts], [], faces); me.update()
    o = bpy.data.objects.new(name, me); coll.objects.link(o); me.materials.append(material)
    if smooth:
        for p in me.polygons: p.use_smooth = True
    return o


def face_toward(o, inside):
    """Turns each face of `o` to look toward the point `inside` (single-sided drawing: the room sees the fronts)."""
    bm = bmesh.new(); bm.from_mesh(o.data)
    flip = [f for f in bm.faces if f.normal.dot(Vector(inside) - f.calc_center_median()) < 0]
    if flip: bmesh.ops.reverse_faces(bm, faces=flip)
    bm.to_mesh(o.data); bm.free()
    return o


def join(name, objs):
    bpy.ops.object.select_all(action='DESELECT')
    for o in objs: o.select_set(True)
    bpy.context.view_layer.objects.active = objs[0]
    bpy.ops.object.join()
    o = bpy.context.view_layer.objects.active; o.name = name
    o.select_set(False)
    return o


def aimed(direction, along=Vector((0, 1, 0))):
    """A rotation (Euler) whose −Z points along `direction` and whose Y lies along `along` as far as it can."""
    z = -Vector(direction).normalized()
    x = Vector(along).cross(z)
    if x.length < 1e-6: x = Vector((1, 0, 0)).cross(z)
    x.normalize(); y = z.cross(x)
    return Matrix((x, y, z)).transposed().to_euler()


def area_light(name, loc, size, direction, color, energy, coll, along=(0, 1, 0)):
    l = bpy.data.lights.new(name, 'AREA'); l.shape = 'RECTANGLE'; l.size, l.size_y = size
    l.color = color; l.energy = energy
    o = bpy.data.objects.new(name, l); o.location = loc; o.rotation_euler = aimed(direction, Vector(along)); coll.objects.link(o)
    return o


def point_light(name, loc, color, energy, radius, coll):
    l = bpy.data.lights.new(name, 'POINT'); l.color = color; l.energy = energy; l.shadow_soft_size = radius
    o = bpy.data.objects.new(name, l); o.location = loc; coll.objects.link(o)
    return o


def inward(i, side=1):
    """The profile's inward normal at its point i (x, z), for the side ±1 (−1 the mirror)."""
    def seg(a, b):
        (x0, z0), (x1, z1) = PROFILE[a], PROFILE[b]
        n = Vector((-(z1 - z0), x1 - x0)); return n.normalized()
    if i == 0: n = seg(0, 1)
    elif i == len(PROFILE) - 1: n = seg(i - 1, i)
    else: n = (seg(i - 1, i) + seg(i, i + 1)).normalized()
    return Vector((n.x * side, n.y))


def P3(i, side, y, depth=0.0):
    """The profile's point i on the side ±1 at y, `depth` in from the surface."""
    x, z = PROFILE[i]; n = inward(i, side)
    return Vector((side * x + n.x * depth, y, z + n.y * depth))


# ---------------------------------------------------------------- the hall: floor, ribs, windows, soffits, the ceiling glass
floor = mesh_from("floor_marble", [(-5.6, HALL_Y0, 0), (5.6, HALL_Y0, 0), (5.6, HALL_Y1, 0), (-5.6, HALL_Y1, 0)], [(0, 1, 2, 3)],
                  M["floor"])
bpy.context.view_layer.objects.active = floor; floor.select_set(True)
bpy.ops.object.mode_set(mode='EDIT'); bpy.ops.mesh.subdivide(number_cuts=20); bpy.ops.object.mode_set(mode='OBJECT')
floor.select_set(False)


def rib(name, y):
    """A rib: a solid of the profile's shape, RIB_W along the hall and RIB_D deep, both sides, joined over the glass by a
    crossbar."""
    bm = bmesh.new()
    for side in (1, -1):
        rings = []
        for i in range(len(PROFILE)):
            ring = []
            for dy, dd in ((-RIB_W / 2, 0.0), (RIB_W / 2, 0.0), (RIB_W / 2, RIB_D), (-RIB_W / 2, RIB_D)):
                ring.append(bm.verts.new(P3(i, side, y + dy, dd)))
            rings.append(ring)
        for a, b_ in zip(rings, rings[1:]):
            for k in range(4):
                bm.faces.new((a[k], a[(k + 1) % 4], b_[(k + 1) % 4], b_[k]))
        bm.faces.new(rings[0]); bm.faces.new(list(reversed(rings[-1])))
    bmesh.ops.recalc_face_normals(bm, faces=bm.faces)
    me = bpy.data.meshes.new(name); bm.to_mesh(me); bm.free()
    o = bpy.data.objects.new(name, me); room.objects.link(o); me.materials.append(M["rib"])
    box(f"{name}_cross", (0, y, CEIL_Z - RIB_D / 2 + 0.02), (2 * GLASS_X + 0.1, RIB_W, RIB_D), M["rib"], bevel=0.02, segs=2)
    return o


for k, y in enumerate(RIBS):
    rib(f"rib_{k}", y)

BAYS = [(HALL_Y0, RIBS[0] - RIB_W / 2)] + [(a + RIB_W / 2, b - RIB_W / 2) for a, b in zip(RIBS, RIBS[1:])] + \
       [(RIBS[-1] + RIB_W / 2, HALL_Y1)]
for bi, (ya, yb) in enumerate(BAYS):
    mid_in = Vector((0, (ya + yb) / 2, 3.0))
    for side in (1, -1):
        s = "r" if side > 0 else "l"
        # the low wall under the window, behind the planter
        face_toward(mesh_from(f"hall_lowwall_{s}{bi}", [P3(0, side, ya), P3(0, side, yb), P3(1, side, yb), P3(1, side, ya)],
                              [(0, 1, 2, 3)], M["lowwall"]), mid_in)
        # the window's frame: a ring in the window's plane round the opening, its corners rounded off by a chamfer, and a
        # thin blue line at its inner edge
        A, B = Vector(PROFILE[1]), Vector(PROFILE[2]); up = (B - A).normalized()
        n = inward(1, side)

        def wpt(u, y, inset=0.0):
            q = A + up * u
            return Vector((side * q.x + n.x * inset, y, q.y + n.y * inset))
        L = (B - A).length; f = 0.11; ch = 0.12
        outer = [wpt(0, ya), wpt(0, yb), wpt(L, yb), wpt(L, ya)]
        # the inner octagon walked round: bottom edge (u=f) from ya to yb, then up the yb side, the top edge back, down
        inner = [wpt(f, ya + f + ch), wpt(f, yb - f - ch), wpt(f + ch, yb - f), wpt(L - f - ch, yb - f), wpt(L - f, yb - f - ch),
                 wpt(L - f, ya + f + ch), wpt(L - f - ch, ya + f), wpt(f + ch, ya + f)]
        verts = outer + inner
        faces = [(0, 1, 5, 4), (1, 2, 8, 7, 6, 5), (2, 3, 10, 9, 8), (3, 0, 4, 11, 10)]
        face_toward(mesh_from(f"hall_winframe_{s}{bi}", verts, faces, M["frame"]), mid_in)
        e = 0.018
        ring = inner + [inner[0]]
        ev, ef = [], []
        for k in range(8):
            p0, p1 = ring[k], ring[k + 1]
            q0, q1 = p0 + Vector((n.x, 0, n.y)) * 0.01, p1 + Vector((n.x, 0, n.y)) * 0.01
            dirv = (p1 - p0).normalized(); toward = (wpt(L / 2, (ya + yb) / 2) - (p0 + p1) / 2).normalized()
            base_i = len(ev)
            ev += [q0, q1, q1 + toward * e, q0 + toward * e]
            ef.append((base_i, base_i + 1, base_i + 2, base_i + 3))
        face_toward(mesh_from(f"hall_winedge_{s}{bi}", ev, ef, M["edge"]), mid_in)
        # the soffit, window head to the glass's edge: lined panels; two warm lines along it, each with its light
        sv = []
        for i in range(2, len(PROFILE)):
            sv += [P3(i, side, ya), P3(i, side, yb)]
        sf = [(2 * k, 2 * k + 1, 2 * k + 3, 2 * k + 2) for k in range(len(PROFILE) - 3)]
        face_toward(mesh_from(f"room_soffit_{s}{bi}", sv, sf, M["soffit"]), mid_in)
        for (i0, i1) in ((4, 5),):
            p = (P3(i0, side, 0) + P3(i1, side, 0)) / 2; nn = (inward(i0, side) + inward(i1, side)).normalized()
            seglen = (yb - ya) - 0.3
            if seglen < 0.4: continue
            c = Vector((p.x + nn.x * 0.02, (ya + yb) / 2, p.z + nn.y * 0.02))
            tilt = math.degrees(math.atan2(nn.x, nn.y))  # the strip's thin side along the soffit's normal
            box(f"room_led_{s}{bi}_{i0}", tuple(c), (0.05, seglen, 0.012), M["led"], rot=(0, tilt, 0))
            area_light(f"soffit_line_{s}{bi}_{i0}", tuple(c + Vector((nn.x, 0, nn.y)) * 0.03), (0.05, seglen),
                       Vector((nn.x, 0, nn.y)), WARM, 30.0 * seglen, house)
# the ceiling glass's frame: beams along its edges and down its middle (the ribs' crossbars cross it)
for x in (-GLASS_X, 0.0, GLASS_X):
    box("room_glassbeam", (x, (HALL_Y0 + HALL_Y1) / 2, CEIL_Z - 0.06), (0.14 if x else 0.10, HALL_Y1 - HALL_Y0, 0.14), M["rib"],
        bevel=0.015, segs=2)


# ---------------------------------------------------------------- the ends: the screen wall (a corridor cut into it), the rear wall
def end_block(name, y0, y1):
    """A solid of the hall's section between y0 and y1, closing it."""
    outline = [(x, z) for x, z in PROFILE] + [(-x, z) for x, z in reversed(PROFILE)]
    bm = bmesh.new()
    f0 = bm.faces.new([bm.verts.new((x, y0, z)) for x, z in outline])
    r = bmesh.ops.extrude_face_region(bm, geom=[f0])
    for v in [g for g in r["geom"] if isinstance(g, bmesh.types.BMVert)]: v.co.y = y1
    bmesh.ops.recalc_face_normals(bm, faces=bm.faces)
    me = bpy.data.meshes.new(name); bm.to_mesh(me); bm.free()
    o = bpy.data.objects.new(name, me); room.objects.link(o); me.materials.append(M["wall"])
    return o


front = end_block("room_endwall", HALL_Y1, HALL_Y1 + 4.0)
CORR_END, CORR_SPRING, CORR_R = HALL_Y1 + 3.2, 2.3, (DOOR_X1 - DOOR_X0) / 2
cx = (DOOR_X0 + DOOR_X1) / 2
bpy.ops.mesh.primitive_cube_add(size=1, location=(cx, (HALL_Y1 - 0.2 + CORR_END) / 2, (0.02 + CORR_SPRING) / 2))
c1 = bpy.context.active_object; c1.scale = (DOOR_X1 - DOOR_X0, CORR_END - HALL_Y1 + 0.2, CORR_SPRING - 0.02)
bpy.ops.object.transform_apply(scale=True)
bpy.ops.mesh.primitive_cylinder_add(vertices=24, radius=CORR_R, depth=CORR_END - HALL_Y1 + 0.2,
                                    location=(cx, (HALL_Y1 - 0.2 + CORR_END) / 2, CORR_SPRING), rotation=(math.radians(90), 0, 0))
c2 = bpy.context.active_object
for c_ in (c1, c2):
    c_.select_set(False)
    md = front.modifiers.new("corridor", 'BOOLEAN'); md.operation = 'DIFFERENCE'; md.object = c_; md.solver = 'EXACT'
    _apply(front, [md]); bpy.data.objects.remove(c_)
mesh_from("floor_corridor", [(DOOR_X0, HALL_Y1, 0.021), (DOOR_X1, HALL_Y1, 0.021), (DOOR_X1, CORR_END, 0.021), (DOOR_X0, CORR_END, 0.021)],
          [(0, 1, 2, 3)], M["floor"])
for k, yy in enumerate((HALL_Y1 + 0.05, HALL_Y1 + 1.0, HALL_Y1 + 2.0, HALL_Y1 + 3.0)):
    # bronze arches down the corridor, a lamp at each
    bpy.ops.mesh.primitive_torus_add(major_radius=CORR_R - 0.04, minor_radius=0.04, major_segments=32, minor_segments=8,
                                     location=(cx, yy, CORR_SPRING), rotation=(math.radians(90), 0, 0))
    t = bpy.context.active_object; t.name = f"hall_corr_arch_{k}"
    bpy.ops.object.mode_set(mode='EDIT'); bm = bmesh.from_edit_mesh(t.data)
    bmesh.ops.delete(bm, geom=[v for v in bm.verts if v.co.z < -0.01], context='VERTS'); bmesh.update_edit_mesh(t.data)
    bpy.ops.object.mode_set(mode='OBJECT'); t.data.materials.append(M["bronze"]); _link(t, room)
    for sx in (-1, 1):
        box(f"hall_corr_jamb_{k}", (cx + sx * (CORR_R - 0.04), yy, CORR_SPRING / 2), (0.08, 0.08, CORR_SPRING), M["bronze"])
    if k:
        box(f"hall_corr_lamp_{k}", (DOOR_X1 - 0.03, yy + 0.5, 1.9), (0.03, 0.06, 0.3), M["sconce"])
        point_light(f"corridor_lamp_{k}", (DOOR_X1 - 0.12, yy + 0.5, 1.9), WARM, 25.0, 0.04, house)
box("hall_corr_door", (cx, CORR_END - 0.02, CORR_SPRING / 2 + 0.3), (DOOR_X1 - DOOR_X0 - 0.1, 0.04, CORR_SPRING + 0.4), M["frame"])
# the screen: a dark panel with rounded corners proud of the wall, the face in it, a fluted console under it with a warm line
SX, SZ = SCREEN_W + 0.7, SCREEN_H + 0.8
box("hall_screen_panel", (0, HALL_Y1 - 0.06, SCREEN_FOOT + SCREEN_H / 2), (SX, 0.12, SZ), M["frame"], bevel=0.25, segs=6)
box("hall_screen_face", (0, SCREEN_FACE_Y + 0.01, SCREEN_FOOT + SCREEN_H / 2), (SCREEN_W, 0.02, SCREEN_H), M["screen"])
box("hall_console", (0, HALL_Y1 - 0.4, 0.22), (5.2, 0.55, 0.44), M["wall"], bevel=0.02, segs=2)
box("hall_console_led", (0, HALL_Y1 - 0.69, 0.012), (5.1, 0.02, 0.012), M["led"])
area_light("console_line", (0, HALL_Y1 - 0.72, 0.02), (5.0, 0.04), Vector((0, -0.3, -1)), WARM, 30.0, house, along=(1, 0, 0))
# the rear wall: a closed sliding door in a bronze frame lined with light, a lamp either side
back = end_block("room_rearwall", HALL_Y0, HALL_Y0 - 3.0)
box("hall_rear_door_l", (-0.52, HALL_Y0 + 0.03, 1.3), (1.02, 0.04, 2.6), M["frame"], bevel=0.01, segs=1)
box("hall_rear_door_r", (0.52, HALL_Y0 + 0.03, 1.3), (1.02, 0.04, 2.6), M["frame"], bevel=0.01, segs=1)
for (c, s) in [((0, HALL_Y0 + 0.05, 2.66), (2.3, 0.06, 0.1)), ((-1.12, HALL_Y0 + 0.05, 1.33), (0.1, 0.06, 2.66)),
               ((1.12, HALL_Y0 + 0.05, 1.33), (0.1, 0.06, 2.66))]:
    box("hall_rear_doorframe", c, s, M["bronze"])
for (c, s) in [((0, HALL_Y0 + 0.085, 2.6), (2.1, 0.01, 0.02)), ((-1.06, HALL_Y0 + 0.085, 1.3), (0.02, 0.01, 2.55)),
               ((1.06, HALL_Y0 + 0.085, 1.3), (0.02, 0.01, 2.55))]:
    box("hall_rear_led", c, s, M["led"])
area_light("rear_door_line", (0, HALL_Y0 + 0.15, 2.62), (2.1, 0.05), Vector((0, 1, -0.6)), WARM, 30.0, house, along=(1, 0, 0))
for sx in (-1, 1):
    box(f"hall_rear_lamp_{sx}", (sx * 2.2, HALL_Y0 + 0.04, 2.2), (0.08, 0.05, 0.34), M["sconce"])
    point_light(f"rear_lamp_{sx}", (sx * 2.2, HALL_Y0 + 0.18, 2.2), WARM, 25.0, 0.04, house)

# ---------------------------------------------------------------- the floor's inlay: a dark line winding down the hall
pts = [Vector(p) for p in ((0.55, HALL_Y1 - 0.7, 0), (0.62, 9.6, 0), (0.15, 7.2, 0), (-0.45, 4.6, 0), (-0.35, 2.4, 0), (0.12, 0.8, 0),
                            (0.18, -0.2, 0))]


def catmull(ps, n):
    out = []
    for i in range(len(ps) - 1):
        p0 = ps[max(0, i - 1)]; p1 = ps[i]; p2 = ps[i + 1]; p3 = ps[min(len(ps) - 1, i + 2)]
        for k in range(n):
            t = k / n
            out.append(0.5 * ((2 * p1) + (-p0 + p2) * t + (2 * p0 - 5 * p1 + 4 * p2 - p3) * t * t + (-p0 + 3 * p1 - 3 * p2 + p3) * t ** 3))
    out.append(ps[-1]); return out


line = catmull(pts, 24); iv, ifc = [], []
for k, p in enumerate(line):
    d = (line[min(k + 1, len(line) - 1)] - line[max(k - 1, 0)]).normalized(); sdir = Vector((d.y, -d.x, 0)) * 0.045
    iv += [p - sdir + Vector((0, 0, 0.003)), p + sdir + Vector((0, 0, 0.003))]
    if k: ifc.append((2 * k - 2, 2 * k - 1, 2 * k + 1, 2 * k))
face_toward(mesh_from("floor_inlay", iv, ifc, M["inlay"]), (0, 0, 5))

# ---------------------------------------------------------------- planters and their plants, uplit
PLANTS, PLANT_LODS = {}, {}


def plant_pieces(scan):
    """The plants of a Poly Haven scan in ASSETS, {letter: mesh}: a scan holds several side by side (…_a, …_b; the
    pachira's bark and leaves of one tree share a letter), each upright here with its origin at the middle of its foot.
    The faces its Alpha map cuts away are dropped — the leaves keep their own outline but draw opaque (alpha-tested
    foliage costs the headset dearly) — and the alpha left out of the materials."""
    if scan in PLANTS: return PLANTS[scan]
    before = set(bpy.data.objects)
    bpy.ops.import_scene.gltf(filepath=os.path.join(ASSETS, scan, f"{scan}_2k.gltf"))
    new = [o for o in bpy.data.objects if o not in before]
    ap = os.path.join(ASSETS, scan, "textures", f"{scan}_alpha_2k.jpg")
    A = None
    if os.path.exists(ap):
        im = bpy.data.images.load(ap); w, h = im.size
        A = np.array(im.pixels[:], dtype=np.float32).reshape(h, w, 4)[:, :, 0]
    by = {}
    for o in new:
        if o.type != 'MESH': continue
        me = o.data.copy(); me.transform(o.matrix_world)
        if A is not None:
            bm = bmesh.new(); bm.from_mesh(me); uvl = bm.loops.layers.uv[0]
            gone = []
            for f in bm.faces:
                u = sum(l[uvl].uv[0] for l in f.loops) / len(f.loops); v = sum(l[uvl].uv[1] for l in f.loops) / len(f.loops)
                if A[min(h - 1, max(0, int(v % 1.0 * h))), min(w - 1, max(0, int(u % 1.0 * w)))] < 0.5: gone.append(f)
            bmesh.ops.delete(bm, geom=gone, context='FACES'); bm.to_mesh(me); bm.free()
        by.setdefault(o.name.rsplit("_", 1)[1], []).append(me)
    for o in new: bpy.data.objects.remove(o)
    for m in {m for ms in by.values() for me in ms for m in me.materials if m is not None and m.use_nodes}:
        bsdf = m.node_tree.nodes.get("Principled BSDF")
        if bsdf is None: continue
        for l in list(bsdf.inputs["Alpha"].links): m.node_tree.links.remove(l)
        bsdf.inputs["Alpha"].default_value = 1.0; m.blend_method = 'OPAQUE'
    out = {}
    for k, ms in by.items():
        objs = [bpy.data.objects.new(f"tmp_{k}", me) for me in ms]
        for o in objs: scene.collection.objects.link(o)
        o = join(f"tmp_{k}", objs) if len(objs) > 1 else objs[0]
        me = o.data; xs, ys, zs = ([v.co[i] for v in me.vertices] for i in range(3))
        me.transform(Matrix.Translation(Vector((-(min(xs) + max(xs)) / 2, -(min(ys) + max(ys)) / 2, -min(zs)))))
        bpy.data.objects.remove(o); out[k] = me
    PLANTS[scan] = out
    return out


def plant(name, scan, letter, foot, yaw, scale, tris):
    """One of a scan's plants, decimated to about `tris` triangles (its UVs, so its maps, kept), turned `yaw` degrees,
    scaled, its foot `foot`; every face turned to the seat's eye — the leaves are single sheets and the drawing culls their
    backs, so each shows the side the viewer sees."""
    d = math.hypot(foot[0] - SEAT[0], foot[1] - SEAT[1])
    tris = int(tris * min(1.0, max(0.35, (5.5 / d) ** 2)))
    key = (scan, letter, tris)
    if key not in PLANT_LODS:
        src = plant_pieces(scan)[letter]
        o = bpy.data.objects.new("lod", src); scene.collection.objects.link(o)
        n = sum(len(p_.vertices) - 2 for p_ in src.polygons)
        if tris < n: o.modifiers.new("lod", 'DECIMATE').ratio = tris / n
        dg = bpy.context.evaluated_depsgraph_get(); dg.update()
        PLANT_LODS[key] = bpy.data.meshes.new_from_object(o.evaluated_get(dg))
        bpy.data.objects.remove(o)
    me = PLANT_LODS[key].copy()
    me.transform(Matrix.Translation(Vector(foot)) @ Matrix.Rotation(math.radians(yaw), 4, 'Z') @ Matrix.Scale(scale, 4))
    o = bpy.data.objects.new(name, me); room.objects.link(o)
    return face_toward(o, (SEAT[0], SEAT[1], SEAT[2] + EYE))


# Maximum triangles per plant at its nearest seat distance; plant() reduces these towards the ends of the hall.
# Both planters are drawn again by the house and screen-light layers, so keep their combined mesh near the old 100k.
TREES = [("pachira_aquatica_01", "d", 2850), ("pachira_aquatica_01", "c", 2350)]
BACK = [("anthurium_botany_01", "a", 2000), ("anthurium_botany_01", "b", 1800), ("calathea_orbifolia_01", "a", 1000)]
FRONT = [("calathea_orbifolia_01", "a", 900), ("calathea_orbifolia_01", "b", 600), ("calathea_orbifolia_01", "c", 600),
         ("anthurium_botany_01", "c", 1200), ("anthurium_botany_01", "d", 1000)]
SOIL_Z = PLANTER_H + 0.0


def planting(side):
    s = "r" if side > 0 else "l"; n = 0
    y = PLANTER_Y0 + 0.9
    while y < PLANTER_Y1 - 0.5:                       # the trees, every ~3.4 m, by the glass
        sc, lt, tr = TREES[n % 2]
        plant(f"leaf_plant_{s}{n}", sc, lt, (side * random.uniform(5.0, 5.2), y, SOIL_Z - 0.02), random.uniform(0, 360),
              random.uniform(1.05, 1.25) if lt == "d" else random.uniform(1.25, 1.45), tr)
        n += 1; y += random.uniform(3.1, 3.7)
    y = PLANTER_Y0 + 0.45
    while y < PLANTER_Y1 - 0.4:                       # the back row between them
        sc, lt, tr = random.choice(BACK)
        plant(f"leaf_plant_{s}{n}", sc, lt, (side * random.uniform(4.85, 5.15), y, SOIL_Z - 0.01), random.uniform(0, 360),
              random.uniform(1.0, 1.25) if sc.startswith("anth") else random.uniform(1.3, 1.6), tr)
        n += 1; y += random.uniform(0.7, 0.9)
    y = PLANTER_Y0 + 0.3
    while y < PLANTER_Y1 - 0.25:                      # the front row, low, along the lit edge
        sc, lt, tr = random.choice(FRONT)
        plant(f"leaf_plant_{s}{n}", sc, lt, (side * random.uniform(4.25, 4.5), y, SOIL_Z - 0.01), random.uniform(0, 360),
              random.uniform(1.2, 1.5), tr)
        n += 1; y += random.uniform(0.45, 0.6)


for side in (1, -1):
    s = "r" if side > 0 else "l"
    xm = side * (PLANTER_X0 + PLANTER_X1) / 2
    box(f"hall_planter_{s}", (xm, (PLANTER_Y0 + PLANTER_Y1) / 2, PLANTER_H / 2), (PLANTER_X1 - PLANTER_X0, PLANTER_Y1 - PLANTER_Y0, PLANTER_H),
        M["planter"], bevel=0.015, segs=2)
    box(f"hall_soil_{s}", (xm, (PLANTER_Y0 + PLANTER_Y1) / 2, PLANTER_H + 0.005), (PLANTER_X1 - PLANTER_X0 - 0.08, PLANTER_Y1 - PLANTER_Y0 - 0.08, 0.01),
        M["soil"])
    box(f"hall_planter_led_{s}", (side * (PLANTER_X0 - 0.005), (PLANTER_Y0 + PLANTER_Y1) / 2, PLANTER_H - 0.03), (0.01, PLANTER_Y1 - PLANTER_Y0 - 0.1, 0.012),
        M["led"])
    planting(side)
    for k in range(int((PLANTER_Y1 - PLANTER_Y0) / 1.6)):
        yy = PLANTER_Y0 + 0.8 + k * 1.6
        area_light(f"uplight_{s}{k}", (xm - side * 0.3, yy, PLANTER_H + 0.03), (0.6, 0.5), Vector((side * 0.25, 0, 1)), WARM, 28.0, house)

# ---------------------------------------------------------------- the lamps on the ribs
for k, y in enumerate(RIBS):
    for side in (1, -1):
        t = (2.5 - PROFILE[1][1]) / (PROFILE[2][1] - PROFILE[1][1])
        p = Vector(PROFILE[1]).lerp(Vector(PROFILE[2]), t); n = inward(1, side)
        at = Vector((side * p.x + n.x * (RIB_D + 0.02), y, p.y + n.y * (RIB_D + 0.02)))
        box(f"room_sconce_body_{k}{side}", tuple(at), (0.06, 0.10, 0.36), M["rib"], bevel=0.01, segs=2)
        box(f"room_sconce_glass_{k}{side}", tuple(at + Vector((n.x, 0, n.y)) * 0.035), (0.012, 0.05, 0.26), M["sconce"])
        point_light(f"sconce_{k}{side}", tuple(at + Vector((n.x, 0, n.y)) * 0.12), WARM, 22.0, 0.05, house)


# ---------------------------------------------------------------- near: the chairs, rugs, tables and candles
def club_chair(name, c, yaw, arms=2, rest=2):
    """A velvet club chair: a deep base, thick rounded arms, a low back, a seat cushion and a back cushion, small dark feet.
    `c` the seat's middle on the floor; it faces +y turned by `yaw`; `arms` / `rest` the subdivision of its parts."""
    T = Matrix.Translation(Vector(c)) @ Matrix.Rotation(math.radians(yaw), 4, 'Z')
    W, D = 0.98, 0.92

    def at(x, y, z): return tuple(T @ Vector((x, y, z)))
    parts = [box(f"{name}_base", at(0, 0, 0.25), (W, D, 0.38), M["velvet"], bevel=0.06, soft=rest, rot=(0, 0, yaw))]
    for sx in (-1, 1):
        parts.append(box(f"{name}_arm", at(sx * (W / 2 - 0.1), -0.01, 0.38), (0.21, D + 0.02, 0.62), M["velvet"], bevel=0.08, soft=arms,
                         rot=(0, 0, yaw)))
    parts.append(box(f"{name}_back", at(0, -D / 2 + 0.12, 0.44), (W - 0.02, 0.24, 0.80), M["velvet"], bevel=0.08, soft=rest,
                     rot=(0, 0, yaw)))
    parts.append(box(f"{name}_seat", at(0, 0.06, 0.48), (W - 0.42, D - 0.30, 0.15), M["velvet"], bevel=0.06, soft=rest, rot=(0, 0, yaw)))
    parts.append(box(f"{name}_cushion", at(0, -D / 2 + 0.33, 0.74), (W - 0.48, 0.18, 0.40), M["pillow"], bevel=0.07, soft=rest,
                     rot=(-10, 0, yaw)))
    for sx in (-1, 1):
        for sy in (-1, 1):
            parts.append(box(f"{name}_foot", at(sx * (W / 2 - 0.08), sy * (D / 2 - 0.08), 0.03), (0.05, 0.05, 0.06), M["steel"],
                             bevel=0.008, segs=2, rot=(0, 0, yaw)))
    return parts


def rug(name, c, rx, ry):
    n = 72; C = Vector(c)
    vs = [C + Vector((0, 0, 0.012))] + [C + Vector((rx * math.cos(2 * math.pi * k / n), ry * math.sin(2 * math.pi * k / n), 0.012))
                                        for k in range(n)]
    pile = face_toward(mesh_from(f"{name}_pile", vs, [(0, k + 1, (k + 1) % n + 1) for k in range(n)], M["rug"]), C + Vector((0, 0, 3)))
    pile.data.uv_layers.new(name="UVMap")                 # the weave in plan, across the hall
    for li, lp in enumerate(pile.data.loops):
        co = pile.data.vertices[lp.vertex_index].co; pile.data.uv_layers[0].data[li].uv = (co.y, co.x)
    ov = []
    for k in range(n):
        a = 2 * math.pi * k / n
        for (sx, z) in ((1.0, 0.014), (1.04, 0.014), (1.04, 0.0)):
            ov.append(C + Vector((rx * sx * math.cos(a), ry * sx * math.sin(a), z)))
    of = []
    for k in range(n):
        a, b_ = 3 * k, 3 * ((k + 1) % n)
        of += [(a, b_, b_ + 1, a + 1), (a + 1, b_ + 1, b_ + 2, a + 2)]
    o = mesh_from(f"{name}_rim", ov, of, M["bronze"])
    face_toward(o, C + Vector((0, 0, 3)))


def side_table(name, c):
    """A pedestal side table: two smoked-glass discs on bronze rims, a dark steel column, a round foot; a candle in a brass cup."""
    C = Vector(c)
    cyl(f"{name}_foot", C + Vector((0, 0, 0.015)), 0.24, 0.03, M["steel"], verts=48)
    cyl(f"{name}_column", C + Vector((0, 0, 0.26)), 0.13, 0.46, M["steel"], verts=48)
    for (dx, dy, r, z) in ((0, 0, 0.33, 0.51), (0.18, 0.16, 0.22, 0.53)):
        cyl(f"{name}_rim", C + Vector((dx, dy, z)), r, 0.03, M["bronze"], verts=64)
        cyl(f"{name}_top", C + Vector((dx, dy, z + 0.016)), r - 0.015, 0.004, M["tabletop"], verts=64)
    K = C + Vector((-0.06, -0.05, 0.525))
    cyl(f"{name}_cup", K + Vector((0, 0, 0.035)), 0.05, 0.07, M["brass"], verts=32, cap=False)
    cyl(f"{name}_cup_base", K + Vector((0, 0, 0.003)), 0.05, 0.006, M["brass"], verts=32)
    cyl(f"{name}_wax", K + Vector((0, 0, 0.03)), 0.042, 0.05, M["wax"], verts=32)
    bpy.ops.mesh.primitive_uv_sphere_add(segments=12, ring_count=8, radius=1.0, location=(0, 0, 0))
    fl = bpy.context.active_object; fl.name = f"{name}_flame"; fl.scale = (0.008, 0.008, 0.02)
    bpy.ops.object.transform_apply(scale=True); fl.location = K + Vector((0, 0, 0.075)); fl.data.materials.append(M["flame"]); _link(fl, room)
    point_light(f"candle_{name}", tuple(K + Vector((0, 0, 0.09))), (1.0, 0.58, 0.25), 3.5, 0.01, house)


club_chair("near_chair_mine", (0.0, -0.3, 0.0), 0, arms=3, rest=2)
for side in (1, -1):
    s = "r" if side > 0 else "l"
    # The outside arm used to cross the planter's inner face by ~0.28 m. Inset the pair, keep its spacing, and
    # fit the rug inside the planter too. The table sits slightly forward, clear of the inside chair and aisle.
    rug(f"near_rug_{s}", (side * 2.25, 4.75, 0), 1.55, 1.45)
    inside = club_chair(f"near_chair_{s}_in", (side * 1.9, 4.35, 0.0), 0)
    outside = club_chair(f"near_chair_{s}_out", (side * 3.12, 4.6, 0.0), side * -4)
    side_table(f"near_table_{s}", (side * 1.05, 5.25, 0.0))
    # Check the final bevelled/subdivided geometry, including the rotated outer arm, before any expensive bake.
    outer_edge = max(side * (o.matrix_world @ v.co).x for o in outside for v in o.data.vertices)
    assert PLANTER_X0 - outer_edge >= 0.30, f"{s} chair too close to planter: {PLANTER_X0 - outer_edge:.3f} m"
    inner_edge = max(side * (o.matrix_world @ v.co).x for o in inside for v in o.data.vertices)
    outer_inner_edge = min(side * (o.matrix_world @ v.co).x for o in outside for v in o.data.vertices)
    assert outer_inner_edge - inner_edge >= 0.15, f"{s} chairs overlap or leave too little clearance"

# ---------------------------------------------------------------- outside: an orbital passenger train across the Earth
def orbital_train():
    """Eight linked cars on the old station arm's sightline, ~100 m from the seat.

    Broad pearl panels, a navy window belt and real gaps read at that distance. All detail is in the far/base atlas:
    its sunlight and Earth fill are authored into the surface normals, independent of the lounge's lamps. It neither
    illuminates nor shadows the interior, so a far-only rebake leaves the room's existing lightmaps valid.
    """
    origin = Vector((-90.0, 30.0, 6.0))
    forward = (Vector((-150.0, 160.0, 20.0)) - origin).normalized()
    side = Vector((forward.y, -forward.x, 0)).normalized()
    up = side.cross(forward).normalized()
    basis = Matrix((side, forward, up)).transposed()

    def P(x, t, z): return origin + basis @ Vector((x, t, z))

    def finish(o):
        o.visible_diffuse = False; o.visible_glossy = False; o.visible_shadow = False
        o.visible_transmission = False
        return o

    def paint(name, colour):
        m = plain(name, (0, 0, 0), emission=colour, base_emission=1.0)
        nt, b = _nodes(m)
        normal = nt.nodes.new("ShaderNodeNewGeometry").outputs["Normal"]
        key = nt.nodes.new("ShaderNodeVectorMath"); key.operation = 'DOT_PRODUCT'
        key.inputs[1].default_value = (side * 0.5 - forward * 0.3 + up).normalized()
        nt.links.new(normal, key.inputs[0])
        shade = _math(nt, 'ADD', 0.20, _math(nt, 'MULTIPLY', _math(nt, 'MAXIMUM', key.outputs["Value"], 0.0), 0.80))
        tint = _mix(nt, shade, (0, 0, 0), colour)
        nt.links.new(tint, b.inputs["Emission Color"])
        return m

    pearl = paint("train_pearl", (0.66, 0.75, 0.83))
    navy = paint("train_navy", (0.022, 0.055, 0.105))
    steel = paint("train_joint", (0.11, 0.16, 0.22))
    brass = paint("train_champagne", (0.64, 0.39, 0.13))
    glass = paint("train_cockpit", (0.045, 0.25, 0.38))
    windows = plain("train_windows", (0, 0, 0), emission=(0.82, 0.52, 0.24), base_emission=0.85)
    cool = plain("train_navigation", (0, 0, 0), emission=(0.13, 0.65, 1.0), base_emission=1.1)

    def part(name, c, size, mat, bevel=0.0):
        o = box("far_train_" + name, (0, 0, 0), size, mat, bevel=bevel)
        o.matrix_world = basis.to_4x4(); o.location = P(*c)
        return finish(o)

    # Faceted round shoulders with flat sides: windows lie outside the skin rather than inside a cylinder.
    section = [(2.2, -1.75), (2.6, -1.12), (2.6, -0.55), (2.6, 0.8), (2.35, 1.45), (1.45, 1.95),
               (-1.45, 1.95), (-2.35, 1.45), (-2.6, 0.8), (-2.6, -0.55), (-2.6, -1.12), (-2.2, -1.75)]

    def hull(name, rings, cockpit=False):
        vs = [P(x * scale, t, z * scale) for t, scale in rings for x, z in section]
        n = len(section); faces = []; mats = []
        for k in range(len(rings) - 1):
            for j in range(n):
                faces.append((k*n+j, k*n+(j+1)%n, (k+1)*n+(j+1)%n, (k+1)*n+j))
                # Continuous dark glazing belt; the nose's upper facets form a wraparound cockpit.
                mats.append(2 if cockpit and k in (1, 2) and 2 <= j <= 8 else 1 if j in (2, 8, 11) else 0)
        faces += [tuple(reversed(range(n))), tuple((len(rings)-1)*n+j for j in range(n))]
        mats += [0, 0]
        o = mesh_from("far_train_" + name, vs, faces, pearl)
        o.data.materials.append(navy); o.data.materials.append(glass)
        for f, mi in zip(o.data.polygons, mats): f.material_index = mi
        bm = bmesh.new(); bm.from_mesh(o.data); bmesh.ops.recalc_face_normals(bm, faces=bm.faces)
        bm.to_mesh(o.data); bm.free()
        return finish(o)

    def panel(name, s, x, t, z, length, height, mat, corner=0.10):
        # Eight-vertex rounded rectangle, deliberately sized for the distant seated view.
        w, h = length/2, height/2; c = min(corner, w, h)
        points = [(-w+c, -h), (w-c, -h), (w, -h+c), (w, h-c),
                  (w-c, h), (-w+c, h), (-w, h-c), (-w, -h+c)]
        o = mesh_from("far_train_" + name, [P(s*x, t+y, z+v) for y, v in points], [tuple(range(8))], mat)
        face_toward(o, P(s*(x+2), t, z))
        return finish(o)

    hull("cab", [(0.0, 0.28), (2.8, 0.54), (6.6, 0.92), (8.2, 1.0), (18.6, 1.0), (19.6, 0.85)], cockpit=True)
    cars = [(8.2, 19.6)] + [(22.0 + k*17.0, 36.0 + k*17.0) for k in range(7)]
    for i, (start, end) in enumerate(cars):
        if i:
            hull(f"car_{i}", [(start, 0.86), (start+0.9, 1.0), (end-0.9, 1.0), (end, 0.86)])
            previous = cars[i-1][1]
            # Narrow articulated couplers leave daylight between the pearl shells.
            part("coupler", (0, (previous+start)/2, -0.20), (3.0, start-previous+0.4, 2.3), steel, 0.28)
            for t in (previous+0.30, start-0.30):
                part("joint_rib", (0, t, -0.20), (3.55, 0.22, 2.65), navy, 0.24)
        centre = (start+end)/2
        for s in (-1, 1):
            panel("lower_trim", s, 2.64, centre, -0.68, end-start-2.2, 0.13, brass, 0.05)
            panel("upper_light", s, 2.62, centre, 0.77, end-start-2.6, 0.08, cool, 0.03)
            count = 4 if i == 0 else 5
            for j in range(count):
                t = centre + (j-(count-1)/2)*2.05
                panel("window_rim", s, 2.64, t, 0.12, 1.63, 0.96, steel, 0.16)
                panel("window", s, 2.67, t, 0.12, 1.35, 0.72, windows, 0.13)
            # End-door seam and a small brass identification bar, readable as a rhythm down the train.
            panel("end_seam", s, 2.62, end-1.15, -0.12, 0.10, 2.05, steel, 0.02)
            panel("mark", s, 2.63, start+1.30, -0.99, 0.52, 0.24, brass, 0.04)
        part("keel", (0, centre, -1.77), (2.6, end-start-2.0, 0.44), navy, 0.18)
        part("roof_panel", (0, centre, 1.98), (1.9, end-start-3.0, 0.12), steel, 0.05)
        for t in (centre-2.0, centre, centre+2.0):
            part("roof_rib", (0, t, 2.06), (2.05, 0.16, 0.12), pearl, 0.04)

    # Swept fins and compact side thrusters break the old uninterrupted pipe silhouette.
    for t in (12.0, 130.0):
        for s in (-1, 1):
            outline = [(2.15, -1.8, -0.9), (4.0, 1.2, -1.25), (4.0, 3.0, -1.25), (2.15, 2.0, -0.9)]
            fin = mesh_from("far_train_fin", [P(s*x, t+y, z+dz) for dz in (-0.04, 0.04) for x, y, z in outline],
                [(0, 3, 2, 1), (4, 5, 6, 7)] + [(j, (j+1)%4, (j+1)%4+4, j+4) for j in range(4)], pearl)
            bm = bmesh.new(); bm.from_mesh(fin.data); bmesh.ops.recalc_face_normals(bm, faces=bm.faces)
            bm.to_mesh(fin.data); bm.free()
            finish(fin)
            part("thruster", (s*3.6, t+1.8, -1.25), (0.80, 3.8, 0.85), navy, 0.30)
            part("thruster_light", (s*3.6, t+3.72, -1.25), (0.52, 0.06, 0.52), cool, 0.18)
    for s in (-1, 1):
        part("headlight", (s*0.60, -0.06, 0.07), (0.36, 0.07, 0.26), cool, 0.08)


orbital_train()

# the sky: a dome far beyond everything, emitting (base layer); it neither lights nor shadows the place
bpy.ops.mesh.primitive_uv_sphere_add(segments=64, ring_count=32, radius=3000.0, location=(0, 0, 0))
sky = bpy.context.active_object; sky.name = "sky_dome"
bpy.ops.object.mode_set(mode='EDIT'); bpy.ops.mesh.select_all(action='SELECT'); bpy.ops.mesh.flip_normals()
bpy.ops.object.mode_set(mode='OBJECT')
sky.data.materials.append(M["sky"]); _link(sky, room)
for p in sky.data.polygons: p.use_smooth = True
sky.visible_diffuse = False; sky.visible_glossy = False; sky.visible_shadow = False; sky.visible_transmission = False

# ---------------------------------------------------------------- lights
# base: the Earth's light through the left windows, cool, broad, from low on the left; the stars' faint all round
earth_at = Vector((-26.0, 9.0, -2.0))
area_light("earthlight", tuple(earth_at), (40.0, 30.0), Vector((0, 3, 3)) - earth_at, (0.55, 0.72, 1.0), 16000.0, base, along=(0, 0, 1))
world = bpy.data.worlds.new("space"); world.use_nodes = True
bg = world.node_tree.nodes["Background"]; bg.inputs["Color"].default_value = (0.010, 0.014, 0.030, 1); bg.inputs["Strength"].default_value = 1.0
scene.world = world
# ambc: the screen's light, as if white
area_light("screen_glow_ambc", (0, SCREEN_FACE_Y - 0.03, SCREEN_FOOT + SCREEN_H / 2), (SCREEN_W, SCREEN_H), Vector((0, -1, 0)),
           (1.0, 1.0, 1.0), math.pi * SCREEN_W * SCREEN_H, ambc, along=(0, 0, 1))
scene["film_tint"] = (0.62, 0.70, 0.85)
scene["film_mean"] = 0.35


# ---------------------------------------------------------------- effects: the lamps' halos (not baked; drawn added, unlit)
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
    k = max(0.0, 1.0 - r) ** 2.4 * 0.8
    return (k, k * 0.66, k * 0.38)


halo_mat = fx_material("fxh_halo", fx_texture("fx_halo_tex", 64, 64, _glow))
hbm = bmesh.new(); huv = hbm.loops.layers.uv.new("UVMap")
EYE_AT = Vector((SEAT[0], SEAT[1], SEAT[2] + EYE))


def halo(p, size):
    P = Vector(p); f = (EYE_AT - P); f.z = 0; f.normalize()
    side = Vector((f.y, -f.x, 0)) * size / 2; up = Vector((0, 0, size / 2))
    vs = [hbm.verts.new(P - side - up), hbm.verts.new(P + side - up), hbm.verts.new(P + side + up), hbm.verts.new(P - side + up)]
    face = hbm.faces.new(vs)
    for loop, uv in zip(face.loops, [(0, 0), (1, 0), (1, 1), (0, 1)]): loop[huv].uv = uv
    if face.normal.dot(EYE_AT - P) < 0: face.normal_flip()


for o in [o for o in house.objects if o.type == 'LIGHT' and o.name.startswith("sconce_")]:
    halo(o.location, 0.55)
hme = bpy.data.meshes.new("fxh_halo"); hbm.to_mesh(hme); hbm.free()
hob = bpy.data.objects.new("fxh_halo", hme); fx.objects.link(hob); hme.materials.append(halo_mat)

# ---------------------------------------------------------------- the seat's camera
cam_data = bpy.data.cameras.new("seat"); cam = bpy.data.objects.new("seat", cam_data); scene.collection.objects.link(cam)
cam.location = (SEAT[0], SEAT[1], SEAT[2] + EYE)
look = Vector(SCREEN_CENTER) - cam.location
cam.rotation_euler = (math.radians(90) + math.atan2(look.z, math.hypot(look.x, look.y)), 0, 0)
cam_data.sensor_fit = 'HORIZONTAL'; cam_data.angle = math.radians(100); cam_data.clip_start = 0.05; cam_data.clip_end = 6000
scene.camera = cam
scene["seat"] = SEAT; scene["eye"] = EYE; scene["step_base"] = 0.0
scene["screen"] = (SCREEN_CENTER[0], SCREEN_CENTER[1], SCREEN_CENTER[2], SCREEN_W, SCREEN_ASPECT)

if OUT:
    bpy.ops.wm.save_as_mainfile(filepath=OUT)
    meshes = [o for o in scene.objects if o.type == 'MESH']
    by = {}
    for o in meshes:
        g = o.name.split("_")[0]
        by[g] = by.get(g, 0) + sum(len(p.vertices) - 2 for p in o.data.polygons)
    print(f"LOUNGE saved {OUT}: {len(meshes)} meshes, {sum(by.values())} triangles {by}, "
          f"{len(house.objects)} house lights, {len(base.objects)} base lights")
