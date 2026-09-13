#!/usr/bin/env bash
# 📱 برهان المحاكي: تثبيتٌ وإقلاعٌ وقراءةُ سجلّ — يُشغَّل داخل عدّاء المحاكي.
# ⛔ لا تُوزَّع أسطرُه في `script:` مباشرةً: عدّاءُ المحاكي ينفّذ كلَّ سطرٍ في
#    صدفةٍ مستقلّة، فتضيع المتغيّرات ويصير الحارس كاذباً (وقع 2026-09-13:
#    `PKG` فارغاً ⇒ `grep "ANR in "` طابق تجمّدَ تطبيقٍ آخر فأسقط التشغيلة).
set -euo pipefail

APK=app/build/outputs/apk/debug/app-debug.apk
adb install -r "$APK"

PKG=$(adb shell pm list packages | tr -d '\r' | grep -oE 'com\.ali\.menbaradkshk[A-Za-z0-9._]*' | head -1)
[ -n "$PKG" ] || { echo '⛔ لم تُثبَّت الحزمة'; exit 1; }
echo "الحزمة: $PKG"

adb shell pm grant "$PKG" android.permission.POST_NOTIFICATIONS || true
adb logcat -c
adb shell am start -n "$PKG/com.ali.menbaradkshk.MainActivity"
sleep 45

adb logcat -d > logcat.txt
adb exec-out screencap -p > screen.png || true
FG=$(adb shell dumpsys activity activities | tr -d '\r' | grep -m1 topResumedActivity || true)
echo "المقدّمة: $FG"

if grep -q "FATAL EXCEPTION" logcat.txt; then
  echo '⛔ انهيار:'; grep -A25 "FATAL EXCEPTION" logcat.txt | head -30; exit 1
fi
if grep -q "ANR in $PKG" logcat.txt; then
  echo '⛔ تجمّد (ANR) في تطبيقنا:'; grep -A10 "ANR in $PKG" logcat.txt | head -15; exit 1
fi
case "$FG" in
  *"$PKG"*) echo "✅ أقلع وبقي في المقدّمة ٤٥ ثانية بلا انهيارٍ ولا تجمّد." ;;
  *) echo "⛔ التطبيق ليس في المقدّمة بعد ٤٥ ثانية"; exit 1 ;;
esac
