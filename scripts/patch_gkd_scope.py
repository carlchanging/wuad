import json

deny = json.load(open(r"F:\Cursor-Projects\vibepm-demo-agent\wuad-android\scripts\gkd-global-deny.json", encoding="utf-8"))
path = r"F:\Cursor-Projects\vibepm-demo-agent\wuad-android\app\src\main\assets\gkd-rules.json"
data = json.load(open(path, encoding="utf-8"))
data["format"] = 3
for r in data["rules"]:
    rid = r.get("id")
    if rid in deny:
        r["matchAnyApp"] = True
        r["matchLauncher"] = False
        r["matchSystemApp"] = False
        r["denyApps"] = ",".join(deny[rid]["deny"])
        r["allowApps"] = ",".join(deny[rid]["allow"])
    elif r.get("pkg") == "*":
        r["matchAnyApp"] = True
        r["matchLauncher"] = False
        r["matchSystemApp"] = False
json.dump(data, open(path, "w", encoding="utf-8"), ensure_ascii=False)
print("patched", path, "format", data["format"], "globals", [r["id"] for r in data["rules"] if r.get("pkg") == "*"])
