package com.therealityteam.videoplayer.data.playback

import com.hierynomus.msdtyp.AccessMask
import com.hierynomus.msfscc.FileAttributes
import com.hierynomus.mssmb2.SMB2CreateDisposition
import com.hierynomus.mssmb2.SMB2ShareAccess
import com.hierynomus.smbj.SMBClient
import com.hierynomus.smbj.auth.AuthenticationContext
import com.hierynomus.smbj.connection.Connection
import com.hierynomus.smbj.share.DiskShare
import com.therealityteam.videoplayer.data.disc.DiscFolder
import com.therealityteam.videoplayer.data.disc.DiscTitle
import com.therealityteam.videoplayer.data.source.RemoteAddress
import com.therealityteam.videoplayer.data.source.RemoteFiles
import com.therealityteam.videoplayer.data.source.RemoteServers
import com.therealityteam.videoplayer.data.source.connectTo
import com.therealityteam.videoplayer.data.subtitle.SubtitleTranscoder
import java.io.OutputStream
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors
import kotlin.concurrent.thread

/**
 * Tiny loopback HTTP server that bridges network shares (SMB, FTP, SFTP) to the built-in
 * player, which streams http(s) but not smb://, ftp:// or sftp://. [register] opens the remote file and returns
 * a `http://127.0.0.1:<port>/…` URL; the server answers Range requests by random-reading the
 * SMB file, so the player can seek. Kept minimal and dependency-free.
 *
 * Text subtitles (.srt .ass .ssa .vtt) go out as UTF-8 whatever they were written in: neither
 * player reads another charset — ExoPlayer assumes UTF-8, and mediamp's libmpv is built without
 * charset guessing — so a GBK or Big5 file beside a video on a share, still the common case for
 * Chinese subtitles, came out as mojibake.
 */
class LocalStreamServer {

    /** What the server streams from: an SMB file, or (in tests) anything that can be read by range. */
    internal interface Source {
        fun size(): Long
        fun copyRange(start: Long, length: Long, out: OutputStream)
        fun close()
    }

    private var server: ServerSocket? = null
    @Volatile private var port = 0
    private val streams = ConcurrentHashMap<String, Source>()
    private val shares = SharePool()

    /** Text subtitles as served, UTF-8, by stream id: read once, they are small. */
    private val texts = ConcurrentHashMap<String, ByteArray>()
    /** The .sub beside a registered VobSub .idx, by the .idx's id ([picturesOf]). */
    private val pictures = ConcurrentHashMap<String, Source>()
    private val pool = Executors.newCachedThreadPool { r -> Thread(r, "aura-stream").apply { isDaemon = true } }

    @Synchronized
    private fun ensureStarted() {
        if (server != null) return
        val s = ServerSocket(0, 8, InetAddress.getByName("127.0.0.1"))
        server = s
        port = s.localPort
        thread(isDaemon = true, name = "aura-stream-accept") {
            while (!s.isClosed) {
                val sock = runCatching { s.accept() }.getOrNull() ?: break
                pool.submit { runCatching { serve(sock) }; runCatching { sock.close() } }
            }
        }
    }

    /**
     * Register an smb:// file and return a local URL the player can stream. [key] names the
     * registration (the path itself, by default): a download reads the file under a key of its own,
     * so its unregister never closes the stream a film playing the same file is reading.
     */
    fun register(smbUri: String, username: String?, password: String?, key: String = smbUri): String? {
        val stream = remoteStream(smbUri, username, password) ?: parseSmb(smbUri)?.let { SmbStream(it, username, password, shares) } ?: return null
        ensureStarted()
        val id = Integer.toHexString(key.hashCode())
        streams.getOrPut(id) { stream }
        val name = smbUri.substringAfterLast('/').ifBlank { "video" }
        return "http://127.0.0.1:$port/$id/$name"
    }

    /**
     * A WebDAV server's file ([uri], http or https) served on loopback with its [username]'s Basic login and
     * by range — what the engines are given for a server that asks for one (Media3 sends no login of its
     * own), and for its text subtitles, which are handed over in UTF-8 like a share's. [key] as for [register].
     */
    fun registerHttp(uri: String, username: String?, password: String?, key: String = uri): String {
        ensureStarted()
        val id = Integer.toHexString(key.hashCode())
        val auth = if (username.isNullOrBlank()) emptyMap()
        else mapOf("Authorization" to "Basic " + java.util.Base64.getEncoder().encodeToString("$username:${password.orEmpty()}".toByteArray()))
        streams.getOrPut(id) { HttpRangeSource(uri, auth) }
        val name = runCatching { java.net.URLDecoder.decode(uri.substringBefore('?').substringAfterLast('/').replace("+", "%2B"), "UTF-8") }
            .getOrDefault(uri.substringAfterLast('/')).ifBlank { "video" }
        return "http://127.0.0.1:$port/$id/$name"
    }

    /** An ftp:// or sftp:// file as a stream, over its server's kept connections (RemoteServers). */
    private fun remoteStream(uri: String, username: String?, password: String?): RemoteStream? {
        val address = RemoteAddress.parse(uri) ?: return null
        return RemoteStream(address, RemoteServers.files(address, username, password))
    }

    /**
     * A disc image (原盘 .iso) — on a share (smb://), on the device (a path), or a WebDAV server's
     * (http, with [username]'s Basic login) — opened for its film ([DiscTitle]): found, its streams are
     * served as one file at the URL returned, read straight from the image. Blocking: reading the image's
     * file system costs a few requests to its share. [key] names the registration, as for [register].
     */
    fun registerDisc(uri: String, username: String?, password: String?, key: String = uri): DiscOpen {
        val image = imageSource(uri, username, password) ?: return DiscOpen.Unreadable
        val bytes = SourceBytes(image)
        val found = runCatching { DiscTitle.find(bytes) }.getOrElse {
            image.close()
            return DiscOpen.Unreadable
        }
        // An image with no film: a data disc of videos plays them; else what it holds, for the log.
        if (found == DiscTitle.Found.NoFilm) {
            val videos = runCatching { DiscTitle.filesOf(bytes)?.let(DiscTitle::videosIn) }.getOrNull().orEmpty()
            if (videos.isNotEmpty()) {
                image.close()
                return DiscOpen.Videos(videos)
            }
            runCatching {
                com.therealityteam.videoplayer.util.Log.i("AuraDisc", "${uri.substringAfterLast('/')}: no film; holds ${DiscTitle.contents(bytes)}")
            }
        }
        return serveDisc(found, image, key, uri.substringAfterLast('/').substringBeforeLast('.'))
    }

    /**
     * One file inside a disc image ([inner], its path there) served as it lies in the image — a data disc's
     * video. Null when the image or the file cannot be read. Blocking.
     */
    fun registerDiscFile(uri: String, username: String?, password: String?, inner: String, key: String): String? {
        val image = imageSource(uri, username, password) ?: return null
        val file = runCatching { DiscTitle.filesOf(SourceBytes(image))?.file(inner) }.getOrNull()
        if (file == null) {
            image.close()
            return null
        }
        ensureStarted()
        val id = Integer.toHexString(key.hashCode())
        streams.remove(id)?.close()
        streams[id] = DiscStream(image, listOf(DiscTitle.Part(inner.substringAfterLast('/'), file.first, file.second)))
        val name = inner.substringAfterLast('/').replace(Regex("[^\\p{L}\\p{N}._-]+"), "_").trim('_').ifBlank { "video" }
        return "http://127.0.0.1:$port/$id/$name"
    }

    /** What reads a disc image at [uri]: a share's, a file server's, a WebDAV server's (with its login), the device's. */
    private fun imageSource(uri: String, username: String?, password: String?): Source? {
        val image: Source = when {
            uri.startsWith("smb://") -> SmbStream(parseSmb(uri) ?: return null, username, password, shares)
            RemoteAddress.parse(uri) != null -> remoteStream(uri, username, password)!!
            uri.startsWith("http://") || uri.startsWith("https://") -> HttpRangeSource(
                uri,
                if (username.isNullOrBlank()) emptyMap()
                else mapOf("Authorization" to "Basic " + java.util.Base64.getEncoder().encodeToString("$username:${password.orEmpty()}".toByteArray())),
            )
            else -> java.io.File(uri.removePrefix("file://")).takeIf { it.isFile }?.let(::FileSource) ?: return null
        }
        return image
    }

    /**
     * A disc ripped as its files — [uri] one of them, or a file under a folder holding BDMV / VIDEO_TS
     * ([com.therealityteam.videoplayer.data.disc.DiscImages.folderOf]) — opened for its film as an image
     * is ([DiscFolder]): the playlist's clips played as one stream, not the largest of them alone (a DVD's
     * film is five 1 GB VOBs; a Blu-ray's can be several clips). On a share (smb://), an FTP / SFTP / NFS
     * server, a WebDAV server or the device. [DiscOpen.NoFilm] when the folder has no playlist to go by.
     */
    fun registerDiscFolder(uri: String, username: String?, password: String?, key: String = uri): DiscOpen {
        val root = com.therealityteam.videoplayer.data.disc.DiscImages.folderOf(uri) ?: return DiscOpen.NoFilm
        val folder = folderOf(root, username, password) ?: return DiscOpen.Unreadable
        val found = runCatching { DiscTitle.find(folder, SourceBytes(folder)) }.getOrElse {
            folder.close()
            return DiscOpen.Unreadable
        }.let { f -> if (f is DiscTitle.Found.Film) DiscTitle.Found.Film(f.title.copy(timings = f.title.timings + " (${folder.stats})")) else f }
        return serveDisc(found, folder, key, root.trimEnd('/').substringAfterLast('/'))
    }

    private fun serveDisc(found: DiscTitle.Found, image: Source, key: String, name: String): DiscOpen {
        val title = when (found) {
            is DiscTitle.Found.Film -> found.title
            DiscTitle.Found.NoFilm, DiscTitle.Found.NotADisc -> { image.close(); return DiscOpen.NoFilm }
        }
        if (title.encrypted) {
            image.close()
            return DiscOpen.Encrypted(title.kind)
        }
        ensureStarted()
        val id = Integer.toHexString(key.hashCode())
        streams.remove(id)?.close()
        streams[id] = DiscStream(image, title.parts, title.kind)
        // (Named for the log and the player's guess at what it is: the extension; nothing a URL must escape.)
        val stem = name.replace(Regex("[^\\p{L}\\p{N}._-]+"), "_").trim('_').ifBlank { "disc" }
        return DiscOpen.Playing("http://127.0.0.1:$port/$id/$stem.${title.kind.extension}", title)
    }

    /**
     * The names in the folder [uri] — on a share, an FTP / SFTP / NFS server, or the device — folders
     * ending in "/"; null when it cannot be listed. For what lies a folder up from a video (a fansub's
     * fonts beside its SPs/). Blocking.
     */
    fun listFolder(uri: String, username: String?, password: String?): List<String>? =
        runCatching { folderOf(uri.trimEnd('/'), username, password) }.getOrNull()?.let { folder ->
            try {
                folder.list("")?.map { if (it.isDirectory) "${it.name}/" else it.name }
            } finally {
                folder.close()
            }
        }

    /** The disc folder at [root] — its files listed and opened where they lie. */
    private fun folderOf(root: String, username: String?, password: String?): DiscFolder? {
        fun join(base: String, relative: String, sep: Char) = listOf(base.trimEnd(sep), relative.replace('/', sep)).filter { it.isNotEmpty() }.joinToString(sep.toString())
        return when {
            root.startsWith("smb://", ignoreCase = true) -> {
                val loc = parseSmb("$root/x")?.let { it.copy(path = it.path.substringBeforeLast('\\', "")) } ?: return null
                DiscFolder(
                    lister = { rel ->
                        shares.share(loc, username, password).list(join(loc.path, rel, '\\')).mapNotNull { e ->
                            val dir = (e.fileAttributes and FileAttributes.FILE_ATTRIBUTE_DIRECTORY.value) != 0L
                            e.fileName.takeUnless { it == "." || it == ".." }?.let { DiscFolder.Listed(it, dir, e.endOfFile.takeIf { !dir }) }
                        }
                    },
                    opener = { rel -> SmbStream(loc.copy(path = join(loc.path, rel, '\\')), username, password, shares) },
                )
            }
            RemoteAddress.parse(root) != null -> {
                val address = RemoteAddress.parse(root)!!
                val files = RemoteServers.files(address, username, password)
                DiscFolder(
                    lister = { rel -> files.list(join(address.path, rel, '/'))?.map { DiscFolder.Listed(it.name, it.isDirectory, it.size) } },
                    opener = { rel -> RemoteStream(address.copy(path = join(address.path, rel, '/')), files) },
                )
            }
            root.startsWith("http://") || root.startsWith("https://") -> {
                val auth = if (username.isNullOrBlank()) emptyMap()
                else mapOf("Authorization" to "Basic " + java.util.Base64.getEncoder().encodeToString("$username:${password.orEmpty()}".toByteArray()))
                val urls = HashMap<String, String>()
                DiscFolder(
                    lister = { rel ->
                        val dir = root.trimEnd('/') + "/" + rel.split('/').joinToString("/") { java.net.URLEncoder.encode(it, "UTF-8").replace("+", "%20") } + "/"
                        kotlinx.coroutines.runBlocking {
                            com.therealityteam.videoplayer.data.source.WebDavSource().listFiles(dir, username, password)
                        }.map { (name, url) -> synchronized(urls) { urls["$rel/$name".uppercase()] = url }; DiscFolder.Listed(name, false, null) }
                    },
                    opener = { rel -> synchronized(urls) { urls[rel.uppercase()] }?.let { HttpRangeSource(it, auth) } },
                )
            }
            else -> {
                val dir = java.io.File(root.removePrefix("file://")).takeIf { it.isDirectory } ?: return null
                DiscFolder(
                    lister = { rel -> java.io.File(dir, rel).listFiles()?.map { DiscFolder.Listed(it.name, it.isDirectory, it.length().takeIf { _ -> it.isFile }) } },
                    opener = { rel -> java.io.File(dir, rel).takeIf { it.isFile }?.let(::FileSource) },
                )
            }
        }
    }

    /** Serve [source] under [name] — for tests, which have no share to read. */
    internal fun register(id: String, name: String, source: Source): String {
        ensureStarted()
        streams[id] = source
        texts.remove(id)
        return "http://127.0.0.1:$port/$id/$name"
    }

    /**
     * The names of the files beside [smbUri] — a registered stream — read over its own login to the
     * share, the one the video opens through anyway: the player's subtitle lookup made a connection
     * and a login of its own, ~0.4 s before the video could start opening.
     */
    fun siblings(key: String): List<String> =
        (streams[Integer.toHexString(key.hashCode())] as? Beside ?: error("not registered: $key")).siblings()

    /** The names in a folder beside a registered stream ([key]): `Subs`, `Subs/<episode>`. */
    fun listBeside(key: String, relative: String): List<String> =
        (streams[Integer.toHexString(key.hashCode())] as? Beside ?: error("not registered: $key")).listBeside(relative)

    /** A stream that can say what lies beside it in its folder — the subtitles and dubs to offer with it. */
    private interface Beside {
        /** The names in the stream's folder, folders ending in "/". */
        fun siblings(): List<String>

        /** The names in the folder [relative] to the stream's own ("Subs", "Subs/Show.S01E01"). */
        fun listBeside(relative: String): List<String>
    }

    /** Closes the registration made under [key] (the path, unless another was given to [register]). */
    fun unregister(key: String) {
        val id = Integer.toHexString(key.hashCode())
        streams.remove(id)?.close()
        pictures.remove(id)?.close()
        texts.remove(id)
    }

    fun shutdown() {
        streams.values.forEach { it.close() }
        streams.clear()
        pictures.values.forEach { it.close() }
        pictures.clear()
        texts.clear()
        shares.closeAll()
        RemoteServers.closeAll()
        runCatching { server?.close() }
        server = null
    }

    // ---- HTTP ------------------------------------------------------------------------------

    private fun serve(sock: Socket) {
        val input = sock.getInputStream()
        val out = sock.getOutputStream()
        val requestLine = readLine(input) ?: return
        var rangeStart = 0L
        var rangeEnd = -1L
        var hasRange = false
        while (true) {
            val h = readLine(input) ?: break
            if (h.isEmpty()) break
            if (h.startsWith("Range:", true)) {
                Regex("""bytes=(\d*)-(\d*)""").find(h)?.let { m ->
                    hasRange = true
                    rangeStart = m.groupValues[1].toLongOrNull() ?: 0L
                    rangeEnd = m.groupValues[2].toLongOrNull() ?: -1L
                }
            }
        }
        // GET /<id>/<name> HTTP/1.1 — the name as the reader wrote it: escaped by most, but a space left
        // as it is by some (java.net.URL), which a split on spaces would cut the extension off.
        val path = requestLine.substringAfter(' ', "").substringBeforeLast(" HTTP/").let(::unescape).ifEmpty { return }
        val id = path.trim('/').substringBefore('/')
        val registered = streams[id] ?: run { writeStatus(out, 404, "Not Found"); return }
        val stream = picturesOf(id, path, registered) ?: registered

        val text = runCatching { textOf(id, path, stream) }.getOrNull()
        // A server that could not be reached at all is said so (504), which the player does not ask again about
        // while a film is still opening (PatientLoadErrorPolicy); anything else that went wrong there is a 502.
        val size = text?.size?.toLong() ?: runCatching { stream.size() }.getOrElse { e ->
            if (unreachable(e)) writeStatus(out, 504, "Gateway Timeout") else writeStatus(out, 502, "Bad Gateway")
            return
        }
        if (rangeEnd < 0 || rangeEnd >= size) rangeEnd = size - 1
        if (rangeStart >= size) { writeStatus(out, 416, "Range Not Satisfiable"); return }
        val length = rangeEnd - rangeStart + 1

        val header = buildString {
            append(if (hasRange) "HTTP/1.1 206 Partial Content\r\n" else "HTTP/1.1 200 OK\r\n")
            append("Content-Type: ${contentType(path)}\r\n")
            append("Accept-Ranges: bytes\r\n")
            append("Content-Length: $length\r\n")
            if (hasRange) append("Content-Range: bytes $rangeStart-$rangeEnd/$size\r\n")
            append("Connection: close\r\n\r\n")
        }
        out.write(header.toByteArray())
        if (text != null) out.write(text, rangeStart.toInt(), length.toInt()) else stream.copyRange(rangeStart, length, out)
        out.flush()
    }

    /**
     * A VobSub's pictures: the .idx registered under [id] is only their index, and the reader (FFmpeg,
     * in mpv) asks for them by the same URL with .sub for .idx — the file of that name beside it.
     * Null for any other request.
     */
    private fun picturesOf(id: String, path: String, registered: Source): Source? {
        if (!path.endsWith(".sub", ignoreCase = true)) return null
        return when (registered) {
            is SmbStream -> {
                val idx = registered.loc.path
                if (!idx.endsWith(".idx", ignoreCase = true)) return null
                pictures.getOrPut(id) {
                    SmbStream(registered.loc.copy(path = idx.dropLast(4) + path.takeLast(4)), registered.user, registered.pass, shares)
                }
            }
            is RemoteStream -> {
                val idx = registered.address.path
                if (!idx.endsWith(".idx", ignoreCase = true)) return null
                pictures.getOrPut(id) { RemoteStream(registered.address.copy(path = idx.dropLast(4) + path.takeLast(4)), registered.files) }
            }
            else -> null
        }
    }

    /** A text subtitle's bytes as UTF-8, or null when [path] is not one (or is too big to be one). */
    private fun textOf(id: String, path: String, stream: Source): ByteArray? {
        if (path.substringAfterLast('.').lowercase() !in TEXT_SUBTITLES) return null
        texts[id]?.let { return it }
        val size = stream.size()
        if (size > SubtitleTranscoder.MAX_BYTES) return null
        val raw = java.io.ByteArrayOutputStream(size.toInt()).also { stream.copyRange(0, size, it) }.toByteArray()
        if (raw.size.toLong() != size) return null // cut short: serve it as it is rather than half
        val bytes = SubtitleTranscoder.charsetToConvert(raw)?.let { from ->
            com.therealityteam.videoplayer.util.Log.i("AuraSubs", "${path.substringAfterLast('/')} read as ${from.name()}, given as UTF-8")
            SubtitleTranscoder.toUtf8(raw, from)
        } ?: raw
        texts[id] = bytes
        return bytes
    }

    /** %XX escapes undone (a '+' stays a '+': it is a name's own, not a space, in a path). */
    private fun unescape(s: String): String {
        if ('%' !in s) return s
        val bytes = java.io.ByteArrayOutputStream()
        var i = 0
        while (i < s.length) {
            val c = s[i]
            if (c == '%' && i + 2 < s.length) {
                val hex = s.substring(i + 1, i + 3).toIntOrNull(16)
                if (hex != null) { bytes.write(hex); i += 3; continue }
            }
            bytes.write(c.toString().toByteArray(Charsets.UTF_8))
            i++
        }
        return bytes.toString("UTF-8")
    }

    private fun writeStatus(out: OutputStream, code: Int, msg: String) =
        runCatching { out.write("HTTP/1.1 $code $msg\r\nContent-Length: 0\r\nConnection: close\r\n\r\n".toByteArray()) }

    private fun readLine(input: java.io.InputStream): String? {
        val sb = StringBuilder()
        var c = input.read()
        if (c == -1) return null
        while (c != -1 && c != '\n'.code) {
            if (c != '\r'.code) sb.append(c.toChar())
            c = input.read()
        }
        return sb.toString()
    }

    private fun contentType(path: String): String = when (path.substringAfterLast('.').lowercase()) {
        "mp4", "m4v" -> "video/mp4"
        "mkv" -> "video/x-matroska"
        "webm" -> "video/webm"
        "mov" -> "video/quicktime"
        "avi" -> "video/x-msvideo"
        "ts", "m2ts", "mts" -> "video/mp2t"
        "vob" -> "video/mpeg"
        else -> "application/octet-stream"
    }

    // ---- SMB-backed stream -----------------------------------------------------------------

    /**
     * One connection and login per share, for everything streamed from it — the video, its subtitle
     * files, the listing that finds them. Each had its own, ~0.4 s apiece on a home NAS. A share whose
     * connection has gone (the NAS slept, the network blinked) is logged into again on next use.
     */
    private class SharePool {
        private class Entry(val client: SMBClient, val connection: Connection, val disk: DiskShare) {
            @Volatile var usedAt: Long = System.currentTimeMillis()
        }
        private val entries = HashMap<String, Entry>()

        // A host that could not be reached a moment ago is not tried again yet. A film on a NAS that is off
        // was asked for by Media3 four times before it gave up, and by the chapter and subtitle reads beside
        // it — each a 3 s connect, one after another behind this pool's lock: 26 s before 无法连接 showed.
        private val unreachableUntil = HashMap<String, Long>()

        @Synchronized
        fun share(loc: SmbLoc, user: String?, pass: String?): DiskShare {
            val key = listOf(loc.host, loc.share, user.orEmpty(), pass.orEmpty()).joinToString("\u0000")
            entries[key]?.let { e ->
                // A connection left a while may be gone at the NAS's end (it closes idle ones; the Wi-Fi slept)
                // and still say it is connected: the first request on it then waited out the socket's 60 s —
                // a film opened minutes after the last one from the same share sat 「已暂停」 that long, on
                // the old film's position. One ECHO answers for it first.
                val now = System.currentTimeMillis()
                if (e.connection.isConnected && e.disk.isConnected && (now - e.usedAt < IDLE_CHECK_MS || answers(e.connection))) {
                    e.usedAt = now
                    return e.disk
                }
                com.therealityteam.videoplayer.util.Log.i("AuraSmb", "${loc.host}/${loc.share}: the connection left ${(now - e.usedAt) / 1000} s ago is gone; logging in again")
                drop(e)
                entries.remove(key)
            }
            unreachableUntil[loc.host]?.let { until ->
                if (System.currentTimeMillis() < until) throw java.net.ConnectException("${loc.host} could not be reached a moment ago")
                unreachableUntil.remove(loc.host)
            }
            val client = streamingSmbClient()
            try {
                val connection = try {
                    client.connectTo(loc.host)
                } catch (e: Exception) {
                    if (unreachable(e)) unreachableUntil[loc.host] = System.currentTimeMillis() + UNREACHABLE_HOLD_MS
                    throw e
                }
                val auth = if (user.isNullOrEmpty()) AuthenticationContext.anonymous()
                else AuthenticationContext(user, (pass ?: "").toCharArray(), null)
                val session = connection.authenticate(auth)
                val disk = session.connectShare(loc.share) as DiskShare
                entries[key] = Entry(client, connection, disk)
                // What every read of this share costs: signing and encryption are done packet by packet.
                runCatching {
                    val p = connection.negotiatedProtocol
                    com.therealityteam.videoplayer.util.Log.i("AuraSmb", "${loc.host}/${loc.share} ${p.dialect}, max read ${p.maxReadSize / 1024} KiB, " +
                        "signing ${session.isSigningRequired}, encryption ${session.sessionContext.isEncryptData}")
                }
                return disk
            } catch (e: Exception) {
                runCatching { client.close() }
                throw e
            }
        }

        /**
         * Lets a connection that no longer answers go — forced, with no logoff to wait for, and apart: closed
         * politely, a half-open one held the new login behind it its 60 s while the film's buffer ran out.
         */
        private fun drop(e: Entry) {
            thread(name = "aura-smb-drop", isDaemon = true) {
                runCatching { e.connection.close(true) }
                runCatching { e.client.close() }
            }
        }

        /** Whether the share's server answers on [c] within [ECHO_MS] (an SMB2 ECHO, no session needed). */
        private fun answers(c: Connection): Boolean = runCatching {
            c.send<com.hierynomus.mssmb2.messages.SMB2Echo>(com.hierynomus.mssmb2.messages.SMB2Echo(c.negotiatedProtocol.dialect))
                .get(ECHO_MS, java.util.concurrent.TimeUnit.MILLISECONDS)
            true
        }.getOrDefault(false)

        @Synchronized
        fun closeAll() {
            entries.values.forEach { runCatching { it.client.close() } }
            entries.clear()
            unreachableUntil.clear()
        }
    }

    private class SmbStream(val loc: SmbLoc, val user: String?, val pass: String?, val shares: SharePool) : Source, Beside {
        // Read without the lock once known: a request must not wait for another's read to learn
        // the size it is already told (a seek waited 271 ms behind the abandoned request's read).
        @Volatile private var file: com.hierynomus.smbj.share.File? = null
        /** The share [file] was opened on: a share logged into again needs the file opened again. */
        @Volatile private var fileShare: DiskShare? = null
        @Volatile private var cachedSize: Long = -1

        private fun share(): DiskShare = shares.share(loc, user, pass)

        private fun file(): com.hierynomus.smbj.share.File {
            val d = share()
            val f = file
            return if (f != null && fileShare === d) f else openFile(d)
        }

        @Synchronized
        private fun openFile(d: DiskShare): com.hierynomus.smbj.share.File {
            file?.takeIf { fileShare === d }?.let { return it }
            // One open handle reused across all Range requests — avoids re-opening per request.
            val f = d.openFile(
                loc.path,
                setOf(AccessMask.GENERIC_READ),
                null,
                setOf(SMB2ShareAccess.FILE_SHARE_READ),
                SMB2CreateDisposition.FILE_OPEN,
                null,
            )
            file = f
            fileShare = d
            return f
        }

        /** The names in the folder [relative] to this file's own ("Subs", "Subs/Show.S01E01"), folders ending in "/". */
        override fun listBeside(relative: String): List<String> {
            val base = loc.path.substringBeforeLast('\\', "")
            val dir = listOf(base, relative.trim('/').replace('/', '\\')).filter { it.isNotEmpty() }.joinToString("\\")
            return share().list(dir).mapNotNull { e ->
                val isDir = (e.fileAttributes and FileAttributes.FILE_ATTRIBUTE_DIRECTORY.value) != 0L
                e.fileName.takeUnless { it == "." || it == ".." }?.let { if (isDir) "$it/" else it }
            }
        }

        /** The files beside this one, by name — over this stream's own login. */
        override fun siblings(): List<String> =
            share().list(loc.path.substringBeforeLast('\\', "")).mapNotNull { e ->
                val dir = (e.fileAttributes and FileAttributes.FILE_ATTRIBUTE_DIRECTORY.value) != 0L
                // A folder beside the file ends in "/": a release's Subs/ holds its subtitles.
                e.fileName.takeUnless { it == "." || it == ".." }?.let { if (dir) "$it/" else it }
            }

        override fun size(): Long = cachedSize.takeIf { it >= 0 } ?: synchronized(this) {
            if (cachedSize < 0) cachedSize = share().getFileInformation(loc.path).standardInformation.endOfFile
            cachedSize
        }

        /**
         * Stream [length] bytes starting at [start] to [out], random-reading from SMB — a small read
         * first, then larger ones as the reader keeps reading. A reader that seeks often (FFmpeg
         * walking a big MP4's atoms, an AVI's index) asks for a few KB at each place and goes on to
         * the next: a first read of 1 MiB made every seek wait for it (~80 ms, 24 of them opening one
         * 3 GB film), and the request it left kept the share busy with another.
         */
        override fun copyRange(start: Long, length: Long, out: OutputStream) {
            val f = file()
            try {
                val buf = ByteArray(MAX_READ)
                var chunk = FIRST_READ
                var offset = start
                var remaining = length
                while (remaining > 0 && offset - start < PIPELINE_AFTER) {
                    val want = minOf(chunk.toLong(), remaining).toInt()
                    chunk = minOf(chunk * 2, MAX_READ)
                    // Not one request at a time: a film opening asks for its head, its index at the end
                    // and its chapters at once (Media3, the chapter reader), and each read waited a round
                    // trip to the share behind the others' — 30-230 ms apiece in a cold start's first
                    // second. The handle takes reads side by side, as [pipelined]'s always have.
                    val read = try {
                        f.read(buf, offset, 0, want)
                    } catch (e: Exception) {
                        // The share went from under the handle: the next request opens it anew.
                        if (file === f) file = null
                        throw e
                    }
                    if (read <= 0) return
                    out.write(buf, 0, read)
                    offset += read
                    remaining -= read
                }
                if (remaining > 0) pipelined(f, offset, remaining, out)
            } catch (_: Exception) {
                // client disconnect / seek — stop streaming this request
            }
        }

        /**
         * A film playing: [PIPELINE_DEPTH] reads of [PIPELINE_READ] in flight, written out in order. One
         * at a time, each waited a round trip to the share before the next was asked for — 16 ms to the
         * user's NAS (a router away, over Wi-Fi), which held a stream to 13 MB/s while a camera's 4:2:2
         * footage plays 25 MB/s, and a same-subnet download ran at 24. Not more in flight than that: a
         * seek abandons what is asked, and it all still comes down the share's one connection ahead of
         * the new place's reads — four of 1 MiB cost each seek ~140 ms.
         */
        private fun pipelined(first: com.hierynomus.smbj.share.File, from: Long, length: Long, out: OutputStream) {
            var f = first
            val end = from + length
            var next = from
            val inFlight = ArrayDeque<Pair<Int, java.util.concurrent.Future<ByteArray>>>()
            fun ask() {
                if (next >= end) return
                val at = next
                val n = minOf(PIPELINE_READ.toLong(), end - at).toInt()
                next += n
                inFlight.addLast(n to READERS.submit<ByteArray> { readFully(f, at, n) })
            }
            repeat(PIPELINE_DEPTH) { ask() }
            try {
                while (inFlight.isNotEmpty()) {
                    val (asked, got) = inFlight.removeFirst()
                    val data = got.get()
                    val writing = System.currentTimeMillis()
                    if (data.isNotEmpty()) out.write(data)
                    if (data.size < asked) return // the file's end, or the share stopped
                    // The reader stopped taking it that long — the film was paused — and the share may have let
                    // the connection go meanwhile: the reads that follow go through the pool, which asks the share
                    // first (SharePool) — not into a connection that would hold them its 60 s.
                    val waited = System.currentTimeMillis() - writing
                    if (waited > IDLE_CHECK_MS) {
                        f = file()
                        com.therealityteam.videoplayer.util.Log.i("AuraSmb", "${loc.share}: the reader came back after ${waited / 1000} s; reading on through the pool")
                    }
                    ask()
                }
            } finally {
                inFlight.forEach { it.second.cancel(false) }
            }
        }

        /** [n] bytes at [at] (fewer only at the file's end), in as many reads as the share needs. */
        private fun readFully(f: com.hierynomus.smbj.share.File, at: Long, n: Int): ByteArray {
            val buf = ByteArray(n)
            var got = 0
            try {
                while (got < n) {
                    val read = f.read(buf, at + got, got, n - got)
                    if (read <= 0) break
                    got += read
                }
            } catch (e: Exception) {
                // The share went from under the handle: the next request opens it anew.
                if (file === f) file = null
            }
            return if (got == n) buf else buf.copyOf(got)
        }

        /** Lets this file go; the share's login stays for the others (SharePool). */
        @Synchronized
        override fun close() {
            runCatching { file?.close() }
            file = null
            fileShare = null
        }
    }

    data class SmbLoc(val host: String, val share: String, val path: String)

    // ---- FTP- and SFTP-backed stream -------------------------------------------------------

    /** A file on an FTP or SFTP server, read by range over its server's kept connections. */
    private class RemoteStream(val address: RemoteAddress, val files: RemoteFiles) : Source, Beside {
        @Volatile private var cachedSize = -1L

        override fun size(): Long = cachedSize.takeIf { it >= 0 } ?: synchronized(this) {
            if (cachedSize < 0) cachedSize = files.size(address.path) ?: throw java.io.IOException("no size for ${address.path}")
            cachedSize
        }

        override fun copyRange(start: Long, length: Long, out: OutputStream) {
            try {
                files.read(address.path, start, length, out)
            } catch (_: Exception) {
                // the server went away mid-film: this request ends, the player retries or reports
            }
        }

        override fun siblings(): List<String> = listBeside("")

        override fun listBeside(relative: String): List<String> {
            val base = address.path.substringBeforeLast('/', "")
            val dir = listOf(base, relative.trim('/')).filter { it.isNotEmpty() }.joinToString("/").ifEmpty { "/" }
            return files.list(dir).orEmpty().map { if (it.isDirectory) "${it.name}/" else it.name }
        }

        /** The server's connections are shared with the walk and other streams: nothing of its own to close. */
        override fun close() {}
    }

    companion object {
        /** Whether [path] is a file this server streams to a player: on an SMB share, an FTP or SFTP server. */
        fun serves(path: String): Boolean = path.startsWith("smb://", ignoreCase = true) || RemoteAddress.parse(path) != null

        /** How long a host that could not be reached is not tried again: past the player's quick retries. */
        private const val UNREACHABLE_HOLD_MS = 10_000L
        /** A pooled connection unused this long is asked whether it is still there before it is used again. */
        private const val IDLE_CHECK_MS = 60_000L
        private const val ECHO_MS = 3_000L

        /** Whether [e] says the server could not be reached at all (off, gone, not routable) — not a refusal of what was asked. */
        internal fun unreachable(e: Throwable): Boolean = generateSequence(e) { it.cause }.any {
            it is java.net.ConnectException || it is java.net.NoRouteToHostException ||
                it is java.net.SocketTimeoutException || it is java.net.UnknownHostException
        }

        private val TEXT_SUBTITLES = setOf("srt", "ass", "ssa", "vtt")
        /** A request's first read from the share, and the size its reads grow to. */
        private const val FIRST_READ = 64 * 1024
        private const val MAX_READ = 1 shl 20
        /** Read this far one at a time (a reader peeking at a file's index goes before), then several in flight. */
        private const val PIPELINE_AFTER = 2L shl 20
        private const val PIPELINE_DEPTH = 4
        private const val PIPELINE_READ = 512 * 1024
        private val READERS = java.util.concurrent.Executors.newCachedThreadPool { r -> Thread(r, "aura-smb-read").apply { isDaemon = true } }
    }

    private fun parseSmb(uri: String): SmbLoc? {
        val parts = uri.removePrefix("smb://").removePrefix("//").trim('/').split('/')
        if (parts.size < 3) return null
        return SmbLoc(parts[0], parts[1], parts.drop(2).joinToString("\\"))
    }
}
