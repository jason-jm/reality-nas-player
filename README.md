# Reality NAS Player

Reality NAS Player 是一款面向 PICO 头显的本地与 NAS 影片播放器，附带 macOS 和 Windows 桌面端。它能整理片库、读取字幕，并在窗口或虚拟影院中播放你的影片。头显端使用 PICO Spatial SDK 6.2.8。

Reality NAS Player plays videos from local storage and network libraries. The PICO app offers spatial cinemas, seat selection and panoramic playback. The desktop app shares the Kotlin library and metadata engine.

## 构建

安装 JDK 21。构建头显端还需 Android SDK Platform 36 和 Android SDK Build Tools。在 Android Studio 中设置 SDK，或将 `ANDROID_HOME` 指向 SDK 安装目录。从仓库根目录执行以下命令。

```sh
./gradlew :app:assembleDebug
./gradlew :core:test :app:testDebugUnitTest
./gradlew -p desktop run
```

在 Windows 上使用 `gradlew.bat`。Android 调试包位于 `app/build/outputs/apk/debug/`。桌面端使用独立的 Gradle 构建，在 macOS 上可运行 `./gradlew -p desktop packageDmg`，在 Windows 上可运行 `gradlew.bat -p desktop packageMsi`。

## 使用

可以从系统文件管理器选择影片并用 Reality NAS Player 打开，也可以在「来源」中扫描头显或连接自己的媒体服务器。公开源码默认从用户设置中读取 TMDB API Key。需要 TMDB 海报和元数据时，在「设置 → 刮削与元数据」中填写自己的密钥。播放本地影片无需账号。应用提供 23 种界面语言。[隐私说明](docs/privacy-policy.md)

Build the Android app with JDK 21, Android SDK Platform 36 and the Gradle wrapper. Run the desktop app with `./gradlew -p desktop run`. To use TMDB metadata, enter your own API key under **Settings → Scraping**. Local playback does not require a TMDB key or a Reality NAS Player account.

## 许可证

The Reality Team 的原创代码采用 MIT 许可证。第三方库、原生二进制、SDK 和素材沿用各自的许可及署名。[MIT 许可证](LICENSE) · [第三方许可与素材出处](THIRD_PARTY_NOTICES.md)
