#!/usr/bin/env python3
"""يولّد `assets/quran/mushaf.jz` — إحداثيات آيات المصحف المصوَّر.

المصدر: قاعدة `ayahinfo` من مشروع quran_android
    https://android.quran.com/data/databases/ayahinfo/ayahinfo_512.zip
جدولها الوحيد `glyphs(page_number, line_number, sura_number, ayah_number,
position, min_x, max_x, min_y, max_y)` — مستطيل **لكل كلمة** (88,246 صفاً).

**لماذا نُجمّع المستطيلات إلى سطر لكل آية؟** لأنّ الغرض تمييزُ الآية الجارية
لا تمييز الكلمة: مستطيل الكلمة يعني 88 ألف سجلّ (≈٢ م.ب) بلا فائدة تُرى،
ومستطيلاً واحداً لكل آية يعني إطاراً واحداً ضخماً يبتلع أسطراً ليست منها.
والوسط — مستطيل لكل سطر تمرّ به الآية — يُعطي تمييزاً مطابقاً للورقة في
13,766 سجلّاً فقط (119 ك.ب مضغوطة).

الإحداثيات تُطبَّع على 0..10000 لكلا المحورين، فتصلح لأي عرض صورة (الصور
كلّها بنسبة 1024:1656 الثابتة)، وتبقى أعداداً صحيحة أصغر من العشريات.

التشغيل:  python tools/build_mushaf_layout.py
"""

import gzip
import json
import os
import sqlite3
import subprocess
import sys
import tempfile
import zipfile

HERE = os.path.dirname(os.path.abspath(__file__))
ASSETS = os.path.join(HERE, os.pardir, "app", "src", "main", "assets", "quran")
INDEX = os.path.join(ASSETS, "index.jz")
DEST = os.path.join(ASSETS, "mushaf.jz")

SOURCE = "https://android.quran.com/data/databases/ayahinfo/ayahinfo_512.zip"
# أبعاد الصور التي حُسبت عليها القاعدة — التطبيع يجعلها غير مهمّة للعرض،
# لكنّها لازمة للقسمة هنا.
SRC_W, SRC_H = 512.0, 828.0
SCALE = 10000
PAGES = 604
AYAHS = 6236

# ⚠️ صفحتا الفاتحة وأوّل البقرة **مزاحتان في المصدر** بسطر كامل: إحداثياتهما
# تقع أسفل نصّها بمقدار ≈635/10000، فيُرسم الإطار على السطر التالي — وفي
# الفاتحة بالذات. قيس الانحراف بالارتباط المتقاطع بين ملفّ الحبر في الصورة
# وملفّ تغطية المستطيلات: صفحة 1 = ‎-630، صفحة 2 = ‎-640، وبقيّة الصفحات
# ‎-40..-70 وهو فرق الهوامش الطبيعيّ لا إزاحة (تحقّق بصريّ على 42 و300 و604).
# يُصحَّح **هنا في الأصل** لا في الشيفرة، كي يستفيد منه كل مستهلك للبيانات
# (التطبيق والموقع) ولا يبقى ترقيعٌ يُنسى في أحدهما.
Y_CORRECTION = {1: -635, 2: -635}


def fetch_db(work):
    """يُنزّل قاعدة الإحداثيات ويفكّها. `curl` لا `urllib`: الخادم يردّ 403
    لوكيل بايثون الافتراضي."""
    zip_path = os.path.join(work, "ayahinfo.zip")
    subprocess.run(["curl", "-sL", "-o", zip_path, SOURCE], check=True)
    with zipfile.ZipFile(zip_path) as z:
        name = next(n for n in z.namelist() if n.endswith(".db"))
        z.extract(name, work)
        return os.path.join(work, name)


def main():
    index = json.loads(gzip.open(INDEX, "rt", encoding="utf-8").read())
    starts = {s["n"]: s["start"] for s in index["surahs"]}

    with tempfile.TemporaryDirectory() as work:
        db = fetch_db(work)
        # الاتصال يُغلق صراحةً: ويندوز يقفل الملفّ ما دام مفتوحاً، فيفشل
        # حذف المجلّد المؤقّت عند الخروج ويسقط السكربت بعد إتمام عمله.
        connection = sqlite3.connect(db)
        try:
            rows = connection.execute(
                """select page_number, sura_number, ayah_number,
                          min(min_x), min(min_y), max(max_x), max(max_y)
                   from glyphs
                   group by page_number, sura_number, ayah_number, line_number
                   order by page_number, min(min_y)"""
            ).fetchall()
        finally:
            connection.close()

    pages = [[] for _ in range(PAGES + 1)]
    for page, sura, ayah, x1, y1, x2, y2 in rows:
        flat = starts[sura] + ayah - 1
        shift = Y_CORRECTION.get(page, 0)
        pages[page].append([
            flat,
            round(x1 / SRC_W * SCALE), round(y1 / SRC_H * SCALE) + shift,
            round(x2 / SRC_W * SCALE), round(y2 / SRC_H * SCALE) + shift,
        ])

    out = {"v": 1, "w": SCALE, "h": SCALE, "pages": [pages[p] for p in range(1, PAGES + 1)]}
    raw = json.dumps(out, separators=(",", ":")).encode()
    # الامتداد `.jz` لا `.gz`: أدوات بناء أندرويد تفكّ ضغط أصول `.gz` عند
    # التحزيم وتحذف اللاحقة، فيختفي الملفّ الذي يطلبه الكود.
    with gzip.open(DEST, "wb", compresslevel=9) as f:
        f.write(raw)

    # تحقّق يفشل بصوت عالٍ بدل أن يُسلّم أصلاً ناقصاً بصمت.
    check = json.loads(gzip.open(DEST, "rt", encoding="utf-8").read())
    covered = {r[0] for page in check["pages"] for r in page}
    problems = []
    if len(check["pages"]) != PAGES:
        problems.append(f"صفحات: {len(check['pages'])} بدل {PAGES}")
    if len(covered) != AYAHS:
        problems.append(f"آيات مغطّاة: {len(covered)} بدل {AYAHS}")
    if any(not (0 <= v <= SCALE) for page in check["pages"] for r in page for v in r[1:5]):
        problems.append("إحداثيات خارج المدى 0..10000")
    if problems:
        sys.exit("فشل التحقّق: " + "؛ ".join(problems))

    rects = sum(len(p) for p in check["pages"])
    print(f"✓ {DEST}")
    print(f"  {rects} مستطيلاً | {len(covered)} آية | {len(check['pages'])} صفحة")
    print(f"  خام {len(raw) // 1024} ك.ب ← مضغوط {os.path.getsize(DEST) // 1024} ك.ب")


if __name__ == "__main__":
    main()
