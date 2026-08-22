# -*- coding: utf-8 -*-
"""
build_quran_assets.py
=====================
يولّد حزمة البيانات القرآنية المحليّة (offline) لتطبيق «منبر ادكصهك».

المخرجات (في app/src/main/assets/quran/):
    index.jz        الفهارس + قائمة الروايات والقرّاء
    text_hafs.jz    6236 نصّاً — حفص عن عاصم (الرسم العثماني، Tanzil)
    text_warsh.jz   6236 نصّاً — ورش عن نافع (Quranpedia mushaf 4)
    text_qalun.jz   6236 نصّاً — قالون عن نافع (Quranpedia mushaf 7)

الاستعمال:
    python tools/build_quran_assets.py            # يُنزّل ما ينقص ثم يبني
    python tools/build_quran_assets.py --offline  # يبني من الكاش فقط

الكاش الافتراضي: tools/.quran_cache/
"""

import argparse
import concurrent.futures
import difflib
import gzip
import json
import os
import re
import sys
import unicodedata
import urllib.request

HERE = os.path.dirname(os.path.abspath(__file__))
CACHE = os.path.join(HERE, ".quran_cache")
OUT = os.path.abspath(os.path.join(HERE, "..", "app", "src", "main", "assets", "quran"))

UA = {"User-Agent": "Mozilla/5.0 (Windows NT 10.0; Win64; x64)"}

HAFS_API = "https://api.alquran.cloud/v1/quran/quran-uthmani"
QP_SURAH = "https://quranpedia.net/surah/{mushaf}/{surah}.md"

# معرّفات مصاحف Quranpedia
QP_HAFS, QP_WARSH, QP_QALUN = 1, 4, 7

AYAH_TOTAL = 6236

OFFLINE = False


# ------------------------------------------------------------------ الشبكة
def fetch(url, path, timeout=90, tries=3):
    if os.path.exists(path) and os.path.getsize(path) > 400:
        return open(path, "rb").read()
    if OFFLINE:
        raise RuntimeError("missing from cache (--offline): " + url)
    last = None
    for _ in range(tries):
        try:
            data = urllib.request.urlopen(
                urllib.request.Request(url, headers=UA), timeout=timeout
            ).read()
            os.makedirs(os.path.dirname(path), exist_ok=True)
            open(path, "wb").write(data)
            return data
        except Exception as e:  # noqa: BLE001
            last = e
    raise RuntimeError("fetch failed %s: %s" % (url, last))


def download_all():
    os.makedirs(CACHE, exist_ok=True)
    fetch(HAFS_API, os.path.join(CACHE, "hafs_tanzil.json"), timeout=240)
    jobs = [
        (m, s)
        for m in (QP_HAFS, QP_WARSH, QP_QALUN)
        for s in range(1, 115)
    ]

    def one(job):
        m, s = job
        return fetch(
            QP_SURAH.format(mushaf=m, surah=s),
            os.path.join(CACHE, "qp", "%d_%d.md" % (m, s)),
        )

    with concurrent.futures.ThreadPoolExecutor(8) as ex:
        list(ex.map(one, jobs))


# ------------------------------------------------------------------ التحليل
VERSE_RE = re.compile(r"^> (.*?) \[(\d+):(\d+)\]\s*$", re.M)
TITLE_RE = re.compile(r'^title: "(.*)"', re.M)


def clean(s):
    """يزيل BOM وعلامات الاتّجاه والمسافات الزائدة."""
    s = s.replace("﻿", "").replace("‏", "").replace("‎", "")
    return re.sub(r"\s+", " ", s).strip()


def qp_surah(mushaf, surah):
    txt = open(
        os.path.join(CACHE, "qp", "%d_%d.md" % (mushaf, surah)), encoding="utf-8"
    ).read()
    return [clean(m[0]) for m in VERSE_RE.findall(txt)]


def qp_title(mushaf, surah):
    txt = open(
        os.path.join(CACHE, "qp", "%d_%d.md" % (mushaf, surah)), encoding="utf-8"
    ).read()
    t = clean(TITLE_RE.search(txt).group(1))
    return t[len("سورة ") :] if t.startswith("سورة ") else t


# البسملة بعد التطبيع (norm_word) — تُستعمل لفصلها عن أول آية في كل سورة
BASMALA_NORM = ["بسم", "الله", "الرحمن", "الرحيم"]


def load_hafs():
    """يعيد (verses[6236], meta[6236]) من Tanzil عبر alquran.cloud."""
    data = json.load(
        open(os.path.join(CACHE, "hafs_tanzil.json"), encoding="utf-8")
    )["data"]["surahs"]
    verses, meta = [], []
    for s in data:
        for a in s["ayahs"]:
            t = clean(a["text"])
            # الواجهة تُلحق البسملة بأول آية من كل سورة عدا الفاتحة والتوبة
            if a["numberInSurah"] == 1 and s["number"] not in (1, 9):
                w = t.split()
                if [norm_word(x) for x in w[:4]] == BASMALA_NORM:
                    t = " ".join(w[4:]).strip()
                else:
                    raise RuntimeError("basmala prefix not found in %d:1" % s["number"])
            verses.append(t)
            meta.append(
                {
                    "surah": s["number"],
                    "ayah": a["numberInSurah"],
                    "juz": a["juz"],
                    "page": a["page"],
                    "hizb": (a["hizbQuarter"] + 3) // 4,
                    "quarter": a["hizbQuarter"],
                }
            )
    assert len(verses) == AYAH_TOTAL, len(verses)
    return verses, meta, data


# ------------------------------------------------ محاذاة الروايات على إطار حفص
DIAC = "".join(
    chr(c)
    for c in list(range(0x0610, 0x061B))
    + list(range(0x064B, 0x0660))
    + list(range(0x06D6, 0x06ED + 1))
    + [0x0640]
)
TRANS = {ord(c): None for c in DIAC}


def norm_word(w):
    """تطبيع قوي: حذف كل التشكيل والعلامات وتوحيد الألف/الياء/الهاء."""
    w = unicodedata.normalize("NFKD", w).translate(TRANS)
    w = (
        w.replace("أ", "ا")
        .replace("إ", "ا")
        .replace("آ", "ا")
        .replace("ٱ", "ا")
        .replace("ى", "ي")
        .replace("ة", "ه")
        .replace("ء", "")
        .replace("ؤ", "و")
        .replace("ئ", "ي")
    )
    return re.sub(r"[^ء-ي]", "", w)


def align_to_hafs(hafs_ayahs, riw_ayahs):
    """يعيد قائمة نصوص بطول len(hafs_ayahs) مقسّمة من نصّ الرواية عند حدود حفص."""
    hw = [w for a in hafs_ayahs for w in a.split()]
    hb = []
    n = 0
    for a in hafs_ayahs:
        n += len(a.split())
        hb.append(n)
    rw = [w for a in riw_ayahs for w in a.split()]

    hn = [norm_word(w) for w in hw]
    rn = [norm_word(w) for w in rw]

    sm = difflib.SequenceMatcher(None, hn, rn, autojunk=False)
    ops = sm.get_opcodes()

    def map_pos(p):
        for tag, i1, i2, j1, j2 in ops:
            if i1 <= p < i2 or (p == i2 == len(hn)):
                if tag == "equal":
                    return j1 + (p - i1)
                if i2 == i1:
                    return j1
                # تناسبيّ داخل كتلة الاختلاف
                return j1 + round((j2 - j1) * (p - i1) / (i2 - i1))
        return len(rn)

    bounds = [0] + [map_pos(b) for b in hb]
    bounds[-1] = len(rw)
    for i in range(1, len(bounds)):
        if bounds[i] < bounds[i - 1]:
            bounds[i] = bounds[i - 1]
    return [" ".join(rw[bounds[i] : bounds[i + 1]]) for i in range(len(hafs_ayahs))]


def build_riwaya(mushaf, hafs_verses, hafs_meta):
    """يبني 6236 نصّاً لرواية Quranpedia معيّنة، مُحاذاة على ترقيم حفص."""
    # بسملة الرواية مستخرَجة حرفياً من آية النمل 27:30
    naml = qp_surah(mushaf, 27)[29]
    i = naml.find("بِسْمِ")
    assert i > 0, "basmala not found in 27:30"
    basmala = naml[i:].strip()

    out = []
    warnings = []
    by_surah = {}
    for idx, m in enumerate(hafs_meta):
        by_surah.setdefault(m["surah"], []).append(idx)

    for s in range(1, 115):
        idxs = by_surah[s]
        hafs_ayahs = [hafs_verses[i] for i in idxs]
        riw = qp_surah(mushaf, s)
        if s == 1:
            # مصاحف ورش/قالون لا تعدّ البسملة آية في الفاتحة — نُعيدها لإطار حفص
            riw = [basmala] + riw
        # المحاذاة إلزاميّة دائماً: حتى حين يتساوى العدد قد تختلف مواضع الفصل
        # (مثال: آل عمران 200 آية في الروايتين لكن بحدود مختلفة).
        res = align_to_hafs(hafs_ayahs, riw)
        for k, t in enumerate(res):
            if not t.strip():
                warnings.append("%d:%d فارغة" % (s, k + 1))
        out.extend(res)
    assert len(out) == AYAH_TOTAL, len(out)
    return out, basmala, warnings


# ------------------------------------------------------------------ الفهارس
def build_index(hafs_meta, hafs_api_surahs, riwayat):
    surahs = []
    start = 0
    for s in hafs_api_surahs:
        n = s["number"]
        cnt = len(s["ayahs"])
        surahs.append(
            {
                "n": n,
                "name": qp_title(QP_HAFS, n),
                "ayahs": cnt,
                "place": "makki" if s["revelationType"] == "Meccan" else "madani",
                "start": start,
                "page": hafs_meta[start]["page"],
            }
        )
        start += cnt

    def firsts(key, total):
        seen, res = {}, []
        for i, m in enumerate(hafs_meta):
            v = m[key]
            if v not in seen:
                seen[v] = i
        for k in range(1, total + 1):
            res.append({"n": k, "start": seen[k]})
        return res

    return {
        "version": 1,
        "ayahCount": AYAH_TOTAL,
        "surahs": surahs,
        "juzs": firsts("juz", 30),
        "hizbs": firsts("hizb", 60),
        "pages": firsts("page", 604),
        "riwayat": riwayat,
    }


# قائمة الملفّات لقارئ الأرشيف (أسماء عربيّة غير قياسيّة، مرمَّزة مسبقاً).
# مولَّدة من https://archive.org/metadata/20231006_20231006_1805 ومحفوظة
# بجانب هذه الأداة حتى تبقى إعادة التوليد ممكنة بلا شبكة.
LAGHDAF_FILES = json.load(
    open(os.path.join(os.path.dirname(os.path.abspath(__file__)), "laghdaf_files.json"),
         encoding="utf-8")
)

RIWAYAT = [
    {
        "id": "hafs",
        "name": "حفص عن عاصم",
        "text": "text_hafs.jz",
        "reciters": [
            {"id": "husary", "name": "محمود خليل الحصري",
             "base": "https://everyayah.com/data/Husary_128kbps/", "mode": "ayah"},
            {"id": "minshawi", "name": "محمد صديق المنشاوي",
             "base": "https://everyayah.com/data/Minshawy_Murattal_128kbps/", "mode": "ayah"},
            {"id": "abdulbasit", "name": "عبد الباسط عبد الصمد (مرتل)",
             "base": "https://everyayah.com/data/Abdul_Basit_Murattal_192kbps/", "mode": "ayah"},
            {"id": "alafasy", "name": "مشاري راشد العفاسي",
             "base": "https://everyayah.com/data/Alafasy_128kbps/", "mode": "ayah"},
            {"id": "sudais", "name": "عبد الرحمن السديس",
             "base": "https://everyayah.com/data/Abdurrahmaan_As-Sudais_192kbps/", "mode": "ayah"},
            {"id": "matroud", "name": "عبد الله المطرود",
             "base": "https://everyayah.com/data/Abdullah_Matroud_128kbps/", "mode": "ayah"},
            {"id": "huthaify", "name": "علي بن عبد الرحمن الحذيفي",
             "base": "https://everyayah.com/data/Hudhaify_128kbps/", "mode": "ayah"},
            {"id": "maher", "name": "ماهر المعيقلي",
             "base": "https://everyayah.com/data/MaherAlMuaiqly128kbps/", "mode": "ayah"},
            {"id": "shuraim", "name": "سعود الشريم",
             "base": "https://everyayah.com/data/Saood_ash-Shuraym_128kbps/", "mode": "ayah"},
            {"id": "shatri", "name": "أبو بكر الشاطري",
             "base": "https://everyayah.com/data/Abu_Bakr_Ash-Shaatree_128kbps/", "mode": "ayah"},
            {"id": "ajamy", "name": "أحمد بن علي العجمي",
             "base": "https://everyayah.com/data/ahmed_ibn_ali_al_ajamy_128kbps/", "mode": "ayah"},
            {"id": "ghamadi", "name": "سعد الغامدي",
             "base": "https://everyayah.com/data/Ghamadi_40kbps/", "mode": "ayah"},
            {"id": "ayyoub", "name": "محمد أيوب",
             "base": "https://everyayah.com/data/Muhammad_Ayyoub_128kbps/", "mode": "ayah"},
            {"id": "jibreel", "name": "محمد جبريل",
             "base": "https://everyayah.com/data/Muhammad_Jibreel_128kbps/", "mode": "ayah"},
            {"id": "basfar", "name": "عبد الله بصفر",
             "base": "https://everyayah.com/data/Abdullah_Basfar_192kbps/", "mode": "ayah"},
            {"id": "hani", "name": "هاني الرفاعي",
             "base": "https://everyayah.com/data/Hani_Rifai_192kbps/", "mode": "ayah"},
            {"id": "juhany", "name": "عبد الله عواد الجهني",
             "base": "https://everyayah.com/data/Abdullaah_3awwaad_Al-Juhaynee_128kbps/", "mode": "ayah"},
            {"id": "qasim", "name": "محسن القاسم",
             "base": "https://everyayah.com/data/Muhsin_Al_Qasim_192kbps/", "mode": "ayah"},
            {"id": "dossari", "name": "ياسر الدوسري",
             "base": "https://everyayah.com/data/Yasser_Ad-Dussary_128kbps/", "mode": "ayah"},
            {"id": "qatami", "name": "ناصر القطامي",
             "base": "https://everyayah.com/data/Nasser_Alqatami_128kbps/", "mode": "ayah"},
            {"id": "budair", "name": "صلاح البدير",
             "base": "https://everyayah.com/data/Salah_Al_Budair_128kbps/", "mode": "ayah"},
            {"id": "banna", "name": "محمود علي البنا",
             "base": "https://everyayah.com/data/mahmoud_ali_al_banna_32kbps/", "mode": "ayah"},
            {"id": "tablawi", "name": "محمد محمود الطبلاوي",
             "base": "https://everyayah.com/data/Mohammad_al_Tablaway_128kbps/", "mode": "ayah"},
            {"id": "abdulsamad_mujawwad", "name": "عبد الباسط عبد الصمد (مجود)",
             "base": "https://everyayah.com/data/Abdul_Basit_Mujawwad_128kbps/", "mode": "ayah"},
            {"id": "husary_mujawwad", "name": "محمود خليل الحصري (مجود)",
             "base": "https://everyayah.com/data/Husary_128kbps_Mujawwad/", "mode": "ayah"},
            {"id": "minshawi_mujawwad", "name": "محمد صديق المنشاوي (مجود)",
             "base": "https://everyayah.com/data/Minshawy_Mujawwad_192kbps/", "mode": "ayah"},
            {"id": "husary_muallim", "name": "محمود خليل الحصري (معلم)",
             "base": "https://everyayah.com/data/Husary_Muallim_128kbps/", "mode": "ayah"},
            {"id": "ali_jaber", "name": "علي جابر",
             "base": "https://everyayah.com/data/Ali_Jaber_64kbps/", "mode": "ayah"},
            {"id": "fares", "name": "فارس عباد",
             "base": "https://everyayah.com/data/Fares_Abbad_64kbps/", "mode": "ayah"},
            {"id": "bukhatir", "name": "صلاح بو خاطر",
             "base": "https://everyayah.com/data/Salaah_AbdulRahman_Bukhatir_128kbps/", "mode": "ayah"},
            {"id": "ayman_sowaid", "name": "أيمن سويد",
             "base": "https://everyayah.com/data/Ayman_Sowaid_64kbps/", "mode": "ayah"},
            {"id": "alili", "name": "عزيز عليلي",
             "base": "https://everyayah.com/data/aziz_alili_128kbps/", "mode": "ayah"},
            {"id": "tunaiji", "name": "خليفة الطنيجي",
             "base": "https://everyayah.com/data/khalefa_al_tunaiji_64kbps/", "mode": "ayah"},
            {"id": "yaser_salamah", "name": "ياسر سلامة",
             "base": "https://everyayah.com/data/Yaser_Salamah_128kbps/", "mode": "ayah"},
            {"id": "sahl_yassin", "name": "سهل ياسين",
             "base": "https://everyayah.com/data/Sahl_Yassin_128kbps/", "mode": "ayah"},
            {"id": "qahtani", "name": "خالد القحطاني",
             "base": "https://everyayah.com/data/Khaalid_Abdullaah_al-Qahtaanee_192kbps/", "mode": "ayah"},
            {"id": "neana", "name": "أحمد نعينع",
             "base": "https://everyayah.com/data/Ahmed_Neana_128kbps/", "mode": "ayah"},
            {"id": "suesy", "name": "علي حجاج السويسي",
             "base": "https://everyayah.com/data/Ali_Hajjaj_AlSuesy_128kbps/", "mode": "ayah"},
            {"id": "abdulkareem", "name": "محمد عبد الكريم",
             "base": "https://everyayah.com/data/Muhammad_AbdulKareem_128kbps/", "mode": "ayah"},
            {"id": "alaqimy", "name": "أكرم العلاقمي",
             "base": "https://everyayah.com/data/Akram_AlAlaqimy_128kbps/", "mode": "ayah"},
            {"id": "abdulsamad_qe", "name": "عبد الصمد",
             "base": "https://everyayah.com/data/AbdulSamad_64kbps_QuranExplorer.Com/", "mode": "ayah"},
            {"id": "ibrahim_akhdar", "name": "إبراهيم الأخضر (آية بآية)",
             "base": "https://everyayah.com/data/Ibrahim_Akhdar_32kbps/", "mode": "ayah"},
            {"id": "parhizgar", "name": "شهريار برهيزكار",
             "base": "https://everyayah.com/data/Parhizgar_48kbps/", "mode": "ayah"},
            {"id": "karim_mansoori", "name": "كريم منصوري",
             "base": "https://everyayah.com/data/Karim_Mansoori_40kbps/", "mode": "ayah"},
            {"id": "mustafa_ismail", "name": "مصطفى إسماعيل (آية بآية)",
             "base": "https://everyayah.com/data/Mustafa_Ismail_48kbps/", "mode": "ayah"},
            {"id": "abkar", "name": "إدريس أبكر",
             "base": "https://server6.mp3quran.net/abkr/", "mode": "surah"},
            {"id": "balilah", "name": "بندر بليلة",
             "base": "https://server6.mp3quran.net/balilah/", "mode": "surah"},
            {"id": "kurdi", "name": "رعد محمد الكردي",
             "base": "https://server6.mp3quran.net/kurdi/", "mode": "surah"},
            {"id": "jaleel", "name": "خالد الجليل",
             "base": "https://server10.mp3quran.net/jleel/", "mode": "surah"},
            {"id": "akdr", "name": "إبراهيم الأخضر",
             "base": "https://server6.mp3quran.net/akdr/", "mode": "surah"},
            {"id": "jormy", "name": "إبراهيم الجرمي",
             "base": "https://server11.mp3quran.net/jormy/", "mode": "surah"},
            {"id": "ibrahim_dosri", "name": "إبراهيم الدوسري",
             "base": "https://server10.mp3quran.net/ibrahim_dosri/Rewayat-Hafs-A-n-Assem/", "mode": "surah"},
            {"id": "3siri", "name": "إبراهيم العسيري",
             "base": "https://server6.mp3quran.net/3siri/", "mode": "surah"},
            {"id": "hawashi", "name": "أحمد الحواشي",
             "base": "https://server11.mp3quran.net/hawashi/", "mode": "surah"},
            {"id": "swlim", "name": "أحمد السويلم",
             "base": "https://server14.mp3quran.net/swlim/Rewayat-Hafs-A-n-Assem/", "mode": "surah"},
            {"id": "trabulsi", "name": "أحمد الطرابلسي",
             "base": "https://server10.mp3quran.net/trabulsi/", "mode": "surah"},
            {"id": "nufais", "name": "أحمد النفيس",
             "base": "https://server16.mp3quran.net/nufais/Rewayat-Hafs-A-n-Assem/", "mode": "surah"},
            {"id": "shaheen", "name": "أحمد خليل شاهين",
             "base": "https://server16.mp3quran.net/shaheen/Rewayat-Hafs-A-n-Assem/", "mode": "surah"},
            {"id": "deban", "name": "أحمد ديبان",
             "base": "https://server16.mp3quran.net/deban/Rewayat-Hafs-A-n-Assem/", "mode": "surah"},
            {"id": "saber", "name": "أحمد صابر",
             "base": "https://server8.mp3quran.net/saber/", "mode": "surah"},
            {"id": "aamer", "name": "أحمد عامر",
             "base": "https://server10.mp3quran.net/Aamer/", "mode": "surah"},
            {"id": "a_maasaraawi", "name": "أحمد عيسى المعصراوي",
             "base": "https://server16.mp3quran.net/a_maasaraawi/Rewayat-Hafs-A-n-Assem/", "mode": "surah"},
            {"id": "a_alemadi", "name": "أنس العمادي",
             "base": "https://server16.mp3quran.net/a_alemadi/Rewayat-Hafs-A-n-Assem/", "mode": "surah"},
            {"id": "bader", "name": "بدر التركي",
             "base": "https://server10.mp3quran.net/bader/Rewayat-Hafs-A-n-Assem/", "mode": "surah"},
            {"id": "peshawa", "name": "بيشه وا قادر الكردي",
             "base": "https://server16.mp3quran.net/peshawa/Rewayat-Hafs-A-n-Assem/", "mode": "surah"},
            {"id": "twfeeq", "name": "توفيق الصايغ",
             "base": "https://server6.mp3quran.net/twfeeq/", "mode": "surah"},
            {"id": "jamal", "name": "جمال شاكر عبد الله",
             "base": "https://server6.mp3quran.net/jamal/", "mode": "surah"},
            {"id": "jaman", "name": "جمعان العصيمي",
             "base": "https://server6.mp3quran.net/jaman/", "mode": "surah"},
            {"id": "j_abdullah", "name": "جنيد آدم عبد الله",
             "base": "https://server16.mp3quran.net/J-Abdullah/Rewayat-Hafs-A-n-Assem/", "mode": "surah"},
            {"id": "hatem", "name": "حاتم فريد الواعر",
             "base": "https://server11.mp3quran.net/hatem/", "mode": "surah"},
            {"id": "h_aldaghriri", "name": "حسن الدغريري",
             "base": "https://server16.mp3quran.net/H-Aldaghriri/Rewayat-Hafs-A-n-Assem/", "mode": "surah"},
            {"id": "h_saleh", "name": "حسن صالح",
             "base": "https://server16.mp3quran.net/h_saleh/Rewayat-Hafs-A-n-Assem/", "mode": "surah"},
            {"id": "k_alzadi", "name": "خالد الزيادي",
             "base": "https://server16.mp3quran.net/K-Alzadi/Rewayat-Hafs-A-n-Assem/", "mode": "surah"},
            {"id": "mohna", "name": "خالد المهنا",
             "base": "https://server11.mp3quran.net/mohna/", "mode": "surah"},
            {"id": "kafi", "name": "خالد عبد الكافي",
             "base": "https://server11.mp3quran.net/kafi/", "mode": "surah"},
            {"id": "kh_mohammadi", "name": "خالد كريم محمدي",
             "base": "https://server16.mp3quran.net/kh_mohammadi/Rewayat-Hafs-A-n-Assem/", "mode": "surah"},
            {"id": "hamza", "name": "داود حمزة",
             "base": "https://server9.mp3quran.net/hamza/", "mode": "surah"},
            {"id": "rami", "name": "رامي الدعيس",
             "base": "https://server6.mp3quran.net/rami/", "mode": "surah"},
            {"id": "zaki", "name": "زكي داغستاني",
             "base": "https://server9.mp3quran.net/zaki/", "mode": "surah"},
            {"id": "alzain", "name": "الزين محمد أحمد",
             "base": "https://server9.mp3quran.net/alzain/", "mode": "surah"},
            {"id": "saad", "name": "سعد المقرن",
             "base": "https://server16.mp3quran.net/saad/Rewayat-Hafs-A-n-Assem/", "mode": "surah"},
            {"id": "s_sadeiq", "name": "سلمان الصديق",
             "base": "https://server16.mp3quran.net/s_sadeiq/Rewayat-Hafs-A-n-Assem/", "mode": "surah"},
            {"id": "s_hashemi", "name": "سيد أحمد هاشمي",
             "base": "https://server16.mp3quran.net/s_hashemi/Rewayat-Hafs-A-n-Assem/", "mode": "surah"},
            {"id": "sayed", "name": "سيد رمضان",
             "base": "https://server12.mp3quran.net/sayed/", "mode": "surah"},
            {"id": "shatri2", "name": "شيخ أبو بكر الشاطري",
             "base": "https://server11.mp3quran.net/shatri/", "mode": "surah"},
            {"id": "taher", "name": "شيرزاد عبد الرحمن طاهر",
             "base": "https://server12.mp3quran.net/taher/", "mode": "surah"},
            {"id": "hkm", "name": "صابر عبد الحكم",
             "base": "https://server12.mp3quran.net/hkm/", "mode": "surah"},
            {"id": "shamrani", "name": "صالح الشمراني",
             "base": "https://server16.mp3quran.net/shamrani/Rewayat-Hafs-A-n-Assem/", "mode": "surah"},
            {"id": "sahood", "name": "صالح الصاهود",
             "base": "https://server8.mp3quran.net/sahood/", "mode": "surah"},
            {"id": "s_alquraishi", "name": "صالح القريشي",
             "base": "https://server16.mp3quran.net/s_alquraishi/Rewayat-Hafs-A-n-Assem/", "mode": "surah"},
            {"id": "habdan", "name": "صالح الهبدان",
             "base": "https://server6.mp3quran.net/habdan/", "mode": "surah"},
            {"id": "salah_hashim_m", "name": "صلاح الهاشم",
             "base": "https://server12.mp3quran.net/salah_hashim_m/", "mode": "surah"},
            {"id": "a_klb", "name": "عادل الكلباني",
             "base": "https://server8.mp3quran.net/a_klb/", "mode": "surah"},
            {"id": "ryan", "name": "عادل ريان",
             "base": "https://server8.mp3quran.net/ryan/", "mode": "surah"},
            {"id": "asim", "name": "عاصم اللحیدان",
             "base": "https://server7.mp3quran.net/asim/Rewayat-Hafs-A-n-Assem/", "mode": "surah"},
            {"id": "a_binaoun", "name": "عبد الإله بن عون",
             "base": "https://server16.mp3quran.net/a_binaoun/Rewayat-Hafs-A-n-Assem/", "mode": "surah"},
            {"id": "thubti", "name": "عبد البارئ الثبيتي",
             "base": "https://server6.mp3quran.net/thubti/", "mode": "surah"},
            {"id": "bari", "name": "عبد البارئ محمد",
             "base": "https://server12.mp3quran.net/bari/", "mode": "surah"},
            {"id": "a_ghailan", "name": "عبد البديع غيلان",
             "base": "https://server16.mp3quran.net/A-Ghailan/Rewayat-Hafs-A-n-Assem/", "mode": "surah"},
            {"id": "a_alshahhat", "name": "عبد الرحمن الشحات",
             "base": "https://server16.mp3quran.net/a_alshahhat/Rewayat-Hafs-A-n-Assem/", "mode": "surah"},
            {"id": "aloosi", "name": "عبد الرحمن العوسي",
             "base": "https://server6.mp3quran.net/aloosi/", "mode": "surah"},
            {"id": "a_majed", "name": "عبد الرحمن الماجد",
             "base": "https://server10.mp3quran.net/a_majed/", "mode": "surah"},
            {"id": "a_albadr", "name": "عبد الرحمن بن عبد الرزاق البدر",
             "base": "https://server16.mp3quran.net/A-AlBadr/Rewayat-Hafs-A-n-Assem/", "mode": "surah"},
            {"id": "soufi", "name": "عبد الرشيد صوفي",
             "base": "https://server16.mp3quran.net/soufi/Rewayat-Hafs-A-n-Assem/", "mode": "surah"},
            {"id": "a_ahmed", "name": "عبد العزيز الأحمد",
             "base": "https://server11.mp3quran.net/a_ahmed/", "mode": "surah"},
            {"id": "a_turki", "name": "عبد العزيز التركي",
             "base": "https://server16.mp3quran.net/a_turki/Rewayat-Hafs-A-n-Assem/", "mode": "surah"},
            {"id": "zahrani", "name": "عبد العزيز الزهراني",
             "base": "https://server9.mp3quran.net/zahrani/", "mode": "surah"},
            {"id": "a_alhazmi", "name": "عبد الكريم الحازمي",
             "base": "https://server16.mp3quran.net/a_alhazmi/Rewayat-Hafs-A-n-Assem/", "mode": "surah"},
            {"id": "buajan", "name": "عبد الله البعيجان",
             "base": "https://server8.mp3quran.net/buajan/", "mode": "surah"},
            {"id": "khalf", "name": "عبد الله الخلف",
             "base": "https://server14.mp3quran.net/khalf/", "mode": "surah"},
            {"id": "a_alqrafi", "name": "عبد الله القرافي",
             "base": "https://server16.mp3quran.net/a_alqrafi/Rewayat-Hafs-A-n-Assem/", "mode": "surah"},
            {"id": "abdullahk", "name": "عبد الله الكندري",
             "base": "https://server10.mp3quran.net/Abdullahk/", "mode": "surah"},
            {"id": "mousa", "name": "عبد الله الموسى",
             "base": "https://server14.mp3quran.net/mousa/Rewayat-Hafs-A-n-Assem/", "mode": "surah"},
            {"id": "kyat", "name": "عبد الله خياط",
             "base": "https://server12.mp3quran.net/kyat/", "mode": "surah"},
            {"id": "a_abdl", "name": "عبد الله عبدل",
             "base": "https://server16.mp3quran.net/a_abdl/Rewayat-Hafs-A-n-Assem/", "mode": "surah"},
            {"id": "gulan", "name": "عبد الله غيلان",
             "base": "https://server8.mp3quran.net/gulan/", "mode": "surah"},
            {"id": "kamel", "name": "عبد الله كامل",
             "base": "https://server16.mp3quran.net/kamel/Rewayat-Hafs-A-n-Assem/", "mode": "surah"},
            {"id": "mohsin_harthi", "name": "عبد المحسن الحارثي",
             "base": "https://server6.mp3quran.net/mohsin_harthi/", "mode": "surah"},
            {"id": "obk", "name": "عبد المحسن العبيكان",
             "base": "https://server12.mp3quran.net/obk/", "mode": "surah"},
            {"id": "qasm", "name": "عبد المحسن القاسم",
             "base": "https://server8.mp3quran.net/qasm/", "mode": "surah"},
            {"id": "kanakeri", "name": "عبد الهادي أحمد كناكري",
             "base": "https://server6.mp3quran.net/kanakeri/", "mode": "surah"},
            {"id": "wdod", "name": "عبد الودود حنيف",
             "base": "https://server8.mp3quran.net/wdod/", "mode": "surah"},
            {"id": "arkani", "name": "عبد الولي الأركاني",
             "base": "https://server6.mp3quran.net/arkani/", "mode": "surah"},
            {"id": "alijon", "name": "عليجان قوري حمدان",
             "base": "https://server16.mp3quran.net/Alijon/Rewayat-Hafs-A-n-Assem/", "mode": "surah"},
            {"id": "hafz", "name": "عماد زهير حافظ",
             "base": "https://server6.mp3quran.net/hafz/", "mode": "surah"},
            {"id": "darweez", "name": "عمر الدريويز",
             "base": "https://server16.mp3quran.net/darweez/Rewayat-Hafs-A-n-Assem/", "mode": "surah"},
            {"id": "i_sanankoua", "name": "عيسى عمر سناكو",
             "base": "https://server16.mp3quran.net/i_sanankoua/Rewayat-Hafs-A-n-Assem/", "mode": "surah"},
            {"id": "f_khamery", "name": "فؤاد الخامري",
             "base": "https://server16.mp3quran.net/f_khamery/Rewayat-Hafs-A-n-Assem/", "mode": "surah"},
            {"id": "f_hajry", "name": "فيصل الهاجري",
             "base": "https://server16.mp3quran.net/f_hajry/Rewayat-Hafs-A-n-Assem/", "mode": "surah"},
            {"id": "zaml", "name": "ماجد الزامل",
             "base": "https://server9.mp3quran.net/zaml/", "mode": "surah"},
            {"id": "mal_allah_jaber", "name": "مال الله عبد الرحمن الجابر",
             "base": "https://server16.mp3quran.net/mal-allah_jaber/Rewayat-Hafs-A-n-Assem/", "mode": "surah"},
            {"id": "shaksh", "name": "ماهر شخاشيرو",
             "base": "https://server10.mp3quran.net/shaksh/", "mode": "surah"},
            {"id": "bukheet", "name": "محمد البخيت",
             "base": "https://server14.mp3quran.net/bukheet/", "mode": "surah"},
            {"id": "m_alzubaidi", "name": "محمد الزبيدي",
             "base": "https://server16.mp3quran.net/M-AlZubaidi/Rewayat-Hafs-A-n-Assem/", "mode": "surah"},
            {"id": "tblawi", "name": "محمد الطبلاوي",
             "base": "https://server12.mp3quran.net/tblawi/", "mode": "surah"},
            {"id": "m_alfaqih", "name": "محمد الفقيه",
             "base": "https://server16.mp3quran.net/M_Alfaqih/Rewayat-Hafs-A-n-Assem/", "mode": "surah"},
            {"id": "lhdan", "name": "محمد اللحيدان",
             "base": "https://server8.mp3quran.net/lhdan/", "mode": "surah"},
            {"id": "mhsny", "name": "محمد المحيسني",
             "base": "https://server11.mp3quran.net/mhsny/", "mode": "surah"},
            {"id": "m_burhaji", "name": "محمد برهجي",
             "base": "https://server16.mp3quran.net/M_Burhaji/Rewayat-Hafs-A-n-Assem/", "mode": "surah"},
            {"id": "m_qari", "name": "محمد خليل القارئ",
             "base": "https://server8.mp3quran.net/m_qari/", "mode": "surah"},
            {"id": "rashad", "name": "محمد رشاد الشريف",
             "base": "https://server10.mp3quran.net/rashad/", "mode": "surah"},
            {"id": "shah", "name": "محمد صالح عالم شاه",
             "base": "https://server12.mp3quran.net/shah/", "mode": "surah"},
            {"id": "khan", "name": "محمد عثمان خان",
             "base": "https://server6.mp3quran.net/khan/", "mode": "surah"},
            {"id": "mrifai", "name": "محمود الرفاعي",
             "base": "https://server11.mp3quran.net/mrifai/", "mode": "surah"},
            {"id": "m_harfoush", "name": "محمود حرفوش",
             "base": "https://server16.mp3quran.net/M-Harfoush/Rewayat-Hafs-A-n-Assem/", "mode": "surah"},
            {"id": "m_abdelhakam", "name": "محمود عبد الحكم",
             "base": "https://server16.mp3quran.net/m_abdelhakam/Rewayat-Hafs-A-n-Assem/", "mode": "surah"},
            {"id": "mukhtar_haj", "name": "مختار الحاج",
             "base": "https://server16.mp3quran.net/mukhtar_haj/Rewayat-Hafs-A-n-Assem/", "mode": "surah"},
            {"id": "afs", "name": "مشاري العفاسي",
             "base": "https://server8.mp3quran.net/afs/", "mode": "surah"},
            {"id": "mustafa", "name": "مصطفى إسماعيل",
             "base": "https://server8.mp3quran.net/mustafa/", "mode": "surah"},
            {"id": "lahoni", "name": "مصطفى اللاهوني",
             "base": "https://server6.mp3quran.net/lahoni/", "mode": "surah"},
            {"id": "ra3ad", "name": "مصطفى رعد العزاوي",
             "base": "https://server8.mp3quran.net/ra3ad/", "mode": "surah"},
            {"id": "harthi", "name": "معيض الحارثي",
             "base": "https://server8.mp3quran.net/harthi/", "mode": "surah"},
            {"id": "muftah_sultany", "name": "مفتاح السلطني",
             "base": "https://server14.mp3quran.net/muftah_sultany/Rewayat-Hafs-A-n-Assem/", "mode": "surah"},
            {"id": "mansor", "name": "منصور السالمي",
             "base": "https://server14.mp3quran.net/mansor/", "mode": "surah"},
            {"id": "bilal", "name": "موسى بلال",
             "base": "https://server11.mp3quran.net/bilal/", "mode": "surah"},
            {"id": "alosfor", "name": "ناصر العصفور",
             "base": "https://server14.mp3quran.net/alosfor/", "mode": "surah"},
            {"id": "nasser_almajed", "name": "ناصر الماجد",
             "base": "https://server14.mp3quran.net/nasser_almajed/", "mode": "surah"},
            {"id": "nabil", "name": "نبيل الرفاعي",
             "base": "https://server9.mp3quran.net/nabil/", "mode": "surah"},
            {"id": "nathier", "name": "نذير المالكي",
             "base": "https://server16.mp3quran.net//nathier/Rewayat-Hafs-A-n-Assem/", "mode": "surah"},
            {"id": "namh", "name": "نعمة الحسان",
             "base": "https://server8.mp3quran.net/namh/", "mode": "surah"},
            {"id": "h_abudalal", "name": "هاشم أبو دلال",
             "base": "https://server16.mp3quran.net/h_abudalal/Rewayat-Hafs-A-n-Assem/", "mode": "surah"},
            {"id": "wdee3", "name": "وديع اليمني",
             "base": "https://server6.mp3quran.net/wdee3/", "mode": "surah"},
            {"id": "qurashi", "name": "ياسر القرشي",
             "base": "https://server9.mp3quran.net/qurashi/", "mode": "surah"},
            {"id": "yahya", "name": "يحيى حوا",
             "base": "https://server12.mp3quran.net/yahya/", "mode": "surah"},
            {"id": "yousef", "name": "يوسف الشويعي",
             "base": "https://server9.mp3quran.net/yousef/", "mode": "surah"},
            {"id": "noah", "name": "يوسف بن نوح أحمد",
             "base": "https://server8.mp3quran.net/noah/", "mode": "surah"},
        ],
    },
    {
        "id": "warsh",
        "name": "ورش عن نافع",
        "text": "text_warsh.jz",
        "reciters": [
            {"id": "dosary", "name": "إبراهيم الدوسري",
             "base": "https://everyayah.com/data/warsh/warsh_ibrahim_aldosary_128kbps/", "mode": "ayah"},
            {"id": "yassin", "name": "ياسين الجزائري",
             "base": "https://everyayah.com/data/warsh/warsh_yassin_al_jazaery_64kbps/", "mode": "ayah"},
            {"id": "abdul_basit_warsh", "name": "عبد الباسط عبد الصمد (آية بآية)",
             "base": "https://everyayah.com/data/warsh/warsh_Abdul_Basit_128kbps/", "mode": "ayah"},
            {"id": "laghdaf_shinqiti", "name": "محمد لغظف الشنقيطي",
             "base": "https://archive.org/download/20231006_20231006_1805/", "mode": "surah",
             # أسماء ملفّات الأرشيف مرمَّزة مسبقاً (٣٣٤ حرفاً لكلٍّ) — تُقرأ من
             # laghdaf_files.json المولَّد بجانب هذه الأداة.
             "files": LAGHDAF_FILES},
            {"id": "basit_warsh", "name": "عبد الباسط عبد الصمد",
             "base": "https://server7.mp3quran.net/basit/Rewayat-Warsh-A-n-Nafi/", "mode": "surah"},
            {"id": "qazabri", "name": "عمر القزابري",
             "base": "https://server9.mp3quran.net/omar_warsh/", "mode": "surah"},
            {"id": "husary_warsh", "name": "محمود خليل الحصري",
             "base": "https://server13.mp3quran.net/husr/Rewayat-Warsh-A-n-Nafi/", "mode": "surah"},
            {"id": "m_sayed_warsh", "name": "محمد السيد",
             "base": "https://server16.mp3quran.net/m_sayed/Rewayat-Warsh-A-n-Nafi/", "mode": "surah"},
            {"id": "qari_warsh", "name": "الحسين الشرعي",
             "base": "https://server11.mp3quran.net/qari/", "mode": "surah"},
            {"id": "koshi_warsh", "name": "الحسين الكوشي",
             "base": "https://server11.mp3quran.net/koshi/", "mode": "surah"},
            {"id": "benkirane_warsh", "name": "عبد الحكيم بنكيران",
             "base": "https://server16.mp3quran.net/A-Benkirane/Rewayat-Warsh-A-n-Nafi/", "mode": "surah"},
            {"id": "bl3_warsh", "name": "رشيد بلعالية",
             "base": "https://server6.mp3quran.net/bl3/Rewayat-Warsh-A-n-Nafi/", "mode": "surah"},
            {"id": "m_abdulkareem_warsh", "name": "محمد عبد الكريم (الأصبهاني)",
             "base": "https://server12.mp3quran.net/m_krm/Rewayat-Warsh-A-n-Nafi-Men-Tariq-Abi-Baker-Alasbahani/", "mode": "surah"},
            {"id": "deban_warsh", "name": "أحمد ديبان (الأزرق)",
             "base": "https://server16.mp3quran.net/deban/Rewayat-Warsh-A-n-Nafi-Men-Tariq-Alazraq/", "mode": "surah"},
        ],
    },
    {
        "id": "qalun",
        "name": "قالون عن نافع",
        "text": "text_qalun.jz",
        "reciters": [
            {"id": "husary_qalun", "name": "محمود خليل الحصري",
             "base": "https://server13.mp3quran.net/husr/Rewayat-Qalon-A-n-Nafi/", "mode": "surah"},
            {"id": "huthaify_qalun", "name": "علي بن عبد الرحمن الحذيفي",
             "base": "https://server9.mp3quran.net/huthifi_qalon/", "mode": "surah"},
            {"id": "dokali", "name": "الدوكالي محمد العالم",
             "base": "https://server7.mp3quran.net/dokali/", "mode": "surah"},
            {"id": "trablsi", "name": "أحمد الطرابلسي",
             "base": "https://server10.mp3quran.net/trablsi/", "mode": "surah"},
            {"id": "tareq_qalun", "name": "طارق عبد الباسط فتح",
             "base": "https://server10.mp3quran.net/tareq/", "mode": "surah"},
            {"id": "akri_qalun", "name": "محمد العكري",
             "base": "https://server16.mp3quran.net/m_akri/Rewayat-Qalon-A-n-Nafi/", "mode": "surah"},
            {"id": "deban_qalun", "name": "أحمد ديبان",
             "base": "https://server16.mp3quran.net/deban/Rewayat-Qalon-A-n-Nafi/", "mode": "surah"},
            {"id": "sneineh_qalun", "name": "محمد أبو سنينة",
             "base": "https://server16.mp3quran.net/sneineh/Rewayat-Qalon-A-n-Nafi/", "mode": "surah"},
            {"id": "qeniwa_qalun", "name": "محمد بشير القنيوة",
             "base": "https://server16.mp3quran.net/qeniwa/Rewayat-Qalon-A-n-Nafi/", "mode": "surah"},
            {"id": "kshidan_qalun", "name": "إبراهيم كشيدان",
             "base": "https://server16.mp3quran.net/i_kshidan/Rewayat-Qalon-A-n-Nafi/", "mode": "surah"},
            {"id": "waleed_qalun", "name": "وليد النائحي (أبي نشيط)",
             "base": "https://server9.mp3quran.net/waleed/", "mode": "surah"},
        ],
    },
]


# ------------------------------------------------------------------ الكتابة
def write_gz(path, obj):
    raw = json.dumps(obj, ensure_ascii=False, separators=(",", ":")).encode("utf-8")
    with gzip.GzipFile(path, "wb", compresslevel=9, mtime=0) as f:
        f.write(raw)
    return len(raw), os.path.getsize(path)


def main():
    global OFFLINE
    ap = argparse.ArgumentParser()
    ap.add_argument("--offline", action="store_true")
    args = ap.parse_args()
    OFFLINE = args.offline

    sys.stdout.reconfigure(encoding="utf-8")
    if not OFFLINE:
        print("تنزيل المصادر…")
        download_all()

    hafs, meta, api_surahs = load_hafs()
    print("حفص:", len(hafs))

    texts = {"hafs": hafs}
    for rid, mushaf in (("warsh", QP_WARSH), ("qalun", QP_QALUN)):
        t, basmala, warn = build_riwaya(mushaf, hafs, meta)
        texts[rid] = t
        print("%s: %d  (بسملة: %s)" % (rid, len(t), basmala))
        if warn:
            print("  ⚠ آيات فارغة:", warn[:20], "…" if len(warn) > 20 else "")

    idx = build_index(meta, api_surahs, RIWAYAT)

    os.makedirs(OUT, exist_ok=True)
    total = 0
    r, c = write_gz(os.path.join(OUT, "index.jz"), idx)
    total += c
    print("index.jz  %7d → %7d" % (r, c))
    for rid in ("hafs", "warsh", "qalun"):
        r, c = write_gz(os.path.join(OUT, "text_%s.jz" % rid), texts[rid])
        total += c
        print("text_%s.jz %7d → %7d" % (rid, r, c))
    print("المجموع المضاف للحزمة: %.1f KB" % (total / 1024.0))

    # ------------------------------------------------------------ التحقّق
    print("\n=== التحقّق ===")
    for rid in ("hafs", "warsh", "qalun"):
        assert len(texts[rid]) == AYAH_TOTAL
        print("len(%s) = %d ✓" % (rid, len(texts[rid])))
    assert sum(s["ayahs"] for s in idx["surahs"]) == AYAH_TOTAL
    acc = 0
    for s in idx["surahs"]:
        assert s["start"] == acc, s
        acc += s["ayahs"]
    print("مجموع آيات السور = 6236 و start متّسق تراكمياً ✓")
    for k, n in (("juzs", 30), ("hizbs", 60), ("pages", 604)):
        lst = idx[k]
        assert len(lst) == n
        st = [x["start"] for x in lst]
        assert st == sorted(st) and st[0] == 0 and max(st) < AYAH_TOTAL
        print("%s = %d تصاعديّة ضمن 0..6235 ✓" % (k, n))
    same = sum(1 for a, b in zip(texts["hafs"], texts["warsh"]) if a == b)
    same_q = sum(1 for a, b in zip(texts["hafs"], texts["qalun"]) if a == b)
    same_wq = sum(1 for a, b in zip(texts["warsh"], texts["qalun"]) if a == b)
    print("آيات متطابقة حرفياً: حفص↔ورش=%d، حفص↔قالون=%d، ورش↔قالون=%d" %
          (same, same_q, same_wq))

    print("\n=== عيّنات ===")
    for rid in ("hafs", "warsh", "qalun"):
        print("--", rid)
        for i in range(0, 7):
            print("  1:%d %s" % (i + 1, texts[rid][i]))
        print("  2:255 %s" % texts[rid][idx["surahs"][1]["start"] + 254])
        st = idx["surahs"][111]["start"]
        for i in range(4):
            print("  112:%d %s" % (i + 1, texts[rid][st + i]))


if __name__ == "__main__":
    main()
