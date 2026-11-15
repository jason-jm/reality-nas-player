package com.therealityteam.videoplayer

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
import java.io.File
import java.io.IOException
import java.io.StringReader
import java.nio.ByteBuffer
import javax.security.auth.Subject

/** An NFSv3 server (dCache's nfs4j) over a local folder, MOUNT and NFS on one port, for tests and probes. */
internal object TestNfsServer {
    fun start(root: File, port: Int, exports: List<String>, bind: String = "127.0.0.1"): OncRpcSvc {
        val vfs = LocalFs(root)
        val table = ExportFile(StringReader(exports.joinToString("") { "$it *(ro,insecure,no_root_squash)\n" }))
        val svc = OncRpcSvcBuilder().withPort(port).withBindAddress(bind).withTCP().withoutAutoPublish().withWorkerThreadIoStrategy().build()
        svc.register(OncRpcProgram(100003, 3), NfsServerV3(table, vfs))
        svc.register(OncRpcProgram(100005, 3), MountServer(table, vfs))
        svc.start()
        return svc
    }
}

/** A read-only file system over a local folder, as nfs4j asks one. */
internal class LocalFs(private val root: File) : VirtualFileSystem {
    private val ids = HashMap<String, Long>()
    private val paths = HashMap<Long, File>()

    @Synchronized private fun inode(f: File): Inode {
        val id = ids.getOrPut(f.canonicalPath) { (ids.size + 1).toLong().also { paths[it] = f.canonicalFile } }
        return Inode.forFile(ByteBuffer.allocate(8).putLong(id).array())
    }
    @Synchronized private fun file(i: Inode): File =
        paths[ByteBuffer.wrap(i.fileId).long] ?: throw org.dcache.nfs.status.StaleException("no such inode")

    override fun getRootInode(): Inode = inode(root)
    override fun lookup(parent: Inode, name: String): Inode {
        val f = if (name == ".") file(parent) else if (name == "..") file(parent).parentFile else File(file(parent), name)
        if (!f.exists()) throw org.dcache.nfs.status.NoEntException(name)
        return inode(f)
    }
    override fun getattr(inode: Inode): Stat = statOf(file(inode))
    private fun statOf(f: File) = Stat().apply {
        setMode((if (f.isDirectory) Stat.S_IFDIR else Stat.S_IFREG) or "755".toInt(8))
        setSize(if (f.isDirectory) 4096 else f.length())
        setNlink(1); setUid(0); setGid(0); setDev(1); setRdev(0)
        setIno(ids[f.canonicalPath] ?: 0)
        setATime(f.lastModified()); setMTime(f.lastModified()); setCTime(f.lastModified()); setBTime(f.lastModified())
        setGeneration(0)
    }
    override fun list(inode: Inode, verifier: ByteArray, cookie: Long): DirectoryStream {
        val dir = file(inode)
        val entries = (dir.listFiles()?.sortedBy { it.name } ?: emptyList()).mapIndexed { i, f ->
            inode(f)
            DirectoryEntry(f.name, inode(f), statOf(f), (i + 3).toLong())
        }.filter { it.cookie > cookie }
        return DirectoryStream(DirectoryStream.ZERO_VERIFIER, entries)
    }
    override fun directoryVerifier(inode: Inode): ByteArray = DirectoryStream.ZERO_VERIFIER
    override fun read(inode: Inode, data: ByteArray, offset: Long, count: Int): Int =
        java.io.RandomAccessFile(file(inode), "r").use { raf ->
            if (offset >= raf.length()) return -1
            raf.seek(offset); raf.read(data, 0, count)
        }
    override fun access(subject: Subject, inode: Inode, mode: Int): Int = mode
    override fun parentOf(inode: Inode): Inode = inode(file(inode).parentFile)
    override fun getFsStat(): FsStat = FsStat(1L shl 40, 1L shl 20, 1L shl 30, 1000)
    override fun readlink(inode: Inode): String = throw IOException("no links")
    override fun create(p: Inode, t: Stat.Type, n: String, s: Subject, m: Int): Inode = throw org.dcache.nfs.status.RoFsException()
    override fun link(p: Inode, l: Inode, n: String, s: Subject): Inode = throw org.dcache.nfs.status.RoFsException()
    override fun mkdir(p: Inode, n: String, s: Subject, m: Int): Inode = throw org.dcache.nfs.status.RoFsException()
    override fun move(a: Inode, b: String, c: Inode, d: String): Boolean = throw org.dcache.nfs.status.RoFsException()
    override fun remove(p: Inode, n: String) = throw org.dcache.nfs.status.RoFsException()
    override fun symlink(p: Inode, n: String, l: String, s: Subject, m: Int): Inode = throw org.dcache.nfs.status.RoFsException()
    override fun write(i: Inode, d: ByteArray, o: Long, c: Int, st: VirtualFileSystem.StabilityLevel) = throw org.dcache.nfs.status.RoFsException()
    override fun commit(i: Inode, o: Long, c: Int) {}
    override fun setattr(i: Inode, s: Stat) = throw org.dcache.nfs.status.RoFsException()
    override fun getAcl(i: Inode): Array<nfsace4> = emptyArray()
    override fun setAcl(i: Inode, a: Array<nfsace4>) {}
    override fun hasIOLayout(i: Inode) = false
    override fun getAclCheckable(): AclCheckable = AclCheckable.UNDEFINED_ALL
    override fun getIdMapper(): NfsIdMapping = org.dcache.nfs.v4.SimpleIdMap()
    override fun getCaseInsensitive() = false
    override fun getCasePreserving() = true
}
