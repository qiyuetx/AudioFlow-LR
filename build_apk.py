#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""
AudioRouterLR 一键构建（不需要 Gradle / Android Studio / AGP）。

为什么走手工链路：这个工程零第三方依赖、UI 全用系统 API 代码搭，
只需要「aapt2 + javac + d8 + zipalign + apksigner」五步就能出一个可安装的 APK，
比拉整套 Gradle/AGP 依赖更快也更可控。工程里的 build.gradle 仍然保留，
用 Android Studio 打开照样能 assembleDebug。

用法：  python build_apk.py
产物：  audio-router-lr-v2.21.apk（debug 签名，装上即用）

工具链位置默认在本仓库同级的 work/toolchain，可用环境变量覆盖：
    ANDROID_JDK          含 bin/javac.exe 的 JDK 17 目录
    ANDROID_BUILD_TOOLS  含 aapt2.exe / lib/d8.jar 的 build-tools 目录
    ANDROID_JAR          android.jar 路径
"""
import hashlib
import os
import re
import shutil
import subprocess
import sys
import zipfile
from pathlib import Path

ROOT = Path(__file__).resolve().parent
WORK = ROOT.parent / "work" / "toolchain"

JDK = Path(os.environ.get("ANDROID_JDK", str(WORK / "jdk" / "jdk-17.0.21+9")))
BT = Path(os.environ.get("ANDROID_BUILD_TOOLS", str(WORK / "build-tools" / "android-14")))
ANDROID_JAR = Path(os.environ.get("ANDROID_JAR", str(WORK / "platform" / "android-34" / "android.jar")))

JAVAC = JDK / "bin" / "javac.exe"
JAVA = JDK / "bin" / "java.exe"
JAR = JDK / "bin" / "jar.exe"
KEYTOOL = JDK / "bin" / "keytool.exe"
AAPT2 = BT / "aapt2.exe"
ZIPALIGN = BT / "zipalign.exe"
D8_JAR = BT / "lib" / "d8.jar"
APKSIGNER_JAR = BT / "lib" / "apksigner.jar"

APP = ROOT / "app"
SRC = APP / "src" / "main" / "java"
RES = APP / "src" / "main" / "res"
MANIFEST = APP / "src" / "main" / "AndroidManifest.xml"
BUILD = ROOT / "build"


def _gradle_version() -> tuple[str, str]:
    """版本号只认 app/build.gradle 一处。

    以前这里写死 "35" / "2.21"：改了 build.gradle 的 versionName，打出来的包还是旧号，
    装上之后「设置 → 关于」和诊断报告都跟着错 —— 用户已经因为"装完对不上货"抱怨过一次。
    build.gradle 是 Android Studio 那条路和这条路共用的唯一事实来源，从它读。
    """
    text = (APP / "build.gradle").read_text(encoding="utf-8")
    code = re.search(r"versionCode\s+(\d+)", text)
    name = re.search(r'versionName\s+"([^"]+)"', text)
    return (code.group(1) if code else "1"), (name.group(1) if name else "1.0")


VERSION_CODE, VERSION_NAME = _gradle_version()
OUT = ROOT / ("audio-router-lr-v%s.apk" % VERSION_NAME)

MIN_SDK = "22"
TARGET_SDK = "34"
PACKAGE = "com.audiomixing.app"


def run(cmd, allow_fail=False):
    r = subprocess.run([str(x) for x in cmd], capture_output=True,
                       text=True, encoding="utf-8", errors="replace")
    if r.returncode != 0 and not allow_fail:
        sys.stdout.write(r.stdout or "")
        sys.stderr.write(r.stderr or "")
        raise SystemExit("命令失败（exit %d）：%s" % (r.returncode, " ".join(str(x) for x in cmd[:4])))
    return r


def step(n, msg):
    print("[%d/9] %s" % (n, msg), flush=True)


def check_toolchain():
    missing = [str(p) for p in (JAVAC, JAVA, JAR, KEYTOOL, AAPT2, ZIPALIGN,
                                D8_JAR, APKSIGNER_JAR, ANDROID_JAR) if not p.exists()]
    if missing:
        raise SystemExit("工具链缺失：\n  " + "\n  ".join(missing)
                         + "\n请设置 ANDROID_JDK / ANDROID_BUILD_TOOLS / ANDROID_JAR")


def unit_test():
    """核心矩阵是纯 Java，先用裸 JDK 跑回归测试，不过就不出包。"""
    step(1, "ChannelRouter 回归测试（纯 JDK）")
    tc = BUILD / "test-classes"
    tc.mkdir(parents=True, exist_ok=True)
    r = run([JAVAC, "-encoding", "UTF-8", "-d", str(tc),
             str(SRC / "com" / "audiomixing" / "app" / "ChannelRouter.java"),
             str(ROOT / "tools" / "ChannelRouterTest.java")])
    r = run([JAVA, "-Dfile.encoding=UTF-8", "-cp", str(tc), PACKAGE + ".ChannelRouterTest"])
    sys.stdout.write(r.stdout or "")
    if "0 failed" not in (r.stdout or ""):
        raise SystemExit("单元测试未通过，拒绝出包")
    return tc


def generate_build_config(gen):
    d = gen / "com" / "audiomixing" / "app"
    d.mkdir(parents=True, exist_ok=True)
    (d / "BuildConfig.java").write_text(
        "package " + PACKAGE + ";\n\n"
        "/** 由 build_apk.py 生成；AGP 构建时由 AGP 自己生成，两份内容一致。 */\n"
        "public final class BuildConfig {\n"
        "    public static final boolean DEBUG = true;\n"
        '    public static final String APPLICATION_ID = "' + PACKAGE + '";\n'
        '    public static final String BUILD_TYPE = "debug";\n'
        "    public static final int VERSION_CODE = " + VERSION_CODE + ";\n"
        '    public static final String VERSION_NAME = "' + VERSION_NAME + '";\n'
        "}\n", encoding="utf-8")


def compile_resources(compiled):
    step(2, "aapt2 编译资源")
    compiled.mkdir(parents=True, exist_ok=True)
    files = [p for p in sorted(RES.rglob("*")) if p.is_file()]
    if not files:
        raise SystemExit("res 目录里没有资源")
    for f in files:
        run([AAPT2, "compile", str(f), "-o", str(compiled)])
    return sorted(compiled.glob("*.flat"))


def link_apk(flats, gen, base):
    step(3, "aapt2 链接 AndroidManifest / resources.arsc")
    cmd = [AAPT2, "link", "-o", str(base), "-I", str(ANDROID_JAR),
           "--manifest", str(MANIFEST),
           "--min-sdk-version", MIN_SDK, "--target-sdk-version", TARGET_SDK,
           "--version-code", VERSION_CODE, "--version-name", VERSION_NAME,
           "--java", str(gen), "--auto-add-overlay"]
    cmd += [str(f) for f in flats]
    r = run(cmd)
    if r.stderr.strip():
        print("  aapt2: " + r.stderr.strip()[:500], flush=True)


def compile_java(gen, classes, argfile):
    step(4, "javac 编译 Java 源码")
    classes.mkdir(parents=True, exist_ok=True)
    sources = sorted(SRC.rglob("*.java")) + sorted(gen.rglob("*.java"))
    # javac 的 @argfile 把反斜杠当转义符，Windows 路径一律换成正斜杠。
    argfile.write_text("\n".join('"%s"' % p.as_posix() for p in sources), encoding="utf-8")
    r = run([JAVAC, "-J-Duser.language=en", "-J-Duser.country=US",
             "-encoding", "UTF-8", "-source", "17", "-target", "17",
             "-cp", str(ANDROID_JAR), "-d", str(classes), "@" + argfile.as_posix()])
    if r.stderr.strip():
        print("  javac: " + r.stderr.strip()[:800], flush=True)
    print("  编译出 %d 个 class" % len(list(classes.rglob("*.class"))), flush=True)


def dex(classes, jar_path, dex_dir):
    step(5, "d8 生成 classes.dex")
    run([JAR, "cf", str(jar_path), "-C", str(classes), "."])
    dex_dir.mkdir(parents=True, exist_ok=True)
    r = run([JAVA, "-cp", str(D8_JAR), "com.android.tools.r8.D8",
             "--lib", str(ANDROID_JAR), "--min-api", MIN_SDK,
             "--output", str(dex_dir), str(jar_path)])
    if r.stdout.strip():
        print("  d8: " + r.stdout.strip()[:500], flush=True)
    if r.stderr.strip():
        print("  d8: " + r.stderr.strip()[:500], flush=True)
    out = dex_dir / "classes.dex"
    if not out.exists():
        raise SystemExit("d8 没有产出 classes.dex")
    return out


def assemble(base, dex_file, unsigned):
    step(6, "把 dex 装进 APK")
    with zipfile.ZipFile(base, "r") as zin, zipfile.ZipFile(unsigned, "w") as zout:
        for it in zin.infolist():
            zi = zipfile.ZipInfo(it.filename, it.date_time)
            zi.compress_type = it.compress_type
            zi.external_attr = it.external_attr
            zi.internal_attr = it.internal_attr
            zi.create_system = it.create_system
            zout.writestr(zi, zin.read(it.filename))
        zout.write(str(dex_file), "classes.dex", compress_type=zipfile.ZIP_DEFLATED)


def align(unsigned, aligned):
    step(7, "zipalign 4 字节对齐")
    run([ZIPALIGN, "-f", "-p", "4", str(unsigned), str(aligned)])


def sign(aligned):
    step(8, "生成 debug 签名并签名")
    ks = BUILD / "debug.keystore"
    if not ks.exists():
        run([KEYTOOL, "-genkeypair", "-alias", "androiddebugkey",
             "-keypass", "android", "-storepass", "android",
             "-keystore", str(ks), "-keyalg", "RSA", "-keysize", "2048",
             "-validity", "10000", "-dname", "CN=Android Debug,O=Android,C=US"])
    if OUT.exists():
        OUT.unlink()
    run([JAVA, "-jar", str(APKSIGNER_JAR), "sign",
         "--ks", str(ks), "--ks-key-alias", "androiddebugkey",
         "--ks-pass", "pass:android", "--key-pass", "pass:android",
         "--v1-signing-enabled", "true", "--v2-signing-enabled", "true",
         "--min-sdk-version", MIN_SDK, "--out", str(OUT), str(aligned)])


def verify():
    step(9, "校验签名与产物")
    r = run([JAVA, "-jar", str(APKSIGNER_JAR), "verify", "--verbose",
             "--min-sdk-version", MIN_SDK, str(OUT)])
    print("  " + (r.stdout or "").strip().replace("\n", "\n  ")[:900], flush=True)
    data = OUT.read_bytes()
    print("\n产物：%s" % OUT)
    print("大小：%.2f MB" % (len(data) / 1048576.0))
    print("SHA256：%s" % hashlib.sha256(data).hexdigest())


def clean_outputs():
    """覆盖式构建：只删明确的产物文件，不动整个 build 目录（避免误删/批量删除）。

    源码文件只增不减时，classes/ 里残留的旧 class 不影响结果；
    aapt2/javac/d8 都会覆盖同名输出。若以后删了某个 .java，手工删一次 build/classes 即可。
    """
    BUILD.mkdir(parents=True, exist_ok=True)
    for p in (BUILD / "base.apk", BUILD / "classes.jar",
              BUILD / "dex" / "classes.dex", BUILD / "unsigned.apk",
              BUILD / "aligned.apk", OUT):
        if p.exists():
            p.unlink()


def main():
    check_toolchain()
    clean_outputs()
    gen = BUILD / "gen"
    generate_build_config(gen)
    unit_test()
    flats = compile_resources(BUILD / "compiled")
    base = BUILD / "base.apk"
    link_apk(flats, gen, base)
    compile_java(gen, BUILD / "classes", BUILD / "sources.txt")
    dex_file = dex(BUILD / "classes", BUILD / "classes.jar", BUILD / "dex")
    unsigned = BUILD / "unsigned.apk"
    assemble(base, dex_file, unsigned)
    aligned = BUILD / "aligned.apk"
    align(unsigned, aligned)
    sign(aligned)
    verify()


if __name__ == "__main__":
    main()
