# R8 for Aura (release; and the dev build with -Paura.minify, for trying it on the headset).
#
# What R8 cannot see by itself: names looked up at run time — native code calling back into Java by name,
# libraries that load their own classes from strings — and a closed SDK that does both.

# Readable stack traces from the field: line numbers kept, the source file name dropped.
-keepattributes SourceFile,LineNumberTable
-renamesourcefileattribute SourceFile

# PICO Spatial SDK: closed, JNI on every side (the runtime calls into its classes by name).
-keep class com.pico.** { *; }
-keep interface com.pico.** { *; }
-dontwarn com.pico.**

# mpv: libmpv's JNI calls MPVLib's static event/log methods back by name.
-keep class is.xyz.mpv.** { *; }

# Our own JNI (libaurass, the chapter stills, the engine's loader): methods called from native by name.
-keep class com.therealityteam.videoplayer.data.playback.AssRenderer { *; }
-keep class com.therealityteam.videoplayer.data.playback.ChapterStills { *; }

# Media3's FFmpeg decoder (a local AAR): its renderer is found by reflection, its natives call back.
-keep class androidx.media3.decoder.ffmpeg.** { *; }

# SMB: smbj dispatches on annotated handlers (mbassador) and loads its crypto by name; dcerpc likewise.
-keep class com.hierynomus.** { *; }
-keep class net.engio.mbassy.** { *; }
-keep class com.rapid7.** { *; }
-dontwarn com.hierynomus.**
-dontwarn net.engio.mbassy.**
-dontwarn org.bouncycastle.**
-dontwarn javax.el.**

# SFTP: JSch builds its ciphers, MACs and key exchanges from class names in its config.
-keep class com.jcraft.jsch.** { *; }
-dontwarn com.jcraft.jsch.**

# FTP: commons-net picks its directory-listing parsers by class name.
-keep class org.apache.commons.net.** { *; }

# 7z fonts: commons-compress / XZ have optional codecs R8 finds missing.
-dontwarn org.apache.commons.compress.**
-dontwarn org.tukaani.xz.**
-dontwarn org.brotli.**
-dontwarn com.github.luben.zstd.**
-dontwarn org.objectweb.asm.**
-dontwarn org.ow2.asm.**

# Optional dependencies some libraries reference but never need on Android.
-dontwarn org.slf4j.**
-dontwarn javax.annotation.**
-dontwarn org.ietf.jgss.**
-dontwarn java.lang.management.**
# dcerpc throws java.rmi's (un)marshal exceptions, which Android has never had — as without R8.
-dontwarn java.rmi.**
