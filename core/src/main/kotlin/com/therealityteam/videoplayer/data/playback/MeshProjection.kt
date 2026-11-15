package com.therealityteam.videoplayer.data.playback

import java.io.ByteArrayOutputStream
import java.util.zip.Inflater
import kotlin.math.ceil
import kotlin.math.ln
import kotlin.math.sqrt

/**
 * A mesh projection (`mshp`, Google's Spherical Video V2; Matroska ProjectionType 3): the picture's own
 * mesh — positions and texture coordinates — written by whatever made the file (YouTube's equi-angular
 * cubemaps among them). Read as Media3's ProjectionDecoder reads it: a full box (version 0), a CRC, the
 * encoding (`raw ` or `dfl8`, raw deflate), then `mesh` boxes — one, or one per eye — each a list of
 * float coordinates, vertices as zig-zag deltas into it (x, y, z, u, v), and sub-meshes of triangles,
 * strips or fans as zig-zag deltas into the vertices, every field packed in as few bits as it needs.
 * Texture coordinates count up from the bottom row, as the video texture's do.
 */
object MeshProjection {

    /** The meshes in an `mshp` box's body (version and flags first); null when it cannot be read. */
    fun parse(body: ByteArray): List<PanoMesh>? = runCatching {
        if (body.size < 12 || body[0].toInt() != 0) return null
        val encoding = String(body, 8, 4, Charsets.ISO_8859_1)
        val data = when (encoding) {
            "raw " -> body.copyOfRange(12, body.size)
            "dfl8" -> inflate(body, 12)
            else -> return null
        }
        meshes(data)
    }.getOrNull()

    private fun inflate(b: ByteArray, from: Int): ByteArray {
        val inflater = Inflater(true)
        try {
            inflater.setInput(b, from, b.size - from)
            val out = ByteArrayOutputStream(b.size * 4)
            val buf = ByteArray(64 * 1024)
            while (!inflater.finished()) {
                val n = inflater.inflate(buf)
                if (n == 0 && (inflater.needsInput() || inflater.needsDictionary())) break
                out.write(buf, 0, n)
                require(out.size() <= MAX_INFLATED) { "mesh too large" }
            }
            return out.toByteArray()
        } finally {
            inflater.end()
        }
    }

    private fun meshes(d: ByteArray): List<PanoMesh>? {
        val out = ArrayList<PanoMesh>()
        var at = 0
        while (at + 8 <= d.size) {
            val size = Bits(d, at * 8).bits(32).toInt()
            val type = String(d, at + 4, 4, Charsets.ISO_8859_1)
            if (size < 8 || at + size > d.size) return null
            if (type == "mesh") out += mesh(d, at + 8) ?: return null
            at += size
        }
        return out.takeIf { it.isNotEmpty() }
    }

    private fun mesh(d: ByteArray, start: Int): PanoMesh? {
        val r = Bits(d, start * 8)
        val coordinateCount = (r.bits(32) and 0x7FFFFFFF).toInt()
        if (coordinateCount !in 1..MAX_COORDINATES) return null
        val coordinates = FloatArray(coordinateCount) { java.lang.Float.intBitsToFloat(r.bits(32).toInt()) }
        val vertexCount = (r.bits(32) and 0x7FFFFFFF).toInt()
        if (vertexCount !in 1..MAX_VERTICES) return null
        val coordinateBits = bitsFor(coordinateCount)
        val vertices = FloatArray(vertexCount * 5)
        val last = IntArray(5)
        for (v in 0 until vertexCount) for (i in 0 until 5) {
            val index = last[i] + zigZag(r.bits(coordinateBits))
            if (index !in 0 until coordinateCount) return null
            vertices[v * 5 + i] = coordinates[index]
            last[i] = index
        }
        r.alignToByte()
        val subMeshCount = r.bits(32).toInt()
        if (subMeshCount !in 0..MAX_SUB_MESHES) return null
        val vertexBits = bitsFor(vertexCount)
        val pos = ArrayList<Float>()
        val tex = ArrayList<Float>()
        val idx = ArrayList<Int>()
        for (s in 0 until subMeshCount) {
            r.bits(8) // texture id: one picture here
            val mode = r.bits(8).toInt()
            val count = r.bits(32).toInt()
            if (count !in 0..MAX_INDICES) return null
            var index = 0
            val local = IntArray(count)
            val first = pos.size / 3
            for (k in 0 until count) {
                index += zigZag(r.bits(vertexBits))
                if (index !in 0 until vertexCount) return null
                local[k] = first + k
                pos += vertices[index * 5]; pos += vertices[index * 5 + 1]; pos += vertices[index * 5 + 2]
                tex += vertices[index * 5 + 3]; tex += vertices[index * 5 + 4]
            }
            when (mode) {
                DRAW_TRIANGLES -> for (k in 0 until count / 3 * 3) idx += local[k]
                DRAW_STRIP -> for (k in 2 until count) {
                    if (k % 2 == 0) { idx += local[k - 2]; idx += local[k - 1]; idx += local[k] }
                    else { idx += local[k - 1]; idx += local[k - 2]; idx += local[k] }
                }
                DRAW_FAN -> for (k in 2 until count) { idx += local[0]; idx += local[k - 1]; idx += local[k] }
                else -> return null
            }
        }
        return PanoMesh(pos.toFloatArray(), tex.toFloatArray(), idx.toIntArray())
    }

    /**
     * [mesh] round the viewer at [radius]: its points put on the sphere (a mesh is drawn on one, of
     * whatever size it was written at), and — when one eye's mesh points into its half of a packed
     * frame ([stereo]) — its texture spread over the whole, for the material to halve again per eye.
     */
    fun fitted(mesh: PanoMesh, radius: Float, stereo: com.therealityteam.videoplayer.data.model.StereoMode): PanoMesh {
        val p = FloatArray(mesh.positions.size)
        for (k in 0 until mesh.vertexCount) {
            val x = mesh.positions[3 * k]
            val y = mesh.positions[3 * k + 1]
            val z = mesh.positions[3 * k + 2]
            val len = sqrt(x * x + y * y + z * z).takeIf { it > 1e-6f } ?: 1f
            p[3 * k] = x / len * radius; p[3 * k + 1] = y / len * radius; p[3 * k + 2] = z / len * radius
        }
        val t = mesh.uvs.copyOf()
        val us = (0 until mesh.vertexCount).map { mesh.uvs[2 * it] }
        val vs = (0 until mesh.vertexCount).map { mesh.uvs[2 * it + 1] }
        when (stereo) {
            com.therealityteam.videoplayer.data.model.StereoMode.SBS -> if ((us.maxOrNull() ?: 1f) <= 0.5f + EPS) {
                for (k in 0 until mesh.vertexCount) t[2 * k] = mesh.uvs[2 * k] * 2f
            } else if ((us.minOrNull() ?: 0f) >= 0.5f - EPS) {
                for (k in 0 until mesh.vertexCount) t[2 * k] = (mesh.uvs[2 * k] - 0.5f) * 2f
            }
            com.therealityteam.videoplayer.data.model.StereoMode.OU -> if ((vs.minOrNull() ?: 0f) >= 0.5f - EPS) {
                for (k in 0 until mesh.vertexCount) t[2 * k + 1] = (mesh.uvs[2 * k + 1] - 0.5f) * 2f
            } else if ((vs.maxOrNull() ?: 1f) <= 0.5f + EPS) {
                for (k in 0 until mesh.vertexCount) t[2 * k + 1] = mesh.uvs[2 * k + 1] * 2f
            }
            else -> {}
        }
        return PanoMesh(p, t, mesh.indices)
    }

    private fun bitsFor(count: Int): Int = ceil(ln(2.0 * count) / ln(2.0)).toInt().coerceAtLeast(1)

    private fun zigZag(n: Long): Int = ((n ushr 1) xor -(n and 1)).toInt()

    /** Big-endian bits from a byte array. */
    private class Bits(private val d: ByteArray, private var bit: Int) {
        fun bits(n: Int): Long {
            var v = 0L
            repeat(n) {
                val byte = d[bit ushr 3].toInt() and 0xFF
                v = (v shl 1) or ((byte ushr (7 - (bit and 7))) and 1).toLong()
                bit++
            }
            return v
        }

        fun alignToByte() { bit = (bit + 7) and 7.inv() }
    }

    private const val DRAW_TRIANGLES = 0
    private const val DRAW_STRIP = 1
    private const val DRAW_FAN = 2
    private const val MAX_COORDINATES = 10_000
    private const val MAX_VERTICES = 32 * 1024
    private const val MAX_INDICES = 128 * 1000
    private const val MAX_SUB_MESHES = 64
    private const val MAX_INFLATED = 8 * 1024 * 1024
    private const val EPS = 1e-3f
}
