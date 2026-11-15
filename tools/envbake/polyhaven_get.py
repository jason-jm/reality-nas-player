"""
polyhaven_get.py — fetches Poly Haven scans (CC0: free for any use, no credit owed) for a scenes/ place, into a folder of
their own: models as glTF with their maps, textures as jpg maps.

  python3 tools/envbake/polyhaven_get.py OUT_DIR [--models ID,…] [--textures ID,…] [--model-res 2k] [--texture-res 4k]

With no lists it fetches the desert's ground and boulders: six Namaqualand boulders (2k) and the sandy_gravel_02 floor
(4k: colour, normal, roughness, displacement), about 115 MB; each scene's docstring names the rest it uses. A file already
there is not fetched again. A fabric without a Diffuse comes in colourways (col_01 …): all of them are fetched.
"""
import argparse, json, os, urllib.request

ap = argparse.ArgumentParser()
ap.add_argument("out")
ap.add_argument("--models", default="namaqualand_boulder_02,namaqualand_boulder_03,namaqualand_boulder_04,"
                                    "namaqualand_boulder_05,namaqualand_boulder_06,namaqualand_boulders_01")
ap.add_argument("--textures", default="sandy_gravel_02")
ap.add_argument("--model-res", default="2k"); ap.add_argument("--texture-res", default="4k")
a = ap.parse_args()


def get(url):
    req = urllib.request.Request(url, headers={"User-Agent": "aura-envbake/1.0 (scene asset fetch)"})  # the API wants one
    with urllib.request.urlopen(req, timeout=300) as r: return r.read()


def save(url, path):
    os.makedirs(os.path.dirname(path), exist_ok=True)
    if os.path.exists(path): return 0
    data = get(url); open(path, "wb").write(data); return len(data)


total = 0
for m in [x for x in a.models.split(",") if x]:
    model_files = json.loads(get(f"https://api.polyhaven.com/files/{m}"))
    f = model_files["gltf"][a.model_res]["gltf"]
    total += save(f["url"], os.path.join(a.out, m, os.path.basename(f["url"])))
    for rel, inc in f.get("include", {}).items():
        total += save(inc["url"], os.path.join(a.out, m, rel))
    # The lounge trims leaf geometry with the separate mask, which the glTF download may omit.
    mask = model_files.get("Alpha", {}).get(a.model_res, {}).get("jpg")
    if mask:
        total += save(mask["url"], os.path.join(a.out, m, "textures", f"{m}_alpha_{a.model_res}.jpg"))
    print(m, "ok", flush=True)
for t in [x for x in a.textures.split(",") if x]:
    files = json.loads(get(f"https://api.polyhaven.com/files/{t}"))
    # most textures have a Diffuse; some fabrics come in colourways instead (col_01, col_02 …): all of them, to choose from
    for k in ["Diffuse", "nor_gl", "Rough", "Displacement"] + ([] if "Diffuse" in files else sorted(c for c in files if c.startswith("col_"))):
        if k in files:
            u = files[k][a.texture_res]["jpg"]["url"]
            total += save(u, os.path.join(a.out, t, os.path.basename(u)))
    print(t, "ok", flush=True)
print("fetched", total, "bytes")
