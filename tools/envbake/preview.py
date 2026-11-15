"""
preview.py — a path-traced look at a place from its seat, to judge lighting before baking it (Blender, headless):

  Blender --background --factory-startup --python tools/envbake/preview.py -- SRC.gltf OUT.png \
      --seat X,Y,Z [--yaw DEG] [--eye 1.15] [--sun ELEV,AZIM,STRENGTH] [--sky STRENGTH] [--samples 128] [--size 1600x900]

SEAT is the floor point under the viewer in the glTF's own coordinates (as Environments.kt has it: y up, the seat
facing -z after YAW); the camera stands EYE metres above it with a 100° horizontal field. The world is Blender's
physical (Nishita) sky with its sun, so no image is needed; AgX tone mapping. Cycles on the GPU (Metal) when present.
"""
import bpy, sys, math, argparse

argv = sys.argv[sys.argv.index("--") + 1:]
ap = argparse.ArgumentParser()
ap.add_argument("src"); ap.add_argument("out")
ap.add_argument("--seat", required=True); ap.add_argument("--yaw", type=float, default=0.0)
ap.add_argument("--eye", type=float, default=1.15)
ap.add_argument("--sun", default="35,200,3.0"); ap.add_argument("--sky", type=float, default=0.6)
ap.add_argument("--samples", type=int, default=128); ap.add_argument("--size", default="1600x900")
a = ap.parse_args(argv)

bpy.ops.wm.read_factory_settings(use_empty=True)
bpy.ops.import_scene.gltf(filepath=a.src)
scene = bpy.context.scene

# glTF (x, y, z) is Blender (x, -z, y).
sx, sy, sz = (float(v) for v in a.seat.split(","))
cam_data = bpy.data.cameras.new("seat"); cam = bpy.data.objects.new("seat", cam_data)
scene.collection.objects.link(cam); scene.camera = cam
cam.location = (sx, -sz, sy + a.eye)
cam.rotation_euler = (math.radians(90), 0, math.radians(a.yaw))
cam_data.sensor_fit = 'HORIZONTAL'; cam_data.angle = math.radians(100); cam_data.clip_start = 0.05

world = bpy.data.worlds.new("sky"); scene.world = world; world.use_nodes = True
nt = world.node_tree; nt.nodes.clear()
sky = nt.nodes.new("ShaderNodeTexSky"); bg = nt.nodes.new("ShaderNodeBackground"); out = nt.nodes.new("ShaderNodeOutputWorld")
elev, azim, sun_strength = (float(v) for v in a.sun.split(","))
sky.sky_type = 'NISHITA' if 'NISHITA' in [i.identifier for i in sky.bl_rna.properties['sky_type'].enum_items] else sky.sky_type
try:
    sky.sun_elevation = math.radians(elev); sky.sun_rotation = math.radians(azim); sky.sun_intensity = sun_strength / 3.0
except Exception:
    pass
bg.inputs["Strength"].default_value = a.sky
nt.links.new(sky.outputs[0], bg.inputs[0]); nt.links.new(bg.outputs[0], out.inputs[0])
# A sun lamp in the same direction: the sky's own disc is small and noisy to sample through a window.
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
scene.cycles.samples = a.samples; scene.cycles.use_denoising = True
w, h = (int(v) for v in a.size.split("x"))
scene.render.resolution_x, scene.render.resolution_y = w, h
scene.render.filepath = a.out
try:
    scene.view_settings.view_transform = 'AgX'
except Exception:
    scene.view_settings.view_transform = 'Filmic'
bpy.ops.render.render(write_still=True)
print("PREVIEW", a.out)
