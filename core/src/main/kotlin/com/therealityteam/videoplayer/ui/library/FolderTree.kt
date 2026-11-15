package com.therealityteam.videoplayer.ui.library

import com.therealityteam.videoplayer.data.model.MediaItem

/**
 * 文件夹: the library as the shares hold it — each source, its folders, the files in them — for
 * whoever knows where a thing is rather than what a database calls it, and for the files no card
 * owns (an extra, a clip, a disc's menu). Built from the library's own paths, so it opens at once
 * and needs no network.
 */
class FolderTree(items: List<MediaItem>, sources: List<Root>) {

    /** A source as the tree starts it: its id, its name, and the URI its files' paths begin with. */
    data class Root(val sourceId: String, val name: String, val uri: String)

    /** Where in the tree: a source and the folders below its root ([segments] empty = the source itself). */
    data class Path(val sourceId: String, val segments: List<String> = emptyList()) {
        fun child(name: String) = Path(sourceId, segments + name)
        val parent: Path? get() = if (segments.isEmpty()) null else Path(sourceId, segments.dropLast(1))
    }

    data class Folder(val path: Path, val name: String, val files: Int)

    data class Listing(val folders: List<Folder>, val files: List<MediaItem>)

    private class Node(val name: String) {
        val children = HashMap<String, Node>()
        val files = ArrayList<MediaItem>()
        var count = 0
    }

    private val roots = LinkedHashMap<String, Pair<Root, Node>>()

    init {
        for (r in sources) roots[r.sourceId] = r to Node(r.name)
        for (item in items) {
            val (root, top) = roots[item.sourceId] ?: continue
            val segments = relative(item.path, root.uri)
            var node = top
            node.count++
            for (s in segments.dropLast(1)) {
                node = node.children.getOrPut(s) { Node(s) }
                node.count++
            }
            node.files += item
        }
    }

    /** The sources that hold anything, as folders. */
    fun sources(): List<Folder> = roots.values
        .filter { (_, node) -> node.count > 0 }
        .map { (root, node) -> Folder(Path(root.sourceId), root.name, node.count) }

    /** What is at [path]: its folders and its files, each in the order a person reads them; null when it is gone. */
    fun list(path: Path): Listing? {
        var node = roots[path.sourceId]?.second ?: return null
        for (s in path.segments) node = node.children[s] ?: return null
        return Listing(
            folders = node.children.values.sortedWith(compareBy(NaturalOrder) { it.name })
                .map { Folder(path.child(it.name), it.name, it.count) },
            files = node.files.sortedWith(compareBy(NaturalOrder) { it.fileName }),
        )
    }

    /** A file found by its name, and the folder it is in. */
    data class Found(val item: MediaItem, val folder: Path)

    /**
     * Files whose own name holds every word of [query] — a release group, `NCOP`, `Hi10p`, an episode's
     * file — whatever card they are on; at most [limit], in reading order of their folders then names.
     */
    fun search(query: String, limit: Int = 300): List<Found> {
        val words = query.lowercase().split(' ').filter { it.isNotBlank() }
        if (words.isEmpty()) return emptyList()
        val out = ArrayList<Found>()
        fun walk(node: Node, path: Path) {
            if (out.size >= limit) return
            for (f in node.files.sortedWith(compareBy(NaturalOrder) { it.fileName })) {
                val name = f.fileName.lowercase()
                if (words.all { it in name }) {
                    out += Found(f, path)
                    if (out.size >= limit) return
                }
            }
            for (child in node.children.values.sortedWith(compareBy(NaturalOrder) { it.name })) walk(child, path.child(child.name))
        }
        for ((root, node) in roots.values) walk(node, Path(root.sourceId))
        return out
    }

    /** The names on the way to [path], the source's first. */
    fun crumbs(path: Path): List<String> = listOfNotNull(roots[path.sourceId]?.first?.name) + path.segments

    companion object {
        /** Where a folder of [files] goes on from: the file left half watched, else the first not yet seen, else the first. */
        fun nextUp(files: List<MediaItem>): MediaItem? =
            files.firstOrNull { it.progress.started && !it.progress.watched }
                ?: files.firstOrNull { !it.progress.watched }
                ?: files.firstOrNull()

        /** Whether a source keeps its files in folders: a share or the device does; a media server or DLNA has its own catalogue. */
        fun hasFolders(type: com.therealityteam.videoplayer.data.model.SourceType): Boolean = type in FOLDERED

        private val FOLDERED = setOf(
            com.therealityteam.videoplayer.data.model.SourceType.LOCAL,
            com.therealityteam.videoplayer.data.model.SourceType.SMB,
            com.therealityteam.videoplayer.data.model.SourceType.WEBDAV,
            com.therealityteam.videoplayer.data.model.SourceType.NFS,
            com.therealityteam.videoplayer.data.model.SourceType.FTP,
            com.therealityteam.videoplayer.data.model.SourceType.SFTP,
        )

        /** [path] below [root] as folder names and the file's own last; whatever comes after the host when it is not below it. */
        fun relative(path: String, root: String): List<String> {
            val p = path.trimEnd('/')
            val r = root.trimEnd('/')
            val rest = if (r.isNotEmpty() && p.startsWith("$r/")) p.substring(r.length + 1)
            else p.substringAfter("://", p).substringAfter('/', p).trimStart('/')
            return rest.split('/').filter { it.isNotEmpty() }
        }
    }

    /** `第2集` before `第10集`, `EP09` before `EP10`: digit runs compared as numbers, the rest ignoring case. */
    object NaturalOrder : Comparator<String> {
        override fun compare(a: String, b: String): Int {
            var i = 0
            var j = 0
            while (i < a.length && j < b.length) {
                val ca = a[i]
                val cb = b[j]
                if (ca.isDigit() && cb.isDigit()) {
                    val si = i
                    val sj = j
                    while (i < a.length && a[i].isDigit()) i++
                    while (j < b.length && b[j].isDigit()) j++
                    val na = a.substring(si, i).trimStart('0')
                    val nb = b.substring(sj, j).trimStart('0')
                    if (na.length != nb.length) return na.length - nb.length
                    val c = na.compareTo(nb)
                    if (c != 0) return c
                } else {
                    val c = ca.lowercaseChar().compareTo(cb.lowercaseChar())
                    if (c != 0) return c
                    i++
                    j++
                }
            }
            return (a.length - i) - (b.length - j)
        }
    }
}
