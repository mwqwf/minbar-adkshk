pluginManagement {
    repositories {
        google()
        mavenCentral()
        gradlePluginPortal()
    }
}

dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        google()
        mavenCentral()
    }
}

rootProject.name = "منبر ادكصهك"
include(":app")

// 🎁 حزم أصول Play (المكتبة دون إنترنت): تُدرج فقط حين تكون مملوءة (CI عبر
// tools/assetpack/fetch.py). محلياً بلا ملفات ⇒ الحزمة كما هي بلا حزم فارغة.
listOf("core_pack", "rest_pack").forEach { pack ->
    val filled = file("$pack/src/main/assets/serving").listFiles()?.any { it.name.endsWith(".ogg") } == true
    if (filled) include(":$pack")
}

