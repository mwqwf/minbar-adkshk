# فهرس المشروع الآليّ — `minbar-adkshk`

> ⛔ مولَّد آلياً بـ`tools/agents/gen_project_index.py` (سير عمل `project-index` عند الدفع إلى الفرع الرئيسيّ). لا تعدّله يدوياً.
> الحالة والخطة والعمل المفتوح في `docs/PROJECT_STATE.md` (يحدّثها الوكلاء في كل تغيير).

## الإصدار
| الملف | الحزمة | versionCode | versionName |
|---|---|---|---|
| `app/build.gradle.kts` | `com.ali.menbaradkshk` | 40 | 2.8.4 |

## وحدات Gradle
`:app` · `:$pack`

## البنية (عدد الملفات المتتبَّعة)
| المجلد | الملفات |
|---|---|
| `.agents/` | 298 |
| `.github/` | 7 |
| `app/` | 121 |
| `core_pack/` | 2 |
| `docs/` | 5 |
| `gradle/` | 2 |
| `rest_pack/` | 2 |
| `tools/` | 18 |

## سير العمل (6)
| الملف | الاسم | المحفّزات | الجدول (cron UTC) | المشغّل |
|---|---|---|---|---|
| `bundle-with-assets.yml` | bundle-with-assets | push, workflow_dispatch | — | ubuntu |
| `emulator-check.yml` | emulator-check | pull_request, workflow_dispatch | — | ubuntu |
| `play-status.yml` | play-status | schedule, workflow_dispatch | 17 3 * * * | ubuntu |
| `play-vitals.yml` | play-vitals | push, schedule, workflow_dispatch | 47 3 * * * | ubuntu |
| `pr-check.yml` | pr-check | pull_request | — | ubuntu |
| `project-index.yml` | project-index | push, workflow_dispatch | — | ubuntu |

## المهارات (26) — `.agents/skills/` (و`.claude/skills/` روابط لها)
`adaptive` (مركزية) · `agp-9-upgrade` (مركزية) · `android-cli` (مركزية) · `android-performance` (مركزية) · `android-play-aso` (مركزية) · `android-security-hardening` (مركزية) · `arabic-rtl-and-islamic-typography` (مركزية) · `automation-first` (مركزية) · `edge-to-edge` (مركزية) · `google-play-publish` (مركزية) · `impeccable` (مركزية) · `kotlin-tooling-agp9-migration` (مركزية) · `media3-cast-integration` (مركزية) · `minbar-platform` (مركزية) · `mwqwf-workflow` (مركزية) · `navigation-3` (مركزية) · `navigation-event` (مركزية) · `play-policy-insights` (مركزية) · `project-registry` (مركزية) · `receiving-code-review` (مركزية) · `systematic-debugging` (مركزية) · `test-driven-development` (مركزية) · `testing-setup` (مركزية) · `verification-before-completion` (مركزية) · `verify-and-review` (مركزية) · `writing-plans` (مركزية)

## وثائق `docs/`
[`CLOUD-SESSION.md`](CLOUD-SESSION.md) · [`OWNER_AUDIT_2026-10-02.md`](OWNER_AUDIT_2026-10-02.md) · [`PROJECT_STATE.md`](PROJECT_STATE.md)
