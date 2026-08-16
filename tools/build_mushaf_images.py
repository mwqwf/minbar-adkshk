#!/usr/bin/env python3
"""يبني صور المصحف الملوّن الرسمي (مجمع الملك فهد) للروايات الثلاث.

المصدر: ملفّات `.ai` الرسميّة المنشورة على archive.org — 604 ملفاً لكل رواية،
وهي في حقيقتها **مستندات PDF** (`%PDF-1.5`) فتُفتح مباشرةً بلا تحويل وسيط.

**لماذا PyMuPDF حصراً؟** جُرّب pdfium فسطّح الزخرفة (ضاع الإطار المسنَّن وشارة
الجزء/الحزب وتلوين علامات الآيات). PyMuPDF يعرض الصفحة كاملةً بزخرفتها.

**لماذا خارج المستودع؟** 1812 صورة ≈ مئات الميغابايت، والصور تُستضاف خارجاً
وتُجلب عند الطلب — فلا تدخل حزمة التطبيق أبداً.

السكربت **قابل لإعادة التشغيل**: ينزّل إن لم يجد الأرشيف في الكاش، ويتخطّى كل
صفحة أُنتجت سليمة من قبل. فمقاطعتُه لا تُضيّع عملاً.

التشغيل:  python tools/build_mushaf_images.py [hafs warsh qalon]
"""

import concurrent.futures as futures
import io
import os
import re
import shutil
import subprocess
import sys
import tempfile
import zipfile

TEMP = os.environ.get("TEMP") or tempfile.gettempdir()
CACHE = os.path.join(TEMP, "mushaf-cache")
OUT = os.path.join(TEMP, "mushaf-out")

# الروابط مُتحقَّق منها؛ الإذن المنشور من المجمع يبيح الاستعمال الحاسوبي المجاني.
SOURCES = {
    "hafs": "https://archive.org/download/1441-ai-hafs_202109/1441-AI-hafs.zip",
    "warsh": "https://archive.org/download/1442-ai-warsh/1442-ai-warsh.zip",
    "qalon": "https://archive.org/download/qaloon-vector/1443-qalon.zip",
}

# مصدر الملفّات يسمّي رواية قالون `qalon`، والتطبيق يسمّيها `qalun` (انظر
# `riwayat` في `index.jz`). فمجلّد الصور باسم التطبيق كي يبنيه الرابط مباشرةً.
ASSET_ID = {"hafs": "hafs", "warsh": "warsh", "qalon": "qalun"}

PAGES = 604
WIDTH = 1000          # عرض الصورة النهائيّة بالبكسل
QUALITY = 80          # جودة WebP — الموازنة بين وضوح الرسم وحجم التنزيل
MIN_BYTES = 10 * 1024  # أقل حجم مقبول؛ ما دونه دليل على تحويل فاشل
BATCH = 32             # صفحات الدفعة الواحدة — سقفٌ لاستهلاك الذاكرة


CHUNK = 8 * 1024 * 1024   # حجم القطعة في التنزيل المتوازي
SEGMENTS = 8              # عدد الاتصالات المتزامنة لكل أرشيف


def fetch(riwaya):
    """يُعيد مسار الأرشيف، منزِّلاً إيّاه على قطعٍ متوازية إن لم يكن في الكاش.

    **لماذا تنزيلٌ مقطَّع؟** archive.org يخنق الاتّصال الواحد (قِيس: ≈٢.٥ م.ب/د)،
    فنصفُ غيغابايت يستغرق ساعات. والخنق **لكل اتّصال** لا لكل عميل، فثمانية
    اتّصالات متوازية تضاعف الحصيلة أضعافاً.

    والقطع تُحفظ ملفّاتٍ مستقلّة ثم تُدمج: هكذا تكون المقاطعة بلا ثمن — كل قطعة
    تمّت تبقى، ولا يُعاد إلا الناقص. (والقطعة الأخيرة قد تكون ناقصة عند القطع،
    فتُحذف كل قطعة لم تبلغ حجمها المتوقَّع.)
    """
    import concurrent.futures as futures

    os.makedirs(CACHE, exist_ok=True)
    path = os.path.join(CACHE, f"{riwaya}.zip")
    if os.path.exists(path) and zipfile.is_zipfile(path):
        return path

    url = SOURCES[riwaya]
    total = int(subprocess.run(
        ["curl", "-sIL", url], capture_output=True, check=True
    ).stdout.decode("latin-1").lower().rsplit("content-length:", 1)[1].split()[0])

    parts = os.path.join(CACHE, f"{riwaya}.parts")
    os.makedirs(parts, exist_ok=True)
    count = (total + CHUNK - 1) // CHUNK

    def size_of(i):
        return min(CHUNK, total - i * CHUNK)

    def grab(i):
        dest = os.path.join(parts, f"{i:05d}")
        if os.path.exists(dest) and os.path.getsize(dest) == size_of(i):
            return 0
        start = i * CHUNK
        end = start + size_of(i) - 1
        for _ in range(5):
            subprocess.run(["curl", "-sL", "--retry", "3", "-r", f"{start}-{end}",
                            "-o", dest, url], check=False)
            if os.path.exists(dest) and os.path.getsize(dest) == size_of(i):
                return 1
        raise SystemExit(f"[{riwaya}] تعذّرت القطعة {i}")

    print(f"[{riwaya}] تنزيل {total // 1048576} م.ب على {count} قطعة", flush=True)
    done = 0
    with futures.ThreadPoolExecutor(max_workers=SEGMENTS) as pool:
        for got in pool.map(grab, range(count)):
            done += got
            if done and done % 10 == 0:
                print(f"[{riwaya}] {done} قطعة", flush=True)

    with open(path, "wb") as out:
        for i in range(count):
            with open(os.path.join(parts, f"{i:05d}"), "rb") as part:
                shutil.copyfileobj(part, out)
    if not zipfile.is_zipfile(path):
        raise SystemExit(f"[{riwaya}] الأرشيف المدموج تالف")
    shutil.rmtree(parts, ignore_errors=True)
    return path


def page_files(zf):
    """يرتّب ملفّات الصفحات 604 بالرقم المستخرَج من الاسم.

    أسماء الروايات الثلاث تختلف (مثل `001___Hafs39__DM.ai`)، فالاعتماد على أوّل
    عدد في اسم الملفّ أمتنُ من افتراض نمطٍ واحد.
    """
    entries = [n for n in zf.namelist()
               if n.lower().endswith(".ai") and not n.endswith("/")]
    keyed = []
    for name in entries:
        m = re.search(r"(\d{1,4})", os.path.basename(name))
        if m:
            keyed.append((int(m.group(1)), name))
    keyed.sort()
    if len(keyed) != PAGES:
        raise SystemExit(f"عدد الصفحات {len(keyed)} بدل {PAGES}")
    return keyed


def render(args):
    """يحوّل صفحة واحدة إلى WebP. يعمل في عمليّة مستقلّة (استيراد داخليّ لازم)."""
    import fitz
    from PIL import Image

    number, data, dest = args
    doc = fitz.open(stream=data, filetype="pdf")
    page = doc[0]
    zoom = WIDTH / page.rect.width          # التكبير يُشتقّ من العرض المطلوب
    pix = page.get_pixmap(matrix=fitz.Matrix(zoom, zoom), alpha=False)
    img = Image.open(io.BytesIO(pix.tobytes("ppm")))
    img.save(dest, "WEBP", quality=QUALITY, method=6)
    doc.close()
    return number, os.path.getsize(dest)


def build(riwaya):
    zip_path = fetch(riwaya)
    out_dir = os.path.join(OUT, ASSET_ID[riwaya])
    os.makedirs(out_dir, exist_ok=True)

    with zipfile.ZipFile(zip_path) as zf:
        keyed = page_files(zf)
        todo = [(n, name) for n, name in keyed
                if not (os.path.exists(os.path.join(out_dir, f"{n:03d}.webp"))
                        and os.path.getsize(os.path.join(out_dir, f"{n:03d}.webp")) >= MIN_BYTES)]

        print(f"[{riwaya}] {len(todo)} صفحة للتحويل", flush=True)
        done = 0
        # عمليّات لا خيوط: العرض عمل حسابيّ بحت يقيّده قفل بايثون العام.
        # ودفعاتٌ صغيرة لا قائمةٌ واحدة: بيانات الصفحات تُنقل بالنسخ إلى
        # العمليّات، وتحميل 604 صفحة دفعةً واحدة يعني مئات الميغابايت في
        # الذاكرة بلا داعٍ.
        with futures.ProcessPoolExecutor(max_workers=os.cpu_count()) as pool:
            for i in range(0, len(todo), BATCH):
                jobs = [(n, zf.read(name), os.path.join(out_dir, f"{n:03d}.webp"))
                        for n, name in todo[i:i + BATCH]]
                for _ in pool.map(render, jobs, chunksize=2):
                    done += 1
                print(f"[{riwaya}] {done}/{len(todo)}", flush=True)

    return verify(riwaya, out_dir)


def verify(riwaya, out_dir):
    """يفشل بصوت عالٍ بدل تسليم مجموعة ناقصة بصمت."""
    missing, tiny, total = [], [], 0
    for n in range(1, PAGES + 1):
        path = os.path.join(out_dir, f"{n:03d}.webp")
        if not os.path.exists(path):
            missing.append(n)
            continue
        size = os.path.getsize(path)
        total += size
        if size < MIN_BYTES:
            tiny.append(n)
    if missing or tiny:
        raise SystemExit(f"[{riwaya}] ناقصة: {missing[:10]} | صغيرة: {tiny[:10]}")
    print(f"✓ [{riwaya}] 604 صفحة | {total / 1048576:.1f} م.ب | "
          f"متوسّط {total // PAGES // 1024} ك.ب", flush=True)
    return total


def main():
    which = sys.argv[1:] or list(SOURCES)
    if not shutil.which("curl"):
        raise SystemExit("curl غير موجود")
    for riwaya in which:
        build(riwaya)


if __name__ == "__main__":
    main()
