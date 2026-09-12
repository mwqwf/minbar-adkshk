#!/usr/bin/env python3
"""ينزّل ملفّات الحزمتين من R2 إلى مجلّدات وحدات الأصول (يتخطّى الموجود بحجمه الصحيح).
يُشغَّل في CI (شبكة مجانية) — وعلى جهاز المالك فقط بطلب صريح (≈600 م.ب).
"""
import hashlib, json, os, sys, urllib.request, concurrent.futures

ROOT = os.path.join(os.path.dirname(__file__), "..", "..")
MAN = json.load(open(os.path.join(ROOT, "asset-packs", "manifest.json"), encoding="utf-8"))
UA = {"User-Agent": "Mozilla/5.0 minbar-assetpack"}
VERIFY = os.environ.get("VERIFY_SHA", "1") == "1"

def target(pack, sha):
    d = os.path.join(ROOT, f"{pack}_pack", "src", "main", "assets", "serving")
    os.makedirs(d, exist_ok=True)
    return os.path.join(d, f"{sha}.ogg")

def one(pack, e):
    path = target(pack, e["sha"])
    if os.path.isfile(path) and os.path.getsize(path) == e["size"]:
        return "skip"
    for attempt in range(3):
        try:
            with urllib.request.urlopen(urllib.request.Request(e["url"], headers=UA), timeout=300) as r, open(path + ".part", "wb") as f:
                h = hashlib.sha256()
                while True:
                    chunk = r.read(1 << 16)
                    if not chunk: break
                    f.write(chunk); h.update(chunk)
            if os.path.getsize(path + ".part") != e["size"]:
                raise IOError("size mismatch")
            if VERIFY and h.hexdigest() != e["sha"]:
                raise IOError("sha mismatch")
            os.replace(path + ".part", path)
            return "ok"
        except Exception as ex:  # noqa
            err = ex
    return f"FAIL {e['sha'][:8]} {err}"

results = {"ok": 0, "skip": 0, "fail": []}
with concurrent.futures.ThreadPoolExecutor(8) as ex:
    futs = [ex.submit(one, pack, e) for pack, items in MAN["packs"].items() for e in items]
    for f in futs:
        r = f.result()
        if r.startswith("FAIL"): results["fail"].append(r)
        else: results[r] += 1
print(results["ok"], "downloaded,", results["skip"], "skipped,", len(results["fail"]), "failed")
for f in results["fail"]: print(f, file=sys.stderr)
sys.exit(1 if results["fail"] else 0)
