package com.therealityteam.videoplayer

import com.therealityteam.videoplayer.data.model.MediaSourceConfig
import com.therealityteam.videoplayer.data.model.SourceType
import com.therealityteam.videoplayer.data.playback.LocalStreamServer
import com.therealityteam.videoplayer.data.source.RemoteAddress
import com.therealityteam.videoplayer.data.source.RemoteServers
import com.therealityteam.videoplayer.data.source.RemoteSource
import com.therealityteam.videoplayer.data.source.SourceException
import com.therealityteam.videoplayer.data.source.SourceFault
import kotlinx.coroutines.runBlocking
import org.dcache.nfs.ExportFile
import org.dcache.nfs.v3.MountServer
import org.dcache.nfs.v3.NfsServerV3
import org.dcache.nfs.v4.NfsIdMapping
import org.dcache.nfs.v4.xdr.nfsace4
import org.dcache.nfs.vfs.AclCheckable
import org.dcache.nfs.vfs.DirectoryEntry
import org.dcache.nfs.vfs.DirectoryStream
import org.dcache.nfs.vfs.FsStat
import org.dcache.nfs.vfs.Inode
import org.dcache.nfs.vfs.Stat
import org.dcache.nfs.vfs.VirtualFileSystem
import org.dcache.oncrpc4j.rpc.OncRpcProgram
import org.dcache.oncrpc4j.rpc.OncRpcSvc
import org.dcache.oncrpc4j.rpc.OncRpcSvcBuilder
import org.junit.AfterClass
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.BeforeClass
import org.junit.Test
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.IOException
import java.io.StringReader
import java.net.HttpURLConnection
import java.net.ServerSocket
import java.net.URL
import java.nio.ByteBuffer
import javax.security.auth.Subject
import kotlin.random.Random

/**
 * The NFS source against an NFSv3 server in this JVM (dCache's nfs4j) exporting a folder the way a
 * NAS does ("/volume1/video" under a bigger tree): walked, read by range, streamed with the subtitle
 * beside the film found.
 */
class NfsSourceTest {

    @Test fun `an NFS source finds its export and lists the film with the nfo beside it`() = runBlocking {
        val uri = "nfs://127.0.0.1:$port/volume1/video"
        val listing = RemoteSource(SourceType.NFS).listing(config(uri))
        val film = listing.videos.single()
        assertEquals("$uri/电影/Film (2010)/Film (2010).mkv", film.path)
        assertEquals(FILM.size.toLong(), film.size)
        assertEquals("$uri/电影/Film (2010)/Film (2010).nfo", film.local?.nfo)
    }

    @Test fun `NFS reads are byte-exact by range, across chunk boundaries and to the end`() {
        val address = RemoteAddress.parse("nfs://127.0.0.1:$port/volume1/video/电影/Film (2010)/Film (2010).mkv")!!
        val files = RemoteServers.files(address, null, null)
        assertEquals(FILM.size.toLong(), files.size(address.path))
        for ((start, length) in listOf(0L to 10L, 131_071L to 3L, 5L to 1_000_000L, FILM.size - 7L to 7L)) {
            val out = ByteArrayOutputStream()
            assertEquals(length, files.read(address.path, start, length, out))
            assertArrayEquals("$start+$length", FILM.copyOfRange(start.toInt(), (start + length).toInt()), out.toByteArray())
        }
        assertEquals(7L, files.read(address.path, FILM.size - 7L, 100L, ByteArrayOutputStream()))
    }

    @Test fun `the stream server serves an NFS film by range and finds its subtitles`() {
        val uri = "nfs://127.0.0.1:$port/volume1/video/电影/Film (2010)/Film (2010).mkv"
        val server = LocalStreamServer()
        try {
            val url = server.register(uri, null, null)!!
            val conn = URL(url).openConnection() as HttpURLConnection
            conn.setRequestProperty("Range", "bytes=1000000-")
            assertEquals(206, conn.responseCode)
            assertArrayEquals(FILM.copyOfRange(1_000_000, FILM.size), conn.inputStream.use { it.readBytes() })
            assertTrue(server.siblings(uri).toString(), "Film (2010).chs.srt" in server.siblings(uri))
        } finally {
            server.shutdown()
        }
    }

    @Test fun `a folder the export does not hold is a missing folder`() = runBlocking {
        RemoteServers.closeAll()
        try {
            RemoteSource(SourceType.NFS).listing(config("nfs://127.0.0.1:$port/volume1/video/nowhere"))
            fail("expected a fault")
        } catch (e: SourceException) {
            assertEquals(SourceFault.NO_FOLDER, e.fault)
        }
    }

    private fun config(uri: String) = MediaSourceConfig(id = "n", type = SourceType.NFS, name = "n", uri = uri)

    companion object {
        val FILM: ByteArray = Random(11).nextBytes(2_500_000)
        lateinit var root: File
        lateinit var svc: OncRpcSvc
        var port = 0

        @BeforeClass @JvmStatic fun server() {
            root = kotlin.io.path.createTempDirectory("nfs").toFile()
            val film = File(root, "volume1/video/电影/Film (2010)").apply { mkdirs() }
            File(root, "volume1/other").mkdirs()
            File(film, "Film (2010).mkv").writeBytes(FILM)
            File(film, "Film (2010).chs.srt").writeText("1\n00:00:01,000 --> 00:00:02,000\n你好\n")
            File(film, "Film (2010).nfo").writeText("<movie><title>Film</title></movie>")
            port = ServerSocket(0).use { it.localPort }
            svc = TestNfsServer.start(root, port, listOf("/volume1/video", "/volume1/other"))
        }

        @AfterClass @JvmStatic fun stop() {
            RemoteServers.closeAll()
            runCatching { svc.stop() }
            root.deleteRecursively()
        }
    }
}
