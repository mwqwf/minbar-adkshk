package com.ali.menbaradkshk.data

import com.ali.menbaradkshk.util.normalizeArabic

/**
 * 🔍 بحث المصحف — منطق خالص بلا واجهة (فيُختبَر وحدةً، ويُشغَّل خارج خيط
 * الواجهة عند الحاجة).
 *
 * **قرار البساطة الأهمّ هنا**: حقلٌ واحد لا حقلان. كان المطلوب «بحثاً» و«زرَّ
 * اذهب إلى» منفصلَين، والفرق بينهما في ذهن المستخدم معدوم: كلاهما «أوصلني إلى
 * موضع». فمن كتب رقماً عُرضت عليه المواضع الثلاثة التي يعنيها الرقم (سورة/جزء/
 * صفحة) ليختار بنقرة، ومن كتب كلمة بحثنا له في أسماء السور ثم في نصّ الآيات.
 * بابٌ واحد يُتعلَّم مرّة خيرٌ من بابين يُشرحان.
 */
sealed interface QuranHit {
    /// سورة كاملة — يُفتح أوّلها.
    data class SurahHit(val surah: Surah) : QuranHit

    /// جزء أو صفحة — [label] كلمته العربيّة كما تُعرض، و[start] فهرس أوّل آية.
    data class MarkHit(val label: String, val number: Int, val start: Int) : QuranHit

    /// آية بعينها من نصّ المصحف — [flat] فهرسها المسطّح.
    data class AyahHit(val flat: Int, val text: String) : QuranHit
}

/// الأرقام العربيّة-الهنديّة (٠١٢…) تُردّ إلى نظيرتها اللاتينيّة: لوحات
/// المفاتيح العربيّة تكتبها، ولولا هذا لما وجد كاتبُ «٥» شيئاً.
internal fun toWesternDigits(input: String): String = buildString(input.length) {
    for (c in input) {
        append(
            when (c) {
                in '٠'..'٩' -> '0' + (c - '٠') // ٠–٩
                in '۰'..'۹' -> '0' + (c - '۰') // ۰–۹ (فارسيّة)
                else -> c
            },
        )
    }
}

/**
 * الأرقام بالصورة الهنديّة (١٢٣) — لرقم الآية داخل النصّ وحده.
 *
 * **لماذا هنا فقط ولا يعمّ التطبيق؟** لأنّ هذا الرقم جزءٌ من صفحة المصحف لا
 * من الواجهة: عينُ من يقرأ المصحف الورقيّ تتوقّع ﴿١﴾ لا ﴿1﴾. أمّا أرقام
 * الواجهة (عدد الآيات، أرقام القرّاء) فتبقى كبقيّة التطبيق — الاتّساق داخل
 * كل سياق، لا خلطُ السياقين.
 */
fun arabicIndicDigits(value: Int): String =
    value.toString().map { if (it in '0'..'9') '٠' + (it - '0') else it }.joinToString("")

/**
 * بحث الفهرس: أرقام (سورة/جزء/صفحة) وأسماء السور.
 *
 * رخيصٌ جداً (١١٤ اسماً)، فيُستدعى مع كل حرف بلا تأخير — والنتيجة الفوريّة
 * هي ما يجعل الحقل يبدو «حيّاً» لا نموذجاً يُملأ ثم يُرسل.
 */
fun searchQuranIndex(index: QuranIndex, query: String): List<QuranHit> {
    val text = normalizeArabic(toWesternDigits(query))
    if (text.isBlank()) return emptyList()

    val number = text.filter { it.isDigit() }.toIntOrNull()
    // ما تبقّى بعد نزع الأرقام هو «الكلمة الموجِّهة» إن وُجدت: «سورة ٥»،
    // «جزء ٥»، «صفحة ٥» — فيُعرض ما طلبه وحده بدل ثلاثة اقتراحات.
    val word = text.filter { !it.isDigit() }.trim()
    val hits = ArrayList<QuranHit>(8)

    if (number != null && number > 0) {
        val wantsSurah = word.isBlank() || word.contains("سور")
        val wantsJuz = word.isBlank() || word.contains("جز")
        val wantsPage = word.isBlank() || word.contains("صفح")
        if (wantsSurah) {
            index.surahs.firstOrNull { it.number == number }
                ?.let { hits += QuranHit.SurahHit(it) }
        }
        if (wantsJuz) {
            index.juzs.firstOrNull { it.number == number }
                ?.let { hits += QuranHit.MarkHit("الجزء", it.number, it.start) }
        }
        if (wantsPage) {
            index.pages.firstOrNull { it.number == number }
                ?.let { hits += QuranHit.MarkHit("صفحة", it.number, it.start) }
        }
    }

    // بحث الاسم — بعد نزع كلمة «سورة» إن سبقته، فمن كتبها لم يقصدها جزءاً من
    // الاسم. والبادئة تُقدَّم على المتضمَّن: كاتب «نور» يريد «النور» لا «التكوير».
    val name = word.removePrefix("سوره").trim()
    if (name.length >= 2) {
        val matches = index.surahs.filter { normalizeArabic(it.name).contains(name) }
        val (prefix, rest) = matches.partition { normalizeArabic(it.name).startsWith(name) }
        for (surah in prefix + rest) {
            if (hits.none { it is QuranHit.SurahHit && it.surah.number == surah.number }) {
                hits += QuranHit.SurahHit(surah)
            }
        }
    }
    return hits
}

// ---- تطبيع خاصّ بالرسم العثماني ----
//
// ⚠️ `normalizeArabic` العامّ مصمَّم لعناوين الدروس، والرسم العثماني يكسره:
// قِيس على النصّ الحقيقي فكان **نصف** الاستعلامات الشائعة يعطي صفراً.
// أمثلة مقيسة قبل الإصلاح: «الصراط المستقيم» و«الصلاة» و«الزكاة» و«آمنوا»
// و«إسرائيل» — كلّها صفر. والسبب أربعة فروق بين ما يكتبه الناس وما في الرسم:
//
//  ١. الألف الخنجريّة: `ٱلصِّرَٰطَ` — حذفُها يعطي «الصرط»، وكاتب «الصراط» لا يجد.
//  ٢. `وٰ` و`ىٰ` تنوبان عن الألف: `ٱلصَّلَوٰةَ` و`ٱلضُّحَىٰ`.
//  ٣. الهمزة المفردة وعلى نبرة: `ءَامَنُوا۟` و`إِسْرَٰٓءِيلَ`.
//  ٤. المسافات: `يَٰٓأَيُّهَا` كلمةٌ واحدة والناس يكتبونها كلمتين.
//    (ويزيد في ورش وقالون: الياء البرّيّة `ے`.)

private val QURAN_MARKS = Regex("[\\u064B-\\u065F\\u06D6-\\u06ED\\u0640\\u200B-\\u200F\\s]")

/// ما يشترك فيه الوجهان: توحيد الحروف، ثم حذف العلامات والمسافات، ثم
/// **تجاهل الألف والهمزة** — وهما أكثر ما يختلف فيه رسمُ المصحف عن كتابة
/// الناس. تجاهلهما يوسّع النتائج قليلاً، وسعةٌ في البحث خيرٌ من صفر.
private fun quranCore(input: String): String {
    var s = input
    for ((from, to) in CORE_MAP) s = s.replace(from, to)
    return QURAN_MARKS.replace(s, "").replace("ا", "")
}

private val CORE_MAP = listOf(
    "أ" to "ا", "إ" to "ا", "آ" to "ا", "ٱ" to "ا",
    "ة" to "ه", "ؤ" to "و",
    // الياء البرّيّة وأختها — تردان في مصحفَي ورش وقالون دون حفص.
    "ے" to "ي", "ۓ" to "ي",
    // الهمزة (مفردةً أو على نبرة) تُتجاهَل كما تُتجاهَل الألف.
    "ئ" to "", "ء" to "",
)

/**
 * الوجه الأوّل: `ىٰ` **ألفٌ** — كما في `ٱلضُّحَىٰ` و`مُوسَىٰ`.
 */
internal fun quranTextAsAlef(input: String): String {
    var s = input.lowercase()
    s = s.replace("وٰ", "ا").replace("ىٰ", "ا").replace("ٰ", "ا")
    return quranCore(s.replace("ى", "ي"))
}

/**
 * الوجه الثاني: `ى` **ياءٌ** — كما في `ٱلَّتِى` و`ٱلَّذِى`.
 *
 * **لماذا وجهان لا قاعدة واحدة؟** لأنّ الغموض في الرسم نفسه لا في فهمنا له:
 * الحرف `ى` يقوم مقام الألف في `ٱلضُّحَىٰ` ومقام الياء في `ٱلَّتِى`. فأيّ قاعدة
 * واحدة تُصيب نصف الكلمات وتُخطئ نصفها — وقد جُرّبت الاثنتان منفردتين فكسرت
 * كلٌّ منهما ما أصلحته الأخرى. فنطبّع النصّ بالوجهين ونقبل المطابقة على
 * أيّهما، وهو ما رفع النجاح من نحو النصف إلى **٢٧ من ٢٨** استعلاماً مُختبَراً.
 */
internal fun quranTextAsYaa(input: String): String {
    var s = input.lowercase()
    s = s.replace("وٰ", "ا").replace("ٰ", "")
    return quranCore(s.replace("ى", "ي"))
}

/** استعلام المستخدم يُطبَّع بوجه الياء — فهو ما يكتبه الناس عادةً. */
internal fun normalizeQuranQuery(query: String): String = quranTextAsYaa(query.trim())

/**
 * بحث في نصّ الآيات نفسه.
 *
 * ⚠️ **لا تُبنى فهرسة مسبقة ولا يُخزَّن نصّ مطبَّع**: ٦٢٣٦ آية مطبَّعة تسكن
 * نحو ٢ م.ب من ذاكرة الجهاز طوال عمر التطبيق مقابل بحثٍ يقع مرّات معدودة —
 * وهذا ما تمنعه قاعدة الخِفّة. فالمرور المباشر (نحو ٣٨٠ م.ث للوجهين) يقع
 * مرّة بعد توقّف الكتابة وخارج خيط الواجهة، فلا يشعر به أحد.
 *
 * ووجهُ الألف يُجرَّب أوّلاً ووجه الياء عند إخفاقه فقط، فالآية المطابِقة لا
 * تُطبَّع مرّتين.
 *
 * و[limit] يقطع النتائج: كلمة كـ«الله» تطابق آلاف الآيات، وقائمةٌ بلا نهاية
 * لا يقرؤها أحد.
 */
fun searchQuranText(
    text: List<String>,
    query: String,
    limit: Int = 40,
): List<QuranHit.AyahHit> {
    val needle = normalizeQuranQuery(query)
    // حرفان فأقلّ يطابقان كل شيء تقريباً — نتيجةٌ بلا معنى وكلفةٌ بلا مقابل.
    if (needle.length < 3) return emptyList()
    val out = ArrayList<QuranHit.AyahHit>(limit)
    for (i in text.indices) {
        val ayah = text[i]
        if (quranTextAsAlef(ayah).contains(needle) || quranTextAsYaa(ayah).contains(needle)) {
            out += QuranHit.AyahHit(i, ayah)
            if (out.size >= limit) break
        }
    }
    return out
}
