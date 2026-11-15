"""
private_theater.py — the private cinema of the user's reference "C" (warm wood slats, leather recliners, LED lines),
built from nothing in Blender: geometry, procedural materials and two groups of lights.

  Blender --background --factory-startup --python tools/envbake/scenes/private_theater.py -- OUT.blend [--detail 0-3]
      [--assets DIR]

The leather, the carpet, the walls' fabric, the velvet and the wood are Poly Haven scans (CC0 — no credit owed): brown_leather,
poly_wool_herringbone, ribbed_corduroy, velour_velvet, teak_veneer, read through real-size UVs (uv_box; the recliners'
swept parts along their sweep), each tinted to the colour the room was designed in (2026-10-26: the procedural colours
read as flat CG). DIR (or $AURA_ENV_ASSETS) is their folder, as tools/envbake/polyhaven_get.py fetches it. A star ceiling
(fibre-optic points, glowing in the base layer — through the film) and velvet curtains framing the screen came with them.

Blender axes: x right, y toward the screen, z up (glTF: x, z, -y). The viewer sits in the second row's middle seat,
one recliner either side and no others (`NEAR_SEATS`); `SEAT` below is the floor under them and `EYE` their seated eye
height. Lights are in two collections, which the
bake keeps apart: "house" (the room's lights — ceiling lines, wall washes, step lights; the switch turns them) and
"base" (what stays when they are off: the screen's own glow). Every size is in metres.

--detail sets how finely it is modelled, for finding what the headset can carry: 0 low (few bevel segments, plain
seats), 1 mid (as first built), 2 high (rounder leather — bevels subdivided — channel-tufted backs, a footrest seam,
the back wall's skyline diffuser), 3 ultra (all of it finer, and quilted wall panels).
"""
import bpy, bmesh, math, sys, os, random
from mathutils import Vector

ARGS = sys.argv[sys.argv.index("--") + 1:] if "--" in sys.argv else []
OUT = ARGS[0] if ARGS else None
DETAIL = int(ARGS[ARGS.index("--detail") + 1]) if "--detail" in ARGS else 1
ASSETS = ARGS[ARGS.index("--assets") + 1] if "--assets" in ARGS else os.environ.get("AURA_ENV_ASSETS", "")
assert os.path.isdir(os.path.join(ASSETS, "brown_leather")), "--assets: the Poly Haven scans (tools/envbake/polyhaven_get.py)"
SEG = [0.34, 1.0, 1.6, 2.4][DETAIL]       # bevel segments, as a share of the mid level's
SUBD = [0, 0, 1, 2][DETAIL]               # subdivision of the leather's rounded parts
ROLL_VERTS = [10, 24, 40, 64][DETAIL]     # round parts' sides
NSLAT = [5, 9, 9, 11][DETAIL]             # walnut slats per panel
TUFT = DETAIL >= 2                        # channel-tufted backs, a footrest seam
DIFFUSER = DETAIL >= 2                    # the back wall's skyline diffuser
QUILT = DETAIL >= 3                       # wall panels quilted in horizontal channels
BACKSTITCH = DETAIL >= 2                  # stitched seams on the shells' backs — what the rows behind look at
SCULPT = DETAIL >= 2                      # backs and cushions swept from side profiles and subdivided, not boxes
# The three recliners are all the seating there is (2026-10-23, the user's word: the rows of low-poly seats made the near
# ones look cheap), so they take the triangles the crowd had — about 24万 in all — where the seat shows them: the swept
# backs and cushions (the neighbours' outlines, the viewer's own lap) subdivided three levels more, the head pads at eye
# height two, the padded boxes and the arms under the hands one — seen from a hand's length, not from three rows back.
CHAIR_SOFT = SUBD + 1 if SUBD else 0      # subdivision of a recliner's padded boxes (lumbar channels, footrest, arm tops)
CHAIR_HEAD = SUBD + 2 if SUBD else 0      # of its head pad
CHAIR_CAGE = SUBD + 3 if SUBD else 1      # of its swept back and cushion

# ---------------------------------------------------------------- the room's measures
W = 7.2            # inner width
FRONT_Y = 0.0      # the screen wall's face
BACK_Y = -9.6      # the back wall's face
H = 3.5            # ceiling height
STAGE_D = 1.25     # stage depth from the screen wall
STAGE_H = 0.38
SCREEN_W = 5.4
SCREEN_ASPECT = 2.39
SCREEN_FOOT = 0.95  # above the front floor
# Each row stands 0.36 m above the one before (stadium seating). At 0.28 a viewer sitting low — a sofa puts the eye
# about 1.0 m up, and the room's floor is the real one — saw the backs of the row ahead reach the screen's foot, and
# under 0.95 m cut into it; at 0.36 a 1.0 m eye sees the backs ahead 4.6–5.4° under the screen's foot, about what a
# 1.15 m eye had (5.5°), and the whole screen shows down to a 0.9 m eye (ray-cast, 2026-10-22).
ROWS = [  # (front edge of the seat row's riser, riser height, seats)
    (-3.05, 0.00, 8),
    (-4.75, 0.36, 6),
    (-6.45, 0.72, 5),
]
ROW_DEPTH = 1.70   # each riser's depth
SEAT_PITCH = 0.86  # seat centre to seat centre
# The viewer's row and the seats in it, either side of the middle one (2026-10-23: the second row — the back one sat a
# little far from the screen — and three recliners only).
VIEW_ROW = 1
NEAR_SEATS = 3
SEAT = (0.0, ROWS[VIEW_ROW][0] - 0.62, ROWS[VIEW_ROW][1])   # floor under the viewer (middle seat): x, y, z
EYE = 1.15

# The step lines stay on at this share of their light with the room's lights off, as a cinema's do through the film:
# the seats and the aisle stay findable, and the dark room has its glow. They are in both layers — this share in
# "base", the rest in "house" — so lights on is still the whole of them.
STEP_BASE = 0.12

SCREEN_H = SCREEN_W / SCREEN_ASPECT
SCREEN_CENTER = (0.0, FRONT_Y - 0.06, SCREEN_FOOT + SCREEN_H / 2)

bpy.ops.wm.read_factory_settings(use_empty=True)
scene = bpy.context.scene
house = bpy.data.collections.new("house"); scene.collection.children.link(house)
base = bpy.data.collections.new("base"); scene.collection.children.link(base)
room = bpy.data.collections.new("room"); scene.collection.children.link(room)
# the screen's light in three zones (left, centre, right thirds), each baked alone in white, for the app to tint with
# what the film shows there (动态氛围光); and the effects (the LED lines' halos), which are not baked: drawn added on top
zones = {}
for z in ("ambl", "ambc", "ambr"):
    zones[z] = bpy.data.collections.new(z); scene.collection.children.link(zones[z])
fx = bpy.data.collections.new("fx"); scene.collection.children.link(fx)


# ---------------------------------------------------------------- materials
def mat(name, color, rough=0.6, metal=0.0, bump=None, emission=None, strength=0.0):
    """A Principled material; `bump` = (kind, scale, strength) for a procedural surface (fabric, carpet, leather,
    wood) whose colour also varies a little with it."""
    m = bpy.data.materials.new(name); m.use_nodes = True
    nt = m.node_tree; b = nt.nodes["Principled BSDF"]
    b.inputs["Roughness"].default_value = rough
    b.inputs["Metallic"].default_value = metal
    col = nt.nodes.new("ShaderNodeRGB"); col.outputs[0].default_value = (*color, 1.0)
    if bump:
        kind, scale, strength_b = bump
        tc = nt.nodes.new("ShaderNodeTexCoord")
        if kind == "wood":
            # walnut: long grain along the object's local x, dark streaks
            mp = nt.nodes.new("ShaderNodeMapping"); mp.inputs["Scale"].default_value = (1.0, 18.0, 18.0)
            nt.links.new(tc.outputs["Object"], mp.inputs[0])
            nz = nt.nodes.new("ShaderNodeTexNoise"); nz.inputs["Scale"].default_value = scale
            nz.inputs["Detail"].default_value = 8.0; nz.inputs["Distortion"].default_value = 2.5
            nt.links.new(mp.outputs[0], nz.inputs[0]); tex = nz
        elif kind == "carpet":
            nz = nt.nodes.new("ShaderNodeTexVoronoi"); nz.inputs["Scale"].default_value = scale
            nt.links.new(tc.outputs["Object"], nz.inputs[0]); tex = nz
        else:  # fabric / leather: fine noise
            nz = nt.nodes.new("ShaderNodeTexNoise"); nz.inputs["Scale"].default_value = scale
            nz.inputs["Detail"].default_value = 10.0
            nt.links.new(tc.outputs["Object"], nz.inputs[0]); tex = nz
        ramp = nt.nodes.new("ShaderNodeValToRGB")
        ramp.color_ramp.elements[0].color = (*[c * 0.62 for c in color], 1)
        ramp.color_ramp.elements[1].color = (*[min(1.0, c * 1.18) for c in color], 1)
        nt.links.new(tex.outputs[0] if kind != "carpet" else tex.outputs["Distance"], ramp.inputs[0])
        nt.links.new(ramp.outputs[0], b.inputs["Base Color"])
        bp = nt.nodes.new("ShaderNodeBump"); bp.inputs["Strength"].default_value = strength_b
        nt.links.new(tex.outputs[0] if kind != "carpet" else tex.outputs["Distance"], bp.inputs["Height"])
        nt.links.new(bp.outputs[0], b.inputs["Normal"])
    else:
        nt.links.new(col.outputs[0], b.inputs["Base Color"])
    if emission is not None:
        b.inputs["Emission Color"].default_value = (*emission, 1.0)
        b.inputs["Emission Strength"].default_value = strength
    return m


def uv_box(o):
    """UVs in metres on each face of a box-like mesh, u along the longer of the face's two in-plane axes: a slat's grain
    runs along the slat, a panel's ribs and a frame's velvet along their length."""
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


def scan_uv(name, tex, tile, tint=None, sat=1.0, col="diff", rot=0.0, normal=1.0, rough=None):
    """A material from a Poly Haven scan in ASSETS read through the mesh's own UVs ("UVMap", in metres), `tile` metres a
    repeat, turned `rot` degrees in the UV plane; `sat` then `tint` bring its colour to the room's design (the scan keeps
    its light and shade, its grain, its weave), its normal map on the UVs' tangents."""
    d = os.path.join(ASSETS, tex)

    def img(kind, colour):
        f = next((x for x in sorted(os.listdir(d)) if f"_{kind}_" in x), None)
        if f is None: return None
        i = bpy.data.images.load(os.path.join(d, f), check_existing=True)
        if not colour: i.colorspace_settings.name = "Non-Color"
        return i
    m = bpy.data.materials.new(name); m.use_nodes = True
    nt = m.node_tree; b = nt.nodes["Principled BSDF"]
    uvn = nt.nodes.new("ShaderNodeUVMap"); uvn.uv_map = "UVMap"
    mp = nt.nodes.new("ShaderNodeMapping"); mp.inputs["Scale"].default_value = (1 / tile, 1 / tile, 1 / tile)
    mp.inputs["Rotation"].default_value = (0, 0, math.radians(rot))
    nt.links.new(uvn.outputs[0], mp.inputs[0])

    def tex_node(image):
        t = nt.nodes.new("ShaderNodeTexImage"); t.image = image; nt.links.new(mp.outputs[0], t.inputs[0]); return t.outputs[0]
    c = tex_node(img(col, True))
    if sat != 1.0:
        hs = nt.nodes.new("ShaderNodeHueSaturation"); hs.inputs["Saturation"].default_value = sat
        nt.links.new(c, hs.inputs["Color"]); c = hs.outputs[0]
    if tint:
        mx = nt.nodes.new("ShaderNodeMix"); mx.data_type = 'RGBA'; mx.blend_type = 'MULTIPLY'; mx.inputs["Factor"].default_value = 1.0
        nt.links.new(c, mx.inputs[6]); mx.inputs[7].default_value = (*tint, 1); c = mx.outputs[2]
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
    "fabric": scan_uv("wall_fabric", "ribbed_corduroy", 0.4, sat=0.0, tint=(0.255, 0.218, 0.187), rot=90.0),
    "ceiling": scan_uv("ceiling_fabric", "velour_velvet", 0.5, sat=0.0, tint=(0.048, 0.048, 0.052)),
    "walnut": scan_uv("walnut", "teak_veneer", 0.9, tint=(0.196, 0.179, 0.211)),
    "walnut_dark": scan_uv("walnut_dark", "teak_veneer", 0.9, tint=(0.092, 0.089, 0.116)),
    "black": scan_uv("masking", "velour_velvet", 0.5, sat=0.0, tint=(0.029, 0.029, 0.029)),
    "curtain": scan_uv("curtain_velvet", "velour_velvet", 0.6, tint=(0.24, 0.69, 0.24)),  # deep oxblood
    "stage": mat("stage", (0.03, 0.028, 0.026), 0.8),
    "carpet": scan_uv("carpet", "poly_wool_herringbone", 0.6, sat=0.0, tint=(0.31, 0.20, 0.125)),
    "leather": scan_uv("leather", "brown_leather", 0.4, sat=0.0, tint=(3.27, 1.88, 1.03), col="albedo"),
    "plastic": mat("cupholder", (0.015, 0.015, 0.015), 0.4),
    "steel": mat("brushed_steel", (0.55, 0.55, 0.56), 0.32, metal=1.0),
    "seam": mat("leather_seam", (0.17, 0.097, 0.053), 0.5),  # piping a shade darker than the leather: dark welts read as brackets
    "led": mat("led_strip", (1.0, 0.75, 0.48), 0.5, emission=(1.0, 0.70, 0.42), strength=0.0),
    # the step lines (risers, stage): their own material — a share of them stays lit in the base layer (STEP_BASE)
    "led_step": mat("led_step", (1.0, 0.75, 0.48), 0.5, emission=(1.0, 0.70, 0.42), strength=0.0),
    "screen": mat("screen", (0.8, 0.8, 0.8), 0.9, emission=(0.62, 0.70, 0.85), strength=0.0),
    # the star ceiling's points: fibre ends, glowing in the base layer alone (bake_layers' "base_emission") — on through the
    # film, as a cinema's are, faint
    "star": mat("star_point", (0.9, 0.85, 0.75), 0.5, emission=(1.0, 0.88, 0.72), strength=0.0),
}
M["star"]["base_emission"] = 6.0


# ---------------------------------------------------------------- geometry helpers
def box(name, cx, cy, cz, sx, sy, sz, material, coll=room, bevel=0.0, segs=3, soft=False, levels=None):
    """A box centred at (cx, cy, cz) sized (sx, sy, sz), bevelled (segments scaled by the detail level) and applied;
    `soft` (leather) also subdivided at the high levels, rounder — `levels` times if given, else SUBD."""
    bpy.ops.mesh.primitive_cube_add(size=1, location=(cx, cy, cz))
    o = bpy.context.active_object; o.name = name; o.scale = (sx, sy, sz)
    bpy.ops.object.transform_apply(location=False, rotation=False, scale=True)
    uv_box(o)
    if bevel > 0:
        # a part to be subdivided takes a coarse bevel: the subdivision rounds it, and a fine one would multiply
        n = 3 if (soft and SUBD) else max(1, round(segs * SEG))
        md = o.modifiers.new("bevel", 'BEVEL'); md.width = bevel; md.segments = n; md.limit_method = 'ANGLE'
        bpy.ops.object.modifier_apply(modifier=md.name)
        if soft and SUBD:
            lv = levels or SUBD
            md = o.modifiers.new("soft", 'SUBSURF'); md.levels = lv; md.render_levels = lv
            bpy.ops.object.modifier_apply(modifier=md.name)
    o.data.materials.append(material)
    if bevel > 0:
        try:
            bpy.ops.object.shade_smooth_by_angle(angle=math.radians(35))
        except Exception:
            bpy.ops.object.shade_smooth()
    for c in o.users_collection: c.objects.unlink(o)
    coll.objects.link(o)
    return o


def plane(name, verts, material, coll=room):
    """A quad from four corners (counter-clockwise seen from its lit side)."""
    me = bpy.data.meshes.new(name); me.from_pydata(verts, [], [list(range(len(verts)))]); me.update()
    o = bpy.data.objects.new(name, me); coll.objects.link(o); me.materials.append(material)
    return uv_box(o)


def area_light(name, loc, size, rot, color, energy, coll):
    l = bpy.data.lights.new(name, 'AREA'); l.shape = 'RECTANGLE'; l.size, l.size_y = size
    l.color = color; l.energy = energy
    o = bpy.data.objects.new(name, l); o.location = loc; o.rotation_euler = rot; coll.objects.link(o)
    return o


def spot(name, loc, rot, color, energy, angle, blend, coll, radius=0.03):
    l = bpy.data.lights.new(name, 'SPOT'); l.color = color; l.energy = energy
    l.spot_size = angle; l.spot_blend = blend; l.shadow_soft_size = radius
    o = bpy.data.objects.new(name, l); o.location = loc; o.rotation_euler = rot; coll.objects.link(o)
    return o


WARM = (1.0, 0.72, 0.46)
HX = W / 2

# ---------------------------------------------------------------- floor, risers, stage
box("floor", 0, (FRONT_Y + BACK_Y) / 2, -0.05, W, FRONT_Y - BACK_Y, 0.1, M["carpet"])
for i, (front, rise, n) in enumerate(ROWS):
    if rise > 0:
        back = BACK_Y if i == len(ROWS) - 1 else ROWS[i + 1][0]
        box(f"riser_{i}", 0, (front + back) / 2, rise / 2, W, front - back, rise, M["carpet"], bevel=0.01, segs=1)
box("stage", 0, FRONT_Y - STAGE_D / 2, STAGE_H / 2, W - 1.4, STAGE_D, STAGE_H, M["stage"], bevel=0.01, segs=1)
# a warm line under each riser's nose (house), washing the carpet of the row below it forward
for i, (front, rise, n) in enumerate(ROWS):
    if rise > 0:
        box(f"riser_led_{i}", 0, front + 0.004, rise - 0.025, W - 0.6, 0.008, 0.01, M["led_step"])
        for coll, share, tag in ((house, 1 - STEP_BASE, ""), (base, STEP_BASE, "_base")):
            area_light(f"riser_line_{i}{tag}", (0, front + 0.02, rise - 0.03), (W - 0.6, 0.02), (math.radians(45), 0, 0), WARM,
                       8.0 * share, coll)
for sx in (-1, 1):  # side steps up to the stage
    box(f"stage_step_{sx}", sx * (HX - 0.35), FRONT_Y - STAGE_D / 2, STAGE_H / 4, 0.7, STAGE_D, STAGE_H / 2, M["stage"])
# the warm line under the stage's nose (house)
box("stage_led", 0, FRONT_Y - STAGE_D - 0.005, STAGE_H - 0.04, W - 1.5, 0.01, 0.012, M["led_step"])

# ---------------------------------------------------------------- the screen wall
plane("front_wall", [(-HX, FRONT_Y, 0), (HX, FRONT_Y, 0), (HX, FRONT_Y, H), (-HX, FRONT_Y, H)], M["black"])
sw, sh = SCREEN_W, SCREEN_H
sz0 = SCREEN_FOOT
box("screen", 0, FRONT_Y - 0.04, sz0 + sh / 2, sw, 0.02, sh, M["screen"])
# a black frame proud of the wall around the screen
for (cx, cz, ssx, ssz) in [(0, sz0 + sh + 0.06, sw + 0.24, 0.12), (0, sz0 - 0.06, sw + 0.24, 0.12),
                           (-(sw / 2 + 0.06), sz0 + sh / 2, 0.12, sh), ((sw / 2 + 0.06), sz0 + sh / 2, 0.12, sh)]:
    box("screen_frame", cx, FRONT_Y - 0.05, cz, ssx, 0.06, ssz, M["black"], bevel=0.008, segs=2)

# ---------------------------------------------------------------- side walls: fabric panels, walnut slats, soffit
PANEL_W, SLAT_W = 1.05, 0.62
PLINTH = 0.22
SOFFIT_Z = 3.0
for side in (-1, 1):
    x = side * HX
    nx = -side  # the wall's face looks inward
    # the plinth (dark, recessed) and the soffit (walnut fascia)
    box(f"plinth_{side}", x - nx * 0.02, (FRONT_Y + BACK_Y) / 2, PLINTH / 2, 0.04, FRONT_Y - BACK_Y, PLINTH, M["walnut_dark"])
    box(f"soffit_{side}", x + nx * 0.22, (FRONT_Y + BACK_Y) / 2, (SOFFIT_Z + H) / 2, 0.44, FRONT_Y - BACK_Y,
        H - SOFFIT_Z, M["walnut"], bevel=0.01, segs=2)
    # the wall behind
    plane(f"wall_{side}", [(x, BACK_Y, 0), (x, FRONT_Y, 0), (x, FRONT_Y, H), (x, BACK_Y, H)] if side < 0 else
          [(x, FRONT_Y, 0), (x, BACK_Y, 0), (x, BACK_Y, H), (x, FRONT_Y, H)], M["fabric"])
    y = FRONT_Y - 0.55
    k = 0
    while y - PANEL_W > BACK_Y + 0.3:
        # a fabric panel, slightly proud of the wall — at the top level quilted in horizontal channels
        if QUILT:
            nq = 8; qh = (SOFFIT_Z - PLINTH - 0.02) / nq
            for qi in range(nq):
                box(f"panel_{side}_{k}_{qi}", x + nx * 0.035, y - PANEL_W / 2, PLINTH + 0.01 + (qi + 0.5) * qh, 0.06,
                    PANEL_W - 0.02, qh - 0.008, M["fabric"], bevel=0.02, segs=3, soft=True)
        else:
            box(f"panel_{side}_{k}", x + nx * 0.035, y - PANEL_W / 2, (PLINTH + SOFFIT_Z) / 2, 0.06, PANEL_W - 0.02,
                SOFFIT_Z - PLINTH - 0.02, M["fabric"], bevel=0.012, segs=2)
        y -= PANEL_W
        if y - SLAT_W <= BACK_Y + 0.3:
            break
        # a walnut slat panel: dark backing and vertical slats
        box(f"slatback_{side}_{k}", x + nx * 0.02, y - SLAT_W / 2, (PLINTH + SOFFIT_Z) / 2, 0.03, SLAT_W,
            SOFFIT_Z - PLINTH, M["black"])
        nslat = NSLAT
        for s in range(nslat):
            sy = y - (s + 0.5) * SLAT_W / nslat
            box(f"slat_{side}_{k}_{s}", x + nx * 0.065, sy, (PLINTH + SOFFIT_Z) / 2, 0.05, SLAT_W / nslat * 0.55,
                SOFFIT_Z - PLINTH, M["walnut"], bevel=0.004, segs=1)
        # behind the slats a warm line, top to bottom (house): it glows through the gaps and on the slats' edges
        box(f"slat_led_{side}_{k}", x + nx * 0.036, y - SLAT_W / 2, (PLINTH + SOFFIT_Z) / 2, 0.004, 0.04,
            SOFFIT_Z - PLINTH - 0.3, M["led"])
        # (turned about y, the light's own x stands up: its first size is its height. Swapped until 2026-10-23, it lay
        # along the wall 2.5 m long at 1.6 m, a bar of light across the slats and over the fabric either side.)
        area_light(f"slat_glow_{side}_{k}", (x + nx * 0.038, y - SLAT_W / 2, (PLINTH + SOFFIT_Z) / 2),
                   (SOFFIT_Z - PLINTH - 0.3, 0.05), (0, math.radians(90 * nx), 0), WARM, 6.0, house)
        # a downlight in the soffit over each slat panel, grazing the slats (house)
        spot(f"slat_spot_{side}_{k}", (x + nx * 0.16, y - SLAT_W / 2, SOFFIT_Z - 0.02), (0, 0, 0), WARM, 7.0,
             math.radians(38), 0.55, house)
        y -= SLAT_W
        k += 1
    # the warm line under the soffit, washing the wall (house) and the line at the plinth (house)
    area_light(f"soffit_line_{side}", (x + nx * 0.25, (FRONT_Y + BACK_Y) / 2, SOFFIT_Z - 0.01), (0.05, FRONT_Y - BACK_Y - 0.4),
               (0, 0, 0), WARM, 26.0, house)
    box(f"soffit_led_{side}", x + nx * 0.25, (FRONT_Y + BACK_Y) / 2, SOFFIT_Z - 0.006, 0.02, FRONT_Y - BACK_Y - 0.4, 0.006, M["led"])
    area_light(f"plinth_line_{side}", (x + nx * 0.07, (FRONT_Y + BACK_Y) / 2, PLINTH + 0.01), (0.02, FRONT_Y - BACK_Y - 0.4),
               (math.radians(180), 0, 0), WARM, 9.0, house)
    box(f"plinth_led_{side}", x + nx * 0.07, (FRONT_Y + BACK_Y) / 2, PLINTH + 0.004, 0.012, FRONT_Y - BACK_Y - 0.4, 0.006, M["led"])

# back wall
plane("back_wall", [(HX, BACK_Y, 0), (-HX, BACK_Y, 0), (-HX, BACK_Y, H), (HX, BACK_Y, H)], M["fabric"])
if DIFFUSER:
    # a skyline diffuser in walnut across the back wall's middle: a grid of square blocks of quasi-random depths
    # (a primitive-root sequence, as real ones use), each its own small box; joined into one object
    cell = 0.075; nx_, nz_ = 40, 18; depths = [((i * i) % 17) / 16 * 0.12 + 0.01 for i in range(nx_ * nz_)]
    bm = bmesh.new()
    for ix in range(nx_):
        for iz in range(nz_):
            d = depths[(ix * 7 + iz * 13) % len(depths)]
            cx_ = (ix - nx_ / 2 + 0.5) * cell; cz_ = 1.05 + (iz + 0.5) * cell
            r = bmesh.ops.create_cube(bm, size=1.0)
            for v in r["verts"]:
                v.co.x = cx_ + v.co.x * (cell - 0.004); v.co.z = cz_ + v.co.z * (cell - 0.004)
                v.co.y = BACK_Y + 0.005 + (v.co.y + 0.5) * d
    me = bpy.data.meshes.new("diffuser"); bm.to_mesh(me); bm.free()
    o = bpy.data.objects.new("diffuser", me); room.objects.link(o); me.materials.append(M["walnut"])

# ---------------------------------------------------------------- ceiling: dark fabric, six warm lines across
plane("ceiling", [(-HX, FRONT_Y, H), (HX, FRONT_Y, H), (HX, BACK_Y, H), (-HX, BACK_Y, H)], M["ceiling"])
inner = W - 0.9
for i in range(6):
    yl = FRONT_Y - 0.9 - i * 1.45
    # a shallow recess (dark walnut lips) and the strip in it
    box(f"ceil_lip_{i}", 0, yl, H - 0.03, inner, 0.10, 0.06, M["walnut_dark"])
    box(f"ceil_led_{i}", 0, yl, H - 0.062, inner - 0.05, 0.035, 0.004, M["led"])
    area_light(f"ceil_line_{i}", (0, yl, H - 0.065), (inner - 0.05, 0.035), (0, 0, 0), WARM, 18.0, house)

# the star ceiling: fibre ends between the lines, of three sizes, none on a line (a point must be bigger than the bake's
# texel or it vanishes: 7–13 mm across), round — octagons: squares read as squares from the front row
rnd = random.Random(11)
sbm = bmesh.new()
n_star = 0
while n_star < 520:
    x = rnd.uniform(-inner / 2 + 0.1, inner / 2 - 0.1); y = rnd.uniform(BACK_Y + 0.6, FRONT_Y - 0.4)
    if min(abs(y - (FRONT_Y - 0.9 - i * 1.45)) for i in range(6)) < 0.12: continue
    r = rnd.choice((0.0035, 0.0045, 0.0065))
    vs = [sbm.verts.new((x + r * math.cos(-2 * math.pi * k / 8), y + r * math.sin(-2 * math.pi * k / 8), H - 0.0015)) for k in range(8)]
    sbm.faces.new(vs)  # clockwise seen from above: it looks down
    n_star += 1
sme = bpy.data.meshes.new("ceil_stars"); sbm.to_mesh(sme); sbm.free()
so = bpy.data.objects.new("ceil_stars", sme); room.objects.link(so); sme.materials.append(M["star"]); uv_box(so)


def curtain(name, x0, x1, z0, z1, y_wall, pleat=0.13, depth=0.07):
    """A velvet drape hung from the ceiling down to z0 between x0 and x1, in front of the wall at y_wall: deep vertical
    pleats (a sine across, every `pleat` metres), gathered a little more at its top, its hem swinging slightly — wound to
    face the seats (−y)."""
    nu, nv = max(24, int(abs(x1 - x0) / pleat * 12)), 40
    bm = bmesh.new(); uvl = bm.loops.layers.uv.new("UVMap")
    grid = {}
    for j in range(nv + 1):
        t = j / nv; z = z0 + (z1 - z0) * t
        for i in range(nu + 1):
            u = i / nu; x = x0 + (x1 - x0) * u
            fold = math.sin(2 * math.pi * (x - x0) / pleat * (1.0 + 0.25 * t))
            sway = 0.012 * math.sin(7.0 * u + 1.3) * (1 - t) ** 2
            grid[i, j] = bm.verts.new((x, y_wall - depth * (1.2 + fold) - sway, z))
    for j in range(nv):
        for i in range(nu):
            a, b, c, d = grid[i, j], grid[i + 1, j], grid[i + 1, j + 1], grid[i, j + 1]
            f = bm.faces.new((a, d, c, b) if x1 > x0 else (a, b, c, d))
            for lp in f.loops: lp[uvl].uv = (lp.vert.co.z, lp.vert.co.x)
    bmesh.ops.recalc_face_normals(bm, faces=bm.faces)
    for f in bm.faces:
        if f.normal.y > 0: f.normal_flip()
    me = bpy.data.meshes.new(name); bm.to_mesh(me); bm.free()
    o = bpy.data.objects.new(name, me); room.objects.link(o); me.materials.append(M["curtain"])
    for p_ in me.polygons: p_.use_smooth = True
    return o


# the curtains: between the screen's frame and the side walls, down to the side steps' tops, a warm light grazing each
for side in (-1, 1):
    xa, xb = side * (SCREEN_W / 2 + 0.2), side * (HX - 0.04)
    curtain(f"curtain_{side}", xa, xb, STAGE_H / 2 + 0.005, H - 0.01, FRONT_Y - 0.01)
    spot(f"curtain_spot_{side}", ((xa + xb) / 2, FRONT_Y - 0.45, H - 0.05), (math.radians(-22), 0, 0), WARM, 8.0,
         math.radians(55), 0.7, house)


# ---------------------------------------------------------------- recliners
def tilted(o, deg, pivot_z):
    """Leans an object back — its top away from the screen, toward -y — by `deg` about the x axis through its own
    centre (`pivot_z` is not used). It leaned forward until 2026-10-22: the sign was the other way."""
    o.rotation_euler = (math.radians(deg), 0, 0)
    bpy.context.view_layer.objects.active = o
    o.select_set(True)
    bpy.ops.object.transform_apply(location=False, rotation=True, scale=False)
    return o


def cage(name, profile, x0, x1, material, levels, loops=0.05, coll=room):
    """A soft solid from a side profile: `profile` — (y, z) points round the outline, counter-clockwise seen from +x — is
    swept across x from x0 to x1, with edge loops `loops` in from each side so the subdivision rounds the sides without
    shrinking the width much, the ends capped; then subdivided `levels` times (Catmull-Clark) and smoothed. A leather
    back that leans and swells, not a tilted box."""
    xs = [x0, x0 + loops, x1 - loops, x1]
    bm = bmesh.new()
    rings = [[bm.verts.new((x, y, z)) for (y, z) in profile] for x in xs]
    n = len(profile)
    # UVs: across the sweep (x) and along the outline (its running length), so the leather's grain runs round the back
    # without a seam; the ends planar
    run = [0.0]
    for i in range(n): run.append(run[-1] + math.dist(profile[i], profile[(i + 1) % n]))
    uvl = bm.loops.layers.uv.new("UVMap")
    for ri, (a, b) in enumerate(zip(rings, rings[1:])):
        for i in range(n):
            f = bm.faces.new((a[i], a[(i + 1) % n], b[(i + 1) % n], b[i]))
            for lp, uv in zip(f.loops, ((xs[ri], run[i]), (xs[ri], run[i + 1]), (xs[ri + 1], run[i + 1]), (xs[ri + 1], run[i]))):
                lp[uvl].uv = uv
    for cap in (bm.faces.new(list(reversed(rings[0]))), bm.faces.new(rings[-1])):
        for lp in cap.loops: lp[uvl].uv = (lp.vert.co.y, lp.vert.co.z)
    bmesh.ops.recalc_face_normals(bm, faces=bm.faces)
    me = bpy.data.meshes.new(name); bm.to_mesh(me); bm.free()
    o = bpy.data.objects.new(name, me); coll.objects.link(o); me.materials.append(material)
    bpy.context.view_layer.objects.active = o; o.select_set(True)
    if levels:
        md = o.modifiers.new("soft", 'SUBSURF'); md.levels = levels; md.render_levels = levels
        bpy.ops.object.modifier_apply(modifier=md.name)
    for p in me.polygons: p.use_smooth = True
    o.select_set(False)
    return o


# the recliner's back seen from the side (y toward the screen, z up, both from the seat's centre on its floor): it
# stands on the base's rear, swells at the lumbar, leans back, rolls over at the top, and its front — under the pads —
# comes down to the cushion
BACK_PROFILE = [(-0.30, 0.30), (-0.46, 0.30), (-0.55, 0.42), (-0.58, 0.72), (-0.585, 0.98), (-0.55, 1.10), (-0.46, 1.13),
                (-0.38, 1.09), (-0.36, 0.98), (-0.33, 0.70), (-0.30, 0.48)]
# the seat cushion's side profile: a crowned top, a rounded front edge over the footrest
CUSHION_PROFILE = [(-0.28, 0.33), (0.30, 0.33), (0.355, 0.37), (0.35, 0.46), (0.28, 0.495), (0.0, 0.505), (-0.28, 0.48)]


def recliner(name, cx, cy, floor_z):
    """A leather recliner facing +y, its seat centred at (cx, cy) on a floor at floor_z: a skirt standing 5 cm off the
    floor with a warm glow under it (house), a seat cushion, a back shell rolled over at the top — stitched on its back at
    the high levels, which is what the row behind sees — and two back pads (lumbar and head) leaning back."""
    parts = []
    L = M["leather"]
    parts.append(box(f"{name}_skirt", cx, cy - 0.02, floor_z + 0.19, 0.62, 0.74, 0.28, L, bevel=0.06, segs=12))
    area_light(f"{name}_underglow", (cx, cy - 0.02, floor_z + 0.045), (0.5, 0.6), (0, 0, 0), WARM, 1.5, house)
    if SCULPT:
        prof = [(cy + y, floor_z + z) for (y, z) in CUSHION_PROFILE]
        parts.append(cage(f"{name}_cushion", prof, cx - 0.28, cx + 0.28, L, CHAIR_CAGE, loops=0.06))
    else:
        parts.append(box(f"{name}_cushion", cx, cy + 0.04, floor_z + 0.41, 0.56, 0.60, 0.16, L, bevel=0.07, segs=6, soft=True))
    if TUFT:
        # the footrest's flap on the skirt's front, a seam round it
        parts.append(box(f"{name}_footrest", cx, cy + 0.355, floor_z + 0.19, 0.54, 0.04, 0.22, L, bevel=0.018, segs=3, soft=True,
                         levels=CHAIR_SOFT))
    # the shell: what the row behind sees — a big padded back with a roll along its top
    if SCULPT:
        prof = [(cy + y, floor_z + z) for (y, z) in BACK_PROFILE]
        parts.append(cage(f"{name}_shell", prof, cx - 0.31, cx + 0.31, L, CHAIR_CAGE, loops=0.07))
        shell = None
    else:
        shell = box(f"{name}_shell", cx, cy - 0.40, floor_z + 0.66, 0.62, 0.20, 0.72, L, bevel=0.07, segs=6, soft=True)
        parts.append(tilted(shell, 8, floor_z + 0.3))
    if BACKSTITCH and SCULPT:
        # two vertical welts down the sculpted back's rear, following its curve: short pieces along the profile
        rear = [(y, z) for (y, z) in BACK_PROFILE if y <= -0.54]
        for si, dx in enumerate((-0.17, 0.17)):
            for k, ((y0, z0), (y1, z1)) in enumerate(zip(rear, rear[1:])):
                ln = math.hypot(y1 - y0, z1 - z0); ang = math.degrees(math.atan2(y1 - y0, z1 - z0))
                seam = box(f"{name}_seam{si}_{k}", cx + dx, cy + (y0 + y1) / 2 - 0.006, floor_z + (z0 + z1) / 2, 0.008, 0.008, ln,
                           M["seam"], bevel=0.004, segs=1)
                seam.rotation_euler = (math.radians(-ang), 0, 0)
                bpy.context.view_layer.objects.active = seam; seam.select_set(True)
                bpy.ops.object.transform_apply(location=False, rotation=True, scale=False); seam.select_set(False)
                parts.append(seam)
    elif BACKSTITCH:
        # two vertical welts and one across under the roll, on the shell's back, leaning with it
        centre = Vector((cx, cy - 0.40, floor_z + 0.66)); lean = math.radians(8)
        for si, (dx, dz, sx_, sz_) in enumerate([(-0.16, -0.04, 0.012, 0.56), (0.16, -0.04, 0.012, 0.56), (0.0, 0.22, 0.46, 0.012)]):
            local = Vector((dx, -0.104, dz))
            at = centre + Vector((local.x, local.y * math.cos(lean) - local.z * math.sin(lean),
                                  local.y * math.sin(lean) + local.z * math.cos(lean)))
            seam = box(f"{name}_seam{si}", at.x, at.y, at.z, sx_, 0.012, sz_, M["seam"], bevel=0.005, segs=2)
            parts.append(tilted(seam, 8, 0))
    if not SCULPT:  # the sculpted back rolls over by itself
        bpy.ops.mesh.primitive_cylinder_add(vertices=ROLL_VERTS, radius=0.085, depth=0.60, location=(cx, cy - 0.44, floor_z + 1.03),
                                            rotation=(0, math.radians(90), 0))
        roll = bpy.context.active_object; roll.name = f"{name}_roll"; roll.data.materials.append(L)
        md = roll.modifiers.new("bevel", 'BEVEL'); md.width = 0.03; md.segments = max(1, round(4 * SEG)); bpy.ops.object.modifier_apply(modifier=md.name)
        bpy.ops.object.shade_smooth()
        for c in roll.users_collection: c.objects.unlink(roll)
        room.objects.link(roll); parts.append(roll)
    # the pads in front of the shell: lumbar and head, a seam between
    if TUFT:
        # three vertical channels, stitched apart
        for ci, dx in enumerate((-0.18, 0.0, 0.18)):
            ch = box(f"{name}_lumbar{ci}", cx + dx, cy - 0.25, floor_z + 0.66, 0.172, 0.14, 0.34, L, bevel=0.05, segs=5, soft=True,
                     levels=CHAIR_SOFT)
            parts.append(tilted(ch, 10, floor_z + 0.5))
    else:
        lumbar = box(f"{name}_lumbar", cx, cy - 0.25, floor_z + 0.66, 0.54, 0.14, 0.34, L, bevel=0.06, segs=6)
        parts.append(tilted(lumbar, 10, floor_z + 0.5))
    head = box(f"{name}_headpad", cx, cy - 0.31, floor_z + 0.97, 0.50, 0.13, 0.22, L, bevel=0.055, segs=6, soft=True,
               levels=CHAIR_HEAD)
    parts.append(tilted(head, 12, floor_z + 0.9))
    return parts


def console(name, cx, cy, floor_z):
    """The arm between two recliners: leather sides and top, walnut faces, two cup holders."""
    parts = []
    parts.append(box(f"{name}_body", cx, cy - 0.04, floor_z + 0.30, 0.20, 0.80, 0.56, M["leather"], bevel=0.05, segs=8))
    parts.append(box(f"{name}_top", cx, cy - 0.04, floor_z + 0.605, 0.22, 0.84, 0.07, M["leather"], bevel=0.03, segs=4,
                     soft=SCULPT, levels=CHAIR_SOFT))
    for face in (-1, 1):
        parts.append(box(f"{name}_wood_{face}", cx + face * 0.101, cy - 0.10, floor_z + 0.31, 0.012, 0.62, 0.46,
                         M["walnut"], bevel=0.004, segs=1))
    bpy.ops.mesh.primitive_torus_add(location=(cx, cy + 0.20, floor_z + 0.64), major_radius=0.048, minor_radius=0.009,
                                     major_segments=ROLL_VERTS * 2, minor_segments=max(4, ROLL_VERTS // 3))
    ring = bpy.context.active_object; ring.name = f"{name}_cup"; ring.data.materials.append(M["steel"])
    bpy.ops.object.shade_smooth()
    for c in ring.users_collection: c.objects.unlink(ring)
    room.objects.link(ring); parts.append(ring)
    parts.append(box(f"{name}_cuphole", cx, cy + 0.20, floor_z + 0.638, 0.085, 0.085, 0.004, M["plastic"]))
    return parts


# the viewer's row: the middle recliner and one either side, an arm between every two and at each end; the other rows'
# risers stay, the room's stepped floor
front, rise, _ = ROWS[VIEW_ROW]
cy = front - 0.62
for si in range(NEAR_SEATS):
    recliner(f"seat_{VIEW_ROW}_{si}", (si - (NEAR_SEATS - 1) / 2) * SEAT_PITCH, cy, rise)
for ci in range(NEAR_SEATS + 1):
    console(f"seat_{VIEW_ROW}_arm{ci}", (ci - NEAR_SEATS / 2) * SEAT_PITCH, cy, rise)

# ---------------------------------------------------------------- the screen's glow (base) and its stand-in for the film
SCREEN_MEAN = 0.35  # a film's mean light, as a share of the screen's white — render_state.py's dusk stand-in's, so a render
                    # of the screen glowing with it and the bake of this light agree (0.25 left the room too dark to see)
# Each zone is baked as if its third of the screen were white — the brightest a film makes it — so the app only ever
# turns it down (a film's frame / white), and 8-bit atlases keep their steps: baked at the film's mean and turned up
# 2.9× for a white frame, the dark ceiling's noise and banding showed as clouds (B3110, 2026-10-22).
for k, z in enumerate(("ambl", "ambc", "ambr")):
    zx = (k - 1) * SCREEN_W / 3
    area_light(f"screen_glow_{z}", (zx, FRONT_Y - 0.09, SCREEN_FOOT + SCREEN_H / 2), (SCREEN_W / 3, SCREEN_H),
               (math.radians(-90), 0, 0), (1.0, 1.0, 1.0), math.pi * SCREEN_W / 3 * SCREEN_H, zones[z])
scene["film_tint"] = (0.62, 0.70, 0.85)  # the colour a still bake (no ambience) gives the film's light
scene["film_mean"] = SCREEN_MEAN       # and how bright: the zones (white) times this


# ---------------------------------------------------------------- effects (not baked; drawn added, unlit)
def fx_texture(name, w, h, fn):
    """An image made pixel by pixel: fn(u, v) → (r, g, b) as stored — sRGB, as the headset reads a colour texture
    (written as linear values they came out about a hundredth as bright)."""
    img = bpy.data.images.new(name, w, h, alpha=False, float_buffer=False)
    px = []
    for j in range(h):
        for i in range(w):
            r, g, b = fn((i + 0.5) / w, (j + 0.5) / h); px += [r, g, b, 1.0]
    img.pixels[:] = px
    img.pack()  # into the .blend: a generated image unpacked came back black when bake_layers.py opened it
    return img


def fx_material(name, img):
    m = bpy.data.materials.new(name); m.use_nodes = True
    nt = m.node_tree; b = nt.nodes["Principled BSDF"]
    t = nt.nodes.new("ShaderNodeTexImage"); t.image = img
    nt.links.new(t.outputs[0], b.inputs["Base Color"])
    return m


# (A projector's beam — a cone from the back wall carrying the film's colour — was tried and dropped on the user's
# word, 2026-10-23.)

# the LED lines' halo: a soft band either side of each strip, as the eye sees a bright line in a dark room
# ("fxh_": follows the room's lights)
himg = fx_texture("fx_halo_tex", 4, 64, lambda u, v: (lambda k: (k * 1.0, k * 0.80, k * 0.62))(0.55 * math.exp(-((v - 0.5) / 0.16) ** 2)))
hmat = fx_material("fxh_halo", himg)
hbm = bmesh.new(); huv = hbm.loops.layers.uv.new("UVMap")
def halo_strip(p0, p1, normal, width):
    """A band `width` wide centred on the strip p0→p1, facing `normal`, spread across the strip's width direction."""
    d = (Vector(p1) - Vector(p0)).normalized(); side = d.cross(Vector(normal)).normalized() * width / 2
    off = Vector(normal) * 0.004
    vs = [hbm.verts.new(Vector(p0) - side + off), hbm.verts.new(Vector(p1) - side + off),
          hbm.verts.new(Vector(p1) + side + off), hbm.verts.new(Vector(p0) + side + off)]
    f = hbm.faces.new(vs)
    for loop, uv in zip(f.loops, [(0, 0), (1, 0), (1, 1), (0, 1)]): loop[huv].uv = uv
for i in range(6):  # the ceiling lines, seen from below
    yl = FRONT_Y - 0.9 - i * 1.45
    halo_strip((-(inner - 0.05) / 2, yl, H - 0.07), ((inner - 0.05) / 2, yl, H - 0.07), (0, 0, -1), 0.16)
for side in (-1, 1):  # the soffit lines
    x = side * HX - side * 0.25
    halo_strip((x, FRONT_Y - 0.2, SOFFIT_Z - 0.012), (x, BACK_Y + 0.2, SOFFIT_Z - 0.012), (0, 0, -1), 0.14)
hme = bpy.data.meshes.new("fxh_halo"); hbm.to_mesh(hme); hbm.free()
hob = bpy.data.objects.new("fxh_halo", hme); fx.objects.link(hob); hme.materials.append(hmat)

# ---------------------------------------------------------------- the seat's camera
cam_data = bpy.data.cameras.new("seat"); cam = bpy.data.objects.new("seat", cam_data); scene.collection.objects.link(cam)
cam.location = (SEAT[0], SEAT[1], SEAT[2] + EYE)
look = Vector(SCREEN_CENTER) - cam.location
cam.rotation_euler = (math.radians(90) + math.atan2(look.z, math.hypot(look.x, look.y)), 0, 0)
cam_data.sensor_fit = 'HORIZONTAL'; cam_data.angle = math.radians(100); cam_data.clip_start = 0.05
scene.camera = cam
scene["seat"] = SEAT; scene["eye"] = EYE; scene["step_base"] = STEP_BASE
scene["screen"] = (SCREEN_CENTER[0], SCREEN_CENTER[1], SCREEN_CENTER[2], SCREEN_W, SCREEN_ASPECT)

# no world light: the room is closed
world = bpy.data.worlds.new("dark"); world.use_nodes = True
world.node_tree.nodes["Background"].inputs["Strength"].default_value = 0.0
scene.world = world

if OUT:
    bpy.ops.wm.save_as_mainfile(filepath=OUT)
    n_obj = len([o for o in scene.objects if o.type == 'MESH'])
    n_tri = sum(len(o.data.polygons) for o in scene.objects if o.type == 'MESH')
    n_tri = sum(len(p.vertices) - 2 for o in scene.objects if o.type == 'MESH' for p in o.data.polygons)
    print(f"THEATER detail {DETAIL} saved {OUT}: {n_obj} meshes, {n_tri} triangles, {len(house.objects)} house lights, {len(base.objects)} base lights")
