#!/usr/bin/env python3
"""🩺 قراءة صحّة التطبيق من Google Play (Android Vitals) بحساب الخدمة — قراءةٌ محضة.

يقرأ لكلّ حزمة: نسبة الأعطال وعدم الاستجابة (ANR) لكلّ versionCode، وقائمة
مشكلات الأعطال مرتّبةً بعدد التقارير مع عيّنة أثرٍ لكلّ واحدة، ومراجعات الأسبوع.
لا يفتح «تحريراً» في Play، فلا يزاحم نشراً جارياً (بخلاف play_publish.py --list).

  python tools/play_vitals.py --sa sa.json --package com.ali.menbaradkshk --out vitals.json
"""
import argparse, datetime, json, sys, urllib.error, urllib.parse, urllib.request

sys.path.insert(0, __import__("os").path.dirname(__file__))
import play_publish  # noqa: E402 — نعيد استعمال توقيع JWT نفسه

REP = "https://playdeveloperreporting.googleapis.com/v1beta1/apps/"
PUB = "https://androidpublisher.googleapis.com/androidpublisher/v3/applications/"
SCOPES = ("https://www.googleapis.com/auth/playdeveloperreporting "
          "https://www.googleapis.com/auth/androidpublisher")


def req(tok, url, body=None):
    data = json.dumps(body).encode() if body is not None else None
    r = urllib.request.Request(url, data=data, method="POST" if data else "GET",
                               headers={"Authorization": "Bearer " + tok, "Content-Type": "application/json"})
    try:
        return json.load(urllib.request.urlopen(r, timeout=120))
    except urllib.error.HTTPError as e:
        return {"_error": e.code, "_body": e.read().decode(errors="replace")[:600]}


def metric(tok, pkg, name, metrics, days=28):
    meta = req(tok, f"{REP}{pkg}/{name}")
    if "_error" in meta:
        return meta
    end = next((f["latestEndTime"] for f in meta.get("freshnessInfo", {}).get("freshnesses", [])
                if f.get("aggregationPeriod") == "DAILY"), None)
    if not end:
        return {"_error": "no-freshness", "_body": json.dumps(meta)[:400]}
    e = datetime.date(end["year"], end["month"], end["day"])
    s = e - datetime.timedelta(days=days)
    d = lambda x: {"year": x.year, "month": x.month, "day": x.day, "timeZone": end.get("timeZone")}
    return req(tok, f"{REP}{pkg}/{name}:query", {
        "timelineSpec": {"aggregationPeriod": "DAILY", "startTime": d(s), "endTime": d(e)},
        "dimensions": ["versionCode"], "metrics": metrics, "pageSize": 1000})


def summarize_metric(res, keys):
    """يجمع الصفوف اليومية إلى متوسّطٍ لكلّ versionCode."""
    if "_error" in res:
        return res
    agg = {}
    for row in res.get("rows", []):
        vc = next((dm.get("stringValue") or str(dm.get("int64Value")) for dm in row.get("dimensions", [])
                   if dm.get("dimension") == "versionCode"), "?")
        a = agg.setdefault(vc, {k: [] for k in keys})
        for m in row.get("metrics", []):
            if m["metric"] in keys:
                v = m.get("decimalValue", {}).get("value") or m.get("int64Value")
                if v is not None:
                    a[m["metric"]].append(float(v))
    return {vc: {k: (round(sum(v) / len(v), 5) if v else None) for k, v in d.items()} | {"days": max(len(v) for v in d.values())}
            for vc, d in sorted(agg.items())}


def main():
    p = argparse.ArgumentParser()
    p.add_argument("--sa", required=True)
    p.add_argument("--package", action="append", required=True)
    p.add_argument("--out", default="vitals.json")
    a = p.parse_args()

    tok = play_publish.access_token(a.sa, SCOPES)

    report = {}
    for pkg in a.package:
        r = report[pkg] = {}
        r["crash"] = summarize_metric(metric(tok, pkg, "crashRateMetricSet",
                                             ["crashRate", "userPerceivedCrashRate", "distinctUsers"]),
                                      ["crashRate", "userPerceivedCrashRate", "distinctUsers"])
        r["anr"] = summarize_metric(metric(tok, pkg, "anrRateMetricSet",
                                           ["anrRate", "userPerceivedAnrRate", "distinctUsers"]),
                                    ["anrRate", "userPerceivedAnrRate", "distinctUsers"])
        issues = req(tok, f"{REP}{pkg}/errorIssues:search?pageSize=50")
        if "_error" in issues:
            r["issues"] = issues
        else:
            r["issues"] = []
            for it in issues.get("errorIssues", []):
                name = it["name"].split("/")[-1]
                q = urllib.parse.urlencode({"filter": f"errorIssueId = {name}", "pageSize": 2})
                rep = req(tok, f"{REP}{pkg}/errorReports:search?{q}")
                it["sampleReports"] = [x.get("reportText", "")[:6000] for x in rep.get("errorReports", [])] \
                    if "_error" not in rep else rep
                r["issues"].append(it)
        rev = req(tok, f"{PUB}{pkg}/reviews?maxResults=50")
        r["reviews"] = rev if "_error" in rev else [
            {"stars": c["comments"][0]["userComment"].get("starRating"),
             "text": c["comments"][0]["userComment"].get("text", "").strip(),
             "version": c["comments"][0]["userComment"].get("appVersionCode"),
             "android": c["comments"][0]["userComment"].get("androidOsVersion"),
             "device": c["comments"][0]["userComment"].get("device")}
            for c in rev.get("reviews", [])]

    json.dump(report, open(a.out, "w", encoding="utf-8"), ensure_ascii=False, indent=1)
    # ملخّص مقروء في السجلّ
    for pkg, r in report.items():
        print(f"\n===== {pkg}")
        print("crash:", json.dumps(r["crash"], ensure_ascii=False))
        print("anr:  ", json.dumps(r["anr"], ensure_ascii=False))
        iss = r["issues"]
        if isinstance(iss, dict):
            print("issues ERROR:", iss)
        else:
            print(f"issues: {len(iss)}")
            for it in iss:
                print(f"--- [{it.get('type')}] reports={it.get('errorReportCount')} users={it.get('distinctUsers')} "
                      f"versions={it.get('firstAppVersion',{}).get('versionCode')}..{it.get('lastAppVersion',{}).get('versionCode')}")
                print("    cause:", it.get("cause"), "| at:", it.get("location"))
                s = it.get("sampleReports")
                if isinstance(s, list) and s:
                    print("    " + "\n    ".join(s[0].splitlines()[:25]))
        rv = r["reviews"]
        print("reviews:", rv if isinstance(rv, dict) else len(rv))
        if isinstance(rv, list):
            for x in rv:
                print(f"  {x['stars']}★ v{x['version']} A{x['android']} {x['device']}: {x['text'][:300]}")


if __name__ == "__main__":
    main()
