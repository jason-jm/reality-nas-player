#!/usr/bin/env python3
"""inject-spherical.py IN.mp4 OUT.mp4 [--stereo mono|top-bottom|left-right] [--projection equirect|cubemap|mesh-eac|none]
   [--bounds TOP,BOTTOM,LEFT,RIGHT] [--yaw DEG] [--pitch DEG] [--roll DEG] [--ambisonic ORDER[+hl]]

Writes Google's Spherical Video V2 boxes (st3d, sv3d > svhd + proj > prhd + equi|cbmp|mshp) into the first
video sample entry of an MP4, as Google's spatial-media tool and cameras do — for test clips the player
must recognise. Bounds are fractions of the full 360x180 frame cropped off each side (VR180: 0,0,0.25,0.25).
--ambisonic writes Google's Spatial Audio box (SA3D: periphonic, ACN, SN3D) into the first audio sample
entry; "+hl" marks the last two channels head-locked stereo.
Chunk offsets (stco/co64) are moved when the moov sits before the media data. No dependencies.
"""
import argparse
import struct


def box(kind, payload):
    return struct.pack(">I", 8 + len(payload)) + kind + payload


def full(kind, payload, version=0, flags=0):
    return box(kind, struct.pack(">I", (version << 24) | flags) + payload)


def walk(data, start, end):
    """(type, box start, payload start, box end) for the boxes in [start, end)."""
    out = []
    at = start
    while at + 8 <= end:
        size, kind = struct.unpack(">I4s", data[at:at + 8])
        header = 8
        if size == 1:
            size = struct.unpack(">Q", data[at + 8:at + 16])[0]
            header = 16
        elif size == 0:
            size = end - at
        out.append((kind, at, at + header, at + size))
        at += size
    return out


CONTAINERS = {b"moov", b"trak", b"mdia", b"minf", b"stbl"}


# ---- a mesh projection like YouTube's: an equi-angular cubemap's mesh (FFmpeg's EAC layout) ----

def eac_mesh(n):
    """Vertices (x, y, z, u, v) and triangle indices of an EAC frame's mesh, n x n cells a face."""
    import math
    faces = {  # FFmpeg's axes (x right, y down, z forward): the point of a face at (uf, vf)
        "R": lambda u, v: (1.0, v, -u), "L": lambda u, v: (-1.0, v, u), "U": lambda u, v: (u, -1.0, v),
        "D": lambda u, v: (u, 1.0, -v), "F": lambda u, v: (u, v, 1.0), "B": lambda u, v: (-u, v, -1.0),
    }
    slot = {"L": 0, "F": 1, "R": 2, "D": 3, "B": 4, "U": 5}
    turns = [0, 0, 0, 3, 1, 3]

    def turn(u, v, q):
        return [(u, v), (-v, u), (-u, -v), (v, -u)][q]

    verts, tris = [], []
    for name, vec in faces.items():
        first = len(verts)
        s_ = slot[name]
        for j in range(n + 1):
            for i in range(n + 1):
                su, sv = -1 + 2 * i / n, -1 + 2 * j / n
                uf, vf = math.tan(su * math.pi / 4), math.tan(sv * math.pi / 4)
                x, y, z = vec(uf, vf)
                ln = math.sqrt(x * x + y * y + z * z)
                ru, rv = turn(uf, vf, turns[s_])
                eu = 2 / math.pi * math.atan(ru) + 0.5
                ev = 2 / math.pi * math.atan(rv) + 0.5
                u = (eu + s_ % 3) / 3
                v_row = ev * 0.5 + 0.5 * (s_ // 3)
                verts.append((x / ln, -y / ln, -z / ln, u, 1 - v_row))
        w = n + 1
        for j in range(n):
            for i in range(n):
                a = first + j * w + i
                tris += [a, a + w, a + 1, a + 1, a + w, a + w + 1]
    return verts, tris


def mesh_box(verts, tris):
    """A 'mesh' box: float coordinates, vertices and one triangle sub-mesh as zig-zag deltas, bit-packed."""
    import math
    f32 = lambda x: struct.unpack(">f", struct.pack(">f", x))[0]
    verts = [tuple(f32(c) for c in v) for v in verts]
    coords = sorted(set(c for v in verts for c in v))
    where = {c: i for i, c in enumerate(coords)}
    bits = []

    def put(value, n):
        bits.extend((value >> (n - 1 - k)) & 1 for k in range(n))

    zig = lambda d: ((d << 1) ^ (d >> 31)) & 0xFFFFFFFF
    put(len(coords), 32)
    for c in coords:
        put(struct.unpack(">I", struct.pack(">f", c))[0], 32)
    put(len(verts), 32)
    cb = math.ceil(math.log2(2 * len(coords)))
    last = [0] * 5
    for v in verts:
        for i in range(5):
            idx = where[v[i]]
            put(zig(idx - last[i]), cb)
            last[i] = idx
    while len(bits) % 8:
        bits.append(0)
    put(1, 32)
    put(0, 8)
    put(0, 8)
    put(len(tris), 32)
    vb = math.ceil(math.log2(2 * len(verts)))
    prev = 0
    for t in tris:
        put(zig(t - prev), vb)
        prev = t
    while len(bits) % 8:
        bits.append(0)
    payload = bytes(int("".join(map(str, bits[k:k + 8])), 2) for k in range(0, len(bits), 8))
    return box(b"mesh", payload)


def zlib_raw(data):
    import zlib
    c = zlib.compressobj(9, zlib.DEFLATED, -15)
    return c.compress(data) + c.flush()


def insert(data, handler, extra):
    """Puts [extra] at the end of the first [handler] track's sample entry, growing the boxes round it."""
    top = walk(data, 0, len(data))
    moov = next(x for x in top if x[0] == b"moov")
    mdat_after_moov = any(x[0] == b"mdat" and x[1] > moov[1] for x in top)
    chain = [moov]
    entry = None
    for trak in (x for x in walk(data, moov[2], moov[3]) if x[0] == b"trak"):
        mdia = next(x for x in walk(data, trak[2], trak[3]) if x[0] == b"mdia")
        hdlr = next(x for x in walk(data, mdia[2], mdia[3]) if x[0] == b"hdlr")
        if data[hdlr[2] + 8:hdlr[2] + 12] != handler:
            continue
        minf = next(x for x in walk(data, mdia[2], mdia[3]) if x[0] == b"minf")
        stbl = next(x for x in walk(data, minf[2], minf[3]) if x[0] == b"stbl")
        stsd = next(x for x in walk(data, stbl[2], stbl[3]) if x[0] == b"stsd")
        entry = walk(data, stsd[2] + 8, stsd[3])[0]
        chain += [trak, mdia, minf, stbl, stsd]
        break
    if entry is None:
        raise SystemExit("no %s track" % handler.decode())

    # Insert at the end of the sample entry, then grow every box that holds it.
    at = entry[3]
    data[at:at] = extra
    for b in chain + [entry]:
        size = struct.unpack(">I", data[b[1]:b[1] + 4])[0]
        if size == 1:
            big = struct.unpack(">Q", data[b[1] + 8:b[1] + 16])[0]
            data[b[1] + 8:b[1] + 16] = struct.pack(">Q", big + len(extra))
        else:
            data[b[1]:b[1] + 4] = struct.pack(">I", size + len(extra))

    if mdat_after_moov:
        moov_new = next(x for x in walk(data, 0, len(data)) if x[0] == b"moov")

        def fix(start, end):
            for kind, s, ps, e in walk(data, start, end):
                if kind in CONTAINERS:
                    fix(ps, e)
                elif kind in (b"stco", b"co64"):
                    n = struct.unpack(">I", data[ps + 4:ps + 8])[0]
                    w = 4 if kind == b"stco" else 8
                    fmt = ">I" if w == 4 else ">Q"
                    for i in range(n):
                        o = ps + 8 + i * w
                        v = struct.unpack(fmt, data[o:o + w])[0]
                        data[o:o + w] = struct.pack(fmt, v + len(extra))
        fix(moov_new[2], moov_new[3])


def main():
    p = argparse.ArgumentParser()
    p.add_argument("src")
    p.add_argument("dst")
    p.add_argument("--stereo", default="mono", choices=["mono", "top-bottom", "left-right"])
    p.add_argument("--projection", default="equirect", choices=["equirect", "cubemap", "mesh-eac", "none"])
    p.add_argument("--bounds", default="0,0,0,0")
    p.add_argument("--yaw", type=float, default=0)
    p.add_argument("--pitch", type=float, default=0)
    p.add_argument("--roll", type=float, default=0)
    p.add_argument("--ambisonic", default="")
    p.add_argument("--padding", type=int, default=0, help="cubemap: the pixels round each face's edge (cbmp padding)")
    a = p.parse_args()

    data = bytearray(open(a.src, "rb").read())
    if a.projection != "none":
        stereo = {"mono": 0, "top-bottom": 1, "left-right": 2}[a.stereo]
        st3d = full(b"st3d", struct.pack(">B", stereo))
        prhd = full(b"prhd", struct.pack(">iii", int(a.yaw * 65536), int(a.pitch * 65536), int(a.roll * 65536)))
        if a.projection == "equirect":
            t, b, l, r = (float(x) for x in a.bounds.split(","))
            shape = full(b"equi", struct.pack(">IIII", *(int(f * 4294967296) & 0xFFFFFFFF for f in (t, b, l, r))))
        elif a.projection == "cubemap":
            shape = full(b"cbmp", struct.pack(">II", 0, a.padding))
        else:
            shape = full(b"mshp", struct.pack(">I", 0) + b"dfl8" + zlib_raw(mesh_box(*eac_mesh(16))))
        sv3d = box(b"sv3d", full(b"svhd", b"Aura test\x00") + box(b"proj", prhd + shape))
        insert(data, b"vide", st3d + sv3d)
    if a.ambisonic:
        order = int(a.ambisonic.split("+")[0])
        hl = a.ambisonic.endswith("+hl")
        n = (order + 1) ** 2 + (2 if hl else 0)
        sa3d = box(b"SA3D", struct.pack(">BBIBBI", 0, 0x80 if hl else 0, order, 0, 0, n) + b"".join(struct.pack(">I", i) for i in range(n)))
        insert(data, b"soun", sa3d)
    open(a.dst, "wb").write(data)


if __name__ == "__main__":
    main()
