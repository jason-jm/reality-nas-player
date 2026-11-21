# Build Reality NAS Player

[Product guide](../README.md) · [Choose a language](languages/README.md) · [v1.2.0 downloads](https://github.com/jason-jm/reality-nas-player/releases/tag/v1.2.0)

**Want to watch a film on PICO Space Pro?** Download the signed [headset APK](https://github.com/jason-jm/reality-nas-player/releases/download/v1.2.0/reality-nas-player-1.2.0-8.apk). These instructions are for running the desktop edition or building the headset app yourself. There is no prebuilt desktop installer in v1.2.0.

## On your Mac or Windows PC

Install a JDK 21 and run the Gradle wrapper from the repository root. The desktop app shares the headset’s library and metadata engine and uses the mouse and keyboard. The virtual screening rooms are a PICO Space Pro feature.

| Platform | Launch the app | Build an installer on this platform |
| --- | --- | --- |
| macOS | `./gradlew -p desktop run` | `./gradlew -p desktop packageDmg` |
| Windows | `.\gradlew.bat -p desktop run` | `.\gradlew.bat -p desktop packageMsi` |

## For PICO Space Pro

Use JDK 21, Android SDK Platform 36 and Android SDK Build Tools. Configure the SDK in Android Studio or set `ANDROID_HOME`. The project uses PICO Spatial SDK 6.2.8 for PICO OS 6. From the repository root:

```sh
./gradlew :app:assembleDebug
./gradlew :core:test :app:testDebugUnitTest
```

On Windows, use `.\gradlew.bat` in place of `./gradlew`. The debug APK is written to `app/build/outputs/apk/debug/`. The [v1.2.0 release](https://github.com/jason-jm/reality-nas-player/releases/tag/v1.2.0) includes checksums for the signed headset APK and the corresponding native source archive.
