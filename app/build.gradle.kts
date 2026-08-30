import java.net.URL
import java.util.Properties
import java.util.zip.GZIPOutputStream

plugins {
    id("com.android.application")
    // ⛔ لا تُعِد `org.jetbrains.kotlin.android`: دعم Kotlin مدمج في AGP 9
    // فأصبح الملحق يرفض التطبيق ويوقف البناء.
    id("org.jetbrains.kotlin.plugin.compose")
}

val signingPropertiesFile = rootProject.file("signing.properties")
val signingProperties = Properties()
if (signingPropertiesFile.exists()) {
    signingPropertiesFile.inputStream().use(signingProperties::load)
}

fun signingValue(property: String, environment: String): String? =
    signingProperties.getProperty(property)?.takeIf(String::isNotBlank)
        ?: providers.environmentVariable(environment).orNull?.takeIf(String::isNotBlank)

val releaseKeyAlias = signingValue("keyAlias", "MINBAR_SIGNING_KEY_ALIAS")
val releaseKeyPassword = signingValue("keyPassword", "MINBAR_SIGNING_KEY_PASSWORD")
val releaseStorePath = signingValue("storeFile", "MINBAR_SIGNING_STORE_FILE")
val releaseStorePassword = signingValue("storePassword", "MINBAR_SIGNING_STORE_PASSWORD")
val hasReleaseSigning = listOf(
    releaseKeyAlias,
    releaseKeyPassword,
    releaseStorePath,
    releaseStorePassword,
).all { !it.isNullOrBlank() } && releaseStorePath?.let(::file)?.exists() == true

// الاسم الظاهر للمستخدم. ثابت واحد لكل أنواع البناء بلا أي لاحقة
// («تجريبي»/dev/beta/…). الفصل عن نسخة Play مضمون بلاحقة الحزمة `.dev`
// وحدها. الحارس أسفل كتلة `android` يوقف البناء إن عاد أحد فأضاف لاحقة.
val canonicalAppLabel = "منبر ادكصهك"

android {
    namespace = "com.ali.menbaradkshk"
    compileSdk = 36

    defaultConfig {
        applicationId = "com.ali.menbaradkshk"
        minSdk = 23
        targetSdk = 36
        // 10 / 1.3.6: يضمّ النص المشروح ومشاركة الصور/النص، ودمج الصوتيات
        // متعدّدة الصيغ، مع إصلاح حفظ النماذج ودوران الصور وتنظيف كاش الدمج.
        // 9 / 1.3.5: إصلاحات «شارك درساً» والإشعارات.
        // 8 / 1.3.4 كانت نسخة معالجة رفض Play (أندرويد أوتو) وتحذير العرض حتى الحافة.
        // رقم الإصدار **يجب** أن يزيد عن كل ما رُفع سابقاً وإلا رفض المتجر الرفع.
        // 11 / 1.4.0: دفعة تدقيق شاملة — استعادة مشاركة الملف الصوتي بعد
        // تنزيله (ضاعت في تحويل Kotlin)، وإصلاح الإشعارات من جذرها (إذن لم
        // يُطلب قطّ + حمولة لا تُقرأ في الخلفية)، وأقفال التنزيل، ومؤقّت النوم،
        // وتركيز الصوت، وطبقات كاش (وسائط/صور/نص مشروح)، وهويّة لونيّة مشتقّة
        // من الأيقونة، وتذكير تحديث.
        // 12 / 1.4.1: عدّاد التحميل الحقيقي (كان الشريط مبنيّاً على عدد
        // الدروس المكتملة فيبقى صفراً طوال تحميل درس واحد، والإشعار بلا شريط
        // إطلاقاً)، ومشاركة الصوتية باسم الدرس وامتداد قياسيّ (كان يُرسل
        // `<معرّف>.ogx` فيعامله واتساب مستنداً لا صوتاً).
        // 13 / 1.5.0: دردشة الإدارة بتصميم واتساب، ووضع داكن كامل للوحة،
        // وتحذير تحديث بملء الشاشة، وتحميل تدريجي يُنهي بطء أوّل تشغيل،
        // وإزالة الواجهات المتوقّفة التي يرصدها فحص Play من جذرها.
        // 18 / 1.8.0: صفحة «الأذكار» محلّية بالكامل (أذكار الصباح والمساء
        // و11 مجموعة مواضيعية بتخريجها، عدّاد يومي يُصفَّر تلقائياً، وسلسلة
        // مداومة، وأربعة تذكيرات بلا خادم)، والمفضّلة اندمجت أول تبويب في
        // «قوائمي» فأفسحت مكانها في الشريط السفلي، وروابط أقسام الموقع
        // تفتح في التطبيق.
        // 19 / 1.9.0 — «المصحف الكامل»: القرآن بثلاث روايات (حفص افتراضاً،
        // وورش وقالون) بنصّ كل رواية برسم مصحفها، وفهرسة رباعيّة، و68 قارئاً
        // مع تمييز الآية الجارية، وتنزيل للعمل بلا إنترنت، ونسخ ومشاركة.
        // ومعها: «تنزيلاتي» انتقلت إلى شريط الأدوات وأخلت مكانها للمصحف،
        // وإشعار الإصدار الجديد يفتح المتجر مباشرة، وتذكير تحديث عند فتح
        // الدروس (مرّتين يومياً بحدّ أقصى)، وتكبير خطّ الأذكار حتى 64sp،
        // ونسخ أيّ نصّ بضغطة مطوّلة، وتأكيد صريح قبل أيّ حذف.
        // 20 / 2.0.0 — **المصحف المصوَّر الملوّن**: صفحات مصاحف مجمع الملك فهد
        // الرسميّة للروايات الثلاث (حفص ١٤٤١، ورش ١٤٤٢، قالون ١٤٤٣) بإطارها
        // المزخرف وعلامات آيها الملوّنة، بملء الشاشة، مع تمييز الآية الجارية
        // فوق الصورة والنقر عليها لبدء التلاوة منها، وتنزيلها للعمل بلا
        // إنترنت. وهو **العرض الافتراضي** والمكتوب خيارٌ ثانٍ.
        // ومعها: بحث المصحف أُعيد بناء تطبيعه (كان نصف الاستعلامات الشائعة
        // يعطي صفراً بسبب الرسم العثماني)، وعلامات مرجعيّة، وخطّ عريض
        // افتراضاً وتكبير بالإصبعين في المصحف والأذكار، وإصلاح تجمّد تكبير
        // الأذكار، وشريط تذكير دائم بالتحديث، وإعلان الإصدار الجديد صار
        // تلقائياً بلا خطوة يدويّة.
        // ٢١ لا ٢٠: رُفعت حزمة ٢٠ إلى Play قبل أن تُنشر، وPlay لا يقبل إعادة
        // استعمال رقم الإصدار ولو لم يُنشر. والمحتوى نفسه، فاسم الإصدار ثابت.
        // ٢٢ / ٢.١.٠: **١٩١ قارئاً** بدل ٦٨ في الروايات الثلاث (ومنهم الشيخ
        // محمد لغظف الشنقيطي بورش)، وبحثٌ عنهم داخل ورقة الاختيار. وبحثٌ
        // جديد **داخل النصّ المشروح** يجد الدرس بما شُرح فيه لا بعنوانه.
        // ووِردٌ يوميّ للمصحف بتذكيره، وتخطّي الصمت في مشغّل الدروس، وتنزيل
        // تلقائيّ لجديد الأقسام المتابَعة، وعدد مرّات الاستماع بجانب كل درس.
        // ومشغّل المصحف صار مشغّل التطبيق نفسه فلا يزاحم الصفحة، وشريط
        // أدوات القراءة لم يعد يُقصّ على الشاشات الضيّقة، ومدخلٌ واحد
        // لتنزيل المصحف صوتاً وصورةً (وكان تنزيل الصور بلا مدخل أصلاً).
        // ومعها دفعةُ تدقيقٍ شاملة: ١٤٧ عيباً مؤكَّداً في التطبيق — أخطرها
        // حلقةٌ لا نهائيّة في زرّ حجم الخطّ، وشاشةُ تنزيلات تحبس المستخدم
        // بلا مخرج، وإشعاراتٌ تصل بمربّع أبيض، وتذكيراتٌ لا يُطبَّق وقتها.
        // ٢٣ / ٢.١.١: نُشرت ٢٢، وPlay لا يقبل إعادة استعمال رقم إصدار.
        // والمحتوى نفسه، ومعه: استخراج نصّ «النص المشروح» من صور الصفحات
        // صار تلقائياً في الخادم — فما كان صوراً لا تُبحَث صار نصّاً
        // يجده البحث، ومنه المتون القديمة كلّها.
        // ٢٤ / ٢.٢.٠: نسخة «أقلّ إنترنتاً وأسهل يداً». المزامنة صارت تجلب ما
        // تغيّر فقط لا الكتالوج كلّه — كان تصحيح حرفٍ في اسم قسم يُنزّل مئات
        // الوثائق على كل جهاز. وأوّل تشغيل صار يسأل سؤالين فيُفعّل التنزيل
        // التلقائي الذي كان مدفوناً في الإعدادات. وعند انقطاع الشبكة يظهر
        // «أظهر المحفوظ فقط» بدل قوائم تنتهي بخطأ. ومعها: زرّ «٣٠ ث»، وإرسال
        // الدرس المنزَّل بالبلوتوث لمن لا إنترنت عنده، وزرّا «أ+/أ−» بدل
        // القرص بإصبعين، و«استمع الآن» داخل إشعار الوِرد، وتصحيح عنوان
        // المساهمة بلا إعادة رفع الملفّ.
        // ٢٥ / ٢.٣.٠: نسخة «راسِل المطوّر». صار للمستخدم طريقٌ مباشر إلى
        // صاحب المشروع — خمس بطاقات لا صندوقاً فارغاً، والصوت فيها بحجم
        // الكتابة أو أكبر لمن لا يكتب بطلاقة، والمحادثة تُفتح إن ردّ المالك.
        // ومعها «تابع من حيث وقفت» بطاقةً أولى في الرئيسية، وتشغيلُ السلسلة
        // درساً بعد درس، وحجمُ التنزيل قبل الضغط لا بعده، وبحثٌ بالصوت لمن
        // لا يكتب، ومؤقّت نومٍ ينتهي بنهاية الدرس، وأذكارٌ تعرف وقتها.
        // وأُصلح ٤٢ خللاً — أخطرها أنّ تعديلات اللوحة كانت لا تصل المستخدمين.
        // ٢٦ / ٢.٣.١: رفضت Play النسخة ٢٥ بخطأين، وكلاهما درسٌ يُحفظ:
        // (١) `RECORD_AUDIO` الجديد جعل Play يستنتج الميكروفون عتاداً
        //     **مطلوباً** فأسقط ٢١ جهازاً — والعلاج إعلان كل عتادٍ مُستنتَج
        //     `required="false"` صراحةً (كتلة `uses-feature` في المانيفست).
        // (٢) إذن الصور يُلزم بإقرار «الوظيفة الأساسيّة للصور والفيديوهات» —
        //     فحُذف الإذن كلّه، وصار اقتراح اللقطة يفتح منتقي النظام الذي لا
        //     يحتاج إذناً أصلاً. و«راسِل المطوّر» صارت صوتاً وكتابةً فقط.
        // ٢٧ / ٢.٤.٠: نسخة «موثوقيةٍ ورئيسيةٍ أرتب». أُصلح ١٩ خللاً من فحص
        // شامل — أهمّها موثوقية «راسِل المطوّر»: لا تضيع رسالة، وتظهر فوراً
        // بلا إنترنت، وتُعاد المحاولة عند الفشل؛ وسقفٌ لمحاولات التحميل؛
        // والنسخة الاحتياطية صارت تشمل علامات المصحف والأذكار وسلسلتها؛
        // و«قوائمي» يفتح على المفضّلة؛ وتحسينات أداء وبطارية واسعة.
        // ومعها: رئيسية أبسط مع «المزيد من القوائم»، والتنزيل الذكي
        // للمتابعات عبر الواي فاي، وإكمال التشغيل من التنزيلات عند انقطاع
        // الإنترنت، وحوار تنزيل المصحف كاملاً، واقتراح تنزيل قسمك المفضّل،
        // ووضع قيادة بأزرار أضخم وفتحٍ تلقائي بالبلوتوث (اختياري)، واحتفالٌ
        // بإتمام القسم بشهادة تُشارك، وزرّ إعادة المحاولة لصفحات المصحف.
        // ثم جولة تدقيق ثانية مستقلة: تنزيل المصحف صار عبر WorkManager فلا
        // يموت بإغلاق التطبيق، وذكرُ الحجم وتحذيرُ بيانات الجوّال قبل أي
        // تنزيل كبير، وردُّ صفحات HTML للبوابات الأسيرة، وإكمالُ التشغيل من
        // التنزيلات صار في الخدمة (يعمل والتطبيق بالخلفية)، ولا صمتَ خمس
        // ثوانٍ بين الدروس بالخلفية، وتأخيرٌ أوّلي للتنزيل الذكي كي لا يزاحم
        // الإقلاع. وخفضُ تكاليف شامل بلا مساس بميزة: رؤوس كاش للتخزين،
        // مسبار مزامنة بقراءة واحدة، وقصّ الإشعارات خادمياً.
        // ٢٨ / ٢.٥.٠ — «المكتبة الكاملة»: أرشيف مضغوط بأضعاف (Opus خادمياً)
        // وهوية محتوى SHA-256 بتحقق كل تنزيل وكشف استبدال الصوت؛ لقطة كتالوج
        // مضمّنة فأول تشغيل بلا إنترنت يعرض المكتبة كلها؛ جلب كامل بطلب CDN
        // واحد بدل مئات القراءات مع حارس النسخة الباردة؛ محرك أولوية T0–T4
        // (سياقك ثم متابعاتك ثم المميز ثم الأرشيف) والأقل بايتات أولاً؛ بذر
        // بادئة كاش البث في التنزيلات؛ صفر تنزيل تلقائي على بيانات الهاتف؛
        // حارس طابور بعد إعادة التشغيل وجولات مجزأة لأندرويد ≤13؛ إخلاء ذكي
        // يحصّن اليدوي؛ سؤال ترحيب واحد؛ سجل تشخيص محلي لرسائل الدعم.
        versionCode = 28
        versionName = "2.5.0"
        manifestPlaceholders["appLabel"] = canonicalAppLabel

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        vectorDrawables.useSupportLibrary = true
    }

    signingConfigs {
        if (hasReleaseSigning) {
            create("release") {
                keyAlias = releaseKeyAlias
                keyPassword = releaseKeyPassword
                storeFile = file(checkNotNull(releaseStorePath))
                storePassword = releaseStorePassword
            }
        }
    }

    buildTypes {
        debug {
            applicationIdSuffix = ".dev"
            versionNameSuffix = "-dev"
            // الاسم الظاهر للمستخدم هو «منبر ادكصهك» في كل الأنواع بلا استثناء.
            // لا تُضِف هنا أي لاحقة (تجريبي/dev/beta) — الفصل عن نسخة Play
            // مضمون أصلاً بلاحقة الحزمة `.dev` أعلاه، لا بالاسم الظاهر.
        }
        release {
            if (hasReleaseSigning) {
                signingConfig = signingConfigs.getByName("release")
            }
            // رموز التصحيح للمكتبات الأصلية (تأتي من تبعيات AndroidX) كي
            // تصل أعطال ANR/Crash إلى Play مفهومة بدل عناوين خام.
            ndk { debugSymbolLevel = "SYMBOL_TABLE" }
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro",
            )
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
        isCoreLibraryDesugaringEnabled = true
    }
    buildFeatures {
        compose = true
        buildConfig = true
    }
    // التطبيق عربيّ حرفيّاً (كل نصوصه في الكود)، لكن مكتبات AndroidX/Compose/
    // media3/Firebase تشحن ترجماتها بـ85+ لغة تصير أقساماً لغويّة في الحزمة.
    // الإبقاء على العربيّة + الافتراضيّة يقلّص الحزمة دون أي أثر على الواجهة.
    androidResources {
        localeFilters += listOf("ar")
        // 🕌 أصول المصحف (`.jz`) مضغوطة بـgzip أصلاً؛ إعادة ضغطها في الحزمة
        // لا تُنقص بايتاً وتُبطئ البناء. والامتداد محايد عمداً: لاحقة `.gz`
        // تجعل AGP يفكّ الضغط ويحذف اللاحقة فيختفي الملف الذي يطلبه الكود.
        noCompress += "jz"
    }
    packaging {
        // These two dependency binaries are already stripped by their publishers. Avoid asking
        // AGP to strip them again (which only emits a warning); native metadata is added below.
        jniLibs.keepDebugSymbols += setOf(
            "**/libandroidx.graphics.path.so",
            "**/libdatastore_shared_counter.so",
        )
        resources.excludes += setOf(
            "/META-INF/{AL2.0,LGPL2.1}",
            // واصفات protobuf النصّية (77 مدخلاً، ~155KB تُسلَّم لكل جهاز).
            // protobuf-javalite يقرأ الواصفات المُصرَّفة داخل dex ولا يفتح هذه
            // الملفات وقت التشغيل إطلاقاً — فحصنا امتدادات المداخل كلّها.
            "**/*.proto",
            // بيانات وصفيّة لا يقرأها شيء وقت التشغيل: مِجسّات تصحيح
            // الكوروتينات، وبصمات إصدارات SDK، وبيانات أدوات البناء.
            "META-INF/*.version",
            "META-INF/*.kotlin_module",
            "kotlin-tooling-metadata.json",
            "DebugProbesKt.bin",
        )
    }
    testOptions {
        unitTests.isIncludeAndroidResources = true
    }
}

kotlin {
    compilerOptions {
        jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17)
    }
}

dependencies {
    val composeBom = platform("androidx.compose:compose-bom:2026.06.00")
    implementation(composeBom)
    androidTestImplementation(composeBom)

    implementation("androidx.core:core-ktx:1.17.0")
    implementation("androidx.activity:activity-compose:1.13.0")
    implementation("androidx.fragment:fragment-ktx:1.8.9")
    // 2.11 targets API 37/AGP 9.1; 2.10 is the newest line compatible
    // with the published app's API 36 toolchain.
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.10.0")
    implementation("androidx.lifecycle:lifecycle-runtime-compose:2.10.0")
    implementation("androidx.lifecycle:lifecycle-viewmodel-compose:2.10.0")
    implementation("androidx.navigation:navigation-compose:2.9.8")
    implementation("androidx.work:work-runtime-ktx:2.11.2")

    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.ui:ui-text-google-fonts")
    implementation("androidx.compose.ui:ui-tooling-preview")
    implementation("androidx.compose.foundation:foundation")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.material:material-icons-extended")
    debugImplementation("androidx.compose.ui:ui-tooling")
    debugImplementation("androidx.compose.ui:ui-test-manifest")

    val media3Version = "1.10.1"
    implementation("androidx.media3:media3-exoplayer:$media3Version")
    implementation("androidx.media3:media3-session:$media3Version")
    implementation("androidx.media3:media3-common-ktx:$media3Version")

    // عرض صور «النص المشروح» (صفحات الكتاب) — نفس نسخة لوحة الإدارة.
    implementation("io.coil-kt.coil3:coil-compose:3.2.0")
    implementation("io.coil-kt.coil3:coil-network-okhttp:3.2.0")
    // قصّ صور صفحات الكتاب قبل الإرفاق (واجهة قصّ جاهزة عبر ActivityResult).
    implementation("com.vanniktech:android-image-cropper:4.6.0")
    // موجودة انتقالياً أصلاً عبر Coil/Media3؛ نعلنها مباشرة لأن دمج الصور
    // يقرأ اتجاه EXIF بنفسه، بلا إضافة أي بايت جديد إلى الحزمة النهائية.
    implementation("androidx.exifinterface:exifinterface:1.4.2")

    val firebaseBom = platform("com.google.firebase:firebase-bom:34.16.0")
    implementation(firebaseBom)
    implementation("com.google.firebase:firebase-auth")
    implementation("com.google.firebase:firebase-firestore")
    implementation("com.google.firebase:firebase-functions")
    implementation("com.google.firebase:firebase-storage")
    implementation("com.google.firebase:firebase-messaging")
    implementation("com.google.firebase:firebase-appcheck-playintegrity")
    debugImplementation("com.google.firebase:firebase-appcheck-debug")

    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.10.2")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-play-services:1.10.2")

    coreLibraryDesugaring("com.android.tools:desugar_jdk_libs:2.1.5")

    testImplementation("junit:junit:4.13.2")
    testImplementation("org.jetbrains.kotlinx:kotlinx-coroutines-test:1.10.2")
    testImplementation("androidx.test:core-ktx:1.7.0")
    testImplementation("org.robolectric:robolectric:4.16.1")
    androidTestImplementation("androidx.test.ext:junit:1.3.0")
    androidTestImplementation("androidx.test.espresso:espresso-core:3.7.0")
    androidTestImplementation("androidx.compose.ui:ui-test-junit4")
}

tasks.matching {
    it.name == "bundleRelease" || it.name == "assembleRelease"
}.configureEach {
    doFirst {
        check(hasReleaseSigning) {
            "Missing release signing values. Use signing.properties or the MINBAR_SIGNING_* environment variables with the original upload key."
        }
    }
}

// حارس دائم لاسم التطبيق الظاهر. سبق أن سُلّمت نسخة باسم «منبر ادكصهك (تجريبي)»
// إلى الجهاز، وهو خطأ لا يجوز تكراره: الاسم الذي يراه المستخدم هو
// «منبر ادكصهك» في **كل** أنواع البناء بلا استثناء، والفصل عن نسخة Play
// يكون بلاحقة الحزمة `.dev` لا بالاسم. يعمل بعد اكتمال كل كتل الـDSL،
// فيلتقط أي لاحقة تُضاف لاحقاً في أي نوع بناء ويوقف البناء فوراً.
androidComponents {
    finalizeDsl { extension ->
        extension.buildTypes.forEach { buildType ->
            val label = buildType.manifestPlaceholders["appLabel"]
            check(label == null || label == canonicalAppLabel) {
                "اسم التطبيق الظاهر في نوع البناء «${buildType.name}» صار «$label». " +
                    "يجب أن يبقى «$canonicalAppLabel» بلا أي لاحقة (تجريبي/dev/beta) " +
                    "في كل الأنواع — الفصل عن نسخة Play بلاحقة الحزمة لا بالاسم."
            }
        }
    }
}

// تحذير Play «لم يتم تحميل أي رموز لتصحيح الأخطاء»: كل المكتبات الأصلية هنا
// تأتي من AndroidX مجرّدةً من جدول الرموز الكامل (.symtab)، فمهمة AGP
// extractReleaseNativeSymbolTables تخرج صفر ملفات ولا يُضمَّن شيء في الحزمة
// فيبقى التحذير. المكتبات تحتفظ بجدولها الديناميكي (.dynsym) — وهو كل ما
// يملكه أحد أصلاً لهذه المكتبات — فنضمّنه بأنفسنا بصيغة <lib>.so.sym التي
// تلتقطها حزمة AAB في BUNDLE-METADATA/com.android.tools.build.debugsymbols
// فيزول التحذير وتتحسّن قراءة أعطالها في Play بلا أي أثر على التطبيق.
// ─── لقطة الكتالوج المضمّنة (معمارية «المكتبة الكاملة») ─────────────────
// تُجلب من واجهة الكتالوج وقت البناء وتُدقَّق (أعدادها تطابق مصفوفاتها) ثم
// تُضغط إلى assets/catalog/snapshot.jz — فأول تشغيل بلا إنترنت يتصفح المكتبة
// كلها. أي فشل جلبٍ أو تدقيق **يُفشل بناء الإصدار** عمداً: لقطة فاسدة أسوأ
// من لا لقطة. للبناء بلا شبكة: ‎-PskipCatalogSnapshot (تبقى لقطة المستودع).
val refreshCatalogSnapshot = tasks.register("refreshCatalogSnapshot") {
    outputs.upToDateWhen { false }
    doLast {
        if (project.hasProperty("skipCatalogSnapshot")) return@doLast
        val text = URL("https://minbar-adkassahk.vercel.app/api/catalog")
            .openConnection().apply { connectTimeout = 20000; readTimeout = 60000 }
            .getInputStream().bufferedReader().readText()
        @Suppress("UNCHECKED_CAST")
        val parsed = groovy.json.JsonSlurper().parseText(text) as Map<String, Any?>
        val counts = parsed["counts"] as Map<*, *>
        val lessons = parsed["lessons"] as List<*>
        val categories = parsed["categories"] as List<*>
        val subcategories = parsed["subcategories"] as List<*>
        require(
            lessons.isNotEmpty() &&
                lessons.size == (counts["lessons"] as Number).toInt() &&
                categories.size == (counts["categories"] as Number).toInt() &&
                subcategories.size == (counts["subcategories"] as Number).toInt(),
        ) { "لقطة الكتالوج فاسدة أو ناقصة — أُفشل البناء حمايةً لأول تشغيل بلا إنترنت." }
        val out = layout.projectDirectory.file("src/main/assets/catalog/snapshot.jz").asFile
        out.parentFile.mkdirs()
        GZIPOutputStream(out.outputStream()).use { it.write(text.toByteArray()) }
        println("لقطة الكتالوج: ${lessons.size} درساً، ${out.length()} بايت")
    }
}
tasks.matching { it.name == "preReleaseBuild" }.configureEach {
    dependsOn(refreshCatalogSnapshot)
}

tasks.matching { it.name == "extractReleaseNativeSymbolTables" }.configureEach {
    doLast {
        val mergedLibs = layout.buildDirectory
            .dir("intermediates/merged_native_libs/release/mergeReleaseNativeLibs/out/lib")
            .get().asFile
        val symbolsOut = layout.buildDirectory
            .dir("intermediates/native_symbol_tables/release/extractReleaseNativeSymbolTables/out")
            .get().asFile
        mergedLibs.walkTopDown().filter { it.isFile && it.extension == "so" }.forEach { so ->
            val target = File(symbolsOut, "${so.parentFile.name}/${so.name}.sym")
            target.parentFile.mkdirs()
            so.copyTo(target, overwrite = true)
        }
    }
}
