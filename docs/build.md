# 构建 / Build from source

[中文产品介绍](../README.md) · [English product guide](../README.en.md) · [PICO v1.0 APK](https://github.com/jason-jm/reality-nas-player/releases/tag/v1.0)

**头显 / PICO.** 使用 JDK 21、Android SDK Platform 36 和 Android SDK Build Tools。在 Android Studio 配置 SDK，或设置 `ANDROID_HOME`。从仓库根目录运行以下命令。On Windows use `gradlew.bat` instead of `./gradlew`.

```sh
./gradlew :app:assembleDebug
./gradlew :core:test :app:testDebugUnitTest
```

The debug APK is under `app/build/outputs/apk/debug/`. The signed consumer APK is on the [release page](https://github.com/jason-jm/reality-nas-player/releases/tag/v1.0). The headset app uses PICO Spatial SDK 6.2.8.

**桌面 / Desktop.** macOS 和 Windows 桌面端共享片库与元数据核心；四个空间影院属于 PICO 头显端。v1.0 没有提供桌面安装包。The desktop app shares the library and metadata engine. Spatial cinemas are a headset feature. Run or package the desktop app from the repository root:

```sh
./gradlew -p desktop run          # macOS
./gradlew -p desktop packageDmg   # macOS
```

```bat
gradlew.bat -p desktop run
gradlew.bat -p desktop packageMsi
```
