#!/usr/bin/env python3
"""🔄 مزامنة أرقام الإصدار في README مع مصدر الحقيقة `app/build.gradle.kts`.

**لماذا؟** كُتب README عند الترحيل من Flutter فذكر `versionCode=6` و`1.3.2`،
وبقي كذلك بينما بلغ التطبيق `32/2.7.2`. توثيقٌ يكذب على قارئه أخطرُ من لا توثيق:
من يراجع المستودع يبني على رقمٍ ميت.

**آليّاً لا يدوياً**: يُستدعى بلا وسائط فيكتب الأرقام الصحيحة، أو بـ`--check`
فيَفشل إن تخلّف README — وهذه الصورة هي حارس البناء في `bundle-with-assets.yml`،
فلا يصل شيءٌ إلى الإنتاج ووصفُه متخلّف.

    python3 tools/sync_readme_version.py           # يُصلح
    python3 tools/sync_readme_version.py --check    # يفحص فقط (خروج 1 عند التخلّف)
"""
from __future__ import annotations

import re
import sys
from pathlib import Path

ROOT = Path(__file__).resolve().parent.parent
GRADLE = ROOT / "app" / "build.gradle.kts"
README = ROOT / "README.md"

# سطور README المحكومة: (نمط الالتقاط، قالب البديل)
RULES = [
    (re.compile(r"(`com\.ali\.menbaradkshk`، و`versionCode=)\d+(`)"), "{code}"),
    (re.compile(r"(- `versionCode`: `)\d+(`)"), "{code}"),
    (re.compile(r"(- `versionName`: `)[^`]+(`)"), "{name}"),
]


def read_version() -> tuple[str, str]:
    """يقرأ `versionCode` و`versionName` من مصدر الحقيقة."""
    src = GRADLE.read_text(encoding="utf-8")
    code = re.search(r"^\s*versionCode\s*=\s*(\d+)", src, re.M)
    name = re.search(r"^\s*versionName\s*=\s*\"([^\"]+)\"", src, re.M)
    if not code or not name:
        sys.exit(f"⛔ تعذّرت قراءة الإصدار من {GRADLE.relative_to(ROOT)}")
    return code.group(1), name.group(1)


def apply(text: str, code: str, name: str) -> str:
    for pattern, tmpl in RULES:
        value = tmpl.format(code=code, name=name)
        text = pattern.sub(lambda m: m.group(1) + value + m.group(2), text)
    return text


def main() -> int:
    check = "--check" in sys.argv[1:]
    code, name = read_version()
    before = README.read_text(encoding="utf-8")
    after = apply(before, code, name)

    if before == after:
        print(f"✅ README مطابقٌ للإصدار {code}/{name}")
        return 0
    if check:
        print(
            f"⛔ README متخلّف عن مصدر الحقيقة ({code}/{name}).\n"
            "   أصلحه بـ: python3 tools/sync_readme_version.py",
            file=sys.stderr,
        )
        return 1
    README.write_text(after, encoding="utf-8")
    print(f"🔄 حُدِّث README إلى الإصدار {code}/{name}")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
