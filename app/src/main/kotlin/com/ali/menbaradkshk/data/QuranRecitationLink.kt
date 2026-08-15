package com.ali.menbaradkshk.data

/**
 * 🔗 جسر بين **صفحة المصحف** و**تلاوات المنبر المسجَّلة**.
 *
 * في المنبر أقسامٌ فرعيّة تحوي المصحف كاملاً بأصوات قرّاء بعينهم، وكلٌّ منها
 * ستّون درساً — درسٌ لكلّ حزب. وصفحة المصحف تعرض النصّ برواياته. الوصل بينهما
 * هو الفائدة كلّها: من يقرأ حزباً برواية ورش يريد أن يسمعه بها من القارئ نفسه.
 *
 * **لماذا معرّفات ثابتة في الشيفرة؟** لأنّ الوصل بحسب الاسم هشّ (الأسماء
 * تُحرَّر من اللوحة فينكسر الربط بصمت)، ولأنّ هذه الأقسام قليلة وثابتة. وإن
 * غاب القسم من المحتوى المُحمَّل لا يظهر التلميح أصلاً — لا رابط ميّت أبداً.
 */
object QuranRecitationLink {

    /** قسم فرعيّ فيه المصحف كاملاً بروايةٍ ما. */
    data class Recitation(
        val riwayaId: String,
        val subcategoryId: String,
        /// اسم القارئ كما يُعرض في التلميح — مختصر لا كامل عنوان القسم.
        val reciter: String,
    )

    /**
     * الأقسام المعروفة. `hafs` يُضاف حين يُنشَأ قسمه — وحتى ذلك الحين لا يظهر
     * تلميح لحفص، ولا يتعطّل شيء.
     */
    private val recitations = listOf(
        Recitation(
            riwayaId = "warsh",
            subcategoryId = "bXrQpqAGn39B780njnxS",
            reciter = "محمد لغظف",
        ),
        Recitation(
            riwayaId = "qalun",
            subcategoryId = "Tghj355uzASPqUijg7gj",
            reciter = "سالم ولد محمد الأمين (الداهن)",
        ),
    )

    fun forRiwaya(riwayaId: String): Recitation? =
        recitations.firstOrNull { it.riwayaId == riwayaId }

    /**
     * رقم الحزب (1..60) الذي تقع فيه الآية ذات الفهرس المسطّح المعطى.
     *
     * الأحزاب في الفهرس مرتّبة تصاعدياً، فآخر حزبٍ بدايتُه ≤ الموضع هو المطلوب.
     */
    fun hizbAt(index: QuranIndex, flatAyah: Int): Int =
        index.hizbs.lastOrNull { flatAyah >= it.start }?.number ?: 1

    /**
     * يعثر على درس الحزب داخل القسم.
     *
     * العناوين في المحتوى القائم مكتوبة بصيغتين مختلفتين («الحزب 53» و«الحزب
     * الثالث والخمسون») لأنّها أُدخلت يدوياً على دفعات. فالمطابقة تجري على
     * **الرقم المستخرَج من العنوان** كيفما كُتب: أرقاماً كانت أم حروفاً. وهذا
     * أمتن من مطابقة النصّ حرفياً، ولا يحتاج تعديل المحتوى القائم.
     */
    fun lessonForHizb(lessons: List<Lesson>, subcategoryId: String, hizb: Int): Lesson? =
        lessons.asSequence()
            .filter { it.subcategoryId == subcategoryId }
            .firstOrNull { hizbNumberIn(it.title) == hizb }

    /**
     * يستخرج رقم الحزب من عنوان درس، أو `null` إن لم يتبيّن.
     *
     * ⚠️ كلمة «حزب» شرطٌ لا زينة: بدونها كان «شرح الأربعون النووية» يُقرأ
     * «الحزب ٤٠»، و«الأصول الثلاثة» تُقرأ «الحزب ٣». عناوين المنبر مليئة
     * بالأعداد في سياقات أخرى، فالتقييد بالكلمة هو ما يمنع فتح درس خاطئ —
     * وفتحُ الخاطئ أسوأ من ألّا نفتح شيئاً.
     */
    fun hizbNumberIn(title: String): Int? {
        val text = title.trim()
        if (!text.contains("حزب")) return null
        // أرقام هنديّة أو عربيّة صريحة أولاً — الأكثر شيوعاً وأقلّ التباساً.
        DIGITS.find(text)?.let { match ->
            val normalized = match.value.map { ch ->
                if (ch in '٠'..'٩') ('0' + (ch - '٠')) else ch
            }.joinToString("")
            normalized.toIntOrNull()?.takeIf { it in 1..60 }?.let { return it }
        }
        return spelledNumber(text)
    }

    private val DIGITS = Regex("[0-9٠-٩]+")

    /**
     * الأعداد المكتوبة حروفاً: «الثالث والخمسون» = 3 + 50.
     *
     * نبحث عن وحدةٍ وعشرةٍ ونجمعهما. التعامل مع تنويعات الإعراب («الخمسون»/
     * «الخمسين») والتاء المربوطة يجري بالتطبيع لا بسرد كل الصور.
     */
    private fun spelledNumber(raw: String): Int? {
        val text = raw
            .replace('أ', 'ا').replace('إ', 'ا').replace('آ', 'ا')
            .replace('ة', 'ه')
        val tens = TENS.entries.firstOrNull { text.contains(it.key) }
        val unit = UNITS.entries.firstOrNull { text.contains(it.key) }
        val total = (tens?.value ?: 0) + (unit?.value ?: 0)
        return total.takeIf { it in 1..60 }
    }

    /// العشرات — تُطابَق بجذعها بلا لاحقة إعراب («خمسو/خمسي» ⇒ «خمس…»).
    private val TENS = linkedMapOf(
        "خمسين" to 50, "خمسون" to 50,
        "اربعين" to 40, "اربعون" to 40,
        "ثلاثين" to 30, "ثلاثون" to 30,
        "عشرين" to 20, "عشرون" to 20,
        "ستين" to 60, "ستون" to 60,
        "عشر" to 10,
    )

    /// الآحاد — الترتيب مقصود: الأطول أولاً كي لا تبتلع «الثاني» كلمةَ
    /// «الثاني عشر»، ولا «العاشر» كلمةَ «الحادي عشر».
    private val UNITS = linkedMapOf(
        "الحادي" to 1, "الثاني" to 2, "الثالث" to 3, "الرابع" to 4,
        "الخامس" to 5, "السادس" to 6, "السابع" to 7, "الثامن" to 8,
        "التاسع" to 9, "العاشر" to 10,
        "الاول" to 1, "الواحد" to 1,
    )
}
