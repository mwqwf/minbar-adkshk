#!/usr/bin/env python3
"""يخطّط حزم أصول Play (on-demand) من كتالوج minbar-api:
- core: الدروس الأكثر استماعاً حتى 480 م.ب (حدّ Play للحزمة 512 م.ب).
- rest: الدفعة التالية حتى 492 م.ب.
- more: بقيّة المكتبة حتى 492 م.ب.
الناتج: asset-packs/manifest.json — {packs:{core:[...], rest:[...], more:[...]}}.
لا يُنزَّل هنا شيء؛ التنزيل في fetch.py (يجري في CI حيث الشبكة مجانية).
"""
import json, os, sys, urllib.request

API = os.environ.get("MINBAR_API", "https://minbar-api.mushafak.workers.dev")
CAP_CORE = int(os.environ.get("CORE_CAP_MB", "480")) * 1024 * 1024
CAP_REST = int(os.environ.get("REST_CAP_MB", "492")) * 1024 * 1024
CAP_MORE = int(os.environ.get("MORE_CAP_MB", "492")) * 1024 * 1024
OUT = os.path.join(os.path.dirname(__file__), "..", "..", "asset-packs", "manifest.json")

req = urllib.request.Request(API + "/v1/catalog", headers={"User-Agent": "Mozilla/5.0 minbar-assetpack"})
cat = json.load(urllib.request.urlopen(req, timeout=120))
lessons = [l for l in cat["lessons"] if l.get("sha256") and l.get("sizeBytes") and l.get("audioStatus", "ready") == "ready"]
lessons.sort(key=lambda l: (-(l.get("views") or 0), -(l.get("createdAtMs") or 0)))

core, rest, more = [], [], []
used_core = used_rest = used_more = 0
seen = set()
for l in lessons:
    sha = l["sha256"]
    if sha in seen:  # دروس تتشارك الصوت نفسه — ملفّ واحد يكفي
        continue
    seen.add(sha)
    entry = {"id": l["id"], "sha": sha, "size": int(l["sizeBytes"]), "url": l["audioUrl"]}
    if used_core + entry["size"] <= CAP_CORE:
        core.append(entry); used_core += entry["size"]
    elif used_rest + entry["size"] <= CAP_REST:
        rest.append(entry); used_rest += entry["size"]
    elif used_more + entry["size"] <= CAP_MORE:
        more.append(entry); used_more += entry["size"]
    else:
        print("⛔ تجاوزت المكتبة سعة الحزم الثلاث (480+492+492 م.ب)", file=sys.stderr)
        sys.exit(2)

os.makedirs(os.path.dirname(OUT), exist_ok=True)
manifest = {
    "generatedAt": cat.get("generatedAt"),
    "coreBytes": used_core,
    "restBytes": used_rest,
    "moreBytes": used_more,
    "packs": {"core": core, "rest": rest, "more": more},
}
json.dump(manifest, open(OUT, "w", encoding="utf-8"), ensure_ascii=False, indent=1)
print(
    f"core={len(core)} files/{used_core/1e6:.1f}MB  "
    f"rest={len(rest)} files/{used_rest/1e6:.1f}MB  "
    f"more={len(more)} files/{used_more/1e6:.1f}MB -> {OUT}"
)
