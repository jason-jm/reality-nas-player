package com.therealityteam.videoplayer

import org.apache.ftpserver.DataConnectionConfigurationFactory
import org.apache.ftpserver.FtpServerFactory
import org.apache.ftpserver.listener.ListenerFactory
import org.apache.ftpserver.usermanager.PropertiesUserManagerFactory
import org.apache.ftpserver.usermanager.impl.BaseUser
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.File

/**
 * Serves a folder to a headset for trying the FTP and NFS sources there: an anonymous FTP server on
 * 2121 (passive 30000–30009) and an NFS server on 20490 exporting its `video` folder, both on
 * loopback — reached from the headset through `adb reverse`, so its source addresses are
 * ftp://127.0.0.1:2121/video and nfs://127.0.0.1:20490/video. No account on either. Runs until
 * `<dir>/stop` appears or the minutes are up:
 *
 *   ./gradlew :core:test --tests '*RemoteServeProbe*' -Daura.serve.dir=/path -Daura.serve.minutes=40
 *
 * With `-Daura.serve.sftp.passfile=<file>` an SFTP server too, on 2222, for the user `aura-test` with the
 * password in that file (a throwaway one, kept out of the command line): sftp://127.0.0.1:2222/video. Its host
 * key is kept beside the password file, so the headset — which pins the key it first sees — knows it next time.
 */
class RemoteServeProbe {

    @Test fun serve() {
        val dir = System.getProperty("aura.serve.dir")?.let(::File)
        assumeTrue("set -Daura.serve.dir", dir != null)
        val minutes = System.getProperty("aura.serve.minutes")?.toLongOrNull() ?: 30
        // -Daura.serve.host=<the Mac's LAN address>: the headset reaches it over the Wi-Fi itself, FTP's passive
        // connections and all, rather than through adb reverse's loopback.
        val host = System.getProperty("aura.serve.host") ?: "127.0.0.1"
        val factory = FtpServerFactory()
        factory.addListener("default", ListenerFactory().apply {
            port = 2121
            serverAddress = host
            dataConnectionConfiguration = DataConnectionConfigurationFactory().apply { passivePorts = "30000-30009" }.createDataConnectionConfiguration()
        }.createListener())
        val users = PropertiesUserManagerFactory().apply { file = File.createTempFile("ftp-users", ".properties") }.createUserManager()
        users.save(BaseUser().apply { name = "anonymous"; homeDirectory = dir!!.absolutePath })
        factory.userManager = users
        val ftp = factory.createServer().apply { start() }
        val nfs = TestNfsServer.start(dir!!, 20490, listOf("/video"), bind = host)
        val sftp = System.getProperty("aura.serve.sftp.passfile")?.let(::File)?.let { passFile ->
            val password = passFile.readText().trim()
            org.apache.sshd.server.SshServer.setUpDefaultServer().apply {
                this.host = host
                port = 2222
                keyPairProvider = org.apache.sshd.server.keyprovider.SimpleGeneratorHostKeyProvider(File(passFile.parentFile, "sftp-host.ser").toPath())
                setPasswordAuthenticator { u, p, _ -> u == "aura-test" && p == password }
                subsystemFactories = listOf(org.apache.sshd.sftp.server.SftpSubsystemFactory())
                fileSystemFactory = org.apache.sshd.common.file.virtualfs.VirtualFileSystemFactory(dir.toPath())
                start()
            }
        }
        println("SERVING ftp://$host:2121/video nfs://$host:20490/video${if (sftp != null) " sftp://$host:2222/video" else ""} from $dir")
        val stop = File(dir, "stop")
        val until = System.currentTimeMillis() + minutes * 60_000
        while (System.currentTimeMillis() < until && !stop.exists()) Thread.sleep(1_000)
        ftp.stop()
        nfs.stop()
        sftp?.stop(true)
    }
}
