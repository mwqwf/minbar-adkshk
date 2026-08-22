# -*- coding: utf-8 -*-
"""تحقّق سريع من سلامة app/src/main/assets/quran/index.jz بعد أي تعديل."""
import gzip, json, os, sys

ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
IDX = os.path.join(ROOT, "app", "src", "main", "assets", "quran", "index.jz")

def main():
    d = json.load(gzip.open(IDX, "rt", encoding="utf-8"))
    errs = []
    def chk(cond, msg):
        print(("  OK   " if cond else "  FAIL ") + msg)
        if not cond:
            errs.append(msg)

    print("بنية الفهرس:")
    chk(d["ayahCount"] == 6236, "ayahCount == 6236 (=%s)" % d["ayahCount"])
    chk(len(d["surahs"]) == 114, "surahs == 114 (=%d)" % len(d["surahs"]))
    chk(len(d["juzs"]) == 30, "juzs == 30 (=%d)" % len(d["juzs"]))
    chk(len(d["hizbs"]) == 60, "hizbs == 60 (=%d)" % len(d["hizbs"]))
    chk(len(d["pages"]) == 604, "pages == 604 (=%d)" % len(d["pages"]))

    riw = d["riwayat"]
    print("الروايات:")
    chk(riw[0]["id"] == "hafs", "riwayat[0].id == hafs")
    want = {"hafs": "text_hafs.jz", "warsh": "text_warsh.jz", "qalun": "text_qalun.jz"}
    chk({r["id"]: r["text"] for r in riw} == want, "حقول text كما هي")

    for r in riw:
        ids = [c["id"] for c in r["reciters"]]
        a = sum(1 for c in r["reciters"] if c["mode"] == "ayah")
        s = sum(1 for c in r["reciters"] if c["mode"] == "surah")
        print("  %-6s: %d قارئاً (ayah=%d, surah=%d)" % (r["id"], len(ids), a, s))
        chk(len(set(ids)) == len(ids), "%s: المعرّفات فريدة" % r["id"])
        chk(all(c["base"].endswith("/") for c in r["reciters"]), "%s: كل base ينتهي بـ/" % r["id"])
        chk(all(c["mode"] in ("ayah", "surah") for c in r["reciters"]), "%s: كل mode صالح" % r["id"])
        chk(all(c["base"].startswith("https://") for c in r["reciters"]), "%s: كل base بـhttps" % r["id"])
        # الحقل الاختياري files: إمّا غائب وإمّا ١١٤ اسماً مرمَّزاً بترتيب المصحف.
        withf = [c for c in r["reciters"] if "files" in c]
        chk(all(len(c["files"]) == 114 for c in withf),
            "%s: كل files فيه ١١٤ ملفاً (%d قارئاً)" % (r["id"], len(withf)))
        chk(all("%" in n or n.isascii() for c in withf for n in c["files"]),
            "%s: أسماء files مرمَّزة" % r["id"])

    print("حجم index.jz: %d بايت" % os.path.getsize(IDX))
    print("النتيجة:", "نجح" if not errs else "فشل (%d)" % len(errs))
    return 1 if errs else 0

sys.exit(main())
