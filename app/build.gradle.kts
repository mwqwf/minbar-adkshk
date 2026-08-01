import java.util.Properties

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
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

android {
    namespace = "com.ali.menbaradkshk"
    compileSdk = 36

    defaultConfig {
        applicationId = "com.ali.menbaradkshk"
        minSdk = 23
        targetSdk = 36
        // 9 / 1.3.5: يضمّ إصلاحات «شارك درساً» (الزرّ يشرح النقص، الإقرارات اختيارية)
        // وشاشة الإشعارات (تمييز «مسحتَها أنت» عن «لم يصل شيء» مع استعادة المُستبعَدة).
        // 8 / 1.3.4 كانت نسخة معالجة رفض Play (أندرويد أوتو) وتحذير العرض حتى الحافة.
        // رقم الإصدار **يجب** أن يزيد عن كل ما رُفع سابقاً وإلا رفض المتجر الرفع.
        versionCode = 9
        versionName = "1.3.5"
        manifestPlaceholders["appLabel"] = "منبر ادكصهك"

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
            manifestPlaceholders["appLabel"] = "منبر ادكصهك (تجريبي)"
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
    }
    packaging {
        resources.excludes += setOf(
            "/META-INF/{AL2.0,LGPL2.1}",
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

// تحذير Play «لم يتم تحميل أي رموز لتصحيح الأخطاء»: كل المكتبات الأصلية هنا
// تأتي من AndroidX مجرّدةً من جدول الرموز الكامل (.symtab)، فمهمة AGP
// extractReleaseNativeSymbolTables تخرج صفر ملفات ولا يُضمَّن شيء في الحزمة
// فيبقى التحذير. المكتبات تحتفظ بجدولها الديناميكي (.dynsym) — وهو كل ما
// يملكه أحد أصلاً لهذه المكتبات — فنضمّنه بأنفسنا بصيغة <lib>.so.sym التي
// تلتقطها حزمة AAB في BUNDLE-METADATA/com.android.tools.build.debugsymbols
// فيزول التحذير وتتحسّن قراءة أعطالها في Play بلا أي أثر على التطبيق.
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
            if (!target.exists()) {
                target.parentFile.mkdirs()
                so.copyTo(target)
            }
        }
    }
}
