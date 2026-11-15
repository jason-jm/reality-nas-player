// Aura: a spherical video seen through a window — the part of the sphere in front of the viewer,
// in perspective, on the GPU. mpv shows the result at the window's shape (video-aspect-override).
//
// mpv's libmpv renderer is its OpenGL one: no shader parameters, and a shader file is read once
// per path for the renderer's life. So the view travels in a texture: a tiny shader written per
// view (Projection.viewShader) saves AURAVIEW, 2x1 — texel 0 (yaw, pitch, fov, aspect), each
// squeezed into 0..1 — and this one, fixed while a file plays, reads it. What the frame holds is
// written in per file (Projection.sphereShader), the headset's projections (core Panorama.uv, which
// follows FFmpeg's vf_v360):
//   aura_mode    0 equirectangular, aura_bounds its coverage (yaw from, to, pitch from, to; radians)
//                1 fisheye (equidistant, one circle filling the frame), aura_lens its half-angle
//                2 dual fisheye (a 360 camera's pair: front lens right, back lens left and mirrored)
//                3 cubemap, aura_grid its faces across and down ("rludfb", unrotated: 3x2, 6x1, 1x6),
//                  aura_pad its faces' padding (cbmp), a part of one face
//                4 equi-angular cubemap (YouTube's EAC), aura_pad its border, a part of the eye's picture
//   aura_stereo  0 one picture, 1 side by side, 2 over-under — the left eye's is shown, from the
//                second half when aura_swap is 1 (the right eye's picture first)
//   aura_pose    the picture's own orientation, undone (on the headset's axes: z behind)

//!HOOK MAIN
//!BIND HOOKED
//!BIND AURAVIEW
//!DESC Aura spherical view
const float aura_mode = {{MODE}};
const float aura_stereo = {{STEREO}};
const float aura_swap = {{SWAP}};
const vec4 aura_bounds = vec4({{BOUNDS}});
const float aura_lens = {{LENS}};
const vec2 aura_grid = vec2({{GRID}});
const vec2 aura_pad = vec2({{PAD}});
const mat3 aura_pose = mat3({{POSE}});
const float AURA_PI = 3.14159265358979;

// Where direction d (x right, y up, z ahead) is in one eye's picture: (u right, v up from the bottom
// row, 1) — or z 0 where the picture has nothing.
vec3 aura_uv(vec3 d) {
    if (aura_mode < 0.5) {
        float yaw = atan(d.x, d.z);
        float pitch = atan(d.y, length(d.xz));
        vec2 uv = vec2((yaw - aura_bounds.x) / (aura_bounds.y - aura_bounds.x), (pitch - aura_bounds.z) / (aura_bounds.w - aura_bounds.z));
        if (uv.x < 0.0 || uv.x > 1.0 || uv.y < 0.0 || uv.y > 1.0) return vec3(0.0);
        return vec3(uv, 1.0);
    }
    if (aura_mode < 1.5) {
        float angle = acos(clamp(d.z, -1.0, 1.0));
        if (angle > aura_lens) return vec3(0.0);
        float h = length(d.xy);
        if (h < 1e-9) return vec3(0.5, 0.5, 1.0);
        return vec3(vec2(0.5) + 0.5 * angle / aura_lens * d.xy / h, 1.0);
    }
    if (aura_mode < 2.5) {
        float angle = acos(clamp(abs(d.z), 0.0, 1.0));
        float h = length(d.xy);
        vec2 a = h < 1e-9 ? vec2(0.0) : angle / aura_lens * d.xy / h;
        float u = d.z >= 0.0 ? 0.5 + (a.x + 1.0) / 4.0 : (1.0 - a.x) / 4.0;
        return vec3(u, 0.5 + 0.5 * a.y, 1.0);
    }
    // A cube's faces, as FFmpeg's xyz_to_cube finds them — its axes: x right, y down, z ahead.
    // Faces: 0 right, 1 left, 2 up, 3 down, 4 front, 5 back.
    vec3 f = vec3(d.x, -d.y, d.z);
    float phi = atan(f.x, f.z);
    float theta = asin(clamp(f.y, -1.0, 1.0));
    int face;
    float phiNorm;
    if (phi >= -AURA_PI / 4.0 && phi < AURA_PI / 4.0) { face = 4; phiNorm = phi; }
    else if (phi >= -(AURA_PI / 2.0 + AURA_PI / 4.0) && phi < -AURA_PI / 4.0) { face = 1; phiNorm = phi + AURA_PI / 2.0; }
    else if (phi >= AURA_PI / 4.0 && phi < AURA_PI / 2.0 + AURA_PI / 4.0) { face = 0; phiNorm = phi - AURA_PI / 2.0; }
    else { face = 5; phiNorm = phi + (phi > 0.0 ? -AURA_PI : AURA_PI); }
    float threshold = atan(cos(phiNorm));
    if (theta > threshold) face = 3; else if (theta < -threshold) face = 2;
    vec2 q;
    if (face == 0) q = vec2(-f.z / f.x, f.y / f.x);
    else if (face == 1) q = vec2(-f.z / f.x, -f.y / f.x);
    else if (face == 2) q = vec2(-f.x / f.y, -f.z / f.y);
    else if (face == 3) q = vec2(f.x / f.y, -f.z / f.y);
    else if (face == 4) q = vec2(f.x / f.z, f.y / f.z);
    else q = vec2(f.x / f.z, -f.y / f.z);
    bool eac = aura_mode > 3.5;
    // Where the face sits, row by row, and its quarter turns (FFmpeg's rotate_cube_face): a plain
    // cubemap in face order; EAC left front right / down back up, the bottom row turned.
    int slot = face;
    int turns = 0;
    if (eac) {
        if (face == 0) slot = 2; else if (face == 1) slot = 0; else if (face == 2) slot = 5;
        else if (face == 3) slot = 3; else if (face == 4) slot = 1; else slot = 4;
        if (slot == 3 || slot == 5) turns = 3; else if (slot == 4) turns = 1;
    }
    if (turns == 1) q = vec2(-q.y, q.x); else if (turns == 3) q = vec2(q.y, -q.x);
    float cols = eac ? 3.0 : aura_grid.x;
    float col = mod(float(slot), cols);
    float row = floor(float(slot) / cols + 0.001);
    float u;
    float vRow;
    if (eac) {
        vec2 e = 2.0 / AURA_PI * atan(q) + 0.5;
        u = (e.x + col) * (1.0 - 2.0 * aura_pad.x) / 3.0 + aura_pad.x;
        vRow = e.y * (0.5 - 2.0 * aura_pad.y) + aura_pad.y + 0.5 * row;
    } else {
        // A padded face holds its picture inside a border (aura_pad, a part of the face).
        u = (col + aura_pad.x + (q.x + 1.0) / 2.0 * (1.0 - 2.0 * aura_pad.x)) / cols;
        vRow = (row + aura_pad.y + (q.y + 1.0) / 2.0 * (1.0 - 2.0 * aura_pad.y)) / aura_grid.y;
    }
    return vec3(u, 1.0 - vRow, 1.0);
}

vec4 hook() {
    vec4 v = AURAVIEW_tex(vec2(0.25, 0.5));
    float yaw = (v.x * 2.0 - 1.0) * AURA_PI;
    float pitch = (v.y * 2.0 - 1.0) * 0.5 * AURA_PI;
    float fov = v.z * AURA_PI;
    float aspect = v.w * 8.0;
    vec2 ndc = HOOKED_pos * 2.0 - 1.0;
    float t = tan(fov * 0.5);
    vec3 dir = normalize(vec3(ndc.x * t, -ndc.y * t / aspect, 1.0));
    float cp = cos(pitch), sp = sin(pitch);
    dir = vec3(dir.x, dir.y * cp + dir.z * sp, -dir.y * sp + dir.z * cp);
    float cy = cos(yaw), sy = sin(yaw);
    dir = vec3(dir.x * cy + dir.z * sy, dir.y, -dir.x * sy + dir.z * cy);
    // Into the picture's own frame: its pose undone, on the headset's axes (z behind).
    vec3 c = aura_pose * vec3(dir.x, dir.y, -dir.z);
    vec3 p = aura_uv(vec3(c.x, c.y, -c.z));
    if (p.z < 0.5) return vec4(0.0, 0.0, 0.0, 1.0);
    // To the texture's way round (y down), then the left eye's part of the frame.
    vec2 src = vec2(p.x, 1.0 - p.y);
    float second = aura_swap > 0.5 ? 0.5 : 0.0;
    if (aura_stereo > 1.5) src.y = src.y * 0.5 + second;
    else if (aura_stereo > 0.5) src.x = src.x * 0.5 + second;
    return HOOKED_tex(src);
}
