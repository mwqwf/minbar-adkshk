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
            {"id": "abkar", "name": "إدريس أبكر",
             "base": "https://server6.mp3quran.net/abkr/", "mode": "surah"},
            {"id": "balilah", "name": "بندر بليلة",
             "base": "https://server6.mp3quran.net/balilah/", "mode": "surah"},
            {"id": "kurdi", "name": "رعد محمد الكردي",
             "base": "https://server6.mp3quran.net/kurdi/", "mode": "surah"},
            {"id": "jaleel", "name": "خالد الجليل",
             "base": "https://server10.mp3quran.net/jleel/", "mode": "surah"},
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
