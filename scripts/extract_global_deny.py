import json
import re

raw_path = r"F:\Cursor-Projects\vibepm-demo-agent\wuad-android\scripts\gkd-raw.json5"
out_path = r"F:\Cursor-Projects\vibepm-demo-agent\wuad-android\scripts\gkd-global-deny.json"
text = open(raw_path, encoding="utf-8").read()
def apps_of(name):
    start = text.find("name:'%s'" % name)
    if start < 0:
        raise SystemExit(name + " not found")
    apps_at = text.find("apps:[", start)
    if apps_at < 0:
        raise SystemExit(name + " apps not found")
    lb = text.find("[", apps_at)
    depth = 0
    end = -1
    for i, c in enumerate(text[lb:], lb):
        if c == "[":
            depth += 1
        elif c == "]":
            depth -= 1
            if depth == 0:
                end = i + 1
                break
    if end < 0:
        raise SystemExit(name + " apps unclosed")
    items = re.findall(r"\{id:'([^']+)',enable:(true|false)\}", text[lb:end])
    return {
        "deny": [i for i, e in items if e == "false"],
        "allow": [i for i, e in items if e == "true"],
    }

groups = {
    "*:0": apps_of("开屏广告-全局"),
    "*:1": apps_of("更新提示-全局"),
    "*:2": apps_of("青少年模式-全局"),
}
for k, v in groups.items():
    print(k, "deny", len(v["deny"]), "allow", len(v["allow"]))
json.dump(groups, open(out_path, "w", encoding="utf-8"), ensure_ascii=False)
print("wrote", out_path)
