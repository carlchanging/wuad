import json
import os
import sys

try:
    import json5
except ImportError:
    sys.exit("pip install json5")

HERE = os.path.dirname(os.path.abspath(__file__))
RAW = os.path.join(HERE, "gkd-raw.json5")
OUT = os.path.join(HERE, "..", "app", "src", "main", "assets", "gkd-rules.json")
URL = "https://cdn.jsdelivr.net/gh/Lin-arm/GKD_subscription@main/dist/gkd.json5"


def join_act(raw):
    if isinstance(raw, str):
        return raw
    if isinstance(raw, list):
        return "|".join(str(x) for x in raw if x)
    return ""


def join_matches(raw):
    if isinstance(raw, str):
        return raw
    if isinstance(raw, list):
        return "||".join(str(x) for x in raw if x)
    return ""


def collect(obj, key):
    out = []

    def eat(raw):
        if isinstance(raw, str) and raw.strip():
            out.append(raw.strip())
        elif isinstance(raw, list):
            for x in raw:
                eat(x)

    eat(obj.get(key))
    if key == "matches":
        eat(obj.get("match"))
    seen = set()
    uniq = []
    for x in out:
        if x not in seen:
            seen.add(x)
            uniq.append(x)
    return uniq


def delay_of(*objs):
    for o in objs:
        if not isinstance(o, dict):
            continue
        n = o.get("actionDelay") or o.get("preDelay") or o.get("delay") or 0
        if n:
            return min(int(n), 5000)
    return 0


def pick(g):
    any_m = []
    and_m = []
    act = join_act(g.get("activityIds"))
    exclude_act = join_act(g.get("excludeActivityIds"))
    exclude_match = ""
    action = g.get("action") or "click"
    delay = delay_of(g)
    match_time = int(g.get("matchTime") or 0)
    action_max = int(g.get("actionMaximum") or 0)
    reset = g.get("resetMatch") or ""
    raw = g.get("rules")
    hit = False
    if isinstance(raw, str) and raw.strip():
        any_m.append(raw.strip())
        hit = True
    elif isinstance(raw, list):
        for it in raw:
            if isinstance(it, str) and it.strip():
                any_m.append(it.strip())
                hit = True
            elif isinstance(it, dict):
                or_parts = collect(it, "anyMatches")
                and_parts = collect(it, "matches")
                if or_parts or and_parts:
                    any_m.extend(or_parts)
                    if not or_parts and len(and_parts) >= 2:
                        and_m.extend(and_parts)
                    else:
                        any_m.extend(and_parts)
                    act = join_act(it.get("activityIds")) or act
                    exclude_act = join_act(it.get("excludeActivityIds")) or exclude_act
                    ex = join_matches(it.get("excludeMatches"))
                    if ex:
                        exclude_match = "||".join(x for x in (exclude_match, ex) if x)
                    action = it.get("action") or action
                    d = delay_of(it, g)
                    if d:
                        delay = d
                    if it.get("matchTime"):
                        match_time = int(it["matchTime"])
                    if it.get("actionMaximum"):
                        action_max = int(it["actionMaximum"])
                    if it.get("resetMatch"):
                        reset = it["resetMatch"]
                    hit = True
    if not hit:
        any_m = collect(g, "anyMatches")
        and_parts = collect(g, "matches")
        if len(and_parts) >= 2 and not any_m:
            and_m = and_parts
        else:
            any_m.extend(and_parts)
        exclude_match = join_matches(g.get("excludeMatches"))
    seen = set()
    any_u = []
    for x in any_m:
        if x and x not in seen:
            seen.add(x)
            any_u.append(x)
    seen = set()
    and_u = []
    for x in and_m:
        if x and x not in seen:
            seen.add(x)
            and_u.append(x)
    return {
        "match": "||".join(any_u),
        "andMatch": "\n".join(and_u),
        "activity": act,
        "excludeActivity": exclude_act,
        "excludeMatch": exclude_match or join_matches(g.get("excludeMatches")),
        "action": action or "click",
        "delay": delay,
        "matchTime": match_time or int(g.get("matchTime") or 0),
        "actionMaximum": action_max or int(g.get("actionMaximum") or 0),
        "resetMatch": reset or (g.get("resetMatch") or ""),
    }


def pre_keys(it):
    raw = it.get("preKeys")
    if raw is None:
        return ""
    if isinstance(raw, (int, float)):
        return str(int(raw))
    if isinstance(raw, list):
        return ",".join(str(x) for x in raw if x is not None and str(x) != "")
    return str(raw)


def row(rid, pkg, app, g, rule_key=0, pre=""):
    gname = g.get("name") or "规则"
    p = pick(g)
    return {
        "id": rid,
        "pkg": pkg,
        "app": app,
        "group": gname,
        "match": p["match"],
        "andMatch": p["andMatch"],
        "activity": p["activity"],
        "excludeActivity": p["excludeActivity"],
        "excludeMatch": p["excludeMatch"],
        "action": p["action"],
        "delay": p["delay"],
        "matchTime": p["matchTime"],
        "actionMaximum": p["actionMaximum"],
        "resetMatch": p["resetMatch"],
        "disableIfGroup": g.get("disableIfAppGroupMatch") or "",
        "matchAnyApp": True if pkg != "*" else g.get("matchAnyApp", True),
        "matchLauncher": False if pkg != "*" else g.get("matchLauncher", False),
        "matchSystemApp": False if pkg != "*" else g.get("matchSystemApp", False),
        "denyApps": ",".join(
            a.get("id") for a in (g.get("apps") or [])
            if isinstance(a, dict) and a.get("id") and a.get("enable") is False
        ) if pkg == "*" else "",
        "allowApps": ",".join(
            a.get("id") for a in (g.get("apps") or [])
            if isinstance(a, dict) and a.get("id") and a.get("enable") is True
        ) if pkg == "*" else "",
        "ruleKey": rule_key,
        "preKeys": pre,
        "enabled": "开屏" in gname,
    }


def expand(pkg, name, g):
    raw = g.get("rules")
    if isinstance(raw, list) and any(isinstance(x, dict) for x in raw):
        out = []
        for i, it in enumerate(raw):
            if isinstance(it, str) and it.strip():
                one = dict(g)
                one["rules"] = it
                out.append(row(f"{pkg}:{g.get('key')}:{i}", pkg, name, one, i, ""))
            elif isinstance(it, dict):
                key = it.get("key", i)
                one = dict(g)
                one["rules"] = [it]
                out.append(row(f"{pkg}:{g.get('key')}:{key}", pkg, name, one, key, pre_keys(it)))
        if out:
            return out
    return [row(f"{pkg}:{g.get('key')}", pkg, name, g)]


def main():
    if not os.path.exists(RAW):
        import urllib.request
        print("download", URL)
        urllib.request.urlretrieve(URL, RAW)
    data = json5.loads(open(RAW, encoding="utf-8").read())
    rows = []
    for app in data.get("apps") or []:
        pkg = app.get("id") or ""
        name = app.get("name") or pkg
        for g in app.get("groups") or []:
            rows.extend(expand(pkg, name, g))
    for g in data.get("globalGroups") or []:
        rows.extend(expand("*", "全局", g))
    os.makedirs(os.path.dirname(OUT), exist_ok=True)
    with open(OUT, "w", encoding="utf-8") as f:
        json.dump({"format": 4, "version": data.get("version"), "rules": rows}, f, ensure_ascii=False)
    print("gkd rules", len(rows), "version", data.get("version"), "bytes", os.path.getsize(OUT))


if __name__ == "__main__":
    main()
