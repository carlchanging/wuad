import json
import re

path = r"F:\Cursor-Projects\vibepm-demo-agent\wuad-android\app\src\main\assets\gkd-rules.json"
data = json.load(open(path, encoding="utf-8"))
rules = data["rules"]

def outside_brackets(s):
    out = []
    depth = 0
    buf = []
    for c in s:
        if c == "[":
            if depth == 0 and buf:
                out.append("".join(buf))
                buf = []
            depth += 1
        elif c == "]":
            if depth > 0:
                depth -= 1
        elif depth == 0:
            buf.append(c)
    if buf:
        out.append("".join(buf))
    return " ".join(out)

def flags(s):
    s = s or ""
    rel = outside_brackets(s)
    return {
        "ancestor": "<<" in rel,
        "nth": bool(re.search(r"[+\-<>]\s*\(|[+\-<>]\s*\d|[+\-<>]n", rel)),
        "index": "index=" in s,
        "parent": "parent." in s,
        "getChild": "getChild" in s,
        "getPrev": "getPrev" in s,
        "nested_paren": bool(re.search(r"\[[^\]]*\(", s)),
        "not_sel": bool(re.search(r"!\((?![A-Za-z])", s)),
        "arrow": "->" in rel,
    }

on = [r for r in rules if r.get("enabled")]
off = [r for r in rules if not r.get("enabled")]
print("total", len(rules), "enabled", len(on), "disabled", len(off))

def tally(rows, title):
    hit = {k: 0 for k in ("ancestor", "nth", "index", "parent", "getChild", "getPrev", "nested_paren", "not_sel", "arrow")}
    any_hard = 0
    for r in rows:
        blob = (r.get("match") or "") + "\n" + (r.get("andMatch") or "") + "\n" + (r.get("excludeMatch") or "")
        f = flags(blob)
        if any(f.values()):
            any_hard += 1
        for k, v in f.items():
            if v:
                hit[k] += 1
    print(title, "hard", any_hard, "/", len(rows), hit)

tally(on, "enabled")
tally(off, "disabled")
tally([r for r in on if "开屏" in (r.get("group") or "")], "enabled-splash")
