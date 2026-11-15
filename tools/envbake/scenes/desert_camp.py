"""
desert_camp.py — an open-air screen in the high desert at dusk (the user's references, lit and dark, 2026-10-23): a sandy
flat among Joshua trees and piles of rounded granite boulders, dry scrub, layered blue mountains under a sky going from the
sunset's orange to deep blue with the first stars; a frame screen on T-feet; a vintage travel trailer on the right — cream
over sage green, aluminium trim, its door open on a lit room — a folding table with a lantern, a green metal box, a step;
a small lantern by the rocks on the left. The viewer sits in a wooden armchair with a green canvas seat on a woven rug, a
terracotta drum table beside them with an enamel mug.

  Blender --background --factory-startup --python tools/envbake/scenes/desert_camp.py -- OUT.blend

baked with --groups "near=near_,gnear=gnear_,gmid@2048:base=gmid_,rocks=rock_,rocks2@2048:base=rockb_,
desert:base+house=desert_,leaves@4096/2:base+house=leaf_,camp=camp_,far:base=far_,sky:base=sky_" — the scrub's leaves and
the near Joshua trees' blades, thousands of tiny islands, on an atlas of their own with small margins; the far plain, the ranges and the sky in the base
layer alone, the film's light only on what is near it.

Blender axes: x right, y toward the screen, z up (glTF: x, z, -y). `SEAT` is the ground under the viewer, `EYE` their seated
eye height. Lights, as tools/envbake/bake_layers.py bakes them apart:
  base  — the dusk: the sky's light (the world), the afterglow low in the west ahead, and the sky dome and the far ridges
          themselves (emitting, "base_emission") — the dark reference;
  house — what the lit reference adds: the trailer's lamps and lit curtains, the two lanterns, and the warm light over the
          camp ("house_emission" glows with them);
  ambc  — the screen's light, in white (动态氛围光).
Near things — the chair, the rug's weave (real relief), the drum table, the mug — are modelled finely. The plants are few:
the Joshua trees (spiky crowns on forking limbs, shaggy with dead leaves) and low dry scrub. Sizes in metres.

The ground and the rocks are photographed scans (Poly Haven, CC0 — no credit owed): the desert floor is sandy_gravel_02,
the boulders namaqualand_boulder_02…06 and namaqualand_boulders_01, decimated by distance; the near chair's wood and canvas
teak_veneer and hessian_380, the drum table planter_pot_clay's clay (2026-10-25), over real-size UVs (uv_box: the grain along
each part). ASSETS is their folder, as
tools/envbake/polyhaven_get.py fetches it (the default list there is this scene's):

  Blender --background --factory-startup --python tools/envbake/scenes/desert_camp.py -- OUT.blend ASSETS

2026-10-24, the user: "太low-poly了，太不逼真了" — the first version's ground was one 700 m sheet (17 cm a texel under the
seat: a smooth plane), its rocks clay balls, its mountains flat cut-outs. Now the ground is tiles, finest round the seat (each
tile its own object, so bake_layers gives the near ones the texels), the rocks scans, the mountains lit ranges in the haze.
"""
import bpy, bmesh, math, sys, random, os
from mathutils import Vector, Matrix, Euler, noise
from mathutils.bvhtree import BVHTree

ARGS = sys.argv[sys.argv.index("--") + 1:] if "--" in sys.argv else []
OUT = ARGS[0] if ARGS else None
ASSETS = ARGS[1] if len(ARGS) > 1 else os.environ.get("AURA_ENV_ASSETS", "")
assert os.path.isdir(os.path.join(ASSETS, "sandy_gravel_02")), "ASSETS: the Poly Haven scans (tools/envbake/polyhaven_get.py)"
random.seed(37)

SEAT = (0.0, 0.0, 0.0)
EYE = 1.05
SCREEN_W, SCREEN_ASPECT = 4.6, 2.0
SCREEN_H = SCREEN_W / SCREEN_ASPECT
SCREEN_Y = 8.8
SCREEN_FOOT = 0.88
SCREEN_CENTER = (0.0, SCREEN_Y - 0.01, SCREEN_FOOT + SCREEN_H / 2)
WEST = Vector((-0.25, 1.0, 0.0)).normalized()      # where the sun has gone: ahead, a little left
WARM = (1.0, 0.62, 0.32)

bpy.ops.wm.read_factory_settings(use_empty=True)
scene = bpy.context.scene
house = bpy.data.collections.new("house"); scene.collection.children.link(house)
base = bpy.data.collections.new("base"); scene.collection.children.link(base)
room = bpy.data.collections.new("room"); scene.collection.children.link(room)
ambc = bpy.data.collections.new("ambc"); scene.collection.children.link(ambc)
fx = bpy.data.collections.new("fx"); scene.collection.children.link(fx)


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




def scan_ground(name, far=False):
    """The desert floor from a scan (Poly Haven's sandy_gravel_02, a 2.53 m tile): its colour, normals and roughness, laid
    twice at different turns and blended by a slow noise so the tile does not repeat, a little less saturated (the Mojave's
    sand is greyer than the Karoo's), and slowly varied in tone. `far`: the plain out to the mountains — darker specks of
    creosote and gravel at metres' scale, patches of pale wash, what the scan's grain cannot show from far off."""
    d = os.path.join(ASSETS, "sandy_gravel_02")

    def img(f, colour):
        i = bpy.data.images.load(os.path.join(d, f), check_existing=True)
        if not colour: i.colorspace_settings.name = "Non-Color"
        return i
    diff, nor, rgh = img("sandy_gravel_02_diff_4k.jpg", True), img("sandy_gravel_02_nor_gl_4k.jpg", False), \
        img("sandy_gravel_02_rough_4k.jpg", False)
    m = bpy.data.materials.new(name); nt, b = _nodes(m)
    tc = nt.nodes.new("ShaderNodeTexCoord")

    def lay(rot, off):
        mp = nt.nodes.new("ShaderNodeMapping"); mp.inputs["Scale"].default_value = (1 / 2.53, 1 / 2.53, 1)
        mp.inputs["Rotation"].default_value = (0, 0, rot); mp.inputs["Location"].default_value = off
        nt.links.new(tc.outputs["Object"], mp.inputs[0])
        out = []
        for im in (diff, nor, rgh):
            t = nt.nodes.new("ShaderNodeTexImage"); t.image = im; nt.links.new(mp.outputs[0], t.inputs[0]); out.append(t.outputs[0])
        return out
    a, b2 = lay(0.0, (0, 0, 0)), lay(0.61, (0.37, 0.71, 0))
    co = _coords(nt)
    mz = nt.nodes.new("ShaderNodeTexNoise"); mz.inputs["Scale"].default_value = 0.35; mz.inputs["Detail"].default_value = 3.0
    nt.links.new(co, mz.inputs["Vector"])
    mask = _ramp(nt, [(0.44, (0, 0, 0)), (0.56, (1, 1, 1))]); nt.links.new(mz.outputs["Fac"], mask.inputs[0])
    k = mask.outputs[0]
    col = _mix(nt, k, a[0], b2[0]); nrm = _mix(nt, k, a[1], b2[1]); rough = _mix(nt, k, a[2], b2[2])
    hs = nt.nodes.new("ShaderNodeHueSaturation"); hs.inputs["Saturation"].default_value = 0.82
    nt.links.new(col, hs.inputs["Color"]); col = hs.outputs[0]
    tz = nt.nodes.new("ShaderNodeTexNoise"); tz.inputs["Scale"].default_value = 0.08; tz.inputs["Detail"].default_value = 5.0
    nt.links.new(co, tz.inputs["Vector"])
    tone = _ramp(nt, [(0.3, (0.80, 0.80, 0.80)), (0.7, (1.08, 1.06, 1.03))]); nt.links.new(tz.outputs["Fac"], tone.inputs[0])
    col = _mix(nt, 1.0, col, tone.outputs[0], 'MULTIPLY')
    if far:
        for scale, keep, colour in ((0.35, 0.72, (0.10, 0.09, 0.07)), (1.3, 0.62, (0.16, 0.13, 0.10))):
            sp = nt.nodes.new("ShaderNodeTexVoronoi"); sp.inputs["Scale"].default_value = scale; nt.links.new(co, sp.inputs["Vector"])
            dot = _ramp(nt, [(0.0, (1, 1, 1)), (0.16, (0.8, 0.8, 0.8)), (0.22, (0, 0, 0))]); nt.links.new(sp.outputs["Distance"], dot.inputs[0])
            sc = nt.nodes.new("ShaderNodeSeparateColor"); nt.links.new(sp.outputs["Color"], sc.inputs[0])
            kp = _ramp(nt, [(keep, (0, 0, 0)), (keep + 0.01, (1, 1, 1))]); nt.links.new(sc.outputs[0], kp.inputs[0])
            col = _mix(nt, _math(nt, 'MULTIPLY', dot.outputs[0], kp.outputs[0]), col, colour)
        wz = nt.nodes.new("ShaderNodeTexNoise"); wz.inputs["Scale"].default_value = 0.02; wz.inputs["Detail"].default_value = 4.0
        nt.links.new(co, wz.inputs["Vector"])
        wash = _ramp(nt, [(0.55, (0, 0, 0)), (0.68, (0.35, 0.35, 0.35))]); nt.links.new(wz.outputs["Fac"], wash.inputs[0])
        col = _mix(nt, wash.outputs[0], col, (0.50, 0.42, 0.33))
        # the haze between the viewer and the plain, thickening with distance: the far plain fades into the ranges' feet
        # ("base_emission": the dusk's, in the base layer alone)
        tc2 = nt.nodes.new("ShaderNodeTexCoord"); off = nt.nodes.new("ShaderNodeVectorMath"); off.operation = 'SUBTRACT'
        off.inputs[1].default_value = (0.0, 3.0, 0.0); nt.links.new(tc2.outputs["Object"], off.inputs[0])
        sep = nt.nodes.new("ShaderNodeSeparateXYZ"); nt.links.new(off.outputs[0], sep.inputs[0])
        dist = _math(nt, 'SQRT', _math(nt, 'ADD', _math(nt, 'MULTIPLY', sep.outputs["X"], sep.outputs["X"]),
                                       _math(nt, 'MULTIPLY', sep.outputs["Y"], sep.outputs["Y"])))
        mr = nt.nodes.new("ShaderNodeMapRange"); mr.inputs["From Min"].default_value = 60.0; mr.inputs["From Max"].default_value = 700.0
        nt.links.new(dist, mr.inputs["Value"])
        hz = _ramp(nt, [(0.0, (0.0, 0.0, 0.0)), (0.35, (0.022, 0.026, 0.062)), (1.0, (0.050, 0.058, 0.140))])
        nt.links.new(mr.outputs[0], hz.inputs[0]); nt.links.new(hz.outputs[0], b.inputs["Emission Color"])
        b.inputs["Emission Strength"].default_value = 0.0
        m["base_emission"] = 1.0
    nt.links.new(col, b.inputs["Base Color"])
    nt.links.new(rough, b.inputs["Roughness"])
    nm = nt.nodes.new("ShaderNodeNormalMap"); nm.inputs["Strength"].default_value = 1.0
    nt.links.new(nrm, nm.inputs["Color"]); nt.links.new(nm.outputs[0], b.inputs["Normal"])
    return m


def mountain(name, rock, haze, strength, top):
    """A far range: dark desert rock lit by the dusk (its gullies and spurs shaded), veiled by the haze — glowing more at its
    foot than at its crest, as the air is thicker low down ("base_emission": in the base layer alone)."""
    m = bpy.data.materials.new(name); nt, b = _nodes(m)
    co = _coords(nt, (0.02, 0.02, 0.05))
    nz = nt.nodes.new("ShaderNodeTexNoise"); nz.inputs["Scale"].default_value = 3.0; nz.inputs["Detail"].default_value = 8.0
    nt.links.new(co, nz.inputs["Vector"])
    tone = _ramp(nt, [(0.3, tuple(c * 0.7 for c in rock)), (0.7, tuple(c * 1.25 for c in rock))])
    nt.links.new(nz.outputs["Fac"], tone.inputs[0]); nt.links.new(tone.outputs[0], b.inputs["Base Color"])
    b.inputs["Roughness"].default_value = 1.0
    tc = nt.nodes.new("ShaderNodeTexCoord"); sep = nt.nodes.new("ShaderNodeSeparateXYZ"); nt.links.new(tc.outputs["Object"], sep.inputs[0])
    mr = nt.nodes.new("ShaderNodeMapRange"); mr.inputs["From Min"].default_value = 0.0; mr.inputs["From Max"].default_value = top
    nt.links.new(sep.outputs["Z"], mr.inputs["Value"])
    fall = _ramp(nt, [(0.0, haze), (1.0, tuple(c * 0.45 for c in haze))]); nt.links.new(mr.outputs[0], fall.inputs[0])
    nt.links.new(fall.outputs[0], b.inputs["Emission Color"])
    b.inputs["Emission Strength"].default_value = 0.0
    m["base_emission"] = strength
    return m


def shaggy(name):
    """A Joshua tree's limbs: dead leaves lying down along them, a shaggy grey-brown, in streaks along the limb."""
    m = bpy.data.materials.new(name); nt, b = _nodes(m)
    co = _coords(nt, (14, 14, 2.5))
    nz = nt.nodes.new("ShaderNodeTexNoise"); nz.inputs["Scale"].default_value = 3.0; nz.inputs["Detail"].default_value = 10.0
    nz.inputs["Distortion"].default_value = 1.5; nt.links.new(co, nz.inputs["Vector"])
    r = _ramp(nt, [(0.3, (0.06, 0.05, 0.04)), (0.55, (0.16, 0.13, 0.10)), (0.8, (0.30, 0.25, 0.19))])
    nt.links.new(nz.outputs["Fac"], r.inputs[0]); nt.links.new(r.outputs[0], b.inputs["Base Color"])
    b.inputs["Roughness"].default_value = 0.9
    _bump(nt, b, nz.outputs["Fac"], 1.0, 0.03)
    return m


def leaves(name, color, rough=0.55):
    m = bpy.data.materials.new(name); nt, b = _nodes(m)
    co = _coords(nt)
    nz = nt.nodes.new("ShaderNodeTexNoise"); nz.inputs["Scale"].default_value = 18.0; nz.inputs["Detail"].default_value = 4.0
    nt.links.new(co, nz.inputs["Vector"])
    r = _ramp(nt, [(0.3, tuple(c * 0.65 for c in color)), (0.7, tuple(min(1, c * 1.3) for c in color))])
    nt.links.new(nz.outputs["Fac"], r.inputs[0]); nt.links.new(r.outputs[0], b.inputs["Base Color"])
    b.inputs["Roughness"].default_value = rough
    return m


def thin_leaf(name, color, rough=0.8, through=0.45):
    """A thin leaf: `leaves`, letting a share of the light through, so a leaf turned from the light or deep in a bush is
    lit from behind — not baked black (a bush's shaded leaves came out as dark confetti close to)."""
    m = leaves(name, color, rough); nt = m.node_tree
    b = nt.nodes["Principled BSDF"]; out = nt.nodes["Material Output"]
    tr = nt.nodes.new("ShaderNodeBsdfTranslucent")
    nt.links.new(b.inputs["Base Color"].links[0].from_socket, tr.inputs["Color"])
    mix = nt.nodes.new("ShaderNodeMixShader"); mix.inputs["Fac"].default_value = through
    nt.links.new(b.outputs[0], mix.inputs[1]); nt.links.new(tr.outputs[0], mix.inputs[2])
    nt.links.new(mix.outputs[0], out.inputs["Surface"])
    return m


def wood(name, color, scale=3.0, rough=0.55, weathered=0.0):
    m = bpy.data.materials.new(name); nt, b = _nodes(m)
    co = _coords(nt, (1, 16, 16))
    nz = nt.nodes.new("ShaderNodeTexNoise"); nz.inputs["Scale"].default_value = scale
    nz.inputs["Detail"].default_value = 9.0; nz.inputs["Distortion"].default_value = 3.0
    nt.links.new(co, nz.inputs["Vector"])
    r = _ramp(nt, [(0.25, tuple(c * 0.6 for c in color)), (0.6, color), (0.85, tuple(min(1, c * 1.3) for c in color))])
    nt.links.new(nz.outputs["Fac"], r.inputs[0])
    c = r.outputs[0]
    if weathered:  # silvered in streaks, as an outdoor chair's arms are
        w = nt.nodes.new("ShaderNodeTexNoise"); w.inputs["Scale"].default_value = 7.0; nt.links.new(co, w.inputs["Vector"])
        wm = _ramp(nt, [(0.45, (0, 0, 0)), (0.7, (1, 1, 1))]); nt.links.new(w.outputs["Fac"], wm.inputs[0])
        c = _mix(nt, _math(nt, 'MULTIPLY', wm.outputs[0], weathered), c, (0.36, 0.30, 0.24))
    nt.links.new(c, b.inputs["Base Color"])
    _bump(nt, b, nz.outputs["Fac"], 0.3, 0.008)
    b.inputs["Roughness"].default_value = rough
    return m


def canvas(name, color):
    m = bpy.data.materials.new(name); nt, b = _nodes(m)
    co = _coords(nt)
    w1 = nt.nodes.new("ShaderNodeTexWave"); w1.wave_type = 'BANDS'; w1.bands_direction = 'X'; w1.inputs["Scale"].default_value = 1 / 0.002
    w2 = nt.nodes.new("ShaderNodeTexWave"); w2.wave_type = 'BANDS'; w2.bands_direction = 'Y'; w2.inputs["Scale"].default_value = 1 / 0.002
    for w in (w1, w2): nt.links.new(co, w.inputs["Vector"])
    weave = _math(nt, 'MULTIPLY', w1.outputs["Fac"], w2.outputs["Fac"])
    nz = nt.nodes.new("ShaderNodeTexNoise"); nz.inputs["Scale"].default_value = 8.0; nt.links.new(co, nz.inputs["Vector"])
    r = _ramp(nt, [(0.3, tuple(c * 0.8 for c in color)), (0.7, tuple(min(1, c * 1.15) for c in color))])
    nt.links.new(nz.outputs["Fac"], r.inputs[0]); nt.links.new(r.outputs[0], b.inputs["Base Color"])
    b.inputs["Roughness"].default_value = 0.9; b.inputs["Sheen Weight"].default_value = 0.3
    _bump(nt, b, weave, 0.3, 0.002)
    return m


def woven(name, x0, y0, bead):
    """The rug's yarn, in step with the mesh's beads (rows of `bead` cells from x0, y0, every other row offset by half): each
    bead its own shade of dark brown to dun, darker toward its edges, near black in the gaps."""
    m = bpy.data.materials.new(name); nt, b = _nodes(m)
    tc = nt.nodes.new("ShaderNodeTexCoord"); sep = nt.nodes.new("ShaderNodeSeparateXYZ"); nt.links.new(tc.outputs["Object"], sep.inputs[0])
    u = _math(nt, 'DIVIDE', _math(nt, 'SUBTRACT', sep.outputs["X"], x0), bead)
    v = _math(nt, 'DIVIDE', _math(nt, 'SUBTRACT', sep.outputs["Y"], y0), bead)
    row = _math(nt, 'FLOOR', v)
    off = _math(nt, 'MULTIPLY', _math(nt, 'MODULO', row, 2.0), 0.5)
    uu = _math(nt, 'ADD', u, off)
    col_i = _math(nt, 'FLOOR', uu)
    bx = _math(nt, 'SUBTRACT', uu, col_i); by = _math(nt, 'SUBTRACT', v, row)
    cm = nt.nodes.new("ShaderNodeCombineXYZ"); nt.links.new(col_i, cm.inputs[0]); nt.links.new(row, cm.inputs[1])
    wn = nt.nodes.new("ShaderNodeTexWhiteNoise"); wn.noise_dimensions = '3D'; nt.links.new(cm.outputs[0], wn.inputs["Vector"])
    tone = _ramp(nt, [(0.0, (0.05, 0.035, 0.025)), (0.5, (0.11, 0.08, 0.055)), (1.0, (0.20, 0.15, 0.10))])
    nt.links.new(wn.outputs["Value"], tone.inputs[0])
    dx = _math(nt, 'DIVIDE', _math(nt, 'SUBTRACT', bx, 0.5), 0.55); dy = _math(nt, 'DIVIDE', _math(nt, 'SUBTRACT', by, 0.5), 0.6)
    shape = _math(nt, 'SUBTRACT', 1.0, _math(nt, 'ADD', _math(nt, 'MULTIPLY', dx, dx), _math(nt, 'MULTIPLY', dy, dy)))
    shade = _ramp(nt, [(0.0, (0.15, 0.15, 0.15)), (0.35, (0.75, 0.75, 0.75)), (1.0, (1.1, 1.1, 1.1))])
    nt.links.new(_math(nt, 'MAXIMUM', shape, 0.0), shade.inputs[0])
    fz = nt.nodes.new("ShaderNodeTexNoise"); fz.inputs["Scale"].default_value = 500.0; nt.links.new(tc.outputs["Object"], fz.inputs["Vector"])
    c = _mix(nt, 1.0, tone.outputs[0], shade.outputs[0], 'MULTIPLY')
    c = _mix(nt, 0.25, c, fz.outputs["Color"], 'MULTIPLY')
    nt.links.new(c, b.inputs["Base Color"])
    b.inputs["Roughness"].default_value = 0.95; b.inputs["Sheen Weight"].default_value = 0.4
    _bump(nt, b, fz.outputs["Fac"], 0.3, 0.002)
    return m


def terracotta(name):
    m = bpy.data.materials.new(name); nt, b = _nodes(m)
    co = _coords(nt)
    nz = nt.nodes.new("ShaderNodeTexNoise"); nz.inputs["Scale"].default_value = 25.0; nz.inputs["Detail"].default_value = 8.0
    nt.links.new(co, nz.inputs["Vector"])
    r = _ramp(nt, [(0.3, (0.34, 0.13, 0.06)), (0.7, (0.48, 0.20, 0.09))]); nt.links.new(nz.outputs["Fac"], r.inputs[0])
    sp = nt.nodes.new("ShaderNodeTexVoronoi"); sp.inputs["Scale"].default_value = 160.0; nt.links.new(co, sp.inputs["Vector"])
    dot = _ramp(nt, [(0.0, (0.75, 0.75, 0.75)), (0.15, (1, 1, 1))]); nt.links.new(sp.outputs["Distance"], dot.inputs[0])
    c = _mix(nt, 1.0, r.outputs[0], dot.outputs[0], 'MULTIPLY')
    nt.links.new(c, b.inputs["Base Color"])
    b.inputs["Roughness"].default_value = 0.6
    _bump(nt, b, nz.outputs["Fac"], 0.2, 0.004)
    return m


def two_tone(name):
    """The trailer's skin: sage green below the belt line, cream above, a bright aluminium strip between; a fine seam
    every 0.4 m along it, as riveted panels have."""
    m = bpy.data.materials.new(name); nt, b = _nodes(m)
    tc = nt.nodes.new("ShaderNodeTexCoord"); sep = nt.nodes.new("ShaderNodeSeparateXYZ"); nt.links.new(tc.outputs["Object"], sep.inputs[0])
    z = sep.outputs["Z"]
    band = _ramp(nt, [(0.0, (0.17, 0.24, 0.18)), (0.3940, (0.17, 0.24, 0.18)), (0.3941, (0.62, 0.63, 0.64)), (0.408, (0.62, 0.63, 0.64)),
                      (0.4081, (0.66, 0.62, 0.52)), (1.0, (0.70, 0.66, 0.56))])
    band.color_ramp.interpolation = 'CONSTANT'
    zz = _math(nt, 'DIVIDE', z, 3.0)
    nt.links.new(zz, band.inputs[0])
    nz = nt.nodes.new("ShaderNodeTexNoise"); nz.inputs["Scale"].default_value = 6.0; nt.links.new(tc.outputs["Object"], nz.inputs["Vector"])
    dirt = _ramp(nt, [(0.3, (0.88, 0.87, 0.85)), (0.7, (1.0, 1.0, 1.0))]); nt.links.new(nz.outputs["Fac"], dirt.inputs[0])
    c = _mix(nt, 1.0, band.outputs[0], dirt.outputs[0], 'MULTIPLY')
    nt.links.new(c, b.inputs["Base Color"])
    b.inputs["Roughness"].default_value = 0.62; b.inputs["Metallic"].default_value = 0.0
    return m


def lino(name):
    """A 1950s trailer's floor: red and cream linoleum in checks, a little worn."""
    m = bpy.data.materials.new(name); nt, b = _nodes(m)
    co = _coords(nt)
    ck = nt.nodes.new("ShaderNodeTexChecker"); ck.inputs["Scale"].default_value = 3.6
    ck.inputs["Color1"].default_value = (0.40, 0.05, 0.04, 1); ck.inputs["Color2"].default_value = (0.70, 0.66, 0.56, 1)
    nt.links.new(co, ck.inputs["Vector"])
    nz = nt.nodes.new("ShaderNodeTexNoise"); nz.inputs["Scale"].default_value = 9.0; nt.links.new(co, nz.inputs["Vector"])
    wear = _ramp(nt, [(0.3, (0.82, 0.82, 0.82)), (0.7, (1.0, 1.0, 1.0))]); nt.links.new(nz.outputs["Fac"], wear.inputs[0])
    nt.links.new(_mix(nt, 1.0, ck.outputs["Color"], wear.outputs[0], 'MULTIPLY'), b.inputs["Base Color"])
    b.inputs["Roughness"].default_value = 0.45
    return m


def quilt(name):
    """A patchwork quilt on the bed: small squares in faded red, mustard and teal."""
    m = bpy.data.materials.new(name); nt, b = _nodes(m)
    co = _coords(nt, (7, 7, 7))
    vo = nt.nodes.new("ShaderNodeTexVoronoi"); vo.feature = 'F1'; vo.inputs["Randomness"].default_value = 0.0
    nt.links.new(co, vo.inputs["Vector"])
    r = _ramp(nt, [(0.0, (0.42, 0.10, 0.08)), (0.33, (0.42, 0.10, 0.08)), (0.34, (0.55, 0.42, 0.12)), (0.66, (0.55, 0.42, 0.12)),
                   (0.67, (0.08, 0.28, 0.28)), (1.0, (0.08, 0.28, 0.28))])
    r.color_ramp.interpolation = 'CONSTANT'
    sc = nt.nodes.new("ShaderNodeSeparateColor"); nt.links.new(vo.outputs["Color"], sc.inputs[0]); nt.links.new(sc.outputs[0], r.inputs[0])
    nt.links.new(r.outputs[0], b.inputs["Base Color"]); b.inputs["Roughness"].default_value = 0.9
    return m


def curtain(name, strength):
    """A lit window's curtain, lamplight behind it (house layer): warm, pleated, brighter in the middle."""
    m = bpy.data.materials.new(name); nt, b = _nodes(m)
    co = _coords(nt)
    wv = nt.nodes.new("ShaderNodeTexWave"); wv.wave_type = 'BANDS'; wv.bands_direction = 'X'; wv.wave_profile = 'SIN'
    wv.inputs["Scale"].default_value = 1 / 0.05 / 6.283
    nt.links.new(co, wv.inputs["Vector"])
    pleat = _ramp(nt, [(0.0, (0.55, 0.55, 0.55)), (1.0, (1.0, 1.0, 1.0))]); nt.links.new(wv.outputs["Fac"], pleat.inputs[0])
    c = _mix(nt, 1.0, (1.0, 0.62, 0.30), pleat.outputs[0], 'MULTIPLY')
    nt.links.new(c, b.inputs["Emission Color"])
    b.inputs["Base Color"].default_value = (0.5, 0.42, 0.30, 1); b.inputs["Roughness"].default_value = 0.9
    b.inputs["Emission Strength"].default_value = 0.0
    m["house_emission"] = strength
    return m


def dusk_sky(name):
    """The dome's face, emitting (base layer alone): deep blue overhead, lighter toward the horizon, the sunset's orange and
    pink low in the west ahead under streaks of cloud lit from below, violet round the rest of the horizon, a few stars."""
    m = bpy.data.materials.new(name); nt, b = _nodes(m)
    b.inputs["Base Color"].default_value = (0, 0, 0, 1)
    tc = nt.nodes.new("ShaderNodeTexCoord")
    d = nt.nodes.new("ShaderNodeVectorMath"); d.operation = 'NORMALIZE'; nt.links.new(tc.outputs["Object"], d.inputs[0])
    D = d.outputs[0]
    sep = nt.nodes.new("ShaderNodeSeparateXYZ"); nt.links.new(D, sep.inputs[0])
    hz = _math(nt, 'MAXIMUM', sep.outputs["Z"], 0.0)
    sky = _ramp(nt, [(0.0, (0.10, 0.10, 0.30)), (0.06, (0.05, 0.07, 0.30)), (0.25, (0.020, 0.045, 0.25)), (1.0, (0.008, 0.020, 0.13))])
    nt.links.new(hz, sky.inputs[0]); col = sky.outputs[0]
    wd = nt.nodes.new("ShaderNodeVectorMath"); wd.operation = 'DOT_PRODUCT'; nt.links.new(D, wd.inputs[0]); wd.inputs[1].default_value = tuple(WEST)
    west = _math(nt, 'POWER', _math(nt, 'MAXIMUM', _math(nt, 'MULTIPLY_ADD', wd.outputs["Value"], 0.5, 0.5), 0.0), 2.2)
    low = _math(nt, 'EXPONENT', _math(nt, 'MULTIPLY', _math(nt, 'POWER', _math(nt, 'DIVIDE', hz, 0.11), 2.0), -1.0))
    glow = _ramp(nt, [(0.0, (0.0, 0.0, 0.0)), (0.35, (0.22, 0.07, 0.12)), (0.7, (0.60, 0.20, 0.10)), (1.0, (0.95, 0.38, 0.12))])
    nt.links.new(_math(nt, 'MULTIPLY', west, low), glow.inputs[0])
    col = _mix(nt, 1.0, col, glow.outputs[0], 'ADD')
    # clouds: thin streaks a little above the horizon, pink-orange where the sunset lights them, grey-violet elsewhere
    sv = nt.nodes.new("ShaderNodeVectorMath"); sv.operation = 'MULTIPLY'; sv.inputs[1].default_value = (2.0, 2.0, 30.0)
    nt.links.new(D, sv.inputs[0])
    cn = nt.nodes.new("ShaderNodeTexNoise"); cn.inputs["Scale"].default_value = 1.6; cn.inputs["Detail"].default_value = 8.0
    cn.inputs["Roughness"].default_value = 0.6; nt.links.new(sv.outputs[0], cn.inputs["Vector"])
    cm = _ramp(nt, [(0.55, (0, 0, 0)), (0.68, (1, 1, 1))]); nt.links.new(cn.outputs["Fac"], cm.inputs[0])
    belt = _math(nt, 'EXPONENT', _math(nt, 'MULTIPLY', _math(nt, 'POWER', _math(nt, 'DIVIDE', _math(nt, 'SUBTRACT', hz, 0.08), 0.05), 2.0), -1.0))
    cmask = _math(nt, 'MULTIPLY', cm.outputs[0], belt)
    ccol = _mix(nt, west, (0.10, 0.08, 0.17), (0.75, 0.30, 0.30))
    col = _mix(nt, _math(nt, 'MULTIPLY', cmask, 0.85), col, ccol)
    st = nt.nodes.new("ShaderNodeTexVoronoi"); st.inputs["Scale"].default_value = 120.0; st.inputs["Randomness"].default_value = 1.0
    nt.links.new(D, st.inputs["Vector"])
    dot = _ramp(nt, [(0.0, (1, 1, 1)), (0.04, (0.5, 0.5, 0.5)), (0.12, (0, 0, 0))]); nt.links.new(st.outputs["Distance"], dot.inputs[0])
    ssp = nt.nodes.new("ShaderNodeSeparateColor"); nt.links.new(st.outputs["Color"], ssp.inputs[0])
    keep = _ramp(nt, [(0.955, (0, 0, 0)), (0.96, (0.8, 0.85, 1.0))]); nt.links.new(ssp.outputs[0], keep.inputs[0])
    high = _math(nt, 'MINIMUM', _math(nt, 'MAXIMUM', _math(nt, 'MULTIPLY', _math(nt, 'SUBTRACT', hz, 0.2), 3.0), 0.0), 1.0)
    stars = _mix(nt, 1.0, _mix(nt, 1.0, dot.outputs[0], keep.outputs[0], 'MULTIPLY'), _math(nt, 'MULTIPLY', high, 1.6), 'MULTIPLY')
    col = _mix(nt, 1.0, col, stars, 'ADD')
    nt.links.new(col, b.inputs["Emission Color"])
    b.inputs["Emission Strength"].default_value = 0.0
    m["base_emission"] = 1.0
    return m


def glowing(name, color, base_emission=None, house_emission=None):
    """Seen only by its own light — a far ridge in the haze (base), a lamp's shade (house): black, emitting."""
    return plain(name, (0.0, 0.0, 0.0), 0.9, emission=color, base_emission=base_emission, house_emission=house_emission)


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


M = {
    "ground": scan_ground("desert_floor"),
    "plain": scan_ground("desert_plain", far=True),
    "limb": shaggy("joshua_limb"),
    "spike": leaves("joshua_leaf", (0.07, 0.09, 0.04)),
    "deadleaf": leaves("joshua_deadleaf", (0.20, 0.16, 0.11), 0.8),
    "scrub": leaves("scrub_twig", (0.22, 0.19, 0.15), 0.85),
    "scrubleaf": thin_leaf("scrub_leaf", (0.2, 0.22, 0.15), 0.8),
    "grass": leaves("dry_grass", (0.30, 0.23, 0.13), 0.8),
    "yucca": leaves("yucca", (0.10, 0.12, 0.07)),
    "far_tree": plain("far_tree", (0.05, 0.05, 0.05), 0.9),
    "range1": mountain("range_near", (0.07, 0.06, 0.055), (0.050, 0.058, 0.140), 1.0, 160),
    "range2": mountain("range_mid", (0.05, 0.05, 0.05), (0.085, 0.100, 0.230), 1.0, 260),
    "range3": mountain("range_far", (0.04, 0.04, 0.045), (0.130, 0.150, 0.330), 1.0, 380),
    "frame": plain("screen_frame", (0.02, 0.02, 0.022), 0.4, metal=0.6),
    "screen": plain("screen", (0.8, 0.8, 0.8), 0.9, emission=(0.62, 0.70, 0.85)),
    # the near chair in scanned wood and cloth (2026-10-25), tinted back to its colours: weathered teak, olive canvas
    "chairwood": scan_uv("chair_wood", "teak_veneer", 0.8, tint=(0.69, 0.67, 0.74), sat=0.8),
    "canvas": scan_uv("chair_canvas", "hessian_380", 0.6, sat=0.0, tint=(0.16, 0.23, 0.14)),
    "rug": woven("woven_rug", -0.9, 0.42, 0.032),
    "terracotta": scan_uv("drum_terracotta", "planter_pot_clay/textures", 0.45, tint=(1.3, 1.3, 1.3), rough=0.85),
    "enamel": plain("mug_enamel", (0.80, 0.80, 0.78), 0.15),
    "enamel_rim": plain("mug_rim", (0.02, 0.02, 0.025), 0.2),
    "coffee": plain("coffee", (0.03, 0.015, 0.008), 0.1),
    "skin": two_tone("trailer_skin"),
    "alu": plain("aluminium", (0.62, 0.63, 0.65), 0.25, metal=1.0),
    "trailer_in": wood("trailer_birch", (0.50, 0.36, 0.20), 6.0, 0.5),
    "well": plain("wheel_well", (0.02, 0.02, 0.02), 0.9),
    "rimpaint": plain("rim_paint", (0.66, 0.62, 0.52), 0.4, metal=0.3),
    "plate": plain("licence_plate", (0.55, 0.45, 0.10), 0.5),
    "lino": lino("trailer_lino"),
    "vinyl": plain("dinette_vinyl", (0.05, 0.28, 0.28), 0.35),
    "laminate": plain("dinette_laminate", (0.72, 0.69, 0.60), 0.3),
    "quilt": quilt("trailer_quilt"),
    "cabinet": wood("cabinet_wood", (0.32, 0.15, 0.06), 4.0, 0.5),
    "bedding": plain("bedding", (0.62, 0.58, 0.52), 0.9),
    "curtain": curtain("curtain_lit", 1.6),
    "curtain_dark": plain("curtain_dark", (0.40, 0.36, 0.30), 0.9),
    "shade": glowing("lamp_shade", (1.0, 0.70, 0.38), house_emission=3.0),
    "picture": plain("picture", (0.25, 0.20, 0.12), 0.6),
    "tyre": plain("tyre", (0.02, 0.02, 0.022), 0.85),
    "chrome": plain("chrome", (0.80, 0.80, 0.82), 0.12, metal=1.0),
    "red": plain("taillight", (0.35, 0.02, 0.02), 0.3),
    "steel": plain("black_steel", (0.025, 0.025, 0.028), 0.45, metal=0.7),
    "tablewood": wood("table_wood", (0.32, 0.17, 0.08), 3.0, 0.6),
    "steptimber": wood("step_timber", (0.30, 0.17, 0.08), 3.0, 0.7),
    "olive_box": plain("olive_box", (0.17, 0.19, 0.12), 0.55, metal=0.5),
    "lantern_metal": plain("lantern_metal", (0.04, 0.04, 0.04), 0.45, metal=0.8),
    "lantern_glass": plain("lantern_glass", (0.06, 0.05, 0.04), 0.1, emission=(1.0, 0.58, 0.24), house_emission=1.2),
    "flame": plain("flame", (0.9, 0.8, 0.6), 0.5, emission=(1.0, 0.65, 0.28), house_emission=25.0),
    "sky": dusk_sky("dusk_sky"),
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




def blob(name, c, s, material, coll=room, subdiv=1, wobble=0.3, seed=0, flat=0.0):
    """An icosphere scaled to s, its surface pushed in and out by noise, its underside flattened (`flat`): a boulder,
    a bush."""
    bpy.ops.mesh.primitive_ico_sphere_add(subdivisions=subdiv, radius=1.0, location=(0, 0, 0))
    o = bpy.context.active_object; o.name = name
    off = Vector((seed * 13.1, seed * 7.7, seed * 3.3))
    for v in o.data.vertices:
        v.co *= 1.0 + wobble * noise.noise(v.co * 1.4 + off)
        if flat and v.co.z < 0: v.co.z *= (1.0 - flat)
        v.co = Vector((v.co.x * s[0], v.co.y * s[1], v.co.z * s[2]))
    o.location = c
    o.data.materials.append(material)
    for p in o.data.polygons: p.use_smooth = True
    return _link(o, coll)


MOUNDS = []   # (x, y, radius, height): sand banked at a shrub's or a tree's foot


def ground_h(x, y):
    """The flat: nearly level round the camp, a gentle swell here and there, small hummocks and ripples underfoot (none
    under the rug and the chair), sand banked round the shrubs' feet, and beyond 60 m the bajada rising slowly to the
    mountains."""
    h = 0.08 * noise.noise(Vector((x * 0.15, y * 0.15, 0.3))) + 0.02 * noise.noise(Vector((x * 0.9, y * 0.9, 1.3)))
    d = math.hypot(x - SEAT[0], y - SEAT[1])
    if d > 1.4:
        f = min(1.0, (d - 1.4) / 0.8)
        h += f * (0.014 * noise.noise(Vector((x * 3.1, y * 3.1, 2.1))) + 0.006 * noise.noise(Vector((x * 8.0, y * 8.0, 5.5))))
    for (mx, my, mr, mh) in MOUNDS:
        q = ((x - mx) ** 2 + (y - my) ** 2) / (mr * mr)
        if q < 4.0: h += mh * math.exp(-q)
    r = math.hypot(x, y - 3.0)
    if r > 40: h += 0.6 * noise.noise(Vector((x * 0.01, y * 0.01, 4.0))) * min(1.0, (r - 40) / 60)
    if r > 60: h += 28.0 * ((r - 60) / 700) ** 2
    return h


def tube(bm, a, b_, r0, r1, sides=6):
    """A tapered tube from a to b, its rings turned to the segment."""
    a, b_ = Vector(a), Vector(b_); d = (b_ - a).normalized()
    s = d.cross(Vector((0, 0, 1)))
    if s.length < 1e-4: s = Vector((1, 0, 0))
    s.normalize(); t = d.cross(s)
    ring = lambda c, r: [bm.verts.new(c + (s * math.cos(k * 2 * math.pi / sides) + t * math.sin(k * 2 * math.pi / sides)) * r)
                         for k in range(sides)]
    r0_, r1_ = ring(a, r0), ring(b_, r1)
    for k in range(sides):
        f = bm.faces.new((r0_[k], r0_[(k + 1) % sides], r1_[(k + 1) % sides], r1_[k]))
        if f.normal.dot(f.calc_center_median() - (a + b_) / 2) < 0: f.normal_flip()


def spike(bm, p, d, length, width):
    """A narrow leaf blade from p along d, two-sided: two faces back to back, a millimetre apart, so each is baked from its
    own side (the headset draws front faces only)."""
    d = Vector(d).normalized(); s = d.cross(Vector((0.3, 0.2, 1.0))).normalized() * width / 2
    n = d.cross(s).normalized() * 0.0005
    tri = (p - s, p + s, p + d * length)
    f = bm.faces.new([bm.verts.new(q + n) for q in tri]); f.normal_update()
    g = bm.faces.new([bm.verts.new(q - n) for q in reversed(tri)]); g.normal_update()
    if f.normal.dot(n) < 0:  # each face on the side it looks to
        for v in f.verts: v.co -= 2 * n
        for v in g.verts: v.co += 2 * n


def rand_dir(rnd, up=0.0):
    while True:
        v = Vector((rnd.uniform(-1, 1), rnd.uniform(-1, 1), rnd.uniform(-1, 1)))
        if 0.05 < v.length <= 1.0:
            v.normalize(); v.z += up; return v.normalized()


def joshua(name, base, height, seed, levels=3, spikes=80, coll=room, blade=1.0, shag=0.0):
    """A Joshua tree: a shaggy trunk forking into limbs that fork again, each limb bending a little on its way and ending in
    a crown of stiff blades pointing every way (the lower ones drooping), a skirt of dead blades under it. `shag`: dead
    leaves a metre, hanging down along the limbs and the trunk's upper two-thirds, as an old tree's are."""
    rnd = random.Random(seed)
    limbs, blades, dead = bmesh.new(), bmesh.new(), bmesh.new()

    def crown(p, d):
        for k in range(spikes):
            v = rand_dir(rnd, 0.25) + d * 0.35; v.normalize()
            spike(blades, p + v * 0.05, v, rnd.uniform(0.28, 0.44) * blade, 0.026 * blade)
        for k in range(spikes // 3):  # the dead skirt: grey blades hanging down the limb below the crown
            v = rand_dir(rnd); v.z = -abs(v.z) - 0.8; v.normalize()
            spike(dead, p - d * 0.12 + v * 0.03, v, rnd.uniform(0.15, 0.25), 0.02)

    def hang(a, b_, r, n):
        ax = (b_ - a).normalized()
        for k in range(n):
            q = a.lerp(b_, rnd.random()); out = ax.cross(rand_dir(rnd)).normalized()
            v = (out * 0.35 + Vector((0, 0, -1)) + ax * 0.1).normalized()
            spike(dead, q + out * r * 0.9, v, rnd.uniform(0.18, 0.32), 0.022)

    def grow(p, d, length, r, level):
        sides = 12 if shag and level >= 2 else 8 if level >= 2 else 6
        q, dd, parts = p, d, 3 if shag else 1
        for k in range(parts):  # a limb in three bends, each a little thinner
            if k: dd = (dd + rand_dir(rnd) * 0.14); dd.z = max(dd.z, 0.3); dd.normalize()
            nq = q + dd * (length / parts)
            r0, r1 = r * (1 - 0.2 * k / parts), r * (1 - 0.2 * (k + 1) / parts)
            tube(limbs, q, nq, r0, r1, sides)
            if shag and (level < levels or k >= 1): hang(q, nq, r0, int(shag * length / parts))
            q = nq
        end, d = q, dd
        if level == 0:
            crown(end, d); return
        n = rnd.choice((2, 3, 3))
        tw = rnd.uniform(0, 2 * math.pi)
        for k in range(n):
            a = tw + 2 * math.pi * k / n + rnd.uniform(-0.4, 0.4)
            ax = Vector((math.cos(a), math.sin(a), 0))
            ang = math.radians(rnd.uniform(26, 44))
            nd = (Matrix.Rotation(ang, 3, ax) @ Vector((0, 0, 1))).lerp(d, 0.35); nd.z = max(nd.z, 0.35); nd.normalize()
            grow(end, nd, length * rnd.uniform(0.55, 0.72), r * 0.7, level - 1)

    B = Vector((base[0], base[1], ground_h(base[0], base[1]) - 0.05))
    trunk_d = Vector((rnd.uniform(-0.04, 0.04), rnd.uniform(-0.04, 0.04), 1)).normalized()
    grow(B, trunk_d, height * 0.42, height * 0.04, levels)
    objs = []
    for bm, mat, nm in ((limbs, "limb", "limbs"), (blades, "spike", "crowns"), (dead, "deadleaf", "dead")):
        me = bpy.data.meshes.new(f"{name}_{nm}"); bm.to_mesh(me); bm.free()
        o = bpy.data.objects.new(f"{name}_{nm}", me); coll.objects.link(o); me.materials.append(M[mat])
        for p_ in me.polygons: p_.use_smooth = nm == "limbs"
        objs.append(o)
    if not shag:
        return join(name, objs)
    # a near tree's blades go with the scrub's leaves, on their atlas: thousands of tiny islands starve in a shared one
    # (its dead leaves baked into black and white bands down the trunk)
    return join(name, objs[:1]), join(f"leaf_{name}", objs[1:])


def scrub(name, c, size, seed, coll=room):
    """A low desert shrub: short forking twigs under a loose, squashed dome of small leaves, each turned its own way so
    the bake shades them one by one and the mass reads as foliage (2026-10-23: a ball at each twig tip read as lollipops,
    a dome of smooth balls as a pile of pebbles). The headset draws front faces only and the bake lights a face from its
    front, so a leaf is two faces back to back, a millimetre apart, each baked from its own side. The leaves are their
    own object, "leaf_…", for their own atlas: thousands of tiny islands want small margins (bake_layers' name@SIZE/MARGIN)."""
    rnd = random.Random(seed)
    tw = bmesh.new(); lf = bmesh.new()
    C = Vector((c[0], c[1], ground_h(c[0], c[1]) - 0.02))
    R, Hh = size * 0.5, size * 0.42
    tips = []

    def branch(p, d, length, level):
        end = p + d * length
        tube(tw, p, end, 0.007 * (level + 1), 0.005 * (level + 1), 3)
        if level == 0:
            tips.append(end); return
        for k in range(rnd.choice((2, 3))):
            nd = d + rand_dir(rnd) * 0.7; nd.z = abs(nd.z) * 0.8 + 0.2; nd.normalize()
            branch(end, nd, length * 0.72, level - 1)

    for k in range(rnd.randint(5, 7)):
        a = rnd.uniform(0, 2 * math.pi); el = math.radians(rnd.uniform(35, 70))
        d = Vector((math.cos(a) * math.cos(el), math.sin(a) * math.cos(el), math.sin(el)))
        branch(C, d, size * rnd.uniform(0.22, 0.3), 2)

    def leaf(p, L):
        n = rand_dir(rnd); u = n.cross(rand_dir(rnd)).normalized(); v = n.cross(u)
        w = L * rnd.uniform(0.4, 0.55)
        tri = [p - u * L * 0.5 - v * w, p - u * L * 0.5 + v * w, p + u * L * rnd.uniform(0.6, 0.8)]
        for side in (1, -1):
            f = lf.faces.new([lf.verts.new(q + n * (0.0005 * side)) for q in tri])
            if f.normal.dot(n) * side < 0: f.normal_flip()

    # leaves: most in clusters round the twig tips, the rest filling the dome so it has no holes in its middle
    # 2026-10-24, on the headset: the leaves' 8.3万 tiny triangles, drawn again in the lamps' layer, dropped 25–41 frames in
    # 20 s (60 % of them: 2) — fewer, a little larger leaves, three-sided twigs, sparser grass and Joshua blades
    for k in range(int(360 * size * size)):
        if k % 3 and tips:
            q = rnd.choice(tips) + rand_dir(rnd) * size * rnd.uniform(0.0, 0.13)
        else:
            while True:
                v3 = Vector((rnd.uniform(-1, 1), rnd.uniform(-1, 1), rnd.uniform(0.2, 1)))
                if v3.length <= 1.0: break
            q = C + Vector((v3.x * R, v3.y * R, v3.z * Hh))
        if q.z < C.z + 0.05: continue
        leaf(q, size * rnd.uniform(0.043, 0.06))
    # the twigs too go on the leaves' atlas: their hundreds of little tubes are islands as tiny as the leaves
    me = bpy.data.meshes.new(f"leaf_{name}_twigs"); tw.to_mesh(me); tw.free()
    o = bpy.data.objects.new(f"leaf_{name}_twigs", me); coll.objects.link(o); me.materials.append(M["scrub"])
    ml = bpy.data.meshes.new(f"leaf_{name}"); lf.to_mesh(ml); lf.free()
    ol = bpy.data.objects.new(f"leaf_{name}", ml); coll.objects.link(ol); ml.materials.append(M["scrubleaf"])
    return o, ol


def yucca(name, c, size, seed, coll=room):
    """A low rosette of stiff blades (the references' yucca by the rocks)."""
    rnd = random.Random(seed); bm = bmesh.new()
    C = Vector((c[0], c[1], ground_h(c[0], c[1])))
    for k in range(60):
        v = rand_dir(rnd, 0.9); v.z = abs(v.z)
        spike(bm, C + v * 0.03, v, size * rnd.uniform(0.6, 1.0), 0.03)
    me = bpy.data.meshes.new(name); bm.to_mesh(me); bm.free()
    o = bpy.data.objects.new(name, me); coll.objects.link(o); me.materials.append(M["yucca"])
    return o


# ---------------------------------------------------------------- where the piles, shrubs and trees stand
# The piles first: (name, middle, size — the boulders' scale, seed, boulders below, boulders dropped on them). Their
# footprints (FOOT) keep everything else out: a tree does not grow through a boulder (2026-10-24, the user: "这些石头的位置
# 完全不合理" — the first scan piles had a Joshua tree in one, a boulder balanced on a pebble, a slab in the air).
PILES = [("rock_pile_left", (-7.8, 10.4), 1.35, 3, 3, 2), ("rock_pile_left2", (-9.4, 4.4), 1.0, 5, 2, 1),
         ("rockb_pile_right", (11.0, 12.6), 1.3, 7, 3, 2), ("rockb_pile_back", (-4.8, -10.0), 1.2, 11, 3, 1),
         ("rockb_pile_back2", (7.0, -7.4), 0.9, 13, 2, 1),
         ("far_pile_0", (-22, 30), 2.2, 17, 3, 2), ("far_pile_1", (30, 24), 2.0, 19, 3, 1), ("far_pile_2", (-30, -20), 2.3, 21, 3, 2)]
FOOT = [(c[0], c[1], 2.4 * sz + 0.6) for (_, c, sz, *_r) in PILES]   # its middle, how far its boulders reach, and a margin


def clear(x, y, more=0.0, of=None):
    """Whether (x, y) is clear of the piles' footprints (and of the points `of`: (x, y, radius))."""
    return all(math.hypot(x - fx, y - fy) > fr + more for (fx, fy, fr) in FOOT + list(of or []))


JOSHUAS = [("desert_joshua_hero", (-4.4, 6.2), 5.0, 1, 3, 90), ("desert_joshua_back", (2.2, -7.2), 4.8, 2, 3, 60),
           ("desert_joshua_right", (13.0, 17.0), 5.2, 3, 3, 55), ("desert_joshua_left", (-11.8, 15.4), 4.4, 4, 2, 55)]
TREES = [(b_[0], b_[1], 0.8) for (_, b_, *_r) in JOSHUAS]
YUCCAS = [(-3.0, 7.9), (-5.6, 3.2), (8.8, 8.6)]
LANTERN_ROCKS = (-3.6, 7.2)
SCRUBS = []
while len(SCRUBS) < 36:
    a = random.uniform(0, 2 * math.pi); r = random.uniform(4.5, 16.0)
    x, y = r * math.cos(a), 3.0 + r * math.sin(a)
    if abs(x) < 2.6 and 5.5 < y < 10.5: continue           # in front of and under the screen
    if 3.8 < x < 8.6 and 2.5 < y < 10.0: continue          # the trailer
    if abs(x) < 1.3 and y < 2.5 and y > -1.5: continue
    if not clear(x, y, 0.3, TREES + [(*q, 0.6) for q in YUCCAS + [LANTERN_ROCKS]]): continue
    SCRUBS.append((x, y, random.uniform(0.6, 1.1)))
MOUNDS += [(x, y, s * 0.55, s * 0.07) for (x, y, s) in SCRUBS] + [(b_[0], b_[1], 0.6, 0.05) for (_, b_, *_r) in JOSHUAS]

# ---------------------------------------------------------------- the ground
# Tiles: 2 m ones within 12 m of the middle, their grid 6.25, 12.5, 25 or 50 cm by how near the seat they are (within 2, 4.5,
# 9 m and beyond); 4 m ones of
# 1 m to 28 m; 8 m ones of 2 m to 60 m. Every grid sits on one lattice, a coarse tile's vertices a subset of a fine one's,
# so a fine tile's edge beside a coarser one follows the coarse one's straight lines — no cracks. Each tile is its own
# object: bake_layers sizes a part's share of the atlas by its nearness, so the sand at the viewer's feet is sharp.
GC = (0.0, 2.0)
ORIGIN = (GC[0] - 60.0, GC[1] - 60.0)
TILES = {}


def add_tiles(prefix, half_in, half_out, size, step_of):
    n = int(round(2 * half_out / size))
    for j in range(n):
        for i in range(n):
            x0, y0 = GC[0] - half_out + i * size, GC[1] - half_out + j * size
            if max(abs(x0 + size / 2 - GC[0]), abs(y0 + size / 2 - GC[1])) < half_in: continue
            TILES[(round(x0, 4), round(y0, 4))] = (prefix, size, step_of(x0, y0, size))


def near_step(x0, y0, size):
    nx, ny = min(max(SEAT[0], x0), x0 + size), min(max(SEAT[1], y0), y0 + size)
    d = math.hypot(nx - SEAT[0], ny - SEAT[1])
    return 0.0625 if d < 2.0 else 0.125 if d < 4.5 else 0.25 if d < 9.0 else 0.5


add_tiles("gnear", 0.0, 12.0, 2.0, near_step)
add_tiles("gmid", 12.0, 28.0, 4.0, lambda *a_: 1.0)
add_tiles("gmid", 28.0, 60.0, 8.0, lambda *a_: 2.0)


def step_at(x, y):
    for size, half in ((2.0, 12.0), (4.0, 28.0), (8.0, 60.0)):
        x0 = GC[0] - half + math.floor((x - (GC[0] - half)) / size) * size
        y0 = GC[1] - half + math.floor((y - (GC[1] - half)) / size) * size
        t = TILES.get((round(x0, 4), round(y0, 4)))
        if t and t[1] == size: return t[2]
    return 0.0


def tile_h(x, y, s, along_x):
    """The ground's height at (x, y) on an edge shared with a tile of step s: on s's straight line between its vertices."""
    if along_x:
        t0 = ORIGIN[0] + math.floor((x - ORIGIN[0]) / s + 1e-6) * s; f = (x - t0) / s
        return ground_h(t0, y) * (1 - f) + ground_h(t0 + s, y) * f
    t0 = ORIGIN[1] + math.floor((y - ORIGIN[1]) / s + 1e-6) * s; f = (y - t0) / s
    return ground_h(x, t0) * (1 - f) + ground_h(x, t0 + s) * f


for (x0, y0), (prefix, size, step) in TILES.items():
    n = int(round(size / step)); bm = bmesh.new(); grid = {}
    for j in range(n + 1):
        for i in range(n + 1):
            x, y = x0 + i * step, y0 + j * step
            s_, along = step, None
            if 0 < j < n and i in (0, n): s_, along = max(step, step_at(x0 - 0.01 if i == 0 else x0 + size + 0.01, y)), False
            elif 0 < i < n and j in (0, n): s_, along = max(step, step_at(x, y0 - 0.01 if j == 0 else y0 + size + 0.01)), True
            z = tile_h(x, y, s_, along) if s_ > step else ground_h(x, y)
            grid[i, j] = bm.verts.new((x, y, z))
    for j in range(n):
        for i in range(n):
            bm.faces.new((grid[i, j], grid[i + 1, j], grid[i + 1, j + 1], grid[i, j + 1]))
    me = bpy.data.meshes.new(f"{prefix}_{x0:g}_{y0:g}"); bm.to_mesh(me); bm.free()
    o = bpy.data.objects.new(me.name, me); room.objects.link(o); me.materials.append(M["ground"])
    for p_ in me.polygons: p_.use_smooth = True

# the plain beyond: rings from under the outer tiles (6 cm below them) to just past the first range's foot
pv, pf = [], []
NA, RINGS = 160, 34
for k in range(RINGS + 1):
    r = 56.0 * (700.0 / 56.0) ** (k / RINGS)
    for i in range(NA):
        a = 2 * math.pi * i / NA
        x, y = r * math.sin(a), 3.0 + r * math.cos(a)
        pv.append((x, y, ground_h(x, y) - 0.06))
for k in range(RINGS):
    for i in range(NA):
        a_, b_ = k * NA + i, k * NA + (i + 1) % NA
        pf.append((a_, b_, b_ + NA, a_ + NA))
face_toward(mesh_from("far_plain", pv, pf, M["plain"], smooth=True), (0, 3, 30))

# dry grass tufts, sparse, near
gbm = bmesh.new()
for k in range(360):
    r = 14.0 * math.sqrt(random.random()) ** 1.2; a = random.uniform(0, 2 * math.pi)
    x, y = r * math.cos(a), 3.0 + r * math.sin(a)
    if abs(x) < 1.0 and -0.6 < y < 1.8: continue           # the chair and the rug
    if 3.8 < x < 8.0 and 3.0 < y < 9.5: continue           # the trailer
    C = Vector((x, y, ground_h(x, y)))
    for b_ in range(random.randint(5, 9)):
        v = rand_dir(random, 1.6); v.z = abs(v.z)
        spike(gbm, C, v, random.uniform(0.06, 0.16), 0.006)
# on the leaves' atlas: thousands of blades in the Joshua trees' one left their limbs a few texels a segment (banded black
# and white)
gme = bpy.data.meshes.new("leaf_grass"); gbm.to_mesh(gme); gbm.free()
o = bpy.data.objects.new("leaf_grass", gme); room.objects.link(o); gme.materials.append(M["grass"])

# ---------------------------------------------------------------- rocks: scanned boulders, piled
SCANS, LODS = {}, {}


def scan_pieces(name, together=False):
    """The meshes of a scan in ASSETS (glTF, 2k maps), upright, each with its origin at the middle of its foot — or,
    `together` (a lantern's brass and glass, a jerrycan's cap), all of them at the middle of their common foot."""
    key = (name, together)
    if key not in SCANS:
        before = set(bpy.data.objects)
        bpy.ops.import_scene.gltf(filepath=os.path.join(ASSETS, name, f"{name}_2k.gltf"))
        new = [o for o in bpy.data.objects if o not in before]
        pieces = []
        for o in new:
            if o.type != 'MESH': continue
            me = o.data.copy(); me.transform(o.matrix_world)
            pieces.append(me)
        for o in new: bpy.data.objects.remove(o)
        groups_ = [pieces] if together else [[me] for me in pieces]
        for g in groups_:
            xs, ys, zs = ([v.co[i] for me in g for v in me.vertices] for i in range(3))
            for me in g: me.transform(Matrix.Translation(Vector((-(min(xs) + max(xs)) / 2, -(min(ys) + max(ys)) / 2, -min(zs)))))
        SCANS[key] = pieces
    return SCANS[key]


def scan_lod(name, piece, tris, together=False):
    """That piece decimated to about `tris` triangles; its UVs, so its maps, kept."""
    key = (name, piece, tris, together)
    if key not in LODS:
        src = scan_pieces(name, together)[piece]
        o = bpy.data.objects.new("lod", src); scene.collection.objects.link(o)
        n = sum(len(p_.vertices) - 2 for p_ in src.polygons)
        if tris < n: o.modifiers.new("lod", 'DECIMATE').ratio = tris / n
        dg = bpy.context.evaluated_depsgraph_get(); dg.update()
        LODS[key] = bpy.data.meshes.new_from_object(o.evaluated_get(dg))
        bpy.data.objects.remove(o)
    return LODS[key]


def prop(name, scan, foot, yaw=0.0, scale=1.0, tris=None, glass=None):
    """A scanned prop from ASSETS, its pieces in their places, decimated to about `tris` triangles in all, turned `yaw`
    degrees and scaled, the middle of its foot at `foot`. Transmission off (Poly Haven's brass has it on, for a renderer's
    sake; the bake would see through it); `glass` in place of a glass material (a lantern's globe glows instead)."""
    pieces = scan_pieces(scan, True)
    counts = [sum(len(p_.vertices) - 2 for p_ in me.polygons) for me in pieces]
    objs = []
    for k, n in enumerate(counts):
        me = scan_lod(scan, k, max(60, int((tris or sum(counts)) * n / sum(counts))), True).copy()
        me.transform(Matrix.Translation(Vector(foot)) @ Matrix.Rotation(math.radians(yaw), 4, 'Z') @ Matrix.Scale(scale, 4))
        o = bpy.data.objects.new(f"{name}_{k}", me); room.objects.link(o); objs.append(o)
    o = join(name, objs) if len(objs) > 1 else objs[0]
    o.name = name
    for i, m in enumerate(o.data.materials):
        if m is None or not m.use_nodes: continue
        b = m.node_tree.nodes.get("Principled BSDF")
        if b is not None and "Transmission Weight" in b.inputs: b.inputs["Transmission Weight"].default_value = 0.0
        if glass is not None and "glass" in m.name.lower(): o.data.materials[i] = glass
    return o


SIZES = {}
ROUND = ("namaqualand_boulder_04", "namaqualand_boulder_03")    # round or blocky enough to carry and to sit on others
SLABS = ("namaqualand_boulder_02", "namaqualand_boulder_05")    # flat ones: on the sand only


def rock(name, scan, piece, xy, scale, yaw, tilt=0.12, seed=0, tris=None):
    """A scanned rock, scaled and turned and tilted a little, its triangles by its size over its distance from the seat
    (the eye sees no finer). Its origin at the middle of its foot; not yet set down."""
    rnd = random.Random(seed)
    if scan not in SIZES:
        me0 = scan_pieces(scan)[piece]
        SIZES[scan] = max(max(v.co[i] for v in me0.vertices) - min(v.co[i] for v in me0.vertices) for i in range(3))
    size = SIZES[scan] * scale
    d = max(1.5, math.hypot(xy[0] - SEAT[0], xy[1] - SEAT[1]))
    tris = tris or int(min(6000, max(250, 26000 * (size / d) ** 2)))
    me = scan_lod(scan, piece, tris).copy()
    me.transform(Euler((rnd.uniform(-tilt, tilt), rnd.uniform(-tilt, tilt), yaw)).to_matrix().to_4x4() @ Matrix.Scale(scale, 4))
    zmin = min(v.co.z for v in me.vertices)
    me.transform(Matrix.Translation(Vector((0, 0, -zmin))))
    o = bpy.data.objects.new(name, me); room.objects.link(o); o.location = (xy[0], xy[1], 0.0)
    return o


def extent(o):
    """Its half-width across the ground and its height."""
    xs, ys, zs = ([v.co[i] for v in o.data.vertices] for i in range(3))
    return max(max(xs) - min(xs), max(ys) - min(ys)) / 2, max(zs) - min(zs)


def on_sand(o, sink):
    """Set down on the ground, sunk `sink` of its height (its lowest point under the sand at its middle's height)."""
    o.location.z = ground_h(o.location.x, o.location.y) - sink * extent(o)[1]


def scan_pile(prefix, c, size, seed, base_n, top_n):
    """A pile: `base_n` boulders on the sand, the biggest in the middle, the others round it against it, sunk a little;
    `top_n` smaller round ones to be dropped onto them (returned, for the physics). `size` scales the boulders (the scans
    are 1–3 m; Joshua Tree's are bigger)."""
    rnd = random.Random(seed)
    first = rock(f"{prefix}_0", ROUND[seed % 2], 0, c, size * rnd.uniform(0.9, 1.0), rnd.uniform(0, 2 * math.pi), seed=seed * 7)
    r0 = extent(first)[0]; on_sand(first, rnd.uniform(0.12, 0.22)); laid = [first]
    for k in range(1, base_n):
        scan = (ROUND + SLABS)[(k + seed) % 4]
        a = 2 * math.pi * k / base_n + rnd.uniform(-0.5, 0.5)
        o = rock(f"{prefix}_{k}", scan, 0, c, size * rnd.uniform(0.6, 0.85), rnd.uniform(0, 2 * math.pi), seed=seed * 7 + k)
        rk = extent(o)[0]; rr = (r0 + rk) * 0.78     # against the middle one, a little into it (they meet below the sand)
        o.location.x, o.location.y = c[0] + math.cos(a) * rr, c[1] + math.sin(a) * rr
        on_sand(o, rnd.uniform(0.1, 0.25)); laid.append(o)
    top = []
    for k in range(top_n):
        a = rnd.uniform(0, 2 * math.pi); rr = r0 * rnd.uniform(0.0, 0.35)
        x, y = c[0] + math.cos(a) * rr, c[1] + math.sin(a) * rr
        o = rock(f"{prefix}_t{k}", ROUND[(k + seed + 1) % 2], 0, (x, y), size * rnd.uniform(0.38, 0.55),
                 rnd.uniform(0, 2 * math.pi), tilt=0.3, seed=seed * 11 + k)
        o.location.z = max(sum(extent(b_)[1] for b_ in laid[:1]), 1.0) + ground_h(x, y) + 0.4 + 2.2 * k
        top.append(o)
    return laid, top


def settle(fixed, falling, frames=260):
    """Drops `falling` onto `fixed` (and the ground) with Blender's rigid bodies — convex hulls on the boulders' meshes,
    rough and dead (no bounce) — and keeps where they come to rest."""
    proxies = []
    for (fx, fy, fr) in FOOT:  # the ground under each pile, for the physics only
        n = 24; vs, fs = [], []
        for j in range(n + 1):
            for i in range(n + 1):
                x, y = fx - fr * 1.6 + 3.2 * fr * i / n, fy - fr * 1.6 + 3.2 * fr * j / n
                vs.append((x, y, ground_h(x, y)))
        for j in range(n):
            for i in range(n):
                a_ = j * (n + 1) + i; fs.append((a_, a_ + 1, a_ + n + 2, a_ + n + 1))
        proxies.append(mesh_from("physics_ground", vs, fs, M["ground"]))
    bpy.ops.rigidbody.world_add(); w = scene.rigidbody_world
    w.substeps_per_frame = 12; w.solver_iterations = 30
    w.point_cache.frame_start = 1; w.point_cache.frame_end = frames
    scene.frame_start, scene.frame_end = 1, frames

    def body(o, kind):
        bpy.ops.object.select_all(action='DESELECT'); bpy.context.view_layer.objects.active = o; o.select_set(True)
        bpy.ops.rigidbody.object_add(type=kind)
        rb = o.rigid_body; rb.friction = 0.95; rb.restitution = 0.0
        rb.collision_shape = 'MESH' if kind == 'PASSIVE' else 'CONVEX_HULL'
        if kind == 'ACTIVE':
            rb.linear_damping = 0.5; rb.angular_damping = 0.85
            xs = extent(o); rb.mass = 2600.0 * (xs[0] ** 2) * xs[1]
    for o in fixed + proxies: body(o, 'PASSIVE')
    for o in falling:
        bpy.ops.object.select_all(action='DESELECT'); bpy.context.view_layer.objects.active = o; o.select_set(True)
        bpy.ops.object.origin_set(type='ORIGIN_CENTER_OF_VOLUME')
        body(o, 'ACTIVE')
    for f in range(1, frames + 1): scene.frame_set(f)
    rest = {o.name: o.matrix_world.copy() for o in falling}
    for o in fixed + proxies + falling:
        bpy.ops.object.select_all(action='DESELECT'); bpy.context.view_layer.objects.active = o; o.select_set(True)
        bpy.ops.rigidbody.object_remove()
    bpy.ops.rigidbody.world_remove(); scene.frame_set(1)
    for o in falling:  # where it came to rest, kept in its mesh (the bake applies transforms anyway)
        o.data.transform(rest[o.name]); o.matrix_world = Matrix.Identity(4)
    for o in proxies: bpy.data.objects.remove(o)


fixed, falling = [], []
for (nm, c, sz, sd, nb, nt_) in PILES:
    f_, t_ = scan_pile(nm, c, sz, sd, nb, nt_); fixed += f_; falling += t_
# single rocks on the sand, clear of everything
SINGLES = []
while len(SINGLES) < 9:
    r = random.uniform(2.6, 14.0); a = random.uniform(0, 2 * math.pi)
    x, y = r * math.cos(a), 3.0 + r * math.sin(a)
    if abs(x) < 2.8 and 5.0 < y < 10.5: continue           # in front of and under the screen
    if 3.4 < x < 9.0 and 2.0 < y < 10.5: continue          # the trailer
    if not clear(x, y, 0.8, TREES + [(sx, sy, ss * 0.7) for (sx, sy, ss) in SCRUBS] + [(*q, 0.7) for q in YUCCAS + [LANTERN_ROCKS]]):
        continue
    SINGLES.append((x, y))
for k, (x, y) in enumerate(SINGLES):
    scan = (ROUND + SLABS)[k % 4]
    o = rock(f"rock_single_{k}", scan, 0, (x, y), random.uniform(0.5, 1.1) / SIZES.get(scan, 2.5) if scan in SIZES else 0.35,
             random.uniform(0, 2 * math.pi), seed=60 + k)
    on_sand(o, random.uniform(0.15, 0.3))
settle(fixed, falling)
# the stones underfoot
k = 0
while k < 80:
    r = random.uniform(0.9, 8.0); a = random.uniform(0, 2 * math.pi)
    x, y = r * math.cos(a), 2.0 + r * math.sin(a)
    if abs(x) < 1.0 and 0.3 < y < 1.9: continue
    if not clear(x, y, 0.0, TREES): continue
    o = rock(f"rock_stone_{k}", "namaqualand_boulders_01", k % 2, (x, y), random.uniform(0.12, 0.45),
             random.uniform(0, 2 * math.pi), tilt=0.3, seed=500 + k, tris=160)
    on_sand(o, 0.3); k += 1
# dry branches lying on the sand (a scan: three together), clear of the piles, the trees and the camp
k = 0
while k < 8:
    r = random.uniform(2.5, 13.0); a = random.uniform(0, 2 * math.pi)
    x, y = r * math.cos(a), 3.0 + r * math.sin(a)
    if abs(x) < 2.8 and 5.0 < y < 10.5: continue
    if 3.0 < x < 9.5 and 1.5 < y < 11.0: continue
    if abs(x) < 1.6 and -1.0 < y < 2.4: continue
    if not clear(x, y, 0.8, TREES + [(*q, 0.7) for q in YUCCAS + [LANTERN_ROCKS]]): continue
    prop(f"desert_branch_{k}", "dry_branches_medium_01", (x, y, ground_h(x, y) - 0.03), yaw=random.uniform(0, 360),
         scale=random.uniform(0.7, 1.1), tris=1200)
    k += 1
# what each rock is near: none may stand in a tree, a shrub, the screen, the trailer or the seat
for o in [o for o in room.objects if o.name.startswith(("rock_", "rockb_")) and "stone" not in o.name]:
    vs = [v.co for v in o.data.vertices]; cx = sum(v.x for v in vs) / len(vs) + o.location.x; cy = sum(v.y for v in vs) / len(vs) + o.location.y
    hw = extent(o)[0]
    for (tx, ty, tr) in TREES + [(*q, 0.4) for q in YUCCAS + [LANTERN_ROCKS]] + [(0.0, 0.5, 1.6)]:
        if math.hypot(cx - tx, cy - ty) < hw + tr * 0.5: print("ROCK TOO NEAR", o.name, (round(tx, 1), round(ty, 1)))
# ---------------------------------------------------------------- Joshua trees, scrub
for (nm, b_, h_, sd, lv, sp) in JOSHUAS:
    joshua(nm, b_, h_, sd, levels=lv, spikes=sp, shag=38.0)
for k, (x, y, sz) in enumerate(SCRUBS):
    scrub(f"desert_scrub_{k}", (x, y), sz, k + 100)
for k, (x, y) in enumerate(YUCCAS):
    yucca(f"leaf_yucca_{k}", (x, y), 0.55, k + 200)
# far away: Joshua trees and bushes in silhouette on the plain, out to the horizon
far = []
for k in range(45):
    a = random.uniform(0, 2 * math.pi); r = random.uniform(18.0, 110.0)
    x, y = r * math.cos(a), 3.0 + r * math.sin(a)
    if abs(x) < 4 and y > 8 and y < 30: continue
    far.append(joshua(f"far_joshua_{k}", (x, y), random.uniform(3.5, 6.0), 300 + k, levels=2, spikes=18, blade=1.6))
for k in range(110):
    a = random.uniform(0, 2 * math.pi); r = random.uniform(15.0, 120.0)
    x, y = r * math.cos(a), 3.0 + r * math.sin(a)
    s = random.uniform(0.4, 0.9)
    far.append(blob("far_bush", (x, y, ground_h(x, y) + s * 0.2), (s, s, s * 0.6), M["scrubleaf"], subdiv=1, wobble=0.4, seed=k))
# the mountains all round, three ranges paling into the haze: each a heightfield in polar strips — the crest a ridged
# fractal (so its outline is broken, peaks and saddles), spurs and gullies running down its face — rising from the bajada
def bajada(r):
    return min(60.0, 28.0 * (max(0.0, r - 60.0) / 700.0) ** 2)


for (dist, depth, top, mat, seed, na, nr) in ((690, 420, 150, "range1", 3, 640, 14), (1250, 600, 250, "range2", 7, 420, 10),
                                              (2050, 800, 370, "range3", 11, 300, 8)):
    vs, fs = [], []
    for j in range(nr + 1):
        t = j / nr
        # the profile from front to back: rising to the crest two-thirds of the way back, falling a little behind it
        env = math.sin(math.pi / 2 * min(1.0, t / 0.65)) ** 1.4 if t <= 0.65 else 1.0 - 0.6 * (t - 0.65) / 0.35
        for i in range(na):
            a = 2 * math.pi * i / na
            r = dist + depth * t
            x, y = r * math.sin(a), 3.0 + r * math.cos(a)
            crest = 0.35 + 0.65 * max(0.0, min(1.0, 0.55 + 0.8 * noise.noise(Vector((math.cos(a) * 2.2, math.sin(a) * 2.2, seed)))))
            rough = noise.ridged_multi_fractal(Vector((x / 150.0, y / 150.0, seed)), 0.9, 2.1, 5, 1.0, 2.0) / 2.2
            vs.append((x, y, bajada(r) + (0.4 if j == 0 else 1.5) + top * env * crest * (0.55 + 0.45 * rough)))
    for j in range(nr):
        for i in range(na):
            a_, b_ = j * na + i, j * na + (i + 1) % na
            fs.append((a_, b_, b_ + na, a_ + na))
    face_toward(mesh_from(f"far_range_{seed}", vs, fs, M[mat], smooth=True), (0, 3, 10))

# ---------------------------------------------------------------- the screen on its frame
FY = SCREEN_Y
box("camp_screen_face", (0, FY + 0.01, SCREEN_FOOT + SCREEN_H / 2), (SCREEN_W, 0.02, SCREEN_H), M["screen"])
box("camp_screen_back", (0, FY + 0.03, SCREEN_FOOT + SCREEN_H / 2), (SCREEN_W + 0.04, 0.015, SCREEN_H + 0.04), M["frame"])
fw = SCREEN_W / 2 + 0.05
for sx in (-1, 1):
    box("camp_frame_post", (sx * fw, FY + 0.04, (SCREEN_FOOT + SCREEN_H + 0.06) / 2), (0.05, 0.05, SCREEN_FOOT + SCREEN_H + 0.06), M["frame"],
        bevel=0.006, segs=2)
    box("camp_frame_foot", (sx * fw, FY + 0.04, 0.025), (0.06, 1.0, 0.05), M["frame"], bevel=0.006, segs=2)
    box("camp_frame_foot_plate", (sx * fw, FY + 0.04, 0.008), (0.32, 0.32, 0.016), M["frame"])
for z in (SCREEN_FOOT - 0.03, SCREEN_FOOT + SCREEN_H + 0.03):
    box("camp_frame_bar", (0, FY + 0.04, z), (2 * fw + 0.05, 0.05, 0.05), M["frame"], bevel=0.006, segs=2)

# ---------------------------------------------------------------- the travel trailer
# 2026-10-24, the user, on the first one: "车子里面看起来也没有内容，外面的桌子看着也很奇怪 … 是不是也少轮子" — its doorway was
# covered by a solid aluminium "frame", its wheel arches opened into the room behind them, its table's legs splayed every
# way. Now: the door stands open on its front hinge (the seat looks in from behind it), the room is furnished where the eye
# goes through the door — a dinette under the far window, cabinets over it, the bed at the back, a red and cream floor —
# the wheels (scanned tyres, cream rims, chrome caps) sit in dark wells behind trimmed arches, and the skin has its seams.
TPOS = Vector((5.9, 6.4, 0.0)); TYAW = math.radians(75.0)
T = Matrix.Translation(TPOS) @ Matrix.Rotation(TYAW, 4, 'Z')   # local: x along it (front +x), y across (door side +y), z up
L, W, H, Z0 = 4.6, 2.05, 2.2, 0.42
AX, TYRE_R = 0.35, 0.30                                          # the axle, a little ahead of the middle; the scanned tyre
FLOOR = Z0 + 0.06                                                # the room's floor
IN_X, IN_Y = L / 2 - 0.35, W / 2 - 0.06                           # the room's half-length and half-width
tparts = []


def profile(n=10):
    pts = []
    corners = [((L / 2 - 0.15, Z0 + 0.15), 0.15, -90, 0), ((L / 2 - 0.85, Z0 + H - 0.85), 0.85, 0, 90),
               ((-L / 2 + 0.6, Z0 + H - 0.6), 0.6, 90, 180), ((-L / 2 + 0.15, Z0 + 0.15), 0.15, 180, 270)]
    for (cx, cz), r, a0, a1 in corners:
        for k in range(n + 1):
            a = math.radians(a0 + (a1 - a0) * k / n)
            pts.append((cx + r * math.cos(a), cz + r * math.sin(a)))
    return pts


PROF = profile()


def roof_z(x):
    """The roof's height over x: flat, rounding down at the front (0.85 m) and the back (0.6 m)."""
    for cx, r, ahead in ((L / 2 - 0.85, 0.85, True), (-L / 2 + 0.6, 0.6, False)):
        if (x > cx) if ahead else (x < cx):
            return Z0 + H - r + math.sqrt(max(0.0, r * r - (x - cx) ** 2))
    return Z0 + H
bm = bmesh.new()
f0 = bm.faces.new([bm.verts.new((x, -W / 2, z)) for x, z in PROF])
r_ = bmesh.ops.extrude_face_region(bm, geom=[f0])
for v in [g for g in r_["geom"] if isinstance(g, bmesh.types.BMVert)]: v.co.y = W / 2
bmesh.ops.recalc_face_normals(bm, faces=bm.faces)
me = bpy.data.meshes.new("camp_trailer_body"); bm.to_mesh(me); bm.free()
body = bpy.data.objects.new("camp_trailer_body", me); room.objects.link(body); me.materials.append(M["skin"])
md = body.modifiers.new("bevel", 'BEVEL'); md.width = 0.07; md.segments = 4; md.limit_method = 'ANGLE'; md.angle_limit = math.radians(40)
_apply(body, [md])


def cutter(c, s, mat=None, cylinder=False, rot=(0, 0, 0)):
    if cylinder:
        bpy.ops.mesh.primitive_cylinder_add(vertices=40, radius=s[0], depth=s[1], location=c, rotation=tuple(math.radians(r) for r in rot))
    else:
        bpy.ops.mesh.primitive_cube_add(size=1, location=c); bpy.context.active_object.scale = s
        bpy.ops.object.transform_apply(scale=True)
    o = bpy.context.active_object
    if mat: o.data.materials.append(mat)
    return o


def boolean(target, tool, op):
    tool.select_set(False)
    md = target.modifiers.new("b", 'BOOLEAN'); md.operation = op; md.object = tool; md.solver = 'EXACT'
    md.material_mode = 'TRANSFER'
    _apply(target, [md]); bpy.data.objects.remove(tool)


# the room inside, the doorway; the wheel boxes standing up into the room; the wells cut through the side into them
boolean(body, cutter((0.0, 0.0, FLOOR + (H - 0.36) / 2), (2 * IN_X, 2 * IN_Y, H - 0.36), M["trailer_in"]), 'DIFFERENCE')
boolean(body, cutter((-0.9, W / 2, Z0 + 0.1 + 0.89), (0.66, 0.4, 1.78), M["trailer_in"]), 'DIFFERENCE')
for side in (-1, 1):
    boolean(body, cutter((AX, side * (IN_Y - 0.16), FLOOR + 0.13), (0.96, 0.36, 0.30), M["trailer_in"]), 'UNION')
    boolean(body, cutter((AX, side * (W / 2 - 0.08), TYRE_R), (TYRE_R + 0.09, 0.56), M["well"], cylinder=True, rot=(90, 0, 0)),
            'DIFFERENCE')
for p in body.data.polygons: p.use_smooth = True
body.data.set_sharp_from_angle(angle=math.radians(35))
tparts.append(body)


def strip(name, pts, side, radius, mat, cyclic=False):
    """A thin round strip along `pts` ((x, z) on the side at `side`·y, or (x, y, z) when side is None)."""
    cv = bpy.data.curves.new(name, 'CURVE'); cv.dimensions = '3D'; cv.bevel_depth = radius; cv.bevel_resolution = 2
    sp = cv.splines.new('POLY'); sp.points.add(len(pts) - 1); sp.use_cyclic_u = cyclic
    for k, q in enumerate(pts): sp.points[k].co = ((q[0], side, q[1]) if side is not None else tuple(q)) + (1,)
    o = bpy.data.objects.new(name, cv); room.objects.link(o)
    bpy.context.view_layer.objects.active = o; o.select_set(True); bpy.ops.object.convert(target='MESH'); o.select_set(False)
    o = bpy.context.view_layer.objects.active; o.data.materials.append(mat); tparts.append(o)
    return o


# the trim along the profile on both sides; the arches' trim; the panels' seams; the belt
for side in (-1, 1):
    strip(f"camp_trailer_trim_{side}", PROF, side * (W / 2 - 0.02), 0.014, M["alu"], cyclic=True)
    arc = [(AX + (TYRE_R + 0.1) * math.cos(math.radians(a)), TYRE_R + (TYRE_R + 0.1) * math.sin(math.radians(a))) for a in range(20, 161, 10)]
    strip(f"camp_trailer_archtrim_{side}", arc, side * (W / 2 + 0.006), 0.012, M["alu"])
    for x in ((-1.37, -0.3, 1.78) if side > 0 else (-1.05, -0.2, 1.75)):
        top = roof_z(x) - 0.12
        tparts.append(box("camp_trailer_seam", (x, side * (W / 2 + 0.002), (Z0 + 0.06 + top) / 2), (0.014, 0.006, top - Z0 - 0.06),
                          M["skin"], bevel=0.003, segs=1))
    tparts.append(box("camp_trailer_belt", (0.0, side * (W / 2 + 0.003), 1.2), (L - 0.4, 0.008, 0.035), M["alu"]))
    tparts.append(box("camp_trailer_awningrail", (0.0, side * (W / 2 + 0.01), Z0 + H - 0.22), (L - 1.6, 0.02, 0.025), M["alu"]))
# windows: lit curtains behind aluminium frames, outside and in (the far ones are what the door shows)
WINDOWS = ((1.15, Z0 + 1.35, 0.95, 0.55, 1), (-1.85, Z0 + 1.35, 0.7, 0.5, 1), (1.0, Z0 + 1.35, 1.0, 0.55, -1),
           (-1.6, Z0 + 1.35, 0.8, 0.5, -1))
for (x, z, w, h, side) in WINDOWS:
    for y, d in ((side * (W / 2 + 0.004), 1), (side * (IN_Y - 0.004), -1)):
        tparts.append(box("camp_trailer_curtain", (x, y, z), (w, 0.01, h), M["curtain"]))
        for (dx, dz, sw, sh) in ((0, h / 2 + 0.02, w + 0.08, 0.04), (0, -h / 2 - 0.02, w + 0.08, 0.04), (-w / 2 - 0.02, 0, 0.04, h + 0.08),
                                 (w / 2 + 0.02, 0, 0.04, h + 0.08), (0, 0, 0.02, h)):
            tparts.append(box("camp_trailer_winframe", (x + dx, y + side * d * 0.008, z + dz), (sw, 0.02, sh), M["alu"] if d > 0 else M["cabinet"]))
    area_light(f"window_{x}_{side}", tuple(T @ Vector((x, side * (W / 2 + 0.15), z))), (w, h), (T.to_3x3() @ Vector((0, side, -0.2))),
               WARM, 8.0 * w * h, house)
# the front window over the tongue, its curtain lit
tparts.append(box("camp_trailer_curtain", (L / 2 + 0.004, 0.0, 1.45), (0.01, 1.3, 0.42), M["curtain"]))
for (dy, dz, sy, sz) in ((0, 0.23, 1.38, 0.04), (0, -0.23, 1.38, 0.04), (-0.67, 0, 0.04, 0.5), (0.67, 0, 0.04, 0.5), (0, 0, 0.02, 0.42)):
    tparts.append(box("camp_trailer_winframe", (L / 2 + 0.012, dy, 1.45 + dz), (0.02, sy, sz), M["alu"]))
# the door: open on its front hinge, a window in it; a thin frame round the doorway; a porch light over it
HINGE = Vector((-0.57, W / 2 + 0.02, 0.0)); OPEN = -100.0
tparts.append(box("camp_trailer_door", tuple(HINGE + Vector((0.32 * 0.174, 0.32 * 0.985, Z0 + 0.1 + 0.89))), (0.64, 0.035, 1.76),
                  M["skin"], bevel=0.02, segs=2, rot=(0, 0, OPEN)))
dn = Vector((math.cos(math.radians(OPEN + 90)), math.sin(math.radians(OPEN + 90)), 0))   # its outside face's normal, open
DC = HINGE + Vector((0.32 * 0.174, 0.32 * 0.985, 0))
tparts.append(box("camp_trailer_doorwin", tuple(DC + dn * 0.02 + Vector((0, 0, Z0 + 1.45))), (0.36, 0.01, 0.32), M["curtain_dark"], rot=(0, 0, OPEN)))
tparts.append(box("camp_trailer_doorknob", tuple(HINGE + Vector((0.6 * 0.174, 0.6 * 0.985, Z0 + 0.95)) + dn * 0.04), (0.03, 0.05, 0.03),
                  M["chrome"], rot=(0, 0, OPEN)))
for (x, z, sx, sz) in ((-1.245, Z0 + 0.99, 0.03, 1.84), (-0.555, Z0 + 0.99, 0.03, 1.84), (-0.9, Z0 + 1.905, 0.72, 0.03)):
    tparts.append(box("camp_trailer_doorframe", (x, W / 2 + 0.006, z), (sx, 0.014, sz), M["alu"]))
tparts.append(box("camp_trailer_porchlamp", (-0.9, W / 2 + 0.05, Z0 + 2.0), (0.14, 0.08, 0.1), M["lantern_glass"], bevel=0.01, segs=2))
tparts.append(box("camp_trailer_porchcap", (-0.9, W / 2 + 0.05, Z0 + 2.06), (0.17, 0.1, 0.02), M["alu"]))
point_light("porch_lamp", tuple(T @ Vector((-0.9, W / 2 + 0.12, Z0 + 1.98))), (1.0, 0.66, 0.34), 14.0, 0.04, house)
tparts.append(box("camp_trailer_vent", (0.9, W / 2 + 0.01, Z0 + 0.45), (0.3, 0.02, 0.18), M["alu"]))
tparts.append(box("camp_trailer_step", (-0.9, W / 2 + 0.33, 0.12), (0.62, 0.42, 0.24), M["steptimber"], bevel=0.015, segs=2))
# the wheels: scanned tyres on cream rims with chrome caps; the axle under the floor
for side in (-1, 1):
    tparts.append(prop("camp_trailer_tyre", "old_tyre", (AX, side * (W / 2 - 0.14), 0.0), tris=2000))
    tparts.append(cyl("camp_trailer_rim", (AX, side * (W / 2 - 0.08), TYRE_R), 0.19, 0.1, M["rimpaint"], verts=36, rot=(90, 0, 0)))
    tparts.append(cyl("camp_trailer_cap", (AX, side * (W / 2 - 0.02), TYRE_R), 0.12, 0.05, M["chrome"], verts=32, rot=(90, 0, 0), r2=0.07))
tparts.append(cyl("camp_trailer_axle", (AX, 0.0, TYRE_R), 0.035, W - 0.3, M["steel"], verts=10, rot=(90, 0, 0)))
# legs at the corners, wound down to the ground; the rear's bumper, lights and plate; the roof's air conditioner and vent
for (x, sy) in ((-L / 2 + 0.35, -1), (-L / 2 + 0.35, 1), (L / 2 - 0.45, -1), (L / 2 - 0.45, 1)):
    tparts.append(cyl("camp_trailer_leg", (x, sy * (W / 2 - 0.2), (Z0 + 0.02) / 2), 0.025, Z0 - 0.02, M["steel"], verts=8))
    tparts.append(box("camp_trailer_legfoot", (x, sy * (W / 2 - 0.2), 0.012), (0.12, 0.12, 0.024), M["steel"]))
tparts.append(box("camp_trailer_bumper", (-L / 2 - 0.06, 0.0, Z0 + 0.04), (0.08, W - 0.2, 0.08), M["chrome"], bevel=0.015, segs=2))
tparts.append(box("camp_trailer_plate", (-L / 2 - 0.004, 0.0, 0.78), (0.01, 0.32, 0.16), M["plate"]))
for side in (-1, 1):
    tparts.append(box("camp_trailer_taillight", (-L / 2 - 0.005, side * 0.72, 0.8), (0.03, 0.1, 0.16), M["red"], bevel=0.01, segs=2))
    tparts.append(box("camp_trailer_marker", (-L / 2 + 0.25, side * (W / 2 + 0.005), Z0 + H - 0.2), (0.06, 0.02, 0.04), M["red"]))
tparts.append(box("camp_trailer_ac", (0.4, 0.0, Z0 + H + 0.14), (0.75, 0.66, 0.28), M["alu"], bevel=0.06, segs=3))
tparts.append(box("camp_trailer_roofvent", (-1.3, 0.0, Z0 + H + 0.06), (0.4, 0.4, 0.12), M["alu"], bevel=0.02, segs=2))
# the tongue: an A-frame to the coupler, its jack, the gas bottle on it
for side in (-1, 1):
    a = Vector((L / 2 - 0.2, side * 0.55, 0.45)); b_ = Vector((L / 2 + 1.05, 0.0, 0.45)); d = b_ - a
    o = cyl("camp_trailer_tongue", tuple((a + b_) / 2), 0.035, d.length, M["steel"], verts=8)
    o.rotation_euler = d.to_track_quat('Z', 'Y').to_euler(); bpy.context.view_layer.objects.active = o; o.select_set(True)
    bpy.ops.object.transform_apply(rotation=True); o.select_set(False); tparts.append(o)
tparts.append(box("camp_trailer_coupler", (L / 2 + 1.1, 0.0, 0.47), (0.25, 0.12, 0.1), M["steel"], bevel=0.02, segs=2))
tparts.append(cyl("camp_trailer_jack", (L / 2 + 0.75, 0.0, 0.25), 0.03, 0.5, M["steel"], verts=10))
tparts.append(cyl("camp_trailer_jackfoot", (L / 2 + 0.75, 0.0, 0.02), 0.08, 0.04, M["steel"], verts=16))
tparts.append(box("camp_trailer_tankplate", (L / 2 + 0.5, 0.0, 0.47), (0.42, 0.7, 0.02), M["steel"]))
tparts.append(prop("camp_trailer_gas", "propane_tank", (L / 2 + 0.5, 0.0, 0.48), yaw=30.0, tris=1500))

# inside, where the door looks: the floor; a dinette under the far window — two benches facing across a table against the
# wall, a lamp and a coffee pot on it; cabinets over it; the bed across the back; a picture; the ceiling's light
tparts.append(box("camp_trailer_floor", (0.0, 0.0, FLOOR + 0.004), (2 * IN_X - 0.01, 2 * IN_Y - 0.01, 0.008), M["lino"]))
DX = 1.0
for bx, face in ((DX - 0.62, 1), (DX + 0.62, -1)):
    tparts.append(box("camp_trailer_bench", (bx, -IN_Y + 0.4, FLOOR + 0.2), (0.46, 0.8, 0.4), M["cabinet"], bevel=0.01, segs=2))
    tparts.append(box("camp_trailer_cushion", (bx, -IN_Y + 0.4, FLOOR + 0.45), (0.46, 0.78, 0.1), M["vinyl"], bevel=0.035, soft=1))
    tparts.append(box("camp_trailer_backrest", (bx - face * 0.2, -IN_Y + 0.4, FLOOR + 0.78), (0.1, 0.78, 0.56), M["vinyl"], bevel=0.035, soft=1))
tparts.append(box("camp_trailer_tabletop", (DX, -IN_Y + 0.38, FLOOR + 0.72), (0.74, 0.72, 0.03), M["laminate"], bevel=0.008, segs=2))
tparts.append(box("camp_trailer_tableedge", (DX, -IN_Y + 0.74, FLOOR + 0.72), (0.76, 0.012, 0.04), M["chrome"]))
tparts.append(cyl("camp_trailer_tableleg", (DX, -IN_Y + 0.45, FLOOR + 0.36), 0.03, 0.7, M["chrome"], verts=12))
tparts.append(cyl("camp_trailer_lampbase", (DX + 0.18, -IN_Y + 0.2, FLOOR + 0.84), 0.035, 0.2, M["alu"], verts=16))
tparts.append(cyl("camp_trailer_lampshade", (DX + 0.18, -IN_Y + 0.2, FLOOR + 1.0), 0.12, 0.15, M["shade"], verts=24, r2=0.07, cap=False))
tparts.append(cyl("camp_trailer_coffeepot", (DX - 0.15, -IN_Y + 0.3, FLOOR + 0.82), 0.06, 0.17, M["enamel"], verts=20, r2=0.045))
tparts.append(cyl("camp_trailer_cup", (DX + 0.05, -IN_Y + 0.55, FLOOR + 0.775), 0.035, 0.08, M["enamel"], verts=16))
for x in (DX - 0.45, DX, DX + 0.45):
    tparts.append(box("camp_trailer_upper", (x, -IN_Y + 0.17, Z0 + H - 0.36), (0.44, 0.34, 0.36), M["cabinet"], bevel=0.008, segs=2))
    tparts.append(box("camp_trailer_upperknob", (x + 0.15, -IN_Y + 0.345, Z0 + H - 0.46), (0.02, 0.012, 0.05), M["chrome"]))
tparts.append(box("camp_trailer_bed", (-IN_X + 0.35, 0.0, FLOOR + 0.22), (0.7, 2 * IN_Y - 0.02, 0.44), M["cabinet"]))
tparts.append(box("camp_trailer_mattress", (-IN_X + 0.35, 0.0, FLOOR + 0.52), (0.68, 2 * IN_Y - 0.04, 0.16), M["bedding"], bevel=0.04, soft=1))
tparts.append(box("camp_trailer_quilt", (-IN_X + 0.4, 0.0, FLOOR + 0.62), (0.58, 2 * IN_Y - 0.02, 0.06), M["quilt"], bevel=0.025, soft=1))
for sy in (-0.4, 0.4):
    tparts.append(box("camp_trailer_pillow", (-IN_X + 0.15, sy, FLOOR + 0.7), (0.24, 0.6, 0.12), M["bedding"], bevel=0.05, soft=2))
tparts.append(box("camp_trailer_picture", (-0.55, -IN_Y + 0.012, Z0 + 1.45), (0.36, 0.02, 0.3), M["cabinet"], bevel=0.01, segs=2))
tparts.append(box("camp_trailer_picture_face", (-0.55, -IN_Y + 0.025, Z0 + 1.45), (0.28, 0.01, 0.22), M["picture"]))
tparts.append(cyl("camp_trailer_ceilinglamp", (0.2, 0.0, Z0 + H - 0.31), 0.13, 0.05, M["shade"], verts=24, r2=0.09))

# outside: by the rear corner the folding table with the lantern (scans), a jerrycan by the front
TB = Vector((-2.3, W / 2 + 1.0, 0.0))
tparts.append(prop("camp_table", "folding_wooden_stool", tuple(TB), yaw=12.0, scale=1.2, tris=3000))
tparts.append(prop("camp_jerrycan", "metal_jerrycan_green", (1.95, W / 2 + 0.62, 0.0), yaw=70.0, tris=2000))
for o in tparts:
    o.matrix_world = T @ o.matrix_world
    bpy.context.view_layer.objects.active = o; o.select_set(True)
    bpy.ops.object.transform_apply(location=True, rotation=True, scale=True); o.select_set(False)
area_light("trailer_inside", tuple(T @ Vector((-0.3, 0.0, Z0 + H - 0.36))), (2.2, 1.4), (0, 0, -1), WARM, 140.0, house)
point_light("trailer_lamp", tuple(T @ Vector((DX + 0.18, -IN_Y + 0.2, FLOOR + 0.98))), (1.0, 0.65, 0.32), 30.0, 0.05, house)
# the room's light falling out of the door onto the step and the sand (not onto the open door beside it: 40 W there
# burnt it white)
area_light("trailer_door_spill", tuple(T @ Vector((-0.9, W / 2 + 0.3, Z0 + 1.5))), (0.6, 0.6), (T.to_3x3() @ Vector((0, 0.5, -1))),
           WARM, 14.0, house)


# ---------------------------------------------------------------- lanterns: on the table, and by the rocks (scans)
def lantern(name, foot, yaw=0.0):
    """Poly Haven's Lantern_01: its brass decimated, its globe glowing (house), a flame in it and its light; returns the
    globe's middle, for its halo."""
    o = prop(name, "Lantern_01", tuple(foot), yaw=yaw, tris=2200, glass=M["lantern_glass"])
    gz = [v.co.z for p_ in o.data.polygons if o.data.materials[p_.material_index] == M["lantern_glass"] for v in [o.data.vertices[i] for i in p_.vertices]]
    c = Vector(foot) + Vector((0, 0, (min(gz) + max(gz)) / 2 - foot[2] if gz else 0.12))
    bpy.ops.mesh.primitive_uv_sphere_add(segments=12, ring_count=8, radius=1.0, location=(0, 0, 0))
    fl = bpy.context.active_object; fl.name = f"{name}_flame"
    for v in fl.data.vertices: v.co = Vector((v.co.x * 0.007, v.co.y * 0.007, v.co.z * 0.018))
    fl.location = c; fl.data.materials.append(M["flame"]); _link(fl, room)
    point_light(f"lantern_{name}", tuple(c), (1.0, 0.6, 0.26), 12.0, 0.03, house)
    return c


TABLE_TOP = T @ (TB + Vector((0.0, 0.0, 0.442 * 1.2)))
LANTERN_TABLE = lantern("camp_lantern_table", TABLE_TOP, yaw=20.0)
PL = Vector((*LANTERN_ROCKS, ground_h(*LANTERN_ROCKS)))
ROCK_GLOW = lantern("camp_lantern_rocks", PL, yaw=-30.0)

# ---------------------------------------------------------------- near: the chair, the rug, the drum table and the mug
for sx in (-1, 1):
    box("near_chair_arm", (sx * 0.34, 0.12, 0.64), (0.095, 0.66, 0.035), M["chairwood"], bevel=0.014, soft=2)
    box("near_chair_frontpost", (sx * 0.34, 0.38, 0.31), (0.055, 0.055, 0.62), M["chairwood"], bevel=0.008, segs=4)
    box("near_chair_backpost", (sx * 0.34, -0.2, 0.47), (0.055, 0.055, 0.94), M["chairwood"], bevel=0.008, segs=4, rot=(-8, 0, 0))
    box("near_chair_siderail", (sx * 0.34, 0.09, 0.16), (0.04, 0.58, 0.05), M["chairwood"], bevel=0.006, segs=3)
    box("near_chair_seatrail", (sx * 0.3, 0.09, 0.42), (0.035, 0.6, 0.04), M["chairwood"], bevel=0.006, segs=3)
box("near_chair_frontrail", (0, 0.38, 0.40), (0.62, 0.04, 0.045), M["chairwood"], bevel=0.006, segs=3)
# the canvas seat: a sling sagging between its rails, rolled over the front rail
sv, sf = [], []
NXS, NYS = 40, 36
for j in range(NYS + 1):
    t = j / NYS
    for i in range(NXS + 1):
        u = i / NXS; x = -0.28 + 0.56 * u
        y = -0.18 + 0.58 * t
        sag = 0.07 * math.sin(math.pi * u) * math.sin(math.pi * min(t / 0.92, 1.0))
        roll = 0.0
        if t > 0.92:  # over the front rail and down
            k = (t - 0.92) / 0.08; y = 0.40 + 0.03 * math.sin(math.pi * k); roll = -0.05 * k
        sv.append((x, y, 0.44 - sag + roll + 0.004 * noise.noise(Vector((x * 30, y * 30, 0)))))
for j in range(NYS):
    for i in range(NXS):
        a = j * (NXS + 1) + i
        sf.append((a, a + 1, a + NXS + 2, a + NXS + 1))
sling = face_toward(mesh_from("near_chair_seat", sv, sf, M["canvas"], smooth=True), (0, 0.1, 1.5))
sling.data.uv_layers.new(name="UVMap")
for li, lp in enumerate(sling.data.loops):
    co = sling.data.vertices[lp.vertex_index].co; sling.data.uv_layers[0].data[li].uv = (co.x, co.y)
box("near_chair_back", (0, -0.22, 0.78), (0.6, 0.02, 0.4), M["canvas"], bevel=0.008, soft=1, rot=(-8, 0, 0))
# the rug: chunky woven beads in rows, the relief in the mesh — what the eye rests on before the screen
RX0, RX1, RY0, RY1 = -0.9, 0.9, 0.42, 1.75
rv, rf = [], []
STEP = 0.0115
nx_, ny_ = int((RX1 - RX0) / STEP), int((RY1 - RY0) / STEP)
BEAD = 0.032
for j in range(ny_ + 1):
    for i in range(nx_ + 1):
        x = RX0 + i * STEP; y = RY0 + j * STEP
        row = math.floor((y - RY0) / BEAD)
        bx = ((x - RX0) / BEAD + (0.5 if row % 2 else 0.0)) % 1.0; by = ((y - RY0) / BEAD) % 1.0
        bead = max(0.0, 1.0 - ((bx - 0.5) / 0.55) ** 2 - ((by - 0.5) / 0.6) ** 2) ** 0.5
        edge = min(x - RX0, RX1 - x, y - RY0, RY1 - y)
        rim = 1.0 if edge > 0.04 else 0.6 + 10 * edge
        rv.append((x, y, ground_h(x, y) + 0.008 + 0.012 * bead * rim))
for j in range(ny_):
    for i in range(nx_):
        a = j * (nx_ + 1) + i
        rf.append((a, a + 1, a + nx_ + 2, a + nx_ + 1))
face_toward(mesh_from("near_rug", rv, rf, M["rug"], smooth=True), (0, 1, 3))
for (c, s) in [((0, RY0, 0.008), (RX1 - RX0, 0.02, 0.016)), ((0, RY1, 0.008), (RX1 - RX0, 0.02, 0.016)),
               ((RX0, (RY0 + RY1) / 2, 0.008), (0.02, RY1 - RY0, 0.016)), ((RX1, (RY0 + RY1) / 2, 0.008), (0.02, RY1 - RY0, 0.016))]:
    box("near_rug_edge", c, s, M["rug"], bevel=0.007, segs=3)
# the drum table, the enamel mug on it
DT = Vector((-0.78, 0.62, 0.0))
drum = uv_box(cyl("near_drum", tuple(DT + Vector((0, 0, 0.26))), 0.25, 0.52, M["terracotta"], verts=72))
md = drum.modifiers.new("bevel", 'BEVEL'); md.width = 0.03; md.segments = 5; md.limit_method = 'ANGLE'; _apply(drum, [md])
for p in drum.data.polygons: p.use_smooth = True
MG = DT + Vector((0.05, 0.05, 0.52))
cyl("near_mug_body", tuple(MG + Vector((0, 0, 0.045))), 0.042, 0.09, M["enamel"], verts=48, cap=False)
cyl("near_mug_inside", tuple(MG + Vector((0, 0, 0.047))), 0.039, 0.086, M["enamel"], verts=48, cap=False)
cyl("near_mug_coffee", tuple(MG + Vector((0, 0, 0.072))), 0.039, 0.004, M["coffee"], verts=40)
cyl("near_mug_base", tuple(MG + Vector((0, 0, 0.002))), 0.042, 0.004, M["enamel"], verts=48)
bpy.ops.mesh.primitive_torus_add(major_radius=0.0425, minor_radius=0.0028, major_segments=48, minor_segments=8, location=MG + Vector((0, 0, 0.09)))
rr = bpy.context.active_object; rr.name = "near_mug_rim"; rr.data.materials.append(M["enamel_rim"]); _link(rr, room)
bpy.ops.mesh.primitive_torus_add(major_radius=0.026, minor_radius=0.0055, major_segments=28, minor_segments=8,
                                 location=MG + Vector((0.055, 0, 0.048)), rotation=(math.radians(90), 0, 0))
hd = bpy.context.active_object; hd.name = "near_mug_handle"; hd.data.materials.append(M["enamel"]); _link(hd, room)
for p in list(hd.data.polygons) + list(rr.data.polygons): p.use_smooth = True

# ---------------------------------------------------------------- the sky dome and the dusk's light
bpy.ops.mesh.primitive_uv_sphere_add(segments=64, ring_count=32, radius=3500.0, location=(0, 0, 0))
sky = bpy.context.active_object; sky.name = "sky_dome"
bpy.ops.object.mode_set(mode='EDIT'); bpy.ops.mesh.select_all(action='SELECT'); bpy.ops.mesh.flip_normals()
bpy.ops.object.mode_set(mode='OBJECT')
sky.data.materials.append(M["sky"]); _link(sky, room)
for p in sky.data.polygons: p.use_smooth = True
sky.visible_diffuse = False; sky.visible_glossy = False; sky.visible_shadow = False; sky.visible_transmission = False
world = bpy.data.worlds.new("dusk"); world.use_nodes = True
bg = world.node_tree.nodes["Background"]; bg.inputs["Color"].default_value = (0.045, 0.065, 0.19, 1); bg.inputs["Strength"].default_value = 2.0
scene.world = world
# the afterglow from the west: a broad warm light low on the horizon ahead
glow_at = WEST * 300 + Vector((0, 3, 12))
area_light("afterglow", tuple(glow_at), (260, 40), -WEST, (1.0, 0.55, 0.38), 14000.0, base, along=(0, 0, 1))
# house: the warm light over the camp in the lit reference — the lamps of the camp out of view, soft
area_light("camp_wash", (0.5, -5.0, 6.0), (6, 3), Vector((0, 1, -0.9)), WARM, 650.0, house, along=(1, 0, 0))
area_light("screen_glow_ambc", (0, SCREEN_Y - 0.03, SCREEN_FOOT + SCREEN_H / 2), (SCREEN_W, SCREEN_H), Vector((0, -1, 0)),
           (1.0, 1.0, 1.0), math.pi * SCREEN_W * SCREEN_H, ambc, along=(0, 0, 1))
scene["film_tint"] = (0.62, 0.70, 0.85)
scene["film_mean"] = 0.35


# ---------------------------------------------------------------- effects: the lanterns' halos (drawn added with the lights)
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
    return (k, k * 0.62, k * 0.32)


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


halo(LANTERN_TABLE, 0.45)
halo(ROCK_GLOW, 0.45)
hme = bpy.data.meshes.new("fxh_halo"); hbm.to_mesh(hme); hbm.free()
hob = bpy.data.objects.new("fxh_halo", hme); fx.objects.link(hob); hme.materials.append(halo_mat)

# ---------------------------------------------------------------- the seat's camera
cam_data = bpy.data.cameras.new("seat"); cam = bpy.data.objects.new("seat", cam_data); scene.collection.objects.link(cam)
cam.location = (SEAT[0], SEAT[1], SEAT[2] + EYE)
look = Vector(SCREEN_CENTER) - cam.location
cam.rotation_euler = (math.radians(90) + math.atan2(look.z, math.hypot(look.x, look.y)), 0, 0)
cam_data.sensor_fit = 'HORIZONTAL'; cam_data.angle = math.radians(100); cam_data.clip_start = 0.05; cam_data.clip_end = 8000
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
    print(f"DESERT saved {OUT}: {len(meshes)} meshes, {sum(by.values())} triangles {by}, "
          f"{len(house.objects)} house lights, {len(base.objects)} base lights")
