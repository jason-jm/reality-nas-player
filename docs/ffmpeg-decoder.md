# Media3 音频解码扩展

Reality NAS Player 的 Media3 播放器优先加载 `FfmpegAudioRenderer`。当系统缺少相应音频解码器时，这个扩展可以在应用内解码 AC-3、E-AC-3、DTS 和 TrueHD。它也支持 FLAC、Opus、Vorbis、AAC、ALAC 和 MLP。扩展位于 `app/libs/media3-decoder-ffmpeg-1.8.0-arm64.aar`。

## 从固定源码重建

在项目根目录执行以下命令。需要 Android NDK `30.0.16248370`、make、patch 和 Python 3。脚本读取 `ANDROID_NDK_HOME` 或 Android SDK 的 NDK 目录，校验下载的源码，然后替换 AAR 的 arm64 JNI 库。

```sh
app/natives/build-media3-audio.sh
```

- AndroidX Media3 1.8.0 的 JNI 和 Java 接口均对应提交 `b7bbc6e2bc3e45ff3ed99884c114c50f03bba5c9`。脚本校验基础 AAR 中的 Java 类和集成元数据。[AndroidX 源码](https://github.com/androidx/media/tree/b7bbc6e2bc3e45ff3ed99884c114c50f03bba5c9/libraries/decoder_ffmpeg)
- FFmpeg 取自 6.0.2 维护线，固定到 `573f2b81ebd97da81def2ba762e81b0328547004`。构建仅启用选定的解码器，以 PIC 静态库链接进 `libffmpegJNI.so`。[FFmpeg 源码](https://github.com/FFmpeg/FFmpeg/tree/573f2b81ebd97da81def2ba762e81b0328547004)
- Media3 在切换 TrueHD 音轨或跳转时重建解码上下文。FFmpeg 6.0.2 对部分音轨依赖预先填写的声道数，会把有效输入判为无效。项目移植了 FFmpeg 8.1.1 从码流识别声道布局的修复，应用内许可页附有补丁全文。[项目补丁](../app/natives/ffmpeg-truehd-layout.patch) · [上游实现](https://github.com/FFmpeg/FFmpeg/blob/n8.1.1/libavcodec/mlpdec.c)

离线重建时，将 `AURA_DECODER_SOURCES` 指向含固定版本的 `ffmpeg-573f2b81ebd97da81def2ba762e81b0328547004.tar.gz` 和 `ffmpeg_jni.cc` 的目录。可用 `AURA_DECODER_AAR` 指定基础 AAR 副本作为输出。源码准备包同时附有 AndroidX 源码、基础 AAR、补丁和脚本。

## 验证音频输出

以下命令生成仅含测试音的八轨 MKV，复制到测试用开发包的私有目录，再运行 `AudioCodecGauntletTest`。将设备序列号替换为本机目标。从项目根目录执行，主机需有 FFmpeg。

```sh
python3 tools/dev/make-audio-gauntlet.py /tmp/audio-gauntlet.mkv
./gradlew -Paura.devPackage :app:assembleDebug :app:assembleDebugAndroidTest
adb -s emulator-5556 install -r app/build/outputs/apk/debug/app-debug.apk
adb -s emulator-5556 install -r app/build/outputs/apk/androidTest/debug/app-debug-androidTest.apk
adb -s emulator-5556 shell -T 'run-as com.therealityteam.videoplayer.dev sh -c "cat > files/audio-gauntlet.mkv"' < /tmp/audio-gauntlet.mkv
adb -s emulator-5556 shell am instrument -w -e class com.therealityteam.videoplayer.AudioCodecGauntletTest com.therealityteam.videoplayer.dev.test/androidx.test.runner.AndroidJUnitRunner
```

测试逐轨检查实际选择的 MIME、已初始化的 FFmpeg 解码器、送到音频输出的新增缓冲区和播放位置。发布前在头显上另验听感和声道定位。
