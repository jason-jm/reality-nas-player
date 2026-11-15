"""
render_state.py — a place built by a scenes/ script, rendered from its seat with its room lights on or off:

  Blender --background --python tools/envbake/render_state.py -- SCENE.blend OUT.png --state on|off
      [--samples 128] [--size 1600x900] [--exposure 0] [--fov 100] [--view seat|front|left|right|up|down]
      [--film dusk|none] [--eye M] [--yaw DEG] [--pitch DEG]

The "house" collection is the room's lights (on/off); "base" (the screen's glow) is always on. The view transform is
Standard — no filmic curve — because the headset shows a baked texture as it is (its unlit tone mapping turned off),
so a render meant to match the headset must not have one either. The screen shows a dusk gradient standing in for
the film, as the user's references do; --film none leaves it bare, as the app shows it with no film, and --eye/--yaw/
--pitch match a headset capture's view.
"""
import bpy, sys, math, argparse
from mathutils import Vector

argv = sys.argv[sys.argv.index("--") + 1:]
ap = argparse.ArgumentParser()
ap.add_argument("blend"); ap.add_argument("out")
ap.add_argument("--state", choices=["on", "off"], default="on")
ap.add_argument("--samples", type=int, default=128); ap.add_argument("--size", default="1600x900")
ap.add_argument("--exposure", type=float, default=0.0); ap.add_argument("--fov", type=float, default=100.0)
ap.add_argument("--view", default="seat")
ap.add_argument("--film", choices=["dusk", "none"], default="dusk",
                help="the screen's face: the dusk stand-in glowing, or none — the bare screen, as the app shows it with no film")
ap.add_argument("--eye", type=float, default=None, help="eye height above the seat's floor (default: the scene's)")
ap.add_argument("--yaw", type=float, default=None); ap.add_argument("--pitch", type=float, default=None)
ap.add_argument("--at", default=None, help="x,y,z of the eye in the scene's own axes, in place of the seat's")
a = ap.parse_args(argv)

bpy.ops.wm.open_mainfile(filepath=a.blend)
scene = bpy.context.scene
LED_STRENGTH = 12.0


def set_state(on):
    for o in bpy.data.collections["house"].objects:
        o.hide_render = not on
    for m in bpy.data.materials:
        if m.name == "led_strip":
            m.node_tree.nodes["Principled BSDF"].inputs["Emission Strength"].default_value = LED_STRENGTH if on else 0.0
        if m.name == "led_step":  # the step lines keep their base share with the room's lights off
            m.node_tree.nodes["Principled BSDF"].inputs["Emission Strength"].default_value = \
                LED_STRENGTH * (1.0 if on else scene.get("step_base", 0.0))
        # bake_layers.py's: a "house_emission" glows with the lights on; a "base_emission" (a sky) always
        b = m.node_tree.nodes.get("Principled BSDF") if m.use_nodes else None
        if b is not None and ("base_emission" in m or "house_emission" in m):
            b.inputs["Emission Strength"].default_value = m.get("base_emission", 0.0) + (m.get("house_emission", 0.0) if on else 0.0)


set_state(a.state == "on")
# the effects (halos, drawn added on the headset) would render as dark squares here
if "fx" in bpy.data.collections:
    for o in bpy.data.collections["fx"].objects: o.hide_render = True
# the screen zones' lights (ambl/ambc/ambr) stand in for the film's light in the bake alone: the render's screen does its own
for z in ("ambl", "ambc", "ambr"):
    if z in bpy.data.collections:
        for o in bpy.data.collections[z].objects: o.hide_render = True

# the screen's face: a dusk gradient, emitting — or, with --film none, the bare white screen, as the app shows it with no film
if a.film == "dusk":
    # the screen lights the room itself now: the bake's stand-in for its light (screen_glow, of the same mean) is not
    # counted twice
    for o in bpy.data.collections["base"].objects:
        if o.name == "screen_glow": o.hide_render = True
    nt = bpy.data.materials["screen"].node_tree
    b = nt.nodes["Principled BSDF"]
    tc = nt.nodes.new("ShaderNodeTexCoord"); sep = nt.nodes.new("ShaderNodeSeparateXYZ")
    ramp = nt.nodes.new("ShaderNodeValToRGB")
    ramp.color_ramp.elements[0].position = 0.0; ramp.color_ramp.elements[0].color = (0.05, 0.09, 0.16, 1)
    ramp.color_ramp.elements[1].position = 1.0; ramp.color_ramp.elements[1].color = (0.18, 0.32, 0.75, 1)
    e = ramp.color_ramp.elements.new(0.42); e.color = (0.85, 0.55, 0.32, 1)
    nt.links.new(tc.outputs["Generated"], sep.inputs[0]); nt.links.new(sep.outputs["Z"], ramp.inputs[0])
    nt.links.new(ramp.outputs[0], b.inputs["Emission Color"])
    b.inputs["Emission Strength"].default_value = 1.0
    b.inputs["Base Color"].default_value = (0, 0, 0, 1)

cam = scene.camera
seat = Vector(scene["seat"]); eye = scene["eye"] if a.eye is None else a.eye
cam.location = seat + Vector((0, 0, eye)) if a.at is None else Vector([float(v) for v in a.at.split(",")])
cam.data.angle = math.radians(a.fov)
yaw_pitch = {"seat": (0, None), "front": (0, None), "left": (60, 8), "right": (-60, 8), "up": (0, 55), "down": (0, -55)}
yaw, pitch = yaw_pitch.get(a.view, (0, None))
if a.yaw is not None: yaw = a.yaw
if a.pitch is not None: pitch = a.pitch
if pitch is None:
    sc = Vector(scene["screen"][:3]); d = sc - cam.location
    pitch = math.degrees(math.atan2(d.z, math.hypot(d.x, d.y)))
cam.rotation_euler = (math.radians(90 + pitch), 0, math.radians(yaw))

scene.render.engine = 'CYCLES'
prefs = bpy.context.preferences.addons['cycles'].preferences
try:
    prefs.compute_device_type = 'METAL'; prefs.get_devices()
    for d in prefs.devices: d.use = True
    scene.cycles.device = 'GPU'
except Exception as ex:
    print("GPU unavailable:", ex)
scene.cycles.samples = a.samples; scene.cycles.use_denoising = True
w, h = (int(v) for v in a.size.split("x"))
scene.render.resolution_x, scene.render.resolution_y = w, h
scene.view_settings.view_transform = 'Standard'; scene.view_settings.look = 'None'
scene.view_settings.exposure = a.exposure
scene.render.filepath = a.out
# where the screen's face lands in the picture (px, top-left origin) — for warping this render onto a headset capture
from bpy_extras.object_utils import world_to_camera_view
bpy.context.view_layer.update()  # the camera as turned above, not as it was saved
sc = scene["screen"]; hw = sc[3] / 2; hh = sc[3] / sc[4] / 2; fy = sc[1] + 0.01  # the face, in front of the frame's back
corners = []
for (dx, dz) in [(-hw, hh), (hw, hh), (-hw, -hh), (hw, -hh)]:  # TL TR BL BR
    v = world_to_camera_view(scene, cam, Vector((sc[0] + dx, fy, sc[2] + dz)))
    corners.append((round(v.x * w, 1), round((1 - v.y) * h, 1)))
print("SCREEN_PX", corners)
bpy.ops.render.render(write_still=True)
print("RENDER", a.out, a.state, a.view)
