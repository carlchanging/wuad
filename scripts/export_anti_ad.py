import os
import re

ROOT = os.path.abspath(os.path.join(os.path.dirname(__file__), "..", ".."))
SRC = os.path.join(ROOT, "app-noad", "js", "anti-ad-set.js")
OUT_DIR = os.path.join(os.path.dirname(__file__), "..", "app", "src", "main", "assets")
OUT = os.path.join(OUT_DIR, "anti-ad.txt")


def main() -> None:
    os.makedirs(OUT_DIR, exist_ok=True)
    text = open(SRC, encoding="utf-8").read()
    parts = re.findall(r'"([^"]+)"', text)
    # first token is the assignment noise if any; keep host-like strings
    hosts = [p.strip().lower() for p in parts if "." in p and " " not in p]
    with open(OUT, "w", encoding="utf-8", newline="\n") as f:
        for host in hosts:
            f.write(host + "\n")
    print("domains", len(hosts), "bytes", os.path.getsize(OUT))


if __name__ == "__main__":
    main()
