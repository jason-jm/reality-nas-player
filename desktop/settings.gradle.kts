// The desktop app (macOS, Windows) is its own Gradle build: it runs on a newer Kotlin and Compose
// Multiplatform than the PICO app can take (the headset's toolchain pins those), and shares the
// engine by compiling the same sources — ../core/src/main/kotlin — rather than a published module.
//
//   ./gradlew -p desktop run            # launch it
//   ./gradlew -p desktop test           # its tests
//   ./gradlew -p desktop packageDmg     # a macOS disk image (packageMsi on Windows)
pluginManagement {
    repositories {
        gradlePluginPortal()
        mavenCentral()
        google()
    }
}

dependencyResolutionManagement {
    repositories {
        mavenCentral()
        google()
    }
}

rootProject.name = "aura-desktop"
