# منبر ادكصهك — تطبيق المستمعين (يُحمَّل تلقائياً في كل جلسة)

> **اقرأ `docs/CLOUD-SESSION.md` كاملاً قبل أيّ فعل** — هو دستور العمل، وفيه المستودعات
> الثلاثة وأوامر البناء والنشر والحرّاس والمحرّمات ومعيار «أُنجز» والحالة الحالية.
> الردود بالعربية دائماً.

## من أنت وأين

هذا مستودع **تطبيق منبر للمستمعين** `com.ali.menbaradkshk` (Kotlin/Compose)، فرع `main`،
والدفع إليه مباشرةً. وأختاه: `mwqwf/minbar-adkshk-admin` (لوحة الإدارة) و`mwqwf/minbar-cloud`
(خادم `minbar-api` على Cloudflare — خاصّ). استنسخهما بـ`gh repo clone` عند الحاجة.

## ⛔ لا أسرار عندك — والنشر بسير عمل

مفاتيح التوقيع وحساب خدمة Play أسرارٌ في GitHub **تُكتب ولا تُقرأ**. لا توقّع محلياً،
ولا ترفع إلى Play بأداة، ولا تطلب من المالك مفتاحاً. كلّ شيء بهذه الأوامر:

```bash
gh workflow run bundle-with-assets.yml -R mwqwf/minbar-adkshk   # بناء + توقيع + نشر ⇐ الإنتاج
gh workflow run emulator-check.yml -R mwqwf/minbar-adkshk       # برهان المحاكي (لقطة + سجلّ)
gh workflow run build-publish.yml -R mwqwf/minbar-adkshk-admin  # اللوحة ⇐ الاختبار المغلق
gh workflow run deploy.yml -R mwqwf/minbar-cloud                # الخادم
gh workflow run play-status.yml -R mwqwf/minbar-adkshk           # حالة التطبيقين في Play
gh workflow run deploy.yml -R mwqwf/minbar-cloud -f what=query -f sql="SELECT …"  # قراءة D1
gh run list -R <repo> -L 1 && gh run view <id> -R <repo> --log   # التحقّق من الأثر
```

⛔⛔ **ولا تقل «يلزم حاسوب المالك»**: كلّ شيء هنا. وإن عجزتَ فالسبب سرٌّ ناقص (سمِّه)،
أو صلاحية رمزك لا تبلغ مستودعاً آخر (اطلب جلسةً عليه)، أو حدٌّ من المنصّة (اذكره).
ولا تطلب منه مفتاحاً ولا كلمة مرور قطّ.

## «ابنِ» و«ارفع» = ثلاثة معاً (أمر المالك 2026-09-12)

1. رفع `versionCode` و`versionName`، **وتحديث «حول» (`ui/AboutScreen.kt`) وملاحظات الإصدار
   (`data/ReleaseNotes.kt`)** — قاعدة قديمة ملزمة.
2. البناء على GitHub لا على أيّ جهاز.
3. النشر آلياً: **هذا التطبيق ⇐ `production` مباشرةً**، واللوحة ⇐ `alpha` دائماً.
   فالفحوص تشتدّ هنا: خمسة حرّاس في سير العمل لا يُضعَّف أحدها.

## ⛔ محرّمات وقعت فعلاً فكلّفت

- لا `ndk { abiFilters }` في حزمة متجر (حصرُ arm64 أسقط ٩٬٤٣٢ جهازاً ورفضه Play).
- لا `pull_request_target` ولا `issue_comment` في أيّ سير عمل (بابا تسريب الأسرار).
- لا حذف لملفّات حزم Play (الحارس `LocalStore.isBundledDownload`).
- لا استعلام عن تطبيقٍ في Play أثناء نشره (التحرير الثاني يُبطل الأول).
- لا `git add <مجلّد>` — مسارات صريحة، ولا إيداع لسرّ قطّ.
- الهاتف ممنوع للاختبار — المحاكي وحده، وهو في CI الآن.

## معيار «أُنجز»

ترجمة + اختبارات خضراء + **برهانٌ مقروء من مصدر الحقيقة**: سجلّ التشغيلة يقول
`jar verified` ويثبت النشر، أو لقطة `emulator-check`. ⛔ لا إعلان إنجازٍ من قول أداةٍ عن نفسها.
