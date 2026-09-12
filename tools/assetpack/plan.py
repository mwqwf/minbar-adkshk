#!/usr/bin/env python3
"""يخطّط حزمتي أصول Play (fast-follow) من كتالوج minbar-api:
- core: الدروس الأكثر استماعاً حتى 480 م.ب (حدّ Play لحزمة fast-follow 512 م.ب).
- rest: بقيّة المكتبة (تنزل بعد core تلقائياً).
الناتج: asset-packs/manifest.json — {packs:{core:[{id,sha,size,url}], rest:[...]}}.
لا يُنزَّل هنا شيء؛ التنزيل في fetch.py (يجري في CI حيث الشبكة مجانية).
"""
import json, os, sys, urllib.request

API = os.environ.get("MINBAR_API", "https://minbar-api.mushafak.workers.dev")
CAP = int(os.environ.get("CORE_CAP_MB", "480")) * 1024 * 1024
OUT = os.path.join(os.path.dirname(__file__), "..", "..", "asset-packs", "manifest.json")

req = urllib.request.Request(API + "/v1/catalog", headers={"User-Agent": "Mozilla/5.0 minbar-assetpack"})
cat = json.load(urllib.request.urlopen(req, timeout=120))
lessons = [l for l in cat["lessons"] if l.get("sha256") and l.get("sizeBytes") and l.get("audioStatus", "ready") == "ready"]
lessons.sort(key=lambda l: (-(l.get("views") or 0), -(l.get("createdAtMs") or 0)))

core, rest, total = [], [], 0
seen = set()
for l in lessons:
    sha = l["sha256"]
    if sha in seen:  # دروس تتشارك الصوت نفسه — ملفّ واحد يكفي
        continue
    seen.add(sha)
    entry = {"id": l["id"], "sha": sha, "size": int(l["sizeBytes"]), "url": l["audioUrl"]}
    if total + entry["size"] <= CAP:
        core.append(entry); total += entry["size"]
    else:
        rest.append(entry)

os.makedirs(os.path.dirname(OUT), exist_ok=True)
manifest = {"generatedAt": cat.get("generatedAt"), "coreBytes": total, "restBytes": sum(e["size"] for e in rest),
            "packs": {"core": core, "rest": rest}}
json.dump(manifest, open(OUT, "w", encoding="utf-8"), ensure_ascii=False, indent=1)
print(f"core={len(core)} files/{total/1e6:.1f}MB  rest={len(rest)} files/{manifest['restBytes']/1e6:.1f}MB -> {OUT}")
if manifest["restBytes"] > 500 * 1024 * 1024:
    print("⚠️ rest تجاوزت 500 م.ب — تحتاج حزمة ثالثة", file=sys.stderr); sys.exit(2)
