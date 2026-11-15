package com.therealityteam.videoplayer.desktop

import com.therealityteam.videoplayer.data.playback.PanoFlatten
import com.therealityteam.videoplayer.data.playback.PanoLayout
import com.therealityteam.videoplayer.data.playback.PanoViewport
import com.therealityteam.videoplayer.desktop.platform.DesktopCore
import com.therealityteam.videoplayer.desktop.platform.Host
import com.therealityteam.videoplayer.desktop.player.MediaOpen
import com.therealityteam.videoplayer.desktop.player.PlayerController
import com.therealityteam.videoplayer.desktop.player.SphereView
import com.therealityteam.videoplayer.desktop.player.formatTime
import androidx.compose.ui.graphics.asSkiaBitmap
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.withTimeoutOrNull
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.openani.mediamp.MediaStatus
import org.openani.mediamp.features.Screenshots
import java.io.File
import java.nio.file.Files

/**
 * A real file through the real player, without a window: mediamp's headless render context
 * (what its own screenshot tests use) stands in for the screen, so decoding, the media opener's
 * subtitle discovery, track reading, seeking and a captured frame can be checked on a locked
 * machine. Off unless asked for:
 *
 *   ./gradlew -p desktop test --tests '*PlaybackProbeTest*' -Daura.probe.play=/path/film.mkv \
 *       -Daura.probe.shot=/tmp/frame.png -i
 */
class PlaybackProbeTest {

    @Test
    fun playAFile() {
        val path = System.getProperty("aura.probe.play")
        assumeTrue("set -Daura.probe.play", path != null)
        DesktopCore.install()
        if (System.getProperty("aura.probe.mpvlog") != null) {
            org.openani.mediamp.mpv.MPVHandle.setLogHandler { m -> if (m.level <= org.openani.mediamp.mpv.MPVLog.INFO) println("MPV $m") }
        }
        val graph = AppGraph(Files.createTempDirectory("aura-play-probe").toFile())
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
        val player = PlayerController(graph, scope)
        runBlocking {
            withContext(Dispatchers.Main) {
                val mp = player.player
                val headless = mp.javaClass.getMethod("createRenderContext\$mediamp_mpv").invoke(mp) as Boolean
                println("PLAYPROBE headless render context: $headless")
                // -Daura.probe.original=기생충: the film's original title, as a scrape gives it (默认's original-language pick).
                val item = MediaOpen.adHocItem(path!!).let { i -> System.getProperty("aura.probe.original")?.let { i.copy(originalTitle = it) } ?: i }
                val t0 = System.currentTimeMillis()
                player.open(item, listOf(item), startMs = System.getProperty("aura.probe.start")?.toLongOrNull() ?: 600_000, title = "probe")
                withTimeout(40_000) { mp.state.first { it.mediaStatus == MediaStatus.Ready || it.mediaStatus is MediaStatus.Error } }
                println("PLAYPROBE opened in ${System.currentTimeMillis() - t0} ms: ${mp.state.value} error=${player.error}")
                delay(4_000)
                println("PLAYPROBE position ${mp.currentPositionMillis.value} ms of ${player.durationMs()} ms, playing=${mp.state.value.isPlaying}")
                // -Daura.probe.guess=accept|dismiss|wait: the picture looked at for a panorama (ProjectionGuess),
                // up to 40 s, and the question answered.
                System.getProperty("aura.probe.guess")?.let { answer ->
                    val asked = withTimeoutOrNull(40_000) { while (player.panoGuess == null) delay(250); player.panoGuess }
                    println("PLAYPROBE guess: ${asked ?: "none"}")
                    when (answer) {
                        "accept" -> player.acceptPanoGuess()
                        "dismiss" -> player.dismissPanoGuess()
                    }
                    delay(1_500)
                    println("PLAYPROBE after the guess: flags=${player.flags} sphere=${player.pano?.projection}")
                }
                // -Daura.probe.abnext=/other/file: an A–B loop set here, then that file opened — does
                // mpv's loop go with it?
                System.getProperty("aura.probe.abnext")?.let { other ->
                    val handle = mp.impl as org.openani.mediamp.mpv.MPVHandle
                    player.cycleAb(); delay(1_000); player.cycleAb()
                    println("PLAYPROBE ab set: app ${player.abA}–${player.abB}, mpv ${handle.getPropertyString("ab-loop-a")}–${handle.getPropertyString("ab-loop-b")}")
                    val next = MediaOpen.adHocItem(other)
                    player.open(next, listOf(next), startMs = 0, title = "probe 2")
                    delay(2_500)
                    println("PLAYPROBE ab after next file: app ${player.abA}–${player.abB}, mpv ${handle.getPropertyString("ab-loop-a")}–${handle.getPropertyString("ab-loop-b")}")
                }
                // -Daura.probe.lift=150 with -Daura.probe.shot=…: the subtitles where they sit with the
                // controls hidden, then lifted over controls that many pixels high (of a 720-high area).
                System.getProperty("aura.probe.lift")?.toFloatOrNull()?.let { px ->
                    mp.pause()
                    delay(600)
                    val base = System.getProperty("aura.probe.shot")!!.removeSuffix(".png")
                    mp.features[Screenshots]?.takeScreenshot("${base}_low.png")
                    player.liftSubtitles(px, 720)
                    delay(600)
                    mp.features[Screenshots]?.takeScreenshot("${base}_lifted.png")
                    println("PLAYPROBE lift $px: sub-margin-y ${(mp.impl as org.openani.mediamp.mpv.MPVHandle).getPropertyString("sub-margin-y")}")
                }
                // -Daura.probe.awake=1: the screen held on while playing and let go on a pause, as the
                // system itself reports it.
                if (System.getProperty("aura.probe.awake") != null) {
                    fun assertions() = ProcessBuilder("pmset", "-g", "assertions").redirectErrorStream(true).start()
                        .inputStream.bufferedReader().readLines().filter { "Aura" in it || "PreventUserIdleDisplaySleep" in it }
                    mp.resume()
                    delay(1_500)
                    com.therealityteam.videoplayer.desktop.platform.KeepAwake.settle()
                    println("PLAYPROBE awake while playing: held=${com.therealityteam.videoplayer.desktop.platform.KeepAwake.isHeld} ${assertions()}")
                    mp.pause()
                    delay(1_500)
                    com.therealityteam.videoplayer.desktop.platform.KeepAwake.settle()
                    println("PLAYPROBE awake after a pause: held=${com.therealityteam.videoplayer.desktop.platform.KeepAwake.isHeld} ${assertions()}")
                }
                player.audioTracks.forEach { println("PLAYPROBE audio $it") }
                player.subtitleTracks.forEach { println("PLAYPROBE sub $it") }
                println("PLAYPROBE chapters ${player.chapters.size}: ${player.chapters.map { it.startMs }}")
                val h = mp.impl as org.openani.mediamp.mpv.MPVHandle
                println("PLAYPROBE audio-device ${h.getPropertyString("audio-device")} current-ao ${h.getPropertyString("current-ao")} mpv ${h.getPropertyString("mpv-version")} ffmpeg ${h.getPropertyString("ffmpeg-version")}")
                println("PLAYPROBE devices ${h.getPropertyString("audio-device-list")}")
                println("PLAYPROBE audio output failed: ${player.audioOutputFailed}")
                System.getProperty("aura.probe.device")?.let { dev ->
                    player.chooseAudioDevice(dev)
                    delay(2_500)
                    println("PLAYPROBE after choosing $dev: failed=${player.audioOutputFailed} ao=${h.getPropertyString("current-ao")}")
                    player.chooseAudioDevice("auto")
                }
                player.mediaInfo().forEach { println("PLAYPROBE info ${it.first}: ${it.second}") }
                // -Daura.probe.cmds="vf add @p:v360=e:flat;set video-crop 960x1080+0+0": mpv commands,
                // run in order after the file opened, each reported, before any screenshot.
                System.getProperty("aura.probe.cmds")?.split(';')?.map { it.trim() }?.filter { it.isNotEmpty() }?.forEach { line ->
                    val args = line.split(Regex("\\s+"))
                    val r = runCatching { h.command(*args.toTypedArray()) }
                    println("PLAYPROBE cmd `$line` -> ${r.exceptionOrNull() ?: "ok"}")
                    delay(600)
                }
                // -Daura.probe.frames=f,f,b: 逐帧 steps on (f) and back (b), each one's position after it.
                System.getProperty("aura.probe.frames")?.split(',')?.map { it.trim() }?.filter { it == "f" || it == "b" }?.forEach { step ->
                    player.stepFrame(forward = step == "f")
                    delay(800)
                    println("PLAYPROBE frame $step -> ${h.getPropertyString("time-pos")} s, paused=${h.getPropertyString("pause")}")
                }
                // -Daura.probe.dragloop=1: 90 drags of the view, 30 a second, while playing — what turning
                // a spherical film costs in dropped frames.
                if (System.getProperty("aura.probe.dragloop") != null) {
                    h.command("set", "pause", "no")
                    delay(500)
                    val drops0 = runCatching { h.getPropertyInt("frame-drop-count") }.getOrDefault(-1)
                    val t0 = System.nanoTime()
                    repeat(90) { player.look(-0.01, 0.002); delay(33) }
                    delay(300)
                    val drops1 = runCatching { h.getPropertyInt("frame-drop-count") }.getOrDefault(-1)
                    println("PLAYPROBE dragloop: 90 drags in ${(System.nanoTime() - t0) / 1_000_000} ms, frames dropped ${drops1 - drops0}, view ${player.view}, shader files ${File(Host.dataDir, "shaders").list()?.size}")
                }
                // -Daura.probe.views="0,0;1.5708,0" with -Daura.probe.shader=… and -Daura.probe.shot=…: the
                // spherical view at each yaw,pitch (radians), one screenshot each (shot name + _N).
                System.getProperty("aura.probe.views")?.let { views ->
                    val text = File(System.getProperty("aura.probe.shader")!!).readText()
                    val dir = Files.createTempDirectory("aura-views").toFile()
                    h.command("set", "video-aspect-override", "16:9")
                    views.split(';').forEachIndexed { i, v ->
                        val (yaw, pitch) = v.split(',').map { it.trim() }
                        val f = File(dir, "view$i.glsl")
                        f.writeText(text.replace("{{YAW}}", yaw).replace("{{PITCH}}", pitch).replace("{{FOV}}", "1.75")
                            .replace("{{ASPECT}}", "1.7777778").replace("{{MODE}}", System.getProperty("aura.probe.mode") ?: "0.0")
                            .replace("{{STEREO}}", "0.0"))
                        h.command("change-list", "glsl-shaders", "set", f.absolutePath)
                        delay(700)
                        val shot = System.getProperty("aura.probe.shot")!!.removeSuffix(".png") + "_$i.png"
                        mp.features[Screenshots]?.takeScreenshot(shot)
                        println("PLAYPROBE view yaw=$yaw pitch=$pitch -> $shot")
                    }
                }
                // -Daura.probe.look="0.25,0;0.25,0": the player's own drags (fractions of the picture), one
                // screenshot after each — the spherical view as the app drives it.
                System.getProperty("aura.probe.look")?.let { looks ->
                    println("PLAYPROBE format ${player.format} spherical=${player.spherical} flags=${player.flags}")
                    delay(800)
                    val base = System.getProperty("aura.probe.shot")!!.removeSuffix(".png")
                    mp.features[Screenshots]?.takeScreenshot("${base}_look0.png")
                    val views = mutableListOf(player.view)
                    looks.split(';').forEachIndexed { i, l ->
                        val (dx, dy) = l.split(',').map { it.trim().toDouble() }
                        player.look(dx, dy)
                        delay(700)
                        mp.features[Screenshots]?.takeScreenshot("${base}_look${i + 1}.png")
                        println("PLAYPROBE look $dx,$dy -> view ${player.view}")
                        views += player.view
                    }
                    // -Daura.probe.ref=/clip's/source-frame.png: each look's shot against the same view worked
                    // out on the CPU — the headset's own mapping (core Panorama, through PanoFlatten).
                    System.getProperty("aura.probe.ref")?.let { ref ->
                        val layout = player.pano ?: return@let println("PLAYPROBE ref: no sphere")
                        views.forEachIndexed { i, view ->
                            val (diff, control) = againstReference(File("${base}_look$i.png"), File(ref), layout, view)
                            println("PLAYPROBE ref look$i: mean difference %.1f (the view 3° off: %.1f)".format(diff, control))
                        }
                    }
                }
                // -Daura.probe.level=tilt,roll: the horizon levelled (画面方向), one shot after — with -Daura.probe.ref and no
                // roll, against the CPU's view looking down by the tilt (lifting the front is looking down at it).
                System.getProperty("aura.probe.level")?.let { lv ->
                    val (tilt, roll) = lv.split(',').map { it.trim().toFloat() }
                    player.resetView()
                    player.nudgeLevel(tilt, roll)
                    delay(900)
                    val base = System.getProperty("aura.probe.shot")!!.removeSuffix(".png")
                    mp.features[Screenshots]?.takeScreenshot("${base}_level.png")
                    println("PLAYPROBE level ${player.level}")
                    System.getProperty("aura.probe.ref")?.takeIf { roll == 0f }?.let { ref ->
                        val (diff, control) = againstReference(File("${base}_level.png"), File(ref), player.pano!!, SphereView(pitch = Math.toRadians(-tilt.toDouble())))
                        println("PLAYPROBE ref level: mean difference %.1f (the view 3° off: %.1f)".format(diff, control))
                    }
                }
                System.getProperty("aura.probe.props")?.split(',')?.forEach { p ->
                    println("PLAYPROBE prop $p = ${runCatching { h.getPropertyString(p.trim()) }.getOrElse { "<$it>" }}")
                }
                player.skip(60_000)
                delay(2_000)
                println("PLAYPROBE after +60s: ${mp.currentPositionMillis.value} ms")
                // The seek bar's pictures: five points across the film, timed, written out to look at.
                System.getProperty("aura.probe.preview")?.let { dir ->
                    File(dir).mkdirs()
                    val duration = player.durationMs()
                    for (f in listOf(0.1, 0.3, 0.5, 0.7, 0.9)) {
                        val at = (duration * f).toLong()
                        val t = System.nanoTime()
                        val img = player.previewFrame(at, 416, 234)
                        val took = (System.nanoTime() - t) / 1_000_000
                        val again = System.nanoTime().let { t2 -> player.previewFrame(at, 416, 234); (System.nanoTime() - t2) / 1_000 }
                        println("PLAYPROBE preview at ${formatTime(at)}: ${img?.width}x${img?.height} in $took ms (cached: $again µs)")
                        img?.let { i ->
                            val png = org.jetbrains.skia.Image.makeFromBitmap(i.asSkiaBitmap()).encodeToData(org.jetbrains.skia.EncodedImageFormat.PNG)!!.bytes
                            File(dir, "preview_${(f * 100).toInt()}.png").writeBytes(png)
                        }
                    }
                }
                System.getProperty("aura.probe.shot")?.let { shot ->
                    mp.features[Screenshots]?.takeScreenshot(shot)
                    println("PLAYPROBE screenshot ${File(shot).length()} bytes at $shot")
                }
                player.dispose()
            }
        }
    }

    /**
     * The view the shader drew ([shot]: the picture 16:9 in the middle of the frame-sized render) against the same
     * view of the clip's source frame [ref] worked out on the CPU (PanoFlatten — core Panorama, the
     * headset's mapping), written beside the shot as _ref.png. Their mean difference per channel (0–255),
     * and the same with the CPU's view 3° to the right: H.264's loss is a few; a picture laid out wrong,
     * or turned, is many — the second number says how many.
     */
    private fun againstReference(shot: File, ref: File, layout: PanoLayout, view: SphereView): Pair<Double, Double> {
        val img = javax.imageio.ImageIO.read(shot)
        // The shot is the frame's size; the view is 16:9 in its middle, bars at the sides or above and below.
        val wide = img.width.toDouble() / img.height > 16.0 / 9
        val w = if (wide) Math.round(img.height * 16.0 / 9).toInt() else img.width
        val h = if (wide) img.height else Math.round(img.width * 9.0 / 16).toInt()
        val got = img.getRGB((img.width - w) / 2, (img.height - h) / 2, w, h, null, 0, w)
        val src = javax.imageio.ImageIO.read(ref)
        val frame = src.getRGB(0, 0, src.width, src.height, null, 0, src.width)
        // As the shader lays it out.
        val l = layout.meshless(src.width, src.height)
        fun cpu(yawOff: Double) = PanoFlatten.view(
            frame, src.width, src.height, l, w, h,
            PanoViewport.Look(Math.toDegrees(view.yaw + yawOff).toFloat(), Math.toDegrees(view.pitch).toFloat(), Math.toDegrees(view.fov).toFloat()),
        )
        val want = cpu(0.0)
        val out = java.awt.image.BufferedImage(w, h, java.awt.image.BufferedImage.TYPE_INT_RGB)
        out.setRGB(0, 0, w, h, want, 0, w)
        javax.imageio.ImageIO.write(out, "png", File(shot.path.removeSuffix(".png") + "_ref.png"))
        fun diff(a: IntArray): Double {
            var sum = 0L
            var n = 0L
            for (y in 4 until h - 4) for (x in 4 until w - 4) {
                val p = a[y * w + x]
                val q = got[y * w + x]
                for (sh in intArrayOf(16, 8, 0)) sum += kotlin.math.abs(((p shr sh) and 0xFF) - ((q shr sh) and 0xFF))
                n += 3
            }
            return sum.toDouble() / n
        }
        return diff(want) to diff(cpu(Math.toRadians(3.0)))
    }
}
