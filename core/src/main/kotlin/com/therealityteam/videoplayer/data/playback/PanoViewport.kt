package com.therealityteam.videoplayer.data.playback

import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt
import kotlin.math.tan

/**
 * A panorama seen through the window: what a camera at the sphere's centre would see, looking [Look],
 * laid flat on the window's plane. The sphere's own mesh ([Panorama.mesh]) is projected — each point
 * where its ray meets the plane — and cut to the window's rectangle, so its seams (an equirectangular
 * picture's back, a cubemap's edges) stay as they are on the sphere: nothing to smear across. Its
 * triangles are small enough that the texture, carried along linearly, stays true.
 */
object PanoViewport {

    /** Where the window looks: yaw to the right and pitch up, degrees; [fov] across the window's width. */
    data class Look(val yaw: Float = 0f, val pitch: Float = 0f, val fov: Float = 90f)

    /**
     * [sphere] (round the viewer, any radius) seen through a [width] × [height] window looking [look]:
     * flat triangles on z = 0, x right and y up from the window's centre, in [width]'s units.
     */
    fun view(sphere: PanoMesh, look: Look, width: Float, height: Float, cornerRadius: Float = 0f): PanoMesh {
        val yaw = Math.toRadians(look.yaw.toDouble())
        val pitch = Math.toRadians(look.pitch.toDouble())
        // The camera's axes: forward, right, up (forward −z at rest).
        val fx = sin(yaw) * cos(pitch); val fy = sin(pitch); val fz = -cos(yaw) * cos(pitch)
        val rx = cos(yaw); val ry = 0.0; val rz = sin(yaw)
        val ux = ry * fz - rz * fy; val uy = rz * fx - rx * fz; val uz = rx * fy - ry * fx
        val tx = tan(Math.toRadians(look.fov / 2.0))
        val ty = tx * height / width
        val halfW = width / 2.0
        val halfH = height / 2.0

        val n = sphere.vertexCount
        val sx = DoubleArray(n)
        val sy = DoubleArray(n)
        val ahead = BooleanArray(n)
        for (k in 0 until n) {
            val x = sphere.positions[3 * k].toDouble()
            val y = sphere.positions[3 * k + 1].toDouble()
            val z = sphere.positions[3 * k + 2].toDouble()
            val len = sqrt(x * x + y * y + z * z).takeIf { it > 0 } ?: 1.0
            val d = (x * fx + y * fy + z * fz) / len
            ahead[k] = d > NEAR
            if (ahead[k]) {
                sx[k] = (x * rx + y * ry + z * rz) / len / d / tx * halfW
                sy[k] = (x * ux + y * uy + z * uz) / len / d / ty * halfH
            }
        }

        val pos = ArrayList<Float>()
        val tex = ArrayList<Float>()
        val idx = ArrayList<Int>()
        val poly = ArrayList<DoubleArray>(9)
        for (t in 0 until sphere.indices.size / 3) {
            val a = sphere.indices[3 * t]; val b = sphere.indices[3 * t + 1]; val c = sphere.indices[3 * t + 2]
            if (!ahead[a] || !ahead[b] || !ahead[c]) continue
            // Wholly outside one edge: nothing of it shows.
            if ((sx[a] < -halfW && sx[b] < -halfW && sx[c] < -halfW) || (sx[a] > halfW && sx[b] > halfW && sx[c] > halfW) ||
                (sy[a] < -halfH && sy[b] < -halfH && sy[c] < -halfH) || (sy[a] > halfH && sy[b] > halfH && sy[c] > halfH)
            ) continue
            poly.clear()
            for (k in intArrayOf(a, b, c)) poly += doubleArrayOf(sx[k], sy[k], sphere.uvs[2 * k].toDouble(), sphere.uvs[2 * k + 1].toDouble())
            var p: List<DoubleArray> = poly
            p = clip(p, 0, -halfW, keepAbove = true)
            p = clip(p, 0, halfW, keepAbove = false)
            p = clip(p, 1, -halfH, keepAbove = true)
            p = clip(p, 1, halfH, keepAbove = false)
            // The window's rounded corners: a corner's arc, as chords, cuts what lies past it.
            if (cornerRadius > 0f && p.size >= 3) p = roundCorners(p, halfW, halfH, cornerRadius.toDouble())
            if (p.size < 3) continue
            val first = pos.size / 3
            for (v in p) {
                pos += v[0].toFloat(); pos += v[1].toFloat(); pos += 0f
                tex += v[2].toFloat(); tex += v[3].toFloat()
            }
            for (k in 1 until p.size - 1) { idx += first; idx += first + k; idx += first + k + 1 }
        }
        return PanoMesh(pos.toFloatArray(), tex.toFloatArray(), idx.toIntArray())
    }

    /**
     * Whether a window of [aspect] (width / height) looking [look] sees only picture — its corners'
     * and edges' rays all land where [layout] has some: a 180's view stops at the picture's edge
     * rather than turning onto nothing. A whole sphere fits every look.
     */
    fun fits(layout: PanoLayout, look: Look, aspect: Float): Boolean {
        val yaw = Math.toRadians(look.yaw.toDouble())
        val pitch = Math.toRadians(look.pitch.toDouble())
        val fx = sin(yaw) * cos(pitch); val fy = sin(pitch); val fz = -cos(yaw) * cos(pitch)
        val rx = cos(yaw); val rz = sin(yaw)
        val ux = -rz * fy; val uy = rz * fx - rx * fz; val uz = rx * fy
        val tx = tan(Math.toRadians(look.fov / 2.0))
        val ty = tx / aspect
        for (sx in EDGE_STEPS) for (sy in EDGE_STEPS) {
            if (sx != -1.0 && sx != 1.0 && sy != -1.0 && sy != 1.0) continue
            val dx = fx + rx * tx * sx + ux * ty * sy
            val dy = fy + uy * ty * sy
            val dz = fz + rz * tx * sx + uz * ty * sy
            val len = sqrt(dx * dx + dy * dy + dz * dz)
            if (Panorama.uv(layout, dx / len, dy / len, dz / len) == null) return false
        }
        return true
    }

    /** Where along a window's edge its rays are tried ([fits]): the corners and between them. */
    private val EDGE_STEPS = listOf(-1.0, -0.5, 0.0, 0.5, 1.0)

    /** [poly] cut to a rectangle's rounded corners of radius [r]: only the corners it reaches. */
    private fun roundCorners(poly: List<DoubleArray>, halfW: Double, halfH: Double, r: Double): List<DoubleArray> {
        var p = poly
        for ((sx, sy) in CORNERS) {
            val cx = sx * (halfW - r)
            val cy = sy * (halfH - r)
            if (p.none { sx * it[0] > sx * cx && sy * it[1] > sy * cy }) continue
            // The quarter circle from the side edge to the top/bottom edge, as chords.
            val start = Math.atan2(0.0, sx) // pointing out sideways
            val turn = if (sx * sy > 0) Math.PI / 2 else -Math.PI / 2
            var ax = cx + r * Math.cos(start)
            var ay = cy + r * Math.sin(start)
            for (k in 1..CORNER_STEPS) {
                val a = start + turn * k / CORNER_STEPS
                val bx = cx + r * Math.cos(a)
                val by = cy + r * Math.sin(a)
                // Keep the side of the chord the corner's centre is on.
                val nx = -(by - ay)
                val ny = bx - ax
                val sign = if (nx * (cx - ax) + ny * (cy - ay) >= 0) 1.0 else -1.0
                p = halfPlane(p, sign * nx, sign * ny, -sign * (nx * ax + ny * ay))
                if (p.size < 3) return p
                ax = bx; ay = by
            }
        }
        return p
    }

    /** [poly] cut to where a·x + b·y + c ≥ 0, the texture carried along. */
    private fun halfPlane(poly: List<DoubleArray>, a: Double, b: Double, c: Double): List<DoubleArray> {
        val out = ArrayList<DoubleArray>(poly.size + 2)
        for (i in poly.indices) {
            val cur = poly[i]
            val prev = poly[(i + poly.size - 1) % poly.size]
            val dc = a * cur[0] + b * cur[1] + c
            val dp = a * prev[0] + b * prev[1] + c
            if ((dc >= 0) != (dp >= 0)) {
                val s = dp / (dp - dc)
                out += DoubleArray(4) { prev[it] + (cur[it] - prev[it]) * s }
            }
            if (dc >= 0) out += cur
        }
        return out
    }

    /** One step of Sutherland–Hodgman: [poly] cut to the side of axis [axis] = [at] it keeps, the texture carried along. */
    private fun clip(poly: List<DoubleArray>, axis: Int, at: Double, keepAbove: Boolean): List<DoubleArray> {
        if (poly.isEmpty()) return poly
        fun inside(v: DoubleArray) = if (keepAbove) v[axis] >= at else v[axis] <= at
        val out = ArrayList<DoubleArray>(poly.size + 2)
        for (i in poly.indices) {
            val cur = poly[i]
            val prev = poly[(i + poly.size - 1) % poly.size]
            val cin = inside(cur)
            val pin = inside(prev)
            if (cin != pin) {
                val s = (at - prev[axis]) / (cur[axis] - prev[axis])
                out += DoubleArray(4) { prev[it] + (cur[it] - prev[it]) * s }
            }
            if (cin) out += cur
        }
        return out
    }

    /** How far ahead a point must be to be projected (the cosine of its angle off the view): a wide view's edges stay. */
    private const val NEAR = 0.02

    /** The window's four corners, as signs of x and y. */
    private val CORNERS = listOf(1.0 to 1.0, -1.0 to 1.0, -1.0 to -1.0, 1.0 to -1.0)
    private const val CORNER_STEPS = 8
}
