package com.therealityteam.videoplayer.desktop.platform

import com.sun.jna.Callback
import com.sun.jna.CallbackReference
import com.sun.jna.Function
import com.sun.jna.Memory
import com.sun.jna.NativeLibrary
import com.sun.jna.Pointer
import com.sun.jna.Structure
import com.therealityteam.videoplayer.util.Log
import javax.swing.SwingUtilities

/** What the system's media controls ask of the player. Called on the UI thread. */
class RemoteCommands(
    val play: () -> Unit,
    val pause: () -> Unit,
    val toggle: () -> Unit,
    val next: () -> Unit,
    val previous: () -> Unit,
    val seekTo: (Long) -> Unit,
)

/**
 * The system's "now playing": the keyboard's media keys, headphone buttons, Control Center and the
 * lock screen — what is playing, how far along, and play / pause / next / previous / scrub from
 * there. macOS only for now (MediaPlayer.framework); elsewhere it does nothing.
 */
interface NowPlaying {
    /** [artworkPath]: a picture file for the title — its poster — or null. */
    fun update(title: String, subtitle: String?, artworkPath: String?, durationMs: Long, positionMs: Long, playing: Boolean, rate: Double)
    fun clear()

    companion object {
        fun create(commands: RemoteCommands): NowPlaying =
            if (Host.isMac) runCatching { MacNowPlaying(commands) }.onFailure { Log.w("AuraNowPlaying", "unavailable", it) }.getOrNull() ?: None
            else None
    }

    object None : NowPlaying {
        override fun update(title: String, subtitle: String?, artworkPath: String?, durationMs: Long, positionMs: Long, playing: Boolean, rate: Double) {}
        override fun clear() {}
    }
}

/**
 * MPRemoteCommandCenter and MPNowPlayingInfoCenter through the Objective-C runtime, by JNA: a
 * class of our own (AuraRemoteCommandHandler) answers the commands by target/action — no blocks —
 * and each update hands the centre a fresh dictionary. libmpv's own media-controls are not built
 * into mediamp's runtime (it does not link MediaPlayer), hence this.
 */
internal class MacNowPlaying(private val commands: RemoteCommands) : NowPlaying {
    private val objc = NativeLibrary.getInstance("objc")
    private val media = NativeLibrary.getInstance("/System/Library/Frameworks/MediaPlayer.framework/MediaPlayer")
    private val msgSend: Function = objc.getFunction("objc_msgSend")
    private val center: Pointer
    private val handler: Pointer

    /** A command's handler: `- (NSInteger)name:(MPRemoteCommandEvent *)event`, 0 = success. */
    fun interface CommandImp : Callback {
        fun invoke(self: Pointer?, cmd: Pointer?, event: Pointer?): Long
    }

    // Held for the program's life: the runtime calls them, and JNA frees what Java forgets.
    private val imps = mutableListOf<CommandImp>()

    init {
        val cls = handlerClass()
        handler = send(send(cls, "alloc"), "init")!!
        val commandCenter = send(cls("MPRemoteCommandCenter"), "sharedCommandCenter")!!
        fun wire(commandGetter: String, action: String) {
            val command = send(commandCenter, commandGetter)!!
            sendVoid(command, "setEnabled:", 1)
            sendVoid(command, "addTarget:action:", handler, sel(action))
        }
        wire("playCommand", "auraPlay:")
        wire("pauseCommand", "auraPause:")
        wire("togglePlayPauseCommand", "auraToggle:")
        wire("nextTrackCommand", "auraNext:")
        wire("previousTrackCommand", "auraPrevious:")
        wire("changePlaybackPositionCommand", "auraSeek:")
        center = send(cls("MPNowPlayingInfoCenter"), "defaultCenter")!!
    }

    private fun handlerClass(): Pointer {
        cls("AuraRemoteCommandHandler")?.let { return it }
        val c = objc.getFunction("objc_allocateClassPair").invokePointer(arrayOf(cls("NSObject"), "AuraRemoteCommandHandler", 0L))
            ?: error("could not make the handler class")
        fun method(name: String, run: (Pointer?) -> Unit) {
            val imp = CommandImp { _, _, event ->
                SwingUtilities.invokeLater { runCatching { run(event) }.onFailure { Log.w(TAG, "command $name failed", it) } }
                0L
            }
            imps += imp
            objc.getFunction("class_addMethod").invokeInt(arrayOf(c, sel(name), imp, "q@:@"))
        }
        method("auraPlay:") { commands.play() }
        method("auraPause:") { commands.pause() }
        method("auraToggle:") { commands.toggle() }
        method("auraNext:") { commands.next() }
        method("auraPrevious:") { commands.previous() }
        // The event is read here, on AppKit's thread, before it is gone: its position, in seconds.
        val seekImp = CommandImp { _, _, event ->
            val seconds = runCatching { msgSend.invokeDouble(arrayOf(event, sel("positionTime"))) }.getOrDefault(-1.0)
            if (seconds >= 0) SwingUtilities.invokeLater { commands.seekTo((seconds * 1000).toLong()) }
            0L
        }
        imps += seekImp
        objc.getFunction("class_addMethod").invokeInt(arrayOf(c, sel("auraSeek:"), seekImp, "q@:@"))
        objc.getFunction("objc_registerClassPair").invokeVoid(arrayOf(c))
        return c
    }

    /** Objects the runtime hands back "autoreleased" are let go of here, not on a thread that never drains. */
    private inline fun <T> pooled(block: () -> T): T {
        val pool = objc.getFunction("objc_autoreleasePoolPush").invokePointer(emptyArray())
        try { return block() } finally { objc.getFunction("objc_autoreleasePoolPop").invokeVoid(arrayOf(pool)) }
    }

    override fun update(title: String, subtitle: String?, artworkPath: String?, durationMs: Long, positionMs: Long, playing: Boolean, rate: Double) = pooled {
        runCatching {
            val info = send(cls("NSMutableDictionary"), "dictionary")!!
            fun put(key: String, value: Pointer?) { if (value != null) sendVoid(info, "setObject:forKey:", value, constant(key)) }
            put("MPMediaItemPropertyTitle", nsString(title))
            subtitle?.let { put("MPMediaItemPropertyArtist", nsString(it)) }
            artworkPath?.let { put("MPMediaItemPropertyArtwork", artwork(it)) }
            put("MPMediaItemPropertyPlaybackDuration", nsNumber(durationMs / 1000.0))
            put("MPNowPlayingInfoPropertyElapsedPlaybackTime", nsNumber(positionMs / 1000.0))
            put("MPNowPlayingInfoPropertyPlaybackRate", nsNumber(if (playing) rate else 0.0))
            put("MPNowPlayingInfoPropertyMediaType", send(cls("NSNumber"), "numberWithInteger:", 2L)) // video
            sendVoid(center, "setNowPlayingInfo:", info)
            // macOS decides which app the media keys go to by this.
            sendVoid(center, "setPlaybackState:", if (playing) 1L else 2L)
        }.onFailure { Log.w(TAG, "could not update now playing", it) }
        Unit
    }

    override fun clear() = pooled {
        runCatching {
            sendVoid(center, "setNowPlayingInfo:", null)
            sendVoid(center, "setPlaybackState:", 3L) // stopped
        }
        Unit
    }

    // ---- Artwork ----------------------------------------------------------------------------
    //
    // MPMediaItemArtwork takes its picture from a block — `NSImage *(^)(CGSize)` — asked whenever
    // the system wants it drawn. Blocks are laid out by the compiler's ABI; one can be built by
    // hand: a global block (never copied, never freed) whose invoke function is ours. All share one
    // function, which finds its picture by the block it was called through.

    /** `{ width, height }`, passed and returned by value. */
    @Structure.FieldOrder("width", "height")
    class CGSize(@JvmField var width: Double = 0.0, @JvmField var height: Double = 0.0) : Structure(), Structure.ByValue

    fun interface ImageRequest : Callback {
        fun invoke(block: Pointer?, size: CGSize): Pointer?
    }

    /** Each block's picture, by the block's address; a block whose picture was let go returns nil. */
    private val pictures = java.util.concurrent.ConcurrentHashMap<Long, Pointer>()
    private val request = ImageRequest { block, _ -> block?.let { pictures[Pointer.nativeValue(it)] } }
    private val signature = Memory(24).apply { setString(0, "@24@?0{CGSize=dd}8", "US-ASCII") }
    private val descriptor = Memory(24).apply {
        setLong(0, 0L) // reserved
        setLong(8, 32L) // the literal's size
        setPointer(16, signature)
    }
    private val globalBlockClass: Pointer = NativeLibrary.getInstance("System").getGlobalVariableAddress("_NSConcreteGlobalBlock")

    /** Blocks made, kept for the program's life: an artwork object may outlive our use of it. */
    private val blocks = mutableListOf<Memory>()

    /** Posters handed to the centre lately, by path: the block that returns each, the artwork that holds it. */
    private class Art(val block: Memory, val image: Pointer, val artwork: Pointer)
    private val arts = LinkedHashMap<String, Art>()

    private fun newBlock(): Memory = Memory(32).apply {
        setPointer(0, globalBlockClass)
        setInt(8, (1 shl 28) or (1 shl 30)) // BLOCK_IS_GLOBAL | BLOCK_HAS_SIGNATURE
        setInt(12, 0)
        setPointer(16, CallbackReference.getFunctionPointer(request))
        setPointer(24, descriptor)
        blocks += this
    }

    /** The artwork for the picture at [path], made once and kept while it is among the last few. */
    private fun artwork(path: String): Pointer? {
        arts[path]?.let { return it.artwork }
        if (!java.io.File(path).isFile) return null
        val image = send(send(cls("NSImage"), "alloc"), "initWithContentsOfFile:", nsString(path)) ?: return null
        val block = newBlock()
        pictures[Pointer.nativeValue(block)] = image
        val artwork = send(send(cls("MPMediaItemArtwork"), "alloc"), "initWithBoundsSize:requestHandler:", CGSize(600.0, 900.0), block)
            ?: return null.also { pictures.remove(Pointer.nativeValue(block)); sendVoid(image, "release") }
        arts[path] = Art(block, image, artwork)
        // Older posters go: their blocks answer nil from now on, and the pictures are released.
        while (arts.size > 4) {
            val (oldPath, old) = arts.entries.first()
            arts.remove(oldPath)
            pictures.remove(Pointer.nativeValue(old.block))
            sendVoid(old.artwork, "release")
            sendVoid(old.image, "release")
        }
        return artwork
    }

    /** The picture the centre's artwork gives when asked for one [w] × [h] — for a check that it draws. */
    internal fun artworkImageSize(w: Double, h: Double): CGSize? = pooled {
        runCatching {
            val info = send(center, "nowPlayingInfo") ?: return@runCatching null
            val art = send(info, "objectForKey:", constant("MPMediaItemPropertyArtwork")) ?: return@runCatching null
            val image = send(art, "imageWithSize:", CGSize(w, h)) ?: return@runCatching null
            msgSend.invoke(CGSize::class.java, arrayOf(image, sel("size"))) as CGSize
        }.onFailure { Log.w(TAG, "artwork check failed", it) }.getOrNull()
    }

    /** What the centre holds now — for a check that it took. */
    internal fun describe(): String? = pooled {
        runCatching {
            val info = send(center, "nowPlayingInfo") ?: return@runCatching null
            val d = send(info, "description") ?: return@runCatching null
            send(d, "UTF8String")?.getString(0, "UTF-8")
        }.getOrNull()
    }

    private fun constant(name: String): Pointer = media.getGlobalVariableAddress(name).getPointer(0)
    private fun cls(name: String): Pointer? = objc.getFunction("objc_getClass").invokePointer(arrayOf(name))
    private fun sel(name: String): Pointer = objc.getFunction("sel_registerName").invokePointer(arrayOf(name))
    private fun send(receiver: Pointer?, selector: String, vararg args: Any?): Pointer? =
        msgSend.invokePointer(arrayOf(receiver, sel(selector), *args))
    private fun sendVoid(receiver: Pointer?, selector: String, vararg args: Any?) {
        msgSend.invokeVoid(arrayOf(receiver, sel(selector), *args))
    }
    private fun nsString(s: String): Pointer? = send(cls("NSString"), "stringWithUTF8String:", s)
    private fun nsNumber(d: Double): Pointer? = send(cls("NSNumber"), "numberWithDouble:", d)

    private companion object { const val TAG = "AuraNowPlaying" }
}
