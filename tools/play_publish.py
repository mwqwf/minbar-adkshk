#!/usr/bin/env python3
"""📤 رفع حزمة AAB إلى Google Play بحساب خدمة — لأيّ تطبيق في حساب المالك.

أداةٌ واحدة تكفي كلّ المشاريع (منبر · لوحته · مصحفك · أيّ تطبيقٍ قادم)، لأنّ إذن
حساب الخدمة في Play ممنوحٌ على مستوى **الحساب** لا على مستوى تطبيق (مقيسٌ
2026-09-12: منبر واللوحة ومصحفك كلّها مسموحة بالدعوة نفسها).

الاستعمال:
  python play_publish.py --list --package com.ali.menbaradkshk
  python play_publish.py --package com.ali.ishaqiyin_admin \\
      --aab release/minbar-admin-1.8.2-vc2024.aab --track internal --notes "..."

حساب الخدمة الافتراضيّ: migration-cache/fcm_service_account.json (⛔ خارج git).
⚠️ لا يرفع هذا السكربت شيئاً إلا بـ`--aab`؛ و`--list` قراءةٌ محضة.
"""
import argparse, base64, json, os, sys, time, urllib.error, urllib.parse, urllib.request
from cryptography.hazmat.primitives import hashes, serialization
from cryptography.hazmat.primitives.asymmetric import padding

API = "https://androidpublisher.googleapis.com/androidpublisher/v3/applications"
DEFAULT_SA = os.path.join(os.path.dirname(__file__), "..", "..", "migration-cache", "fcm_service_account.json")
_b64 = lambda b: base64.urlsafe_b64encode(b).rstrip(b"=")


def _retry(fn, tries=5, wait=4):
    """شبكة المالك ضعيفة ومتقطّعة — كلّ نداءٍ يُعاد، وخطأ الخادم يُرفع كما هو."""
    for i in range(tries):
        try:
            return fn()
        except urllib.error.HTTPError:
            raise
        except Exception:
            if i == tries - 1:
                raise
            time.sleep(wait)


def access_token(sa_path: str) -> str:
    sa = json.load(open(sa_path, encoding="utf-8"))
    now = int(time.time())
    head = _b64(json.dumps({"alg": "RS256", "typ": "JWT"}).encode())
    claims = _b64(json.dumps({
        "iss": sa["client_email"],
        "scope": "https://www.googleapis.com/auth/androidpublisher",
        "aud": "https://oauth2.googleapis.com/token",
        "iat": now, "exp": now + 3600,
    }).encode())
    key = serialization.load_pem_private_key(sa["private_key"].encode(), password=None)
    sig = _b64(key.sign(head + b"." + claims, padding.PKCS1v15(), hashes.SHA256()))
    jwt = (head + b"." + claims + b"." + sig).decode()
    data = urllib.parse.urlencode({
        "grant_type": "urn:ietf:params:oauth:grant-type:jwt-bearer", "assertion": jwt}).encode()
    res = _retry(lambda: json.load(urllib.request.urlopen(
        urllib.request.Request("https://oauth2.googleapis.com/token", data=data), timeout=120)))
    return res["access_token"]


def call(tok, url, method="GET", data=None, ctype="application/json"):
    req = urllib.request.Request(url, data=data, method=method,
                                 headers={"Authorization": "Bearer " + tok, "Content-Type": ctype})
    return _retry(lambda: urllib.request.urlopen(req, timeout=900))


def main() -> int:
    p = argparse.ArgumentParser()
    p.add_argument("--package", required=True)
    p.add_argument("--aab")
    p.add_argument("--track", default="internal")
    p.add_argument("--notes", default="")
    p.add_argument("--sa", default=DEFAULT_SA)
    p.add_argument("--list", action="store_true", help="اعرض المسارات وإصداراتها ولا ترفع شيئاً")
    p.add_argument("--promote", help="رقّ إصداراً مرفوعاً سلفاً إلى مسارٍ آخر (versionCode)")
    a = p.parse_args()

    tok = access_token(a.sa)
    base = f"{API}/{a.package}/edits"
    edit = json.load(call(tok, base, "POST", b""))["id"]
    try:
        if a.list or (not a.aab and not a.promote):
            tracks = json.load(call(tok, f"{base}/{edit}/tracks"))
            for tr in tracks.get("tracks", []):
                for r in tr.get("releases", []):
                    if r.get("versionCodes"):
                        print(f"{tr['track']:<12} {r.get('status'):<10} {r['versionCodes']}  {r.get('name','')}")
            return 0

        if a.promote:
            rel = {"name": str(a.promote), "versionCodes": [str(a.promote)], "status": "completed"}
            if a.notes:
                rel["releaseNotes"] = [{"language": "ar", "text": a.notes[:500]}]
            call(tok, f"{base}/{edit}/tracks/{a.track}", "PUT",
                 json.dumps({"track": a.track, "releases": [rel]}).encode())
            call(tok, f"{base}/{edit}:commit", "POST", b"")
            print(f"تمّت الترقية: {a.promote} إلى مسار «{a.track}».")
            return 0

        size = os.path.getsize(a.aab)
        print(f"رفع {size/1e6:.1f} م.ب …")
        with open(a.aab, "rb") as f:
            up = json.load(call(
                tok,
                f"https://androidpublisher.googleapis.com/upload/androidpublisher/v3/applications/"
                f"{a.package}/edits/{edit}/bundles?uploadType=media",
                "POST", f.read(), "application/octet-stream"))
        code = up["versionCode"]
        print("رُفعت — versionCode =", code)

        rel = {"name": str(code), "versionCodes": [str(code)], "status": "completed"}
        if a.notes:
            rel["releaseNotes"] = [{"language": "ar", "text": a.notes[:500]}]
        call(tok, f"{base}/{edit}/tracks/{a.track}", "PUT",
             json.dumps({"track": a.track, "releases": [rel]}).encode())
        call(tok, f"{base}/{edit}:commit", "POST", b"")
        print(f"✅ نُشرت على مسار «{a.track}» — لا حاجة إلى متصفّح.")
        return 0
    except urllib.error.HTTPError as e:
        body = e.read().decode("utf-8", "ignore")
        print("⛔ خطأ", e.code, ":", body[:400], file=sys.stderr)
        try:
            call(tok, f"{base}/{edit}", "DELETE")
        except Exception:
            pass
        return 1


if __name__ == "__main__":
    raise SystemExit(main())
