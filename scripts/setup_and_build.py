import os
import sys
import zipfile
import urllib.request
import subprocess
import shutil

ROOT = os.path.abspath(os.path.join(os.path.dirname(__file__), ".."))
TOOL = os.environ.get("WUAD_ANDROID_HOME", r"F:\android-dev")
JDK_DIR = os.path.join(TOOL, "jdk-17")
SDK_DIR = os.path.join(TOOL, "sdk")
GRADLE_DIR = os.path.join(TOOL, "gradle-8.9")
JDK_ZIP = os.path.join(TOOL, "jdk17.zip")
SDK_ZIP = os.path.join(TOOL, "cmdline-tools.zip")
GRADLE_ZIP = os.path.join(TOOL, "gradle-8.9-bin.zip")
WRAPPER_JAR = os.path.join(ROOT, "gradle", "wrapper", "gradle-wrapper.jar")

JDK_URLS = [
    "https://mirrors.tuna.tsinghua.edu.cn/Adoptium/17/jdk/x64/windows/OpenJDK17U-jdk_x64_windows_hotspot_17.0.20.1_1.zip",
    "https://mirror.lzu.edu.cn/adoptium/17/jdk/x64/windows/OpenJDK17U-jdk_x64_windows_hotspot_17.0.20.1_1.zip",
    "https://mirrors.tuna.tsinghua.edu.cn/Adoptium/17/jdk/x64/windows/OpenJDK17U-jdk_x64_windows_hotspot_17.0.10_7.zip",
]
CMDLINE_URLS = [
    "https://mirrors.cloud.tencent.com/AndroidSDK/commandlinetools-win-11076708_latest.zip",
    "https://dl.google.com/android/repository/commandlinetools-win-11076708_latest.zip",
]
GRADLE_URLS = [
    "https://mirrors.cloud.tencent.com/gradle/gradle-8.9-bin.zip",
    "https://services.gradle.org/distributions/gradle-8.9-bin.zip",
]
WRAPPER_URLS = [
    "https://mirrors.cloud.tencent.com/gradle/gradle-8.9-bin.zip",
]


def log(msg: str) -> None:
    print(msg, flush=True)


def download(urls, dest: str, min_size: int = 1_000_000) -> None:
    if isinstance(urls, str):
        urls = [urls]
    os.makedirs(os.path.dirname(dest), exist_ok=True)
    if os.path.exists(dest) and os.path.getsize(dest) > min_size:
        log("exists " + dest)
        return
    last = None
    for url in urls:
        try:
            log("download " + url)
            req = urllib.request.Request(url, headers={"User-Agent": "wuad-setup"})
            with urllib.request.urlopen(req, timeout=180) as r, open(dest, "wb") as f:
                shutil.copyfileobj(r, f)
            if os.path.getsize(dest) > min_size:
                log("saved " + dest + " " + str(os.path.getsize(dest)))
                return
        except Exception as e:
            last = e
            log("fail " + url + " " + str(e))
    raise RuntimeError("download failed: " + dest + " " + str(last))


def unzip_to(zip_path: str, dest: str) -> None:
    os.makedirs(dest, exist_ok=True)
    with zipfile.ZipFile(zip_path) as z:
        z.extractall(dest)


def find_java_home() -> str:
    for root, dirs, files in os.walk(JDK_DIR):
        if "bin" in dirs and os.path.exists(os.path.join(root, "bin", "java.exe")):
            return root
    return JDK_DIR


def find_sdkmanager() -> str:
    candidates = [
        os.path.join(SDK_DIR, "cmdline-tools", "latest", "bin", "sdkmanager.bat"),
        os.path.join(SDK_DIR, "cmdline-tools", "bin", "sdkmanager.bat"),
    ]
    for c in candidates:
        if os.path.exists(c):
            return c
    for root, dirs, files in os.walk(os.path.join(SDK_DIR, "cmdline-tools")):
        if "sdkmanager.bat" in files:
            return os.path.join(root, "sdkmanager.bat")
    return ""


def prepare_cmdline_layout() -> None:
    # official layout: sdk/cmdline-tools/latest/...
    latest = os.path.join(SDK_DIR, "cmdline-tools", "latest")
    if os.path.exists(os.path.join(latest, "bin", "sdkmanager.bat")):
        return
    extracted = os.path.join(SDK_DIR, "cmdline-tools")
    # zip contains cmdline-tools/*
    inner = os.path.join(SDK_DIR, "cmdline-tools", "cmdline-tools")
    if os.path.exists(inner) and not os.path.exists(latest):
        os.makedirs(os.path.dirname(latest), exist_ok=True)
        os.rename(inner, latest)


def has_java(path: str) -> bool:
    if not os.path.exists(path):
        return False
    for root, dirs, files in os.walk(path):
        if "java.exe" in files and os.path.basename(root) == "bin":
            return True
    return False


def main() -> int:
    os.makedirs(TOOL, exist_ok=True)
    if not has_java(JDK_DIR):
        download(JDK_URLS, JDK_ZIP)
        if os.path.exists(JDK_DIR):
            shutil.rmtree(JDK_DIR, ignore_errors=True)
        tmp = os.path.join(TOOL, "jdk-unpack")
        if os.path.exists(tmp):
            shutil.rmtree(tmp, ignore_errors=True)
        unzip_to(JDK_ZIP, tmp)
        kids = [os.path.join(tmp, n) for n in os.listdir(tmp)]
        src = kids[0] if len(kids) == 1 else tmp
        shutil.move(src, JDK_DIR)
        shutil.rmtree(tmp, ignore_errors=True)

    java_home = find_java_home()
    log("JAVA_HOME=" + java_home)

    if not find_sdkmanager():
        download(CMDLINE_URLS, SDK_ZIP)
        unzip_to(SDK_ZIP, SDK_DIR)
        prepare_cmdline_layout()

    sdkmanager = find_sdkmanager()
    if not sdkmanager:
        log("sdkmanager missing")
        return 1
    log("sdkmanager=" + sdkmanager)

    env = os.environ.copy()
    env["JAVA_HOME"] = java_home
    env["ANDROID_SDK_ROOT"] = SDK_DIR
    env["ANDROID_HOME"] = SDK_DIR
    env["PATH"] = os.path.join(java_home, "bin") + os.pathsep + env.get("PATH", "")

    pkgs = ["platform-tools", "platforms;android-35", "build-tools;35.0.0"]
    cmd = [sdkmanager, "--sdk_root=" + SDK_DIR] + pkgs
    log("install sdk: " + " ".join(pkgs))
    p = subprocess.run(cmd, input="y\n" * 40, text=True, env=env, cwd=ROOT)
    if p.returncode != 0:
        log("sdkmanager failed " + str(p.returncode))
        return p.returncode

    lic = subprocess.run(
        [sdkmanager, "--sdk_root=" + SDK_DIR, "--licenses"],
        input="y\n" * 80,
        text=True,
        env=env,
        cwd=ROOT,
    )
    log("licenses " + str(lic.returncode))

    local = os.path.join(ROOT, "local.properties")
    with open(local, "w", encoding="utf-8") as f:
        f.write("sdk.dir=" + SDK_DIR.replace("\\", "/") + "\n")

    if not os.path.exists(os.path.join(GRADLE_DIR, "bin", "gradle.bat")):
        download(GRADLE_URLS, GRADLE_ZIP)
        unzip_to(GRADLE_ZIP, TOOL)
        # folder gradle-8.9
        if not os.path.exists(GRADLE_DIR):
            for n in os.listdir(TOOL):
                if n.startswith("gradle-8.9"):
                    shutil.move(os.path.join(TOOL, n), GRADLE_DIR)
                    break

    gradle = os.path.join(GRADLE_DIR, "bin", "gradle.bat")
    log("build debug apk")
    b = subprocess.run(
        [gradle, "assembleDebug", "--no-daemon"],
        cwd=ROOT,
        env=env,
    )
    apk = os.path.join(ROOT, "app", "build", "outputs", "apk", "debug", "app-debug.apk")
    log("apk exists=" + str(os.path.exists(apk)) + " " + apk)
    return b.returncode


if __name__ == "__main__":
    sys.exit(main())
