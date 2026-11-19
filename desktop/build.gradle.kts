import org.jetbrains.compose.desktop.application.dsl.TargetFormat
import java.util.zip.ZipEntry
import java.util.zip.ZipFile
import java.util.zip.ZipOutputStream

plugins {
    // Pinned to what mediamp 0.5.0 was built against: its video surface reaches into Skiko's
    // renderer, so the Compose (and with it Skiko) version has to be the one it knows.
    kotlin("jvm") version "2.3.20"
    kotlin("plugin.compose") version "2.3.20"
    id("org.jetbrains.compose") version "1.12.0"
}

group = "com.therealityteam"
version = "1.0.0"

kotlin {
    jvmToolchain(21)
}

// The headset's strings, read by the desktop as its own (i18n/Strings.kt): one vocabulary in every
// language Aura speaks, kept in one place. The headset keeps each area's words in a file of its own
// (strings_player.xml, …); each language's files are joined here into the one strings.xml it reads.
val headsetStrings = tasks.register("headsetStrings") {
    val res = file("../app/src/main/res")
    val folders = listOf(
        "values", "values-zh", "values-b+zh+Hant", "values-ja", "values-ko", "values-de", "values-fr", "values-es", "values-it", "values-pt", "values-ru",
        "values-nl", "values-pl", "values-sv", "values-da", "values-nb", "values-fi", "values-cs", "values-el", "values-ro", "values-tr", "values-th", "values-ms",
    ).filter { res.resolve(it).isDirectory }
    val out = layout.buildDirectory.dir("generated/headset-strings/i18n/headset")
    inputs.files(folders.map { f -> fileTree(res.resolve(f)) { include("strings*.xml") } })
    outputs.dir(out)
    doLast {
        for (folder in folders) {
            val bodies = res.resolve(folder).listFiles { f -> f.name.startsWith("strings") && f.name.endsWith(".xml") }!!
                .sortedBy { it.name }
                .map { it.readText().substringAfter("<resources").substringAfter(">").substringBeforeLast("</resources>") }
            val dir = out.get().asFile.resolve(folder).apply { mkdirs() }
            dir.resolve("strings.xml").writeText("<?xml version=\"1.0\" encoding=\"utf-8\"?>\n<resources>" + bodies.joinToString("\n") + "</resources>\n")
        }
    }
}

sourceSets {
    main {
        // The engine the PICO app runs, compiled here as part of this app (see settings.gradle.kts).
        kotlin.srcDir("../core/src/main/kotlin")
        resources.srcDir(layout.buildDirectory.dir("generated/headset-strings"))
    }
}

tasks.named<Copy>("processResources") {
    dependsOn(headsetStrings)
    providers.gradleProperty("reality.tmdbKeyFile").orNull?.let { path ->
        val keyFile = rootDir.parentFile.resolve(path)
        require(keyFile.isFile && keyFile.readText().trim().matches(Regex("[0-9a-fA-F]{32}")))
        from(keyFile) {
            into("reality-nas-player")
            rename { "tmdb-api-key.txt" }
        }
    }
}

/** This machine as mediamp names platforms: macos-arm64, windows-x64, … */
val hostPlatform: String = run {
    val os = System.getProperty("os.name").lowercase()
    val arch = System.getProperty("os.arch").lowercase()
    val o = when {
        "win" in os -> "windows"
        "mac" in os -> "macos"
        else -> "linux"
    }
    "$o-" + if (arch == "aarch64" || arch == "arm64") "arm64" else "x64"
}

val mediamp = "0.5.0"

dependencies {
    implementation(compose.desktop.currentOs)
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-swing:1.10.2")

    // Playback: libmpv through mediamp, drawn straight into the Compose scene (Metal on macOS,
    // Direct3D on Windows — no copies). The native runtime is per platform.
    implementation("org.openani.mediamp:mediamp-api:$mediamp")
    implementation("org.openani.mediamp:mediamp-mpv:$mediamp")
    runtimeOnly("org.openani.mediamp:mediamp-mpv-runtime-$hostPlatform:$mediamp")

    // What the shared engine needs (core/build.gradle.kts), plus what Android gives it for free.
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-core:1.10.2")
    implementation("com.hierynomus:smbj:0.12.2")
    implementation("com.rapid7.client:dcerpc:0.12.13")
    implementation("com.dgtlrepublic:anitomyJ:0.0.7")
    implementation("commons-net:commons-net:3.11.1") // FTP sources
    implementation("com.github.mwiede:jsch:0.2.20") // SFTP sources
    implementation("org.apache.commons:commons-compress:1.27.1") // a fansub's fonts in a .7z
    implementation("org.tukaani:xz:1.10")
    implementation("org.json:json:20240303")
    implementation("com.ibm.icu:icu4j:78.3") // pinyin / romaji for search, as Android's ICU does
    implementation("net.java.dev.jna:jna-platform:5.19.1") // Windows DPAPI for stored passwords

    implementation("com.composables:icons-lucide:1.1.0")

    testImplementation(kotlin("test"))
    testImplementation(compose.desktop.uiTestJUnit4)
    // Real FTP and SFTP servers on loopback for RemoteEndToEndProbe (Apache-2.0).
    testImplementation("org.apache.ftpserver:ftpserver-core:1.2.0")
    testImplementation("org.apache.sshd:sshd-sftp:2.12.1")
}

// mediamp publishes its test libraries and Material as runtime dependencies; nothing in this app
// (or in mediamp's own classes) uses them, and the icon set alone is 38 MB of the download.
configurations.named("runtimeClasspath") {
    exclude(group = "org.jetbrains.compose.material", module = "material-icons-extended")
    exclude(group = "org.jetbrains.compose.material", module = "material-icons-extended-desktop")
    exclude(group = "org.jetbrains.compose.material3")
    exclude(group = "org.jetbrains.compose.ui", module = "ui-test")
    exclude(group = "org.jetbrains.compose.ui", module = "ui-test-desktop")
    exclude(group = "org.jetbrains.compose.ui", module = "ui-test-junit4")
    exclude(group = "org.jetbrains.compose.ui", module = "ui-test-junit4-desktop")
    exclude(group = "com.google.truth")
    exclude(group = "junit")
    exclude(group = "org.jetbrains.kotlinx", module = "kotlinx-coroutines-test")
    exclude(group = "org.jetbrains.kotlinx", module = "kotlinx-coroutines-test-jvm")
}

/**
 * Native jars are trimmed — and mediamp's FFmpeg swapped — on their way into the app:
 * - mediamp's mpv runtime for macOS holds each FFmpeg library three times over — libavcodec.dylib,
 *   libavcodec.62.dylib and libavcodec.62.11.100.dylib are the same 6 MB — and libmpv twice. Every
 *   library links the middle name (`otool -L`: @loader_path/libavcodec.62.dylib), so the other two
 *   go, from the jar and from the list mediamp unpacks by: 23 of its 51 MB.
 * - That FFmpeg is cut down for streaming (no FLV, no MPEG-1, no MJPEG, no DV, no VP8…): its
 *   libraries are replaced by the full LGPL build of the same major that natives/build-ffmpeg.sh
 *   makes (natives/out/<platform>), when it has been made. A marker, aura-ffmpeg.txt, goes into the
 *   jar with them, and the app unpacks the runtime into a folder named for it (MpvRuntime).
 * - Skiko's arm64 runtime jar also carries the x64 library, 22 MB an arm64 Mac never opens.
 */
abstract class TrimNativeJar : TransformAction<TrimNativeJar.Params> {
    interface Params : TransformParameters {
        @get:InputFiles
        @get:PathSensitive(PathSensitivity.NAME_ONLY)
        val ffmpeg: ConfigurableFileCollection
    }

    @get:InputArtifact
    abstract val input: Provider<FileSystemLocation>

    override fun transform(outputs: TransformOutputs) {
        val jar = input.get().asFile
        val name = jar.name
        val trims = name.startsWith("mediamp-mpv-runtime-macos-") || name.startsWith("skiko-awt-runtime-macos-")
        if (!trims) { outputs.file(input); return }
        val ours: Map<String, File> =
            if (name.startsWith("mediamp-mpv-runtime-")) parameters.ffmpeg.files.filter { it.name.endsWith(".dylib") }.associateBy { it.name }
            else emptyMap()
        ZipFile(jar).use { zin ->
            val names = zin.entries().toList().map { it.name }.toSet()
            val versioned = Regex("""^(lib[\w+-]+?)((?:\.\d+)*)\.dylib$""")
            fun drop(n: String): Boolean {
                if (name.startsWith("skiko-awt-runtime-macos-arm64")) return n.startsWith("libskiko-macos-x64")
                if (name.startsWith("skiko-awt-runtime-macos-x64")) return n.startsWith("libskiko-macos-arm64")
                val m = versioned.matchEntire(n) ?: return false
                val base = m.groupValues[1]
                val parts = m.groupValues[2].split('.').filter { it.isNotEmpty() }
                return when {
                    // libavcodec.dylib, when libavcodec.62.dylib is there
                    parts.isEmpty() -> names.any { it != n && Regex(Regex.escape(base) + """\.\d+\.dylib""").matches(it) }
                    // libavcodec.62.11.100.dylib, when libavcodec.62.dylib is there
                    parts.size >= 2 -> "$base.${parts[0]}.dylib" in names
                    else -> false
                }
            }
            ZipOutputStream(outputs.file(name).outputStream()).use { zout ->
                for (e in zin.entries()) {
                    if (drop(e.name) || drop(e.name.removeSuffix(".sha256"))) continue
                    zout.putNextEntry(ZipEntry(e.name))
                    val replacement = ours[e.name]
                    when {
                        e.name.startsWith("mpv-natives") && e.name.endsWith(".txt") -> {
                            val kept = zin.getInputStream(e).bufferedReader().readLines().filter { it.isNotBlank() && !drop(it.trim()) }
                            zout.write(kept.joinToString("\n", postfix = "\n").toByteArray())
                        }
                        replacement != null -> replacement.inputStream().use { it.copyTo(zout) }
                        else -> zin.getInputStream(e).use { it.copyTo(zout) }
                    }
                    zout.closeEntry()
                }
                if (ours.isNotEmpty()) {
                    // What the runtime is, for the folder it unpacks into: our libraries' sizes and
                    // times change with every build of them.
                    zout.putNextEntry(ZipEntry("aura-ffmpeg.txt"))
                    val id = ours.values.sortedBy { it.name }.joinToString(",") { "${it.name}:${it.length()}" }.hashCode().toUInt().toString(16)
                    zout.write("ffmpeg-lgpl-$id\n".toByteArray())
                    zout.closeEntry()
                }
            }
        }
    }
}

/** This machine as natives/build-ffmpeg.sh names its output: macos-arm64, macos-x64. */
val ffmpegPlatform: String = run {
    val os = System.getProperty("os.name").lowercase()
    val arch = if (System.getProperty("os.arch").lowercase().let { it == "aarch64" || it == "arm64" }) "arm64" else "x64"
    when {
        "mac" in os -> "macos-$arch"
        "win" in os -> "windows-$arch"
        else -> "linux-$arch"
    }
}
val ffmpegDir = layout.projectDirectory.dir("natives/out/$ffmpegPlatform")

val nativeTrimmed = Attribute.of("aura.nativeTrimmed", Boolean::class.javaObjectType)
dependencies {
    attributesSchema { attribute(nativeTrimmed) }
    artifactTypes.getByName("jar") { attributes.attribute(nativeTrimmed, false) }
    registerTransform(TrimNativeJar::class) {
        from.attribute(nativeTrimmed, false)
        to.attribute(nativeTrimmed, true)
        parameters { ffmpeg.from(fileTree(ffmpegDir) { include("*.dylib") }) }
    }
}
// The tests play through the same runtime the app ships.
configurations.named("runtimeClasspath") { attributes { attribute(nativeTrimmed, true) } }
configurations.named("testRuntimeClasspath") { attributes { attribute(nativeTrimmed, true) } }

// A Mac app is never packaged with mediamp's cut-down FFmpeg by accident.
tasks.matching { it.name == "createDistributable" || it.name.startsWith("package") }.configureEach {
    doFirst {
        if (ffmpegPlatform.startsWith("macos") && fileTree(ffmpegDir) { include("*.dylib") }.files.size < 7) {
            throw GradleException("FFmpeg for $ffmpegPlatform is not built: run desktop/natives/build-ffmpeg.sh first")
        }
    }
}

compose.desktop {
    application {
        mainClass = "com.therealityteam.videoplayer.desktop.MainKt"
        jvmArgs += listOf("-Xss4m")

        nativeDistributions {
            targetFormats(TargetFormat.Dmg, TargetFormat.Msi, TargetFormat.Exe)
            packageName = "Reality NAS Player"
            packageVersion = "1.1.0"
            description = "A video player for your own library"
            vendor = "The Reality Team"
            copyright = "Free and open source"
            // What jlink keeps of the JDK: what jdeps finds the jars using, plus TLS's elliptic curves
            // (TMDB and friends are HTTPS) and full locale data (language names in Chinese). Not
            // jdk.javadoc, which jdeps also lists: only ICU's javadoc taglets (com.ibm.icu.dev.tool.docs)
            // touch it, they never load at run time, and it drags the whole compiler in with it.
            modules(
                "java.instrument", "java.naming", "java.rmi", "java.security.jgss", "java.sql",
                "jdk.unsupported", "jdk.crypto.ec", "jdk.localedata", "jdk.accessibility",
                "java.management", "java.net.http", // WebDAV's PROPFIND (DesktopCore)
            )
            // Video files can be opened with Aura from Finder / Explorer.
            listOf(
                "mkv" to "video/x-matroska", "mp4" to "video/mp4", "m4v" to "video/x-m4v", "mov" to "video/quicktime",
                "avi" to "video/x-msvideo", "webm" to "video/webm", "ts" to "video/mp2t", "m2ts" to "video/mp2t",
                "wmv" to "video/x-ms-wmv", "flv" to "video/x-flv", "mpg" to "video/mpeg", "rmvb" to "application/vnd.rn-realmedia-vbr",
            ).forEach { (ext, mime) ->
                fileAssociation(mime, ext, "Video", project.file("packaging/Aura.png"), project.file("packaging/Aura.ico"), project.file("packaging/Aura.icns"))
            }

            macOS {
                bundleID = "com.therealityteam.aura"
                appCategory = "public.app-category.entertainment"
                iconFile.set(project.file("packaging/Aura.icns"))
                dockName = "Reality NAS Player"
            }
            windows {
                iconFile.set(project.file("packaging/Aura.ico"))
                menuGroup = "Reality NAS Player"
                perUserInstall = true
                shortcut = true
                dirChooser = true
                upgradeUuid = "6f0b3f46-1d0e-4c55-9a39-2f5f7c9b1a51"
            }
            linux {
                iconFile.set(project.file("packaging/Aura.png"))
            }
        }
    }
}

tasks.withType<Test>().configureEach {
    useJUnit()
    val aura = System.getProperties().filterKeys { it.toString().startsWith("aura.") }
    aura.forEach { (k, v) -> systemProperty(k.toString(), v) }
    // A probe asked for on the command line runs every time, not "up to date" from the last one.
    if (aura.isNotEmpty()) outputs.upToDateWhen { false }
}

// -Daura.* on the Gradle command line reaches the app (`run`), for debugging switches.
tasks.withType<JavaExec>().configureEach {
    System.getProperties()
        .filterKeys { it.toString().startsWith("aura.") }
        .forEach { (k, v) -> systemProperty(k.toString(), v) }
}
