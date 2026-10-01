#!/usr/bin/env python3
"""📝 حارس ملاحظات الإصدار: رفعُ `versionCode` بلا موجزٍ جديد لا يمرّ.

**لماذا؟** في هذا المستودع الدمجُ ينشر إلى الإنتاج، والموجز في
`data/ReleaseNotes.kt` هو ما يقرؤه الناس في إشعار «تتوفّر نسخة أحدث».
وكان التعليق هناك يعِد بـ«حارس بناءٍ يمنع نسيانه» ولم يكن له وجود في
`app/build.gradle.kts` ولا في أيّ سير عمل. فهذا هو الحارس الموعود.

يقارن الشجرة الحاليّة بمرجع الأساس (افتراضاً `origin/main`):
إن تغيّر `versionCode` ولم يتغيّر `SUMMARY` ⇒ خروجٌ بـ1 ورسالةٌ عربيّة.

    python3 tools/check_release_notes.py                 # مقابل origin/main
    python3 tools/check_release_notes.py --base <مرجع>
"""
from __future__ import annotations

import argparse
import re
import subprocess
import sys
from pathlib import Path

ROOT = Path(__file__).resolve().parent.parent
GRADLE = "app/build.gradle.kts"
NOTES = "app/src/main/kotlin/com/ali/menbaradkshk/data/ReleaseNotes.kt"

CODE_RE = re.compile(r"^\s*versionCode\s*=\s*(\d+)", re.M)
# الموجز سلسلةُ سلاسل مقتبسة تُجمع بـ`+` حتى الدالّة التالية
SUMMARY_RE = re.compile(r"SUMMARY\s*:\s*String\s*=(.*?)\n\s*/\*\*", re.S)


def at_base(base: str, rel: str) -> str | None:
    r = subprocess.run(["git", "show", f"{base}:{rel}"], cwd=ROOT, capture_output=True, text=True)
    return r.stdout if r.returncode == 0 else None


def version_code(src: str) -> str | None:
    m = CODE_RE.search(src)
    return m.group(1) if m else None


def summary(src: str) -> str:
    m = SUMMARY_RE.search(src)
    body = m.group(1) if m else src
    return "".join(re.findall(r'"((?:[^"\\]|\\.)*)"', body)).strip()


def main() -> int:
    ap = argparse.ArgumentParser()
    ap.add_argument("--base", default="origin/main")
    a = ap.parse_args()

    base_gradle = at_base(a.base, GRADLE)
    if base_gradle is None:
        print(f"⚠️ تعذّرت قراءة {GRADLE} من {a.base} — لا مقارنة", file=sys.stderr)
        return 0
    old, new = version_code(base_gradle), version_code((ROOT / GRADLE).read_text(encoding="utf-8"))
    if old == new:
        print(f"✅ versionCode لم يتغيّر ({new}) — لا يلزم موجزٌ جديد")
        return 0

    base_notes = at_base(a.base, NOTES) or ""
    cur_notes = (ROOT / NOTES).read_text(encoding="utf-8")
    if summary(base_notes) == summary(cur_notes):
        print(
            f"⛔ رُفع versionCode من {old} إلى {new} ولم يتغيّر موجز الإصدار في {NOTES}.\n"
            "الدمج هنا ينشر إلى الإنتاج، والموجز هو ما يقرؤه الناس في إشعار التحديث.\n"
            "اكتب في SUMMARY ما يكسبه المستخدم في هذه النسخة (≤300 حرف، بلغته لا بلغتنا).",
            file=sys.stderr,
        )
        return 1
    print(f"✅ versionCode {old} ⇒ {new} ومعه موجزٌ جديد")
    return 0


if __name__ == "__main__":
    sys.exit(main())
