import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    alias(libs.plugins.kotlin.jvm)
}

// What Aura is underneath any screen: scanning sources, reading file names, scraping, grouping the
// wall, subtitles, the library on disk. The PICO app and the desktop app both run it, so nothing
// here may reach for Android or a UI toolkit — a platform hands in what only it can do (a key
// store, an image decoder, a transliterator, a log) instead.

java {
    sourceCompatibility = JavaVersion.VERSION_11
    targetCompatibility = JavaVersion.VERSION_11
}

kotlin {
    compilerOptions { jvmTarget.set(JvmTarget.JVM_11) }
}

tasks.named<Copy>("processResources") {
    from(rootProject.file("private-build/tmdb-api-key.txt")) { into("reality-nas-player") }
}

// Forward -Daura.* to the test JVM so the poster-wall evaluation harness (eval/README.md) can
// point CardDumpTest at a library dump. Ordinary test runs are unaffected.
tasks.withType<Test>().configureEach {
    // -Daura.jfr=<file>: a Flight Recorder profile of the test JVM (GroupingPerfProbe).
    System.getProperty("aura.jfr")?.let { jvmArgs("-XX:StartFlightRecording=filename=$it,settings=profile") }
    System.getProperties()
        .filterKeys { it.toString().startsWith("aura.") }
        .forEach { (k, v) -> systemProperty(k.toString(), v) }
}

dependencies {
    implementation(libs.kotlinx.coroutines.core)
    api(libs.smbj)
    api(libs.dcerpc) // SMB share enumeration (srvsvc/IPC$) for host-only browsing
    implementation(libs.anitomyj) // anime filename parser (Anitomy port)
    implementation(libs.commons.net) // FTP / FTPS sources (Apache-2.0)
    implementation(libs.jsch) // SFTP sources (mwiede's maintained JSch, BSD)
    implementation(libs.commons.compress) // a fansub's fonts in a .7z (Apache-2.0)
    implementation(libs.xz) // 7z's LZMA (XZ for Java, 0BSD)
    // org.json is part of Android; the desktop app and the JVM tests bring the reference library.
    compileOnly(libs.orgjson)
    testImplementation(libs.orgjson)
    testImplementation(libs.junit)
    // Real servers on loopback for the FTP and SFTP sources' tests (Apache-2.0).
    testImplementation(libs.ftpserver.core)
    testImplementation(libs.sshd.sftp)
    testImplementation(libs.nfs4j.core) // an NFSv3 server in the test JVM (LGPL)
}
