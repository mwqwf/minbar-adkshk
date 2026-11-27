# فهرس المشروع الآليّ — `minbar-adkshk`

> ⛔ مولَّد آلياً بـ`tools/agents/gen_project_index.py` (سير عمل `project-index` عند الدفع إلى الفرع الرئيسيّ). لا تعدّله يدوياً.
> الحالة والخطة والعمل المفتوح في `docs/PROJECT_STATE.md` (يحدّثها الوكلاء في كل تغيير).

## الإصدار
| الملف | الحزمة | versionCode | versionName |
|---|---|---|---|
| `app/build.gradle.kts` | `com.ali.menbaradkshk` | 41 | 2.9.0 |

## وحدات Gradle
`:app` · `:$pack`

## البنية (عدد الملفات المتتبَّعة)
| المجلد | الملفات |
|---|---|
| `.agents/` | 26 |
| `.github/` | 7 |
| `app/` | 341 |
| `core_pack/` | 2 |
| `docs/` | 5 |
| `gradle/` | 2 |
| `more_pack/` | 2 |
| `rest_pack/` | 2 |
| `tools/` | 21 |

## سير العمل (6)
| الملف | الاسم | المحفّزات | الجدول (cron UTC) | المشغّل |
|---|---|---|---|---|
| `bundle-with-assets.yml` | bundle-with-assets | push, workflow_dispatch | — | ubuntu |
| `emulator-check.yml` | emulator-check | pull_request, workflow_dispatch | — | ubuntu |
| `play-status.yml` | play-status | schedule, workflow_dispatch | 17 3 * * * | ubuntu |
| `play-vitals.yml` | play-vitals | push, schedule, workflow_dispatch | 47 3 * * * | ubuntu |
| `pr-check.yml` | pr-check | pull_request | — | ubuntu |
| `project-index.yml` | project-index | push, workflow_dispatch | — | ubuntu |

## المهارات (6) — `.agents/skills/` (و`.claude/skills/` روابط لها)
`android-engineering` (مركزية) · `arabic-text-integrity` (مركزية) · `engineering-discipline` (مركزية) · `google-play` (مركزية) · `minbar-platform` (مركزية) · `mwqwf-workflow` (مركزية)

## وثائق `docs/`
[`CLOUD-SESSION.md`](CLOUD-SESSION.md) · [`OWNER_AUDIT_2026-10-02.md`](OWNER_AUDIT_2026-10-02.md) · [`PROJECT_STATE.md`](PROJECT_STATE.md)
