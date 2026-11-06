import gzip
import os
import ssl
import sys
import urllib.request

DEST_DIR = os.path.join(os.path.dirname(__file__), "app", "src", "main", "jniLibs", "arm64-v8a")
DEST_SO = os.path.join(DEST_DIR, "libmihomo.so")
GZ = r"F:\android-dev\mihomo-android-arm64-v8-v1.19.33.gz"
URLS = [
    "https://ghfast.top/https://github.com/MetaCubeX/mihomo/releases/download/v1.19.33/mihomo-android-arm64-v8-v1.19.33.gz",
    "https://ghproxy.net/https://github.com/MetaCubeX/mihomo/releases/download/v1.19.33/mihomo-android-arm64-v8-v1.19.33.gz",
    "https://github.com/MetaCubeX/mihomo/releases/download/v1.19.33/mihomo-android-arm64-v8-v1.19.33.gz",
]

def fetch(url, dest):
    print("GET", url, flush=True)
    ctx = ssl.create_default_context()
    req = urllib.request.Request(url, headers={"User-Agent": "WuadFetch/0.2"})
    with urllib.request.urlopen(req, context=ctx, timeout=60) as r, open(dest, "wb") as f:
        while True:
            chunk = r.read(1024 * 256)
            if not chunk:
                break
            f.write(chunk)
            print("..", f.tell(), flush=True)

def main():
    os.makedirs(DEST_DIR, exist_ok=True)
    if not os.path.exists(GZ) or os.path.getsize(GZ) < 15_000_000:
        last = None
        for url in URLS:
            try:
                fetch(url, GZ)
                last = None
                break
            except Exception as e:
                last = e
                print("FAIL", url, e, flush=True)
        if last is not None and (not os.path.exists(GZ) or os.path.getsize(GZ) < 1_000_000):
            raise last
    print("GZ", os.path.getsize(GZ), flush=True)
    with gzip.open(GZ, "rb") as src, open(DEST_SO, "wb") as dst:
        dst.write(src.read())
    print("SO", os.path.getsize(DEST_SO), DEST_SO, flush=True)
    with open(DEST_SO, "rb") as f:
        magic = f.read(4)
    if magic != b"\x7fELF":
        raise SystemExit("not elf: %r" % magic)
    print("ELF_OK", flush=True)
    patch_packages_path(DEST_SO)

def patch_packages_path(so_path):
    # 官方 android 内核硬编码读 /data/system/packages.xml，普通 App 没权限。
    # 等长替换成应用私有路径，启动时由 MihomoEngine 写一份空 xml。
    old = b"/data/system/packages.xml"
    new = b"/data/data/app.wuad/p.xml"
    if len(old) != len(new):
        raise SystemExit("path length mismatch")
    data = bytearray(open(so_path, "rb").read())
    i = data.find(old)
    if i < 0:
        if data.find(new) >= 0:
            print("PATCH_ALREADY", flush=True)
            return
        raise SystemExit("packages.xml path not found in so")
    data[i : i + len(old)] = new
    open(so_path, "wb").write(data)
    print("PATCH_OK", i, flush=True)

if __name__ == "__main__":
    main()
