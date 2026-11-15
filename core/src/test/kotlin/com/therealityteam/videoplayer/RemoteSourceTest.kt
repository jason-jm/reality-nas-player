package com.therealityteam.videoplayer

import com.therealityteam.videoplayer.data.model.MediaSourceConfig
import com.therealityteam.videoplayer.data.model.SourceType
import com.therealityteam.videoplayer.data.playback.LocalStreamServer
import com.therealityteam.videoplayer.data.source.PinnedHostKeys
import com.therealityteam.videoplayer.data.source.RemoteAddress
import com.therealityteam.videoplayer.data.source.RemoteServers
import com.therealityteam.videoplayer.data.source.RemoteSource
import com.therealityteam.videoplayer.data.source.SourceException
import com.therealityteam.videoplayer.data.source.SourceFault
import kotlinx.coroutines.runBlocking
import org.apache.ftpserver.FtpServer
import org.apache.ftpserver.FtpServerFactory
import org.apache.ftpserver.listener.ListenerFactory
import org.apache.ftpserver.usermanager.PropertiesUserManagerFactory
import org.apache.ftpserver.usermanager.impl.BaseUser
import org.apache.sshd.common.file.virtualfs.VirtualFileSystemFactory
import org.apache.sshd.server.SshServer
import org.apache.sshd.server.keyprovider.SimpleGeneratorHostKeyProvider
import org.apache.sshd.sftp.server.SftpSubsystemFactory
import org.junit.AfterClass
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.BeforeClass
import org.junit.Test
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.OutputStream
import java.net.HttpURLConnection
import java.net.ServerSocket
import java.net.URL
import kotlin.random.Random

/**
 * The FTP and SFTP sources against real servers on loopback (Apache FtpServer, Apache MINA SSHD),
 * serving one folder: a film in a CJK-named folder with spaces, its subtitles, an nfo, a Subs folder.
 */
class RemoteSourceTest {

    @Test fun `addresses are read as people write them`() {
        assertEquals(RemoteAddress("ftp", "nas", 21, "/video/电影/a b.mkv"), RemoteAddress.parse("ftp://nas/video/电影/a b.mkv"))
        assertEquals(RemoteAddress("sftp", "10.0.0.2", 2222, "/"), RemoteAddress.parse("sftp://user@10.0.0.2:2222"))
        assertEquals(RemoteAddress("sftp", "fe80::1", 22, "/srv"), RemoteAddress.parse("SFTP://[fe80::1]/srv/"))
        assertEquals("ftp://nas:2121/x/y.mkv", RemoteAddress("ftp", "nas", 2121, "/").at("/x/y.mkv"))
        assertEquals("sftp://nas/x", RemoteAddress("sftp", "nas", 22, "/").at("/x"))
        assertNull(RemoteAddress.parse("smb://nas/video"))
        assertNull(RemoteAddress.parse("ftp://"))
    }

    @Test fun `an FTP source lists its films with the nfo beside them`() = listsTheFilm("ftp://127.0.0.1:$ftpPort/video", SourceType.FTP)

    @Test fun `an SFTP source lists its films with the nfo beside them`() = listsTheFilm("sftp://127.0.0.1:$sftpPort/video", SourceType.SFTP)

    private fun listsTheFilm(uri: String, type: SourceType) = runBlocking {
        val listing = RemoteSource(type).listing(config(type, uri))
        val film = listing.videos.single()
        assertEquals("$uri/电影/Film (2010)/Film (2010).mkv", film.path)
        assertEquals(FILM.size.toLong(), film.size)
        assertEquals("$uri/电影/Film (2010)/Film (2010).nfo", film.local?.nfo)
        assertTrue(listing.unread.isEmpty())
    }

    @Test fun `FTP reads are byte-exact by range, and a reader that hangs up leaves the connection good`() = readsByRange("ftp://127.0.0.1:$ftpPort")

    @Test fun `SFTP reads are byte-exact by range, and a reader that hangs up leaves the connection good`() = readsByRange("sftp://127.0.0.1:$sftpPort")

    private fun readsByRange(server: String) {
        val address = RemoteAddress.parse("$server$FILM_PATH")!!
        val files = RemoteServers.files(address, USER, PASS)
        assertEquals(FILM.size.toLong(), files.size(address.path))
        for ((start, length) in listOf(0L to 100L, 1L to 300_000L, 262_143L to 2L, FILM.size - 10L to 10L, 1_000_000L to 1_500_000L)) {
            val out = ByteArrayOutputStream()
            assertEquals(length, files.read(address.path, start, length, out))
            assertArrayEquals("$start+$length", FILM.copyOfRange(start.toInt(), (start + length).toInt()), out.toByteArray())
        }
        // Asked to the end, given less: the run stops at the file's end.
        assertEquals(10L, files.read(address.path, FILM.size - 10L, 1000L, ByteArrayOutputStream()))
        // A player's seek: it hangs up a few KB into an open-ended read, again and again.
        repeat(6) { i ->
            val hangUp = object : OutputStream() {
                var n = 0
                override fun write(b: Int) = write(byteArrayOf(b.toByte()), 0, 1)
                override fun write(b: ByteArray, off: Int, len: Int) {
                    n += len
                    if (n > 4_000) throw java.io.IOException("reader went away")
                }
            }
            files.read(address.path, i * 100_000L, FILM.size - i * 100_000L, hangUp)
        }
        val out = ByteArrayOutputStream()
        files.read(address.path, 123_456L, 50_000L, out)
        assertArrayEquals(FILM.copyOfRange(123_456, 173_456), out.toByteArray())
    }

    @Test fun `a name a server could take for a pattern is the file of that name, over FTP and SFTP`() {
        for (server in listOf("ftp://127.0.0.1:$ftpPort", "sftp://127.0.0.1:$sftpPort")) for (name in ODD_NAMES) {
            val address = RemoteAddress.parse("$server/odd/$name")!!
            val files = RemoteServers.files(address, USER, PASS)
            assertEquals("$server $name", ODD.size.toLong(), files.size(address.path))
            val out = ByteArrayOutputStream()
            assertEquals(1000L, files.read(address.path, ODD.size - 1000L, 1000L, out))
            assertArrayEquals("$server $name", ODD.copyOfRange(ODD.size - 1000, ODD.size), out.toByteArray())
        }
    }

    @Test fun `the stream server serves an FTP film by range and finds its subtitles`() = streams("ftp://127.0.0.1:$ftpPort$FILM_PATH")

    @Test fun `the stream server serves an SFTP film by range and finds its subtitles`() = streams("sftp://127.0.0.1:$sftpPort$FILM_PATH")

    private fun streams(uri: String) {
        val server = LocalStreamServer()
        try {
            val url = server.register(uri, USER, PASS)!!
            fun get(range: String?): Pair<Int, ByteArray> {
                val conn = URL(url).openConnection() as HttpURLConnection
                range?.let { conn.setRequestProperty("Range", it) }
                return conn.responseCode to conn.inputStream.use { it.readBytes() }
            }
            assertArrayEquals(FILM, get(null).second)
            val (code, part) = get("bytes=500000-")
            assertEquals(206, code)
            assertArrayEquals(FILM.copyOfRange(500_000, FILM.size), part)
            assertArrayEquals(FILM.copyOfRange(7, 70_007), get("bytes=7-70006").second)
            val beside = server.siblings(uri).toSet()
            assertTrue(beside.toString(), "Film (2010).chs.srt" in beside && "Subs/" in beside)
            assertEquals(listOf("Film (2010).eng.srt"), server.listBeside(uri, "Subs"))
            // A GBK subtitle beside it comes out as UTF-8, as from a share.
            val srt = uri.substringBeforeLast('/') + "/Film (2010).chs.srt"
            val srtUrl = server.register(srt, USER, PASS)!!
            val text = URL(srtUrl).openStream().use { it.readBytes() }.toString(Charsets.UTF_8)
            assertTrue(text, "你好" in text)
        } finally {
            server.shutdown()
        }
    }

    @Test fun `a wrong password, a missing folder and a dead port are told apart`() = runBlocking {
        for ((type, base) in listOf(SourceType.FTP to "ftp://127.0.0.1:$ftpPort", SourceType.SFTP to "sftp://127.0.0.1:$sftpPort")) {
            assertEquals(SourceFault.AUTH, faultOf { RemoteSource(type).listing(config(type, "$base/video", pass = "wrong")) })
            assertEquals(SourceFault.NO_FOLDER, faultOf { RemoteSource(type).listing(config(type, "$base/nowhere")) })
            val dead = ServerSocket(0).use { it.localPort }
            val scheme = base.substringBefore("://")
            assertEquals(SourceFault.UNREACHABLE, faultOf { RemoteSource(type).listing(config(type, "$scheme://127.0.0.1:$dead/video")) })
        }
    }

    @Test fun `an SFTP server whose key changed is refused`() = runBlocking {
        val dir = File(root, "video")
        val port = ServerSocket(0).use { it.localPort }
        val first = sftpServer(dir, port, File(keys, "first.ser"))
        RemoteSource(SourceType.SFTP).listing(config(SourceType.SFTP, "sftp://127.0.0.1:$port/"))
        first.stop(true)
        RemoteServers.closeAll()
        val second = sftpServer(dir, port, File(keys, "second.ser"))
        try {
            assertEquals(SourceFault.HOST_CHANGED, faultOf { RemoteSource(SourceType.SFTP).listing(config(SourceType.SFTP, "sftp://127.0.0.1:$port/")) })
            // Added again by the user: the server is taken as it now is.
            PinnedHostKeys.forget("127.0.0.1", port)
            RemoteServers.closeAll()
            assertNotNull(RemoteSource(SourceType.SFTP).listing(config(SourceType.SFTP, "sftp://127.0.0.1:$port/")))
        } finally {
            second.stop(true)
            RemoteServers.closeAll()
        }
    }

    private suspend fun faultOf(block: suspend () -> Unit): SourceFault? {
        RemoteServers.closeAll()
        return try {
            block()
            fail("expected a fault")
            null
        } catch (e: SourceException) {
            e.fault
        } finally {
            RemoteServers.closeAll()
        }
    }

    private fun config(type: SourceType, uri: String, pass: String = PASS) =
        MediaSourceConfig(id = "t", type = type, name = "t", uri = uri, username = USER, password = pass)

    companion object {
        const val USER = "aura"
        const val PASS = "p@ss word"
        const val FILM_PATH = "/video/电影/Film (2010)/Film (2010).mkv"
        val FILM: ByteArray = Random(7).nextBytes(3_000_000)
        /** Names an FTP server may read as patterns in MLST (Apache FtpServer answers them with the folder's facts). */
        val ODD_NAMES = listOf("What If? (2020).mkv", "A [Bracket] {Curly} (2015).mkv", "Film #1 100% & Co (2019).mkv")
        val ODD: ByteArray = Random(8).nextBytes(200_000)
        lateinit var root: File
        lateinit var keys: File
        private lateinit var ftp: FtpServer
        private lateinit var sftp: SshServer
        var ftpPort = 0
        var sftpPort = 0

        @BeforeClass @JvmStatic fun servers() {
            root = createTempDir("remote")
            keys = createTempDir("keys")
            val film = File(root, "video/电影/Film (2010)").apply { mkdirs() }
            File(film, "Film (2010).mkv").writeBytes(FILM)
            val lines = listOf("你好，欢迎来到这里。", "我们明天早上出发。", "这是一个很长的故事。", "他说他不会再回来了。", "天气越来越冷了。")
            val srt = lines.mapIndexed { i, t -> "${i + 1}\n00:00:0${i + 1},000 --> 00:00:0${i + 2},000\n$t\n" }.joinToString("\n")
            File(film, "Film (2010).chs.srt").writeBytes(srt.toByteArray(charset("GBK")))
            File(film, "Film (2010).nfo").writeText("<movie><title>Film</title></movie>")
            File(film, "Subs").mkdirs()
            File(film, "Subs/Film (2010).eng.srt").writeText("1\n00:00:01,000 --> 00:00:02,000\nHello\n")
            File(root, "odd").mkdirs()
            ODD_NAMES.forEach { File(root, "odd/$it").writeBytes(ODD) }

            val factory = FtpServerFactory()
            val listener = ListenerFactory().apply { port = ServerSocket(0).use { it.localPort } }.createListener()
            factory.addListener("default", listener)
            val users = PropertiesUserManagerFactory().apply { file = File(keys, "ftp-users.properties").apply { createNewFile() } }.createUserManager()
            users.save(BaseUser().apply { name = USER; password = PASS; homeDirectory = root.absolutePath })
            factory.userManager = users
            ftp = factory.createServer().apply { start() }
            ftpPort = listener.port

            sftpPort = ServerSocket(0).use { it.localPort }
            sftp = sftpServer(root, sftpPort, File(keys, "host.ser"))
            PinnedHostKeys.reset()
        }

        fun sftpServer(dir: File, port: Int, key: File): SshServer = SshServer.setUpDefaultServer().apply {
            this.port = port
            keyPairProvider = SimpleGeneratorHostKeyProvider(key.toPath())
            setPasswordAuthenticator { u, p, _ -> u == USER && p == PASS }
            subsystemFactories = listOf(SftpSubsystemFactory())
            fileSystemFactory = VirtualFileSystemFactory(dir.toPath())
            start()
        }

        @AfterClass @JvmStatic fun stop() {
            RemoteServers.closeAll()
            runCatching { ftp.stop() }
            runCatching { sftp.stop(true) }
            root.deleteRecursively()
            keys.deleteRecursively()
        }
    }
}
