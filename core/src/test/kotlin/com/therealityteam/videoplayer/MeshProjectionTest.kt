package com.therealityteam.videoplayer

import com.therealityteam.videoplayer.data.model.ProjectionMode
import com.therealityteam.videoplayer.data.model.StereoMode
import com.therealityteam.videoplayer.data.playback.MeshProjection
import com.therealityteam.videoplayer.data.playback.PanoMesh
import com.therealityteam.videoplayer.data.playback.Panorama
import com.therealityteam.videoplayer.data.playback.SphericalInfo
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayOutputStream
import java.util.zip.Deflater
import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.ln

/**
 * Mesh projections, written here the way the spec lays them out (bit-packed zig-zag deltas, as
 * Media3's reader takes them: no padding between sub-meshes) and read back.
 */
class MeshProjectionTest {

    private class BitWriter {
        val out = ByteArrayOutputStream()
        private var cur = 0
        private var n = 0
        fun bits(v: Long, count: Int) {
            for (i in count - 1 downTo 0) {
                cur = (cur shl 1) or ((v ushr i) and 1L).toInt()
                if (++n == 8) { out.write(cur); cur = 0; n = 0 }
            }
        }
        fun align() { if (n > 0) { out.write(cur shl (8 - n)); cur = 0; n = 0 } }
    }

    private fun zig(d: Int): Long = ((d shl 1) xor (d shr 31)).toLong() and 0xFFFFFFFFL
    private fun bitsFor(count: Int) = ceil(ln(2.0 * count) / ln(2.0)).toInt()

    /** A vertex: x, y, z, u, v. */
    private class SubMesh(val mode: Int, val vertices: List<Int>)

    private fun meshBox(vertices: List<FloatArray>, subs: List<SubMesh>): ByteArray {
        val coords = vertices.flatMap { it.toList() }.distinct().sorted()
        val w = BitWriter()
        w.bits(coords.size.toLong(), 32)
        coords.forEach { w.bits(java.lang.Float.floatToIntBits(it).toLong() and 0xFFFFFFFFL, 32) }
        w.bits(vertices.size.toLong(), 32)
        val cb = bitsFor(coords.size)
        val last = IntArray(5)
        for (v in vertices) for (i in 0 until 5) {
            val index = coords.indexOf(v[i])
            w.bits(zig(index - last[i]), cb)
            last[i] = index
        }
        w.align()
        w.bits(subs.size.toLong(), 32)
        val vb = bitsFor(vertices.size)
        for (s in subs) {
            w.bits(0, 8)
            w.bits(s.mode.toLong(), 8)
            w.bits(s.vertices.size.toLong(), 32)
            var prev = 0
            for (i in s.vertices) { w.bits(zig(i - prev), vb); prev = i }
        }
        w.align()
        val payload = w.out.toByteArray()
        return be32(8 + payload.size) + "mesh".toByteArray() + payload
    }

    private fun be32(v: Int) = byteArrayOf((v ushr 24).toByte(), (v ushr 16).toByte(), (v ushr 8).toByte(), v.toByte())

    private fun mshp(meshes: List<ByteArray>, deflate: Boolean): ByteArray {
        val raw = meshes.fold(ByteArray(0)) { a, b -> a + b }
        val data = if (!deflate) raw else {
            val d = Deflater(Deflater.DEFAULT_COMPRESSION, true)
            d.setInput(raw); d.finish()
            val out = ByteArrayOutputStream()
            val buf = ByteArray(4096)
            while (!d.finished()) out.write(buf, 0, d.deflate(buf))
            d.end()
            out.toByteArray()
        }
        return ByteArray(4) + be32(0x12345678) + (if (deflate) "dfl8" else "raw ").toByteArray() + data
    }

    /** Four points of a square in front, and its corners in the picture. */
    private val square = listOf(
        floatArrayOf(-1f, -1f, -1f, 0f, 0f),
        floatArrayOf(1f, -1f, -1f, 1f, 0f),
        floatArrayOf(1f, 1f, -1f, 1f, 1f),
        floatArrayOf(-1f, 1f, -1f, 0f, 1f),
    )

    /** The triangles of [m] as their vertices' (x, y, z, u, v). */
    private fun triangles(m: PanoMesh): List<List<List<Float>>> = m.indices.toList().chunked(3).map { t ->
        t.map { k -> listOf(m.positions[3 * k], m.positions[3 * k + 1], m.positions[3 * k + 2], m.uvs[2 * k], m.uvs[2 * k + 1]) }
    }

    @Test
    fun rawAndDeflatedMeshesReadBack() {
        for (deflate in listOf(false, true)) {
            val box = meshBox(square, listOf(SubMesh(0, listOf(0, 1, 2, 0, 2, 3))))
            val meshes = MeshProjection.parse(mshp(listOf(box), deflate))
            assertNotNull("deflate=$deflate", meshes)
            val tris = triangles(meshes!!.single())
            assertEquals(2, tris.size)
            assertEquals(listOf(-1f, -1f, -1f, 0f, 0f), tris[0][0])
            assertEquals(listOf(1f, 1f, -1f, 1f, 1f), tris[1][1])
            assertEquals(listOf(-1f, 1f, -1f, 0f, 1f), tris[1][2])
        }
    }

    @Test
    fun stripsAndFansBecomeTriangles() {
        val box = meshBox(square, listOf(SubMesh(1, listOf(0, 1, 3, 2)), SubMesh(2, listOf(0, 1, 2, 3))))
        val mesh = MeshProjection.parse(mshp(listOf(box), deflate = true))!!.single()
        // A strip of four is two triangles; a fan of four is two.
        assertEquals(4, mesh.indices.size / 3)
        val tris = triangles(mesh)
        // The fan's triangles share its first point.
        assertEquals(tris[2][0], tris[3][0])
        assertEquals(listOf(-1f, -1f, -1f, 0f, 0f), tris[2][0])
    }

    @Test
    fun aStereoFilesTwoMeshesAndTheLeftEyesHalf() {
        val left = square.map { floatArrayOf(it[0], it[1], it[2], it[3] * 0.5f, it[4]) }
        val right = square.map { floatArrayOf(it[0], it[1], it[2], 0.5f + it[3] * 0.5f, it[4]) }
        val tri = listOf(SubMesh(0, listOf(0, 1, 2, 0, 2, 3)))
        val meshes = MeshProjection.parse(mshp(listOf(meshBox(left, tri), meshBox(right, tri)), deflate = false))!!
        assertEquals(2, meshes.size)
        // The left eye's mesh spread back over the whole frame: the material halves it again per eye.
        val fitted = MeshProjection.fitted(meshes[0], 50f, StereoMode.SBS)
        assertEquals(1f, fitted.uvs.toList().filterIndexed { i, _ -> i % 2 == 0 }.maxOrNull()!!, 1e-6f)
        for (k in 0 until fitted.vertexCount) {
            val r = Math.sqrt((0..2).sumOf { (fitted.positions[3 * k + it] * fitted.positions[3 * k + it]).toDouble() })
            assertEquals(50.0, r, 1e-3)
        }
    }

    @Test
    fun aFilesMeshIsWhatTheSphereShows() {
        val box = meshBox(square, listOf(SubMesh(0, listOf(0, 1, 2, 0, 2, 3))))
        val info = SphericalInfo(projection = ProjectionMode.EQ360, mesh = true, meshBody = mshp(listOf(box), deflate = true))
        val layout = info.layout()!!
        assertNotNull(layout.ownMesh)
        val shown = Panorama.mesh(layout, 10f)
        assertEquals(6, shown.indices.size)
        assertTrue(shown.positions.all { abs(it) <= 10f })
        // A mesh that cannot be read is taken for YouTube's EAC.
        assertEquals(ProjectionMode.EAC, SphericalInfo(projection = ProjectionMode.EQ360, mesh = true, meshBody = ByteArray(12)).layout()!!.projection)
    }

    @Test
    fun garbageIsNoMesh() {
        assertNull(MeshProjection.parse(ByteArray(0)))
        assertNull(MeshProjection.parse(ByteArray(4) + be32(0) + "zstd".toByteArray()))
        assertNull(MeshProjection.parse(ByteArray(4) + be32(0) + "raw ".toByteArray() + be32(4) + "mesh".toByteArray()))
    }
}
