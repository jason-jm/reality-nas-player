import java.util.Properties

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
}

// The store identity has its own key. A public clone without the private configuration builds unsigned.
val signingFile = rootProject.file("signing/reality-nas-player.properties")
val signingProps = Properties().apply {
    if (signingFile.isFile) signingFile.inputStream().use { load(it) }
}

android {
    testOptions {
        // JVM tests may construct classes that touch android.util.Log / SystemClock: no-ops, not throws.
        unitTests.isReturnDefaultValues = true
    }

    namespace = "com.therealityteam.videoplayer"
    compileSdk = 36

    defaultConfig {
        applicationId = "com.therealityteam.realitynasplayer"
        manifestPlaceholders["appLabel"] = "@string/app_name"
        minSdk = 35
        targetSdk = 35
        versionCode = 8
        versionName = "1.2.0"

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        ndk { abiFilters.add("arm64-v8a") }
        // The `aura.*` intent extras (DeepLink): automation hooks for checking a build on the headset — removing a
        // source, forgetting progress, turning the cast receiver on, mpv options. LaunchActivity is exported, so in a
        // release any app could send them; only the debug build (the dev packages, optimized or not) listens.
        buildConfigField("boolean", "AUTOMATION", "false")
    }

    if (signingFile.isFile) {
        signingConfigs {
            create("release") {
                storeFile = signingFile.resolveSibling(signingProps.getProperty("storeFile"))
                storePassword = signingProps.getProperty("storePassword")
                keyAlias = signingProps.getProperty("keyAlias")
                keyPassword = signingProps.getProperty("keyPassword")
            }
        }
    }

    buildTypes {
        // -Paura.devPackage: a debug build beside the installed app (….realitynasplayer.dev, "Reality NAS Player Dev"), for a
        // headset whose Aura was signed on another machine — its library and settings stay as they are.
        // -Paura.devPackage=<name> names another (….realitynasplayer.<name>, "Reality NAS Player <Name>"): =fresh is an empty
        // install beside both, for trying what a new user first sees without clearing anybody's library.
        debug {
            buildConfigField("boolean", "AUTOMATION", "true")
            // en-XA: every English string ~40% longer and accented — what a long language does to a button, tried on the
            // headset (`cmd locale set-app-locales <pkg> --locales en-XA` + `aura.i18n_scan`) before any translation.
            isPseudoLocalesEnabled = true
            if (project.hasProperty("aura.devPackage")) {
                val name = (project.property("aura.devPackage") as String).ifBlank { "dev" }.takeUnless { it == "true" } ?: "dev"
                applicationIdSuffix = ".$name"
                manifestPlaceholders["appLabel"] = "Reality NAS Player " + name.replaceFirstChar { it.uppercase() }
            }
            // -Paura.optimized: the same build not debuggable, so ART compiles it as it would a release
            // and its frame times are the ones a user sees (a debuggable app runs far slower code). Its
            // files cannot be read with run-as while it is installed; reinstall without it for that.
            if (project.hasProperty("aura.optimized")) isDebuggable = false
            // -Paura.minify: R8 as the release has it, on the dev build — a shrunk build tried on the headset
            // beside the installed app, which a release build (the other key) cannot be.
            if (project.hasProperty("aura.minify")) {
                isMinifyEnabled = true
                proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
            }
        }
        release {
            // R8: the dex 43 → 12 MB, the APK 254 → 223 MB, the compiled code on the headset 25 → 16 MB.
            // proguard-rules.pro keeps what is found by name at run time (the PICO SDK, mpv's JNI, smbj, JSch…);
            // tried in full on the headset with the dev build's -Paura.minify before it was turned on here.
            isMinifyEnabled = true
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
            if (signingFile.isFile) {
                signingConfig = signingConfigs.getByName("release")
            }
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_11
        targetCompatibility = JavaVersion.VERSION_11
    }
    kotlinOptions {
        jvmTarget = "11"
    }
    // The Spatial Editor bundle (the living-room scene), the environments' models and the KTX
    // lighting map are read by the SDK straight out of the APK: stored, not deflated. Their images
    // are JPEG/PNG already, so deflating them would buy little.
    androidResources {
        noCompress += listOf(".bundle", ".ktx", ".glb", ".usdz")
        // The languages Aura has strings in, told to the system (android:localeConfig) from the values-* folders:
        // the headset's own app-language setting lists them, and the per-app language accepts them.
        generateLocaleConfig = true
    }

    buildFeatures {
        compose = true
        buildConfig = true
    }
}

// Forward -Daura.* to the test JVM so the poster-wall evaluation harness (eval/README.md) can
// point CardDumpTest at a library dump. Ordinary test runs are unaffected.
tasks.withType<Test>().configureEach {
    System.getProperties()
        .filterKeys { it.toString().startsWith("aura.") }
        .forEach { (k, v) -> systemProperty(k.toString(), v) }
}

dependencies {
    // Scanning, scraping, grouping, sources, subtitles — what Aura is on every platform.
    implementation(project(":core"))
    implementation(libs.androidx.core.ktx)
    implementation(platform(libs.bom))
    implementation(libs.core)
    implementation(libs.platform)
    implementation(libs.foundation)
    implementation(libs.design)
    implementation(libs.sense)
    implementation(libs.tracking)
    implementation(libs.androidx.annotation)
    implementation(libs.androidx.appcompat)
    implementation(libs.androidx.activity.compose)
    implementation(libs.koin.android)
    implementation(libs.smbj)
    implementation(libs.dcerpc) // SMB share enumeration (srvsvc/IPC$) for host-only browsing
    // Media3/ExoPlayer drives the VideoComponent render path (official third-party-player
    // architecture) — track selection, embedded+sidecar subtitles, broad container support.
    implementation(libs.media3.exoplayer)
    implementation(libs.media3.session)
    // HLS (.m3u8): what a phone's 投屏 hands over from Bilibili / Tencent Video, and what a server streams.
    // Without it, DefaultMediaSourceFactory threw on any .m3u8 and took the whole app down.
    implementation(libs.media3.exoplayer.hls)
    // DASH (.mpd), likewise — mpv's FFmpeg has no DASH reader to hand it to.
    implementation(libs.media3.exoplayer.dash)
    // FfmpegAudioRenderer, built from Media3 1.8.0 + the pinned FFmpeg 6.0.2 sources for arm64 (see
    // docs/ffmpeg-decoder.md). The headset licenses no Dolby/DTS decoders, so without this
    // most movie remuxes play silent: AC3/EAC3/DTS/TrueHD all decode in software here.
    implementation(files("libs/media3-decoder-ffmpeg-1.8.0-arm64.aar"))
    implementation("com.dgtlrepublic:anitomyJ:0.0.7") // anime filename parser (Anitomy port)
    testImplementation(libs.junit)
    // Real org.json on the unit-test classpath (the mockable android.jar only stubs it),
    // so the scrape clients can be exercised against the live APIs from a JVM test.
    testImplementation("org.json:json:20240303")
    androidTestImplementation(libs.androidx.junit)
    androidTestImplementation(libs.androidx.espresso.core)
    debugImplementation(libs.androidx.ui.tooling)
    debugImplementation(libs.androidx.ui.tooling.preview)
}

configurations.all {
    resolutionStrategy {
        exclude("androidx.compose.ui", "ui")
        exclude("androidx.compose.ui", "ui-graphics")
        exclude("androidx.compose.ui", "ui-text")
        exclude("androidx.compose.foundation", "foundation")
    }
}
