#!/usr/bin/env python3
"""يولّد `mushaf_<riwaya>.jz` — إحداثيات آيات المصحف الملوّن الرسمي للروايات الثلاث.

المصدر: <https://github.com/quranpedia/quran-svg> برخصة **CC0 1.0** — إحداثيات
مستخرَجة من ملفّات مجمع الملك فهد نفسها التي نعرض صورها، فالتطابق مضمون بالبناء
لا بالتقريب.

**كيف تُقرأ الإحداثيات؟** لكل صفحة ملفّ `json` فيه لكل آية `polygon`، وهو في
الواقع سلسلة مسارات فرعيّة (`M…Z`) كلٌّ منها **مستطيل شريط سطر** جاهز — لا نحتاج
تفكيك مضلّع معقّد، يكفي أخذ صندوق كل مسار فرعي. وهذا هو المطلوب بعينه: مستطيل
لكل شريط لا مستطيلٌ واحد يبتلع أسطراً ليست من الآية.

**فضاء الإحداثيات:** إحداثيات الـjson في فضاء الـSVG، ويربطها بفضاء الصفحة
(PDF) تحويلُ الجذر `matrix(a 0 0 d e f)` المكتوب في ملفّ الـSVG نفسه. فنقرأه من
الملفّ ولا نفترضه: صفحتا الفاتحة وأوّل البقرة لهما تحويل مختلف عن بقيّة المصحف
(إطارهما أصغر)، ولو افترضنا تحويلاً واحداً لانزاح الإطار في أخطر صفحتين.

**الترقيم — نقطة حرجة:**
* حفص: `ayahRef` = الفهرس المسطّح 0..6235 (نفس `text_*.jz`)، و`numbering":"flat"`.
* ورش وقالون: `ayahRef` = `سورة*1000 + آية` **بترقيم الرواية نفسها**
  (6213 و6214 آية)، و`"numbering":"riwaya"`.
  **لماذا لا نحاذيها على عدّ حفص؟** لأنّ المحاذاة القسريّة اجتهادٌ قد يُخطئ في
  كتاب الله، ولا فائدة تُرجى منها هنا: كل قرّاء ورش وقالون في التطبيق بنمط
  «سورة كاملة»، فتمييز الآية أثناء التلاوة لا ينطبق عليهما أصلاً، والإحداثيات
  عندهما للنقر والتنقّل فقط.

التشغيل:  python tools/build_mushaf_layout_v2.py [hafs warsh qalon]
المتطلّب: نسخة من مستودع quran-svg، ونسخة من أرشيفات الصفحات (للأبعاد الحقيقيّة).
"""

import gzip
import json
import os
import re
import subprocess
import sys
import tempfile
import zipfile

HERE = os.path.dirname(os.path.abspath(__file__))
ASSETS = os.path.join(HERE, os.pardir, "app", "src", "main", "assets", "quran")
INDEX = os.path.join(ASSETS, "index.jz")

TEMP = os.environ.get("TEMP") or tempfile.gettempdir()
CACHE = os.path.join(TEMP, "mushaf-cache")
SVG_REPO = os.path.join(TEMP, "quran-svg")
SVG_URL = "https://github.com/quranpedia/quran-svg.git"

RIWAYAT = ("hafs", "warsh", "qalon")

# مصدر الإحداثيات يسمّي رواية قالون `qalon`، والتطبيق يسمّيها `qalun`
# (انظر `riwayat` في `index.jz` و`text_qalun.jz`). فتُترجَم التسمية هنا مرّة
# واحدة: اسم الأصل يجب أن يطابق معرّف الرواية في التطبيق وإلا لم يجده.
ASSET_ID = {"hafs": "hafs", "warsh": "warsh", "qalon": "qalun"}
PAGES = 604
SCALE = 10000

# الأرشيفات نفسها التي يستعملها build_mushaf_images.py — نقرأ منها أبعاد الصفحة
# الحقيقيّة بدل افتراضها، لأنّ التطبيع لا يصحّ إلا بالنسبة إلى الصورة المعروضة.
IMAGE_ZIPS = {
    "hafs": "hafs.zip",
    "warsh": "warsh.zip",
    "qalon": "qalon.zip",
}

MATRIX_RE = re.compile(r"matrix\(\s*([-\d.]+)[\s,]+[-\d.]+[\s,]+[-\d.]+[\s,]+"
                       r"([-\d.]+)[\s,]+([-\d.]+)[\s,]+([-\d.]+)\s*\)")
NUMBER_RE = re.compile(r"-?\d+(?:\.\d+)?")


def ensure_repo():
    """يجلب ملفّات الإحداثيات وحدها.

    **استنساخ متفرّق مقصود:** ملفّات الـSVG في المستودع نصف ميغابايت للصفحة
    (≈١.١ غ.ب للروايات الثلاث) ولا نحتاج منها إلا سطر التحويل في رأسها، بينما
    ملفّات الـjson كلّها ١٥ م.ب. فنجلب الـjson فقط، ونقرأ رأس الـSVG بطلب مدى
    عبر الشبكة (انظر [matrices]).
    """
    if os.path.isdir(os.path.join(SVG_REPO, "mushafs")):
        return
    subprocess.run(
        ["git", "clone", "--depth", "1", "--filter=blob:none", "--sparse",
         SVG_URL, SVG_REPO],
        check=True,
    )
    subprocess.run(
        ["git", "-C", SVG_REPO, "sparse-checkout", "set"]
        + [f"mushafs/{r}/kfqc/json" for r in RIWAYAT],
        check=True,
    )


RAW = "https://raw.githubusercontent.com/quranpedia/quran-svg/main/mushafs"


def matrices(riwaya):
    """تحويل الجذر لكل صفحة — يُقرأ من رأس ملفّ الـSVG بطلب مدى (Range).

    **لماذا لا نفترض تحويلاً واحداً؟** لأنّ صفحتَي الفاتحة وأوّل البقرة إطارهما
    أصغر وتحويلهما مختلف؛ ولو عمّمنا تحويل بقيّة المصحف لانزاح الإطار في أخطر
    صفحتين. والنتيجة تُخزَّن في الكاش فلا تُجلب إلا مرّة واحدة.
    """
    import concurrent.futures as futures

    os.makedirs(CACHE, exist_ok=True)
    cache = os.path.join(CACHE, f"matrices_{riwaya}.json")
    if os.path.exists(cache):
        with open(cache, encoding="utf-8") as fh:
            return {int(k): v for k, v in json.load(fh).items()}

    def fetch(page):
        url = f"{RAW}/{riwaya}/kfqc/svg/{page:03d}.svg"
        # المدى يكبر تدريجياً: التحويل في أوّل الملفّ عادةً، لكنّه يتأخّر في
        # بعض الصفحات خلف زخرفةٍ طويلة، فنوسّع بدل أن نجلب الملفّ كلّه.
        for size in (4096, 65536, 1048576):
            out = subprocess.run(
                ["curl", "-sL", "--retry", "3", "-r", f"0-{size}", url],
                capture_output=True,
            ).stdout.decode("utf-8", "ignore")
            m = MATRIX_RE.search(out)
            if m:
                return page, [float(g) for g in m.groups()]
        raise SystemExit(f"[{riwaya}] لا تحويل في صفحة {page}")

    found = {}
    with futures.ThreadPoolExecutor(max_workers=16) as pool:
        for page, value in pool.map(fetch, range(1, PAGES + 1)):
            found[page] = value
    with open(cache, "w", encoding="utf-8") as fh:
        json.dump(found, fh)
    return found


def page_sizes(riwaya):
    """أبعاد الصفحات الحقيقيّة من ملفّات `.ai` (وهي PDF).

    تُقرأ صفحةً صفحة لا مرّة واحدة: لو اختلف مقاس صفحة في المصحف عن أخواتها
    لانزاح إطارها وحدها، وهو عيبٌ يصعب اكتشافه لاحقاً.
    """
    import pymupdf

    path = os.path.join(CACHE, IMAGE_ZIPS[riwaya])
    sizes = {}
    with zipfile.ZipFile(path) as zf:
        entries = []
        for name in zf.namelist():
            if not name.lower().endswith(".ai"):
                continue
            m = re.search(r"(\d{1,4})", os.path.basename(name))
            if m:
                entries.append((int(m.group(1)), name))
        entries.sort()
        for number, name in entries:
            doc = pymupdf.open(stream=zf.read(name), filetype="pdf")
            rect = doc[0].rect
            sizes[number] = (rect.width, rect.height)
            doc.close()
    if len(sizes) != PAGES:
        raise SystemExit(f"[{riwaya}] أبعاد {len(sizes)} صفحة بدل {PAGES}")
    return sizes


def rects_of(polygon):
    """مستطيلات شرائط الأسطر لآية واحدة.

    للمصدر **صيغتان** لا واحدة، ولا بدّ من دعمهما معاً:

    1. `M…Z M…Z` — سلسلة مسارات فرعيّة كلٌّ منها مستطيل شريط جاهز (بقيّة
       المصحف). يكفي صندوق كل مسار فرعي.
    2. `x,y x,y …` — مضلّع واحد مغلق على هيئة سُلّم (صفحتا الفاتحة وأوّل
       البقرة). يُفكَّك بمسحٍ أفقيّ: قِس تقاطعات المضلّع عند وسط كل شريط بين
       إحداثيَي y متتاليين، فتُعطي امتداد الآية في ذلك الشريط بالضبط.
       (المضلّع قائم الزوايا، فالمسح في الوسط يصف الشريط كلّه بلا تقريب.)
    """
    if "M" in polygon:
        out = []
        for sub in polygon.split("M")[1:]:
            nums = [float(t) for t in NUMBER_RE.findall(sub)]
            if len(nums) < 6:                # أقلّ من ثلاث نقاط ليس شكلاً
                continue
            xs, ys = nums[0::2], nums[1::2]
            out.append((min(xs), min(ys), max(xs), max(ys)))
        return out

    nums = [float(t) for t in NUMBER_RE.findall(polygon)]
    points = list(zip(nums[0::2], nums[1::2]))
    if len(points) < 3:
        return []
    edges = list(zip(points, points[1:] + points[:1]))
    levels = sorted({y for _, y in points})

    out = []
    for top, bottom in zip(levels, levels[1:]):
        middle = (top + bottom) / 2
        crossings = []
        for (x1, y1), (x2, y2) in edges:
            if (y1 <= middle < y2) or (y2 <= middle < y1):
                crossings.append(x1 + (middle - y1) * (x2 - x1) / (y2 - y1))
        crossings.sort()
        for left, right in zip(crossings[0::2], crossings[1::2]):
            if right > left:
                out.append((left, top, right, bottom))

    # دمج الشرائط المتتالية المتطابقة أفقياً: تقسيمُ سطرٍ واحد إلى شريحتين
    # بسبب رأسٍ في مضلّع مجاور تضخيمٌ للملفّ بلا فائدة تُرى.
    merged = []
    for left, top, right, bottom in out:
        if merged and merged[-1][0] == left and merged[-1][2] == right \
                and abs(merged[-1][3] - top) < 1e-6:
            merged[-1][3] = bottom
        else:
            merged.append([left, top, right, bottom])
    return [tuple(r) for r in merged]


def flat_starts():
    """بداية كل سورة في الفهرس المسطّح — لترميز حفص كما في `mushaf.jz` القائم."""
    index = json.loads(gzip.open(INDEX, "rt", encoding="utf-8").read())
    return {s["n"]: s["start"] for s in index["surahs"]}


def build(riwaya):
    starts = flat_starts() if riwaya == "hafs" else None
    sizes = page_sizes(riwaya)
    transforms = matrices(riwaya)
    json_dir = os.path.join(SVG_REPO, "mushafs", riwaya, "kfqc", "json")

    pages = []
    for page in range(1, PAGES + 1):
        width, height = sizes[page]
        a, d, e, f = transforms[page]
        # عكس التحويل: من فضاء الـSVG إلى فضاء الصفحة، ثم تطبيع بأصلٍ أعلى يسار.
        # (d سالبة لأنّ محور PDF الرأسي يصعد، فالعكس يقلبه إلى النزول.)
        with open(os.path.join(json_dir, f"{page:03d}.json"), encoding="utf-8") as fh:
            ayat = json.load(fh)

        records = []
        for ayah in ayat:
            surah, number = int(ayah["surahNumber"]), int(ayah["ayahNumber"])
            ref = (starts[surah] + number - 1) if starts else surah * 1000 + number
            for X1, Y1, X2, Y2 in rects_of(ayah["polygon"]):
                px1, px2 = (X1 - e) / a, (X2 - e) / a
                # نقطتا Y تنقلبان بالعكس، فيُعاد ترتيبهما بعد التحويل.
                py1, py2 = (Y1 - f) / d, (Y2 - f) / d
                top, bottom = min(py1, py2), max(py1, py2)
                rect = [
                    ref,
                    round(px1 / width * SCALE),
                    round((height - bottom) / height * SCALE),
                    round(px2 / width * SCALE),
                    round((height - top) / height * SCALE),
                ]
                # القصّ على حدود الصفحة: بعض المسارات تتجاوزها بكسرٍ من النقطة،
                # والمستهلك يفترض المدى 0..10000 فيقع خارج الصورة لو مرّ.
                for i in (1, 2, 3, 4):
                    rect[i] = max(0, min(SCALE, rect[i]))
                if rect[3] > rect[1] and rect[4] > rect[2]:
                    records.append(rect)
        records.sort(key=lambda r: (r[2], r[1]))   # من أعلى الصفحة إلى أسفلها
        pages.append(records)

    # ⚠️ بعد هذا السكربت شغّل `tools/crop_mushaf_margins.py` مرّة واحدة: يقصّ
    # الهوامش البيضاء من الصور ويعيد تطبيع هذه الإحداثيات على الصندوق المقصوص
    # ويضيف `pw`/`ph`. (غير قابل للتكرار — وجود `pw` دليل أنّه نُفِّذ.)
    numbering = "flat" if riwaya == "hafs" else "riwaya"
    out = {"v": 1, "w": SCALE, "h": SCALE, "numbering": numbering, "pages": pages}
    raw = json.dumps(out, separators=(",", ":")).encode()
    dest = os.path.join(ASSETS, f"mushaf_{ASSET_ID[riwaya]}.jz")
    # ⛔ الامتداد `.jz` لا `.gz`: أدوات بناء أندرويد تفكّ ضغط أصول `.gz` عند
    # التحزيم وتحذف اللاحقة، فيختفي الملفّ الذي يطلبه الكود.
    with gzip.open(dest, "wb", compresslevel=9) as fh:
        fh.write(raw)

    verify(dest, riwaya)
    return dest


def verify(dest, riwaya):
    """يفشل بصوت عالٍ بدل تسليم أصلٍ مختلّ بصمت."""
    data = json.loads(gzip.open(dest, "rt", encoding="utf-8").read())
    problems = []
    if len(data["pages"]) != PAGES:
        problems.append(f"صفحات: {len(data['pages'])}")
    if any(not (0 <= v <= SCALE) for p in data["pages"] for r in p for v in r[1:5]):
        problems.append("إحداثيات خارج المدى")
    empty = [i + 1 for i, p in enumerate(data["pages"]) if not p]
    if empty:
        problems.append(f"صفحات فارغة: {empty[:10]}")
    if problems:
        raise SystemExit(f"[{riwaya}] فشل التحقّق: " + "؛ ".join(problems))
    rects = sum(len(p) for p in data["pages"])
    ayat = len({r[0] for p in data["pages"] for r in p})
    print(f"✓ {dest}\n  {rects} مستطيلاً | {ayat} آية | ترقيم {data['numbering']}"
          f" | {os.path.getsize(dest) // 1024} ك.ب", flush=True)


def main():
    ensure_repo()
    for riwaya in (sys.argv[1:] or RIWAYAT):
        build(riwaya)


if __name__ == "__main__":
    main()
