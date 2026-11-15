package com.therealityteam.videoplayer.desktop

import com.therealityteam.videoplayer.data.model.MediaSourceConfig
import com.therealityteam.videoplayer.data.model.SourceType
import com.therealityteam.videoplayer.desktop.platform.DesktopCore
import com.therealityteam.videoplayer.desktop.player.PlayerController
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import org.apache.ftpserver.FtpServerFactory
import org.apache.ftpserver.listener.ListenerFactory
import org.apache.ftpserver.usermanager.PropertiesUserManagerFactory
import org.apache.ftpserver.usermanager.impl.BaseUser
import org.apache.sshd.common.file.virtualfs.VirtualFileSystemFactory
import org.apache.sshd.server.SshServer
import org.apache.sshd.server.keyprovider.SimpleGeneratorHostKeyProvider
import org.apache.sshd.sftp.server.SftpSubsystemFactory
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.openani.mediamp.MediaStatus
import java.io.File
import java.net.ServerSocket
import java.nio.file.Files
import kotlin.test.assertTrue

/**
 * An FTP and an SFTP source the way a user adds them: a film in a CJK folder with a GBK subtitle
 * and a dub beside it, on real servers on loopback — walked, opened through the proxy and played by
 * mpv, the subtitle and the dub offered with it. Needs ffmpeg to make the film:
 *
 *   ./gradlew -p desktop test --tests '*RemoteEndToEndProbe*' -Daura.remote.e2e=1 -Daura.mpv.opts=ao=null -i
 */
class RemoteEndToEndProbe {

    @Test
    fun addScanPlay() {
        assumeTrue("set -Daura.remote.e2e=1", System.getProperty("aura.remote.e2e") != null)
        val root = Files.createTempDirectory("aura-remote-e2e").toFile()
        val folder = File(root, "video/电影/Big Film (2010)").apply { mkdirs() }
        val film = File(folder, "Big Film (2010).mkv")
        fun ff(vararg args: String) = ProcessBuilder(listOf("ffmpeg", "-hide_banner", "-loglevel", "error", "-y") + args)
            .inheritIO().start().waitFor().also { check(it == 0) { "ffmpeg ${args.toList()}" } }
        ff("-f", "lavfi", "-i", "testsrc2=s=640x360:r=24:d=120", "-f", "lavfi", "-i", "sine=f=440:d=120",
            "-c:v", "libx264", "-preset", "veryfast", "-pix_fmt", "yuv420p", "-c:a", "aac", "-shortest", film.path)
        ff("-f", "lavfi", "-i", "sine=f=880:d=120", "-c:a", "libopus", "-b:a", "48k", File(folder, "Big Film (2010).国语.mka").path)
        val srt = (1..20).joinToString("\n") { i -> "$i\n00:00:${"%02d".format(i * 2)},000 --> 00:00:${"%02d".format(i * 2 + 1)},500\n第${i}句台词，我们出发吧。\n" }
        File(folder, "Big Film (2010).chs.srt").writeBytes(srt.toByteArray(charset("GBK")))

        val ftpPort = ServerSocket(0).use { it.localPort }
        val factory = FtpServerFactory()
        factory.addListener("default", ListenerFactory().apply { port = ftpPort }.createListener())
        val users = PropertiesUserManagerFactory().apply { file = File(root, "users.properties").apply { createNewFile() } }.createUserManager()
        users.save(BaseUser().apply { name = "aura"; password = "pw"; homeDirectory = root.absolutePath })
        factory.userManager = users
        val ftp = factory.createServer().apply { start() }
        val sftpPort = ServerSocket(0).use { it.localPort }
        val sftp = SshServer.setUpDefaultServer().apply {
            port = sftpPort
            keyPairProvider = SimpleGeneratorHostKeyProvider(File(root, "host.ser").toPath())
            setPasswordAuthenticator { u, p, _ -> u == "aura" && p == "pw" }
            subsystemFactories = listOf(SftpSubsystemFactory())
            fileSystemFactory = VirtualFileSystemFactory(root.toPath())
            start()
        }
        try {
            DesktopCore.install()
            for ((type, uri) in listOf(SourceType.FTP to "ftp://127.0.0.1:$ftpPort/video", SourceType.SFTP to "sftp://127.0.0.1:$sftpPort/video")) {
                val graph = AppGraph(Files.createTempDirectory("aura-remote-graph").toFile())
                val config = MediaSourceConfig(
                    id = "${type.name.lowercase()}:e2e", type = type, name = "Test $type", uri = uri,
                    username = "aura", password = "pw", addedAt = System.currentTimeMillis(),
                )
                graph.sources.upsert(config)
                val items = runBlocking {
                    graph.media.loaded.first { it }
                    val t0 = System.currentTimeMillis()
                    val found = graph.scanner.discover(config)
                    println("REMOTEE2E $type discovered $found in ${System.currentTimeMillis() - t0} ms")
                    graph.media.all()
                }
                items.forEach { println("REMOTEE2E $type item ${it.path}") }
                val item = items.single { it.fileName.endsWith(".mkv") }
                val player = PlayerController(graph, CoroutineScope(SupervisorJob() + Dispatchers.Main))
                runBlocking {
                    withContext(Dispatchers.Main) {
                        val mp = player.player
                        mp.javaClass.getMethod("createRenderContext\$mediamp_mpv").invoke(mp)
                        val t0 = System.currentTimeMillis()
                        player.open(item, listOf(item), startMs = 30_000, title = item.title)
                        withTimeout(40_000) { mp.state.first { it.mediaStatus == MediaStatus.Ready || it.mediaStatus is MediaStatus.Error } }
                        println("REMOTEE2E $type opened in ${System.currentTimeMillis() - t0} ms: ${mp.state.value.mediaStatus} ${player.error ?: ""}")
                        delay(3_000)
                        player.subtitleTracks.forEach { println("REMOTEE2E $type subtitle $it") }
                        player.audioTracks.forEach { println("REMOTEE2E $type audio $it") }
                        player.skip(40_000)
                        delay(2_500)
                        val pos = mp.currentPositionMillis.value
                        println("REMOTEE2E $type after a 40 s jump: $pos ms")
                        val subs = player.subtitleTracks.size
                        val audios = player.audioTracks.size
                        val dub = player.audioTracks.firstOrNull { it.external }
                        player.dispose()
                        assertTrue(pos > 60_000, "$type played past the jump ($pos)")
                        assertTrue(subs >= 1, "$type offered the subtitle beside it")
                        assertTrue(audios >= 2, "$type offered the dub beside it ($audios)")
                        assertTrue(dub?.title == "国语" && dub.language == "zh", "$type named the dub by its tag, in its language ($dub)")
                    }
                }
            }
        } finally {
            ftp.stop()
            sftp.stop(true)
            root.deleteRecursively()
        }
    }
}
