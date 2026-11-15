#!/usr/bin/env python3
"""gen-licenses.py — writes app/src/main/assets/licenses.txt, the text of 设置 › 关于 › 开源许可.

Every licence text is taken from a file that ships with the software it covers (the native sources under
~/aura-native, the jars in the Gradle cache, the NDK's NOTICE files); only the four short templates
(MIT, BSD-3-Clause, 0BSD for components whose jars carry no licence file) are written out here, with the
copyright line their projects give. Run it again when a dependency or a native library's version changes:

    python3 tools/dev/gen-licenses.py

Versions are read from where they are pinned; the Java/Kotlin list is the release build's runtime classpath
(./gradlew :app:dependencies --configuration releaseRuntimeClasspath) grouped by licence.
"""
import glob
import os
import re
import subprocess
import sys
import zipfile

ROOT = os.path.dirname(os.path.dirname(os.path.dirname(os.path.abspath(__file__))))
HOME = os.path.expanduser("~")
DEPS = f"{HOME}/aura-native/mpv/mpv-android/buildscripts/deps"
GRADLE = f"{HOME}/.gradle/caches/modules-2/files-2.1"
NDK = sorted(glob.glob(f"{HOME}/Library/Android/sdk/ndk/30.*"))[-1]
OUT = f"{ROOT}/app/src/main/assets/licenses.txt"


def read(path):
    with open(path, encoding="utf-8", errors="replace") as f:
        return f.read().strip("\n")


def lines(path, first, last):
    """Lines [first, last] (1-based, inclusive) of a file."""
    with open(path, encoding="utf-8", errors="replace") as f:
        return "".join(f.readlines()[first - 1:last]).strip("\n")


def section(path, start, end_marker):
    """From the line holding [start] to the line holding [end_marker] (inclusive)."""
    text = read(path).splitlines()
    i = next(n for n, l in enumerate(text) if start in l)
    j = next(n for n in range(i, len(text)) if end_marker in text[n])
    return "\n".join(text[i:j + 1])


def jar_file(group_artifact, name):
    jars = sorted(j for j in glob.glob(f"{GRADLE}/{group_artifact}/*/*/*.jar") if "sources" not in j and "javadoc" not in j)
    with zipfile.ZipFile(jars[-1]) as z:
        return z.read(name).decode("utf-8", "replace").strip("\n")


def bouncy_castle():
    """Its licence is the MIT text under its own copyright line (org/bouncycastle/LICENSE holds both as string
    constants, but a class file keeps its strings in constant-pool order, not the text's): the line from there."""
    jars = sorted(glob.glob(f"{GRADLE}/org.bouncycastle/bcprov-jdk18on/*/*/bcprov-jdk18on-*.jar"))
    with zipfile.ZipFile(jars[-1]) as z:
        raw = z.read("org/bouncycastle/LICENSE.class").decode("latin-1")
    copyright_line = re.search(r"Copyright \(c\) [ -~]+?\(https://www\.bouncycastle\.org\)", raw).group(0)
    return copyright_line + "\n\n" + MIT


def mpv_android_mit():
    return read(f"{HOME}/aura-native/mpv/mpv-android/LICENSE")


MIT = """Permission is hereby granted, free of charge, to any person obtaining a copy of this software and
associated documentation files (the "Software"), to deal in the Software without restriction, including
without limitation the rights to use, copy, modify, merge, publish, distribute, sublicense, and/or sell copies
of the Software, and to permit persons to whom the Software is furnished to do so, subject to the following
conditions:

The above copyright notice and this permission notice shall be included in all copies or substantial portions
of the Software.

THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR IMPLIED, INCLUDING BUT NOT LIMITED
TO THE WARRANTIES OF MERCHANTABILITY, FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT. IN NO EVENT SHALL THE
AUTHORS OR COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER LIABILITY, WHETHER IN AN ACTION OF
CONTRACT, TORT OR OTHERWISE, ARISING FROM, OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS
IN THE SOFTWARE."""

BSD3 = """Redistribution and use in source and binary forms, with or without modification, are permitted provided that
the following conditions are met:

1. Redistributions of source code must retain the above copyright notice, this list of conditions and the
   following disclaimer.
2. Redistributions in binary form must reproduce the above copyright notice, this list of conditions and the
   following disclaimer in the documentation and/or other materials provided with the distribution.
3. Neither the name of the copyright holder nor the names of its contributors may be used to endorse or promote
   products derived from this software without specific prior written permission.

THIS SOFTWARE IS PROVIDED BY THE COPYRIGHT HOLDERS AND CONTRIBUTORS "AS IS" AND ANY EXPRESS OR IMPLIED
WARRANTIES, INCLUDING, BUT NOT LIMITED TO, THE IMPLIED WARRANTIES OF MERCHANTABILITY AND FITNESS FOR A PARTICULAR
PURPOSE ARE DISCLAIMED. IN NO EVENT SHALL THE COPYRIGHT HOLDER OR CONTRIBUTORS BE LIABLE FOR ANY DIRECT,
INDIRECT, INCIDENTAL, SPECIAL, EXEMPLARY, OR CONSEQUENTIAL DAMAGES (INCLUDING, BUT NOT LIMITED TO, PROCUREMENT OF
SUBSTITUTE GOODS OR SERVICES; LOSS OF USE, DATA, OR PROFITS; OR BUSINESS INTERRUPTION) HOWEVER CAUSED AND ON ANY
THEORY OF LIABILITY, WHETHER IN CONTRACT, STRICT LIABILITY, OR TORT (INCLUDING NEGLIGENCE OR OTHERWISE) ARISING
IN ANY WAY OUT OF THE USE OF THIS SOFTWARE, EVEN IF ADVISED OF THE POSSIBILITY OF SUCH DAMAGE."""

ZERO_BSD = """Permission to use, copy, modify, and/or distribute this software for any purpose with or without fee is
hereby granted.

THE SOFTWARE IS PROVIDED "AS IS" AND THE AUTHOR DISCLAIMS ALL WARRANTIES WITH REGARD TO THIS SOFTWARE INCLUDING
ALL IMPLIED WARRANTIES OF MERCHANTABILITY AND FITNESS. IN NO EVENT SHALL THE AUTHOR BE LIABLE FOR ANY SPECIAL,
DIRECT, INDIRECT, OR CONSEQUENTIAL DAMAGES OR ANY DAMAGES WHATSOEVER RESULTING FROM LOSS OF USE, DATA OR PROFITS,
WHETHER IN AN ACTION OF CONTRACT, NEGLIGENCE OR OTHER TORTIOUS ACTION, ARISING OUT OF OR IN CONNECTION WITH THE
USE OR PERFORMANCE OF THIS SOFTWARE."""


def runtime_groups():
    """The release build's runtime classpath as group IDs (Gradle's dependency report)."""
    out = subprocess.run(
        ["./gradlew", ":app:dependencies", "--configuration", "releaseRuntimeClasspath", "-q"],
        cwd=ROOT, capture_output=True, text=True, check=True,
    ).stdout
    return sorted({m.group(1) for m in re.finditer(r"([a-zA-Z0-9_.-]+):[a-zA-Z0-9_.-]+:[0-9]", out)})


# Friendly names for the Apache-2.0 groups, in the order shown.
APACHE = [
    ("androidx.", "Android Jetpack（AndroidX：Compose、Lifecycle、Activity、Core、Window 等）"),
    ("androidx.media3", "AndroidX Media3（ExoPlayer）及其 FFmpeg 解码扩展的 Java 部分"),
    ("org.jetbrains.kotlin", "Kotlin 标准库"),
    ("org.jetbrains.kotlinx", "kotlinx.coroutines、kotlinx.serialization"),
    ("org.jetbrains", "JetBrains Annotations"),
    ("io.insert-koin", "Koin"),
    ("com.hierynomus", "smbj、asn-one"),
    ("commons-net", "Apache Commons Net"),
    ("org.apache.commons", "Apache Commons Compress、Commons Lang"),
    ("commons-io", "Apache Commons IO"),
    ("commons-codec", "Apache Commons Codec"),
    ("commons-collections", "Apache Commons Collections"),
    ("com.google.guava", "Guava"),
    ("com.google.errorprone", "Error Prone Annotations"),
    ("com.google.j2objc", "J2ObjC Annotations"),
    ("com.google.code.findbugs", "JSR 305 Annotations"),
    ("co.touchlab", "Stately"),
    ("org.jspecify", "JSpecify"),
    ("com.pico.spatial.ui", "PICO SpatialUI（声明为 Apache-2.0 的组件）"),
]


def main():
    groups = runtime_groups()
    apache = []
    for prefix, name in APACHE:
        hit = [g for g in groups if g == prefix.rstrip(".") or g.startswith(prefix)]
        if prefix == "androidx.":
            hit = [g for g in hit if not g.startswith("androidx.media3")]
        if hit and name not in apache:
            apache.append(name)

    patch = read(f"{ROOT}/app/natives/mpv-patches/ffmpeg-mediacodec-stall.patch")
    lgpl = read(f"{DEPS}/ffmpeg/COPYING.LGPLv2.1")
    apache_text = jar_file("commons-net/commons-net", "META-INF/LICENSE.txt")
    commons_notice = jar_file("commons-net/commons-net", "META-INF/NOTICE.txt")
    llvm = section(f"{NDK}/NOTICE.toolchain", "---- LLVM Exceptions to the Apache 2.0 License ----", "Software.")
    mpl = section(f"{NDK}/NOTICE", "Mozilla Public License Version 2.0", "defined by the Mozilla Public License, v. 2.0.")
    ftl = read(f"{DEPS}/freetype2/docs/FTL.TXT")

    parts = []
    add = parts.append
    add("开源许可 · Open-source licences")
    add("Reality NAS Player 由下列开源软件构成或使用了它们，感谢各位作者。组件清单之后附有各许可证全文。\n"
        "Reality NAS Player is built with the open-source software listed below; the full licence texts follow the list.")

    add("■ 原生库 · Native libraries")
    add("FFmpeg 8.1.1 — LGPL-2.1-or-later\n"
        "  libavcodec、libavformat、libavutil、libavfilter、libavdevice、libswresample、libswscale（独立的共享库 .so，供 mpv 使用）。\n"
        "  含我们的一处修改（MediaCodec 解码器不出画面时报错返回），补丁全文见下文。\n"
        "FFmpeg 6.0.2（573f2b81ebd97da81def2ba762e81b0328547004）— LGPL-2.1-or-later\n"
        "  Media3 1.8.0 的 libffmpegJNI.so 静态链接此版本，含移植自 FFmpeg 8.1.1 的 TrueHD 声道布局修复，补丁见下文。\n"
        "mpv 0.41.0 — LGPL-2.1-or-later（以 -Dgpl=false 构建）\n"
        "libplacebo 7.360.1 — LGPL-2.1-or-later\n"
        "FriBidi 1.0.17 — LGPL-2.1-or-later\n"
        "libass 0.17.5 — ISC\n"
        "HarfBuzz 14.5.0 — MIT\n"
        "FreeType 2.14.3 — FreeType License (FTL)\n"
        "Fontconfig 2.18.3 — MIT\n"
        "libxml2 2.15.4 — MIT\n"
        "libunibreak 8.0 — zlib\n"
        "mpv-android（JNI 层与 MPVLib）— MIT\n"
        "LLVM libc++（libc++_shared.so）— Apache-2.0 WITH LLVM-exception")

    add("■ 源码与替换 · Source and relinking (LGPL)")
    add("FFmpeg、mpv、libplacebo 与 FriBidi 以 GNU 宽通用公共许可证（LGPL）2.1 或更高版本授权，未启用任何 GPL 组件。"
        "它们的源码可从各项目官方获取（ffmpeg.org、mpv.io、code.videolan.org/videolan/libplacebo、github.com/fribidi/fribidi），"
        "上列版本即 Reality NAS Player 所用版本，两个 FFmpeg 构建的补丁全文见下方。"
        "如需 Reality NAS Player 所用的完整源码与构建脚本，请通过本应用在商店页面上的开发者联系方式索取，我们自发布之日起三年内免费提供。"
        "这些库以独立的共享库形式包含在安装包中（Media3 的 FFmpeg 扩展的源码在 Media3 项目中公开），你可以用自行编译的兼容版本替换它们。\n"
        "FFmpeg is a trademark of the FFmpeg project.")
    add("FFmpeg 8.1.1 修改 · Our change to FFmpeg 8.1.1 (ffmpeg-mediacodec-stall.patch)\n" + patch)
    add("FFmpeg 6.0.2 修改 · TrueHD channel layout (ffmpeg-truehd-layout.patch)\n"
        + read(f"{ROOT}/app/natives/ffmpeg-truehd-layout.patch"))

    add("■ Apache License 2.0")
    add("\n".join(apache) + "\n\n" + commons_notice)
    add("■ Mozilla Public License 2.0\nanitomyJ（文件名解析）— 未修改，源码见 github.com/Vorror/anitomyJ")
    add("■ BSD 与 ISC 许可\n"
        "JSch（mwiede 维护版）— BSD-3-Clause（含 JZlib：BSD；jBCrypt：ISC）\n"
        "dcerpc（Rapid7，github.com/rapid7/smbj-rpc）— BSD-3-Clause\n"
        "libass — ISC")
    add("■ MIT 许可\n"
        "MBassador、SLF4J API、Checker Framework Qualifiers、Bouncy Castle（Bouncy Castle 许可，即 MIT）、"
        "HarfBuzz、Fontconfig、libxml2、mpv-android")
    add("■ 其他\n"
        "XZ for Java — 0BSD\n"
        "libunibreak — zlib\n"
        "FreeType — FreeType License (FTL)\n"
        "PICO Spatial SDK — 依 PICO 开发者服务协议使用（非开源）")

    add("━━━━ 许可证全文 · Licence texts ━━━━")
    add("GNU LESSER GENERAL PUBLIC LICENSE 2.1 — FFmpeg, mpv, libplacebo, FriBidi\n\n" + lgpl)
    add("Apache License 2.0\n\n" + apache_text + "\n\nLLVM libc++:\n" + llvm)
    add("Mozilla Public License 2.0 — anitomyJ\n\n" + mpl)
    add("JSch — BSD\n\n" + jar_file("com.github.mwiede/jsch", "META-INF/LICENSE.txt")
        + "\n\nJZlib\n\n" + jar_file("com.github.mwiede/jsch", "META-INF/LICENSE.JZlib.txt")
        + "\n\njBCrypt — ISC\n\n" + jar_file("com.github.mwiede/jsch", "META-INF/LICENSE.jBCrypt.txt"))
    add("dcerpc — BSD-3-Clause\n\nCopyright (c) Rapid7 and the smbj-rpc contributors (github.com/rapid7/smbj-rpc)\n\n" + BSD3)
    add("libass — ISC\n\n" + read(f"{DEPS}/libass/COPYING"))
    add("MBassador — MIT\n\nCopyright (c) Benjamin Diedrichsen (github.com/bennidi/mbassador)\n\n" + MIT)
    add("SLF4J API — MIT\n\n" + jar_file("org.slf4j/slf4j-api", "META-INF/LICENSE.txt"))
    add("Checker Framework Qualifiers — MIT\n\n" + jar_file("org.checkerframework/checker-qual", "META-INF/LICENSE.txt"))
    add("Bouncy Castle\n\n" + bouncy_castle())
    add("HarfBuzz — MIT\n\n" + read(f"{DEPS}/harfbuzz/COPYING"))
    add("Fontconfig — MIT\n\n" + read(f"{DEPS}/fontconfig/COPYING"))
    add("libxml2 — MIT\n\n" + read(f"{DEPS}/libxml2/Copyright"))
    add("mpv-android — MIT\n\n" + mpv_android_mit())
    add("XZ for Java — 0BSD\n\n" + ZERO_BSD)
    add("libunibreak — zlib\n\n" + read(f"{DEPS}/unibreak/LICENCE"))
    add("FreeType License (FTL)\n\n" + ftl)

    text = "\n\n\n".join(p.strip("\n") for p in parts) + "\n"
    os.makedirs(os.path.dirname(OUT), exist_ok=True)
    with open(OUT, "w", encoding="utf-8") as f:
        f.write(text)
    print(f"{OUT}: {len(text.encode('utf-8')) // 1024} KB, {len(apache)} Apache groups", file=sys.stderr)


if __name__ == "__main__":
    main()
