#!/usr/bin/env python3
"""🚧 حارسٌ يمنع عودة نسخةٍ ميّتة من الموقع إلى هذا المستودع.

**الخطأ الذي وقع (2026-09-13)**: كان هنا مجلّد `site/` فيه صفحات الموقع،
فظُنَّ أنّه المنشور. وصُحّحت فيه سياسةُ الخصوصية — ولم ينتفع بها أحد، لأنّ
`minbar-adkassahk.vercel.app` يُنشر من مستودعٍ آخر: **mwqwf/menbar-site**
(مشروع Vercel `prj_xgQzb3NbkMtfPmJGlwiEzlXdD6H8`).

⛔ ونسخةٌ ميّتة من وثيقةٍ قانونية أسوأ من غيابها: تُطمئن من يقرؤها إلى أنّ
الأمر مُصلَح وهو لم يُصلَح. فلا يُعاد إنشاء `site/` هنا.

ولو نُقل الموقع إلى هذا المستودع يوماً، فالنقل **لا يتمّ بنقل الملفّات**: يجب
أوّلاً إعادةُ ربط مشروع Vercel من لوحته (Settings ← Git) بهذا المستودع مع ضبط
Root Directory. فإن سبق نقلُ الملفّات ربطَ Vercel عادت المشكلة نفسها: نسختان،
والمنشورة هي القديمة. وعند إتمام النقل يُحذف هذا الحارس بإيداعٍ يذكر السبب.

    python3 tools/no_dead_site_copy.py    # يخرج بـ1 إن عادت النسخة
"""
from __future__ import annotations

import sys
from pathlib import Path

ROOT = Path(__file__).resolve().parent.parent
# مجلّداتٌ لو ظهرت هنا بصفحات ويب فهي نسخةٌ ميّتة على الأرجح.
SUSPECT_DIRS = ("site", "web", "www", "public")
WEB_SUFFIXES = {".html", ".htm"}


def main() -> int:
    found: list[str] = []
    for name in SUSPECT_DIRS:
        directory = ROOT / name
        if not directory.is_dir():
            continue
        for path in directory.rglob("*"):
            if path.suffix.lower() in WEB_SUFFIXES:
                found.append(str(path.relative_to(ROOT)))

    if not found:
        print("✅ لا نسخةَ ميّتة من الموقع في هذا المستودع")
        return 0

    print("⛔ عادت نسخةٌ من صفحات الموقع إلى هذا المستودع:", file=sys.stderr)
    for path in sorted(found)[:20]:
        print("   ·", path, file=sys.stderr)
    print(
        "\nوالموقع يُنشر من mwqwf/menbar-site لا من هنا، فهذه النسخة لن تصل إلى\n"
        "أحد — وسياسةُ خصوصيةٍ ميّتة أسوأ من غيابها.\n"
        "· أصلح صفحات الموقع في mwqwf/menbar-site.\n"
        "· وإن كان النقل مقصوداً: أعد ربط مشروع Vercel أوّلاً ثمّ احذف هذا الحارس.",
        file=sys.stderr,
    )
    return 1


if __name__ == "__main__":
    raise SystemExit(main())
