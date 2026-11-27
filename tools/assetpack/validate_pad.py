#!/usr/bin/env python3
"""Offline diagnostic: validate Minbar's release audio pack inventory and bytes."""
import argparse
import collections
import gzip
import hashlib
import io
import json
from pathlib import Path
import re
import sys
import zipfile

from pad_membership import MEMBERSHIP, MAX_MEMBERSHIP, encode

SNAPSHOT = "base/assets/catalog/snapshot.jz"
PACKS = ("core", "rest", "more")
FILENAME = re.compile(r"([0-9a-f]{64})\.ogg\Z")
SHA = re.compile(r"[0-9a-f]{64}\Z")
MAX_CATALOG = 32 * 1024 * 1024


def expected(raw):
    with gzip.GzipFile(fileobj=io.BytesIO(raw)) as src:
        s = src.read(MAX_CATALOG + 1)
    if len(s) > MAX_CATALOG:
        raise ValueError("catalog too large")
    cat = json.loads(s)
    rows = cat["lessons"]
    if not rows or len(rows) != cat["counts"]["lessons"]:
        raise ValueError("missing or inconsistent catalog lessons")
    shas = {}
    missing = 0
    for row in rows:
        digest = row.get("sha256")
        if not isinstance(digest, str) or not SHA.fullmatch(digest):
            missing += 1
            continue
        n = row.get("sizeBytes")
        if type(n) is int and n > 0:
            if digest in shas and shas[digest] not in (None, n):
                raise ValueError("catalog size conflict for one audio hash")
            shas[digest] = n
        else:
            shas.setdefault(digest, None)
    return len(rows), shas, missing


def has_first_opus_packet(prefix):
    # Scope: correct Ogg BOS and a complete exact version-1 Opus identification
    # packet; not a decoder or an exhaustive duration/bitrate quality check.
    if len(prefix) < 47 or prefix[:5] != b"OggS\0" or prefix[5] != 2:
        return False
    if prefix[18:22] != b"\0" * 4:
        return False
    segments = prefix[26]
    if not segments or 27 + segments >= len(prefix):
        return False
    length = 0
    for lace in prefix[27:27+segments]:
        length += lace
        if lace < 255:
            break
    else:
        return False
    packet = prefix[27+segments:27+segments+length]
    if len(packet) != length or len(packet) < 19 or packet[:9] != b"OpusHead\1":
        return False
    channels, mapping = packet[9], packet[18]
    return (mapping == 0 and channels in (1, 2) and len(packet) == 19) or (
        mapping in (1, 255) and channels > 0 and len(packet) == 21 + channels
    )


def inspect(bundle, require_membership=False):
    errors = collections.Counter()
    with zipfile.ZipFile(bundle) as z:
        members = collections.Counter(z.namelist())
        if members[SNAPSHOT] != 1 or z.getinfo(SNAPSHOT).file_size > MAX_CATALOG:
            raise ValueError("missing, duplicate or oversized packaged catalog")
        rows, shas, no_sha = expected(z.read(SNAPSHOT))
        packs = {pack: 0 for pack in PACKS}
        valid = {}
        for info in z.infolist():
            match = re.fullmatch(r"(core|rest|more)/assets/serving/([^/]+)", info.filename)
            if not match:
                if any(info.filename.startswith(f"{pack}/assets/serving/") for pack in PACKS) and not info.is_dir():
                    errors["unexpected_entry"] += 1
                continue
            if match[2] == ".gitkeep" and info.file_size == 0:
                continue
            name = FILENAME.fullmatch(match[2])
            if not name:
                errors["unexpected_entry"] += 1
                continue
            pack, digest = match[1], name[1]
            packs[pack] += 1
            if members[info.filename] != 1 or digest in valid:
                errors["duplicate_audio"] += 1
                continue
            if info.file_size <= 0:
                errors["empty_audio"] += 1
                continue
            if digest not in shas:
                errors["not_in_packaged_catalog"] += 1
                continue
            if shas[digest] is not None and shas[digest] != info.file_size:
                errors["declared_size_mismatch"] += 1
                continue
            with z.open(info) as f:
                prefix = f.read(1024)
                if not has_first_opus_packet(prefix):
                    errors["missing_ogg_opus_head"] += 1
                    continue
                calculated = hashlib.sha256(prefix)
                while block := f.read(1024 * 1024):
                    calculated.update(block)
                if calculated.hexdigest() != digest:
                    errors["sha256_mismatch"] += 1
                    continue
            valid[digest] = pack
        if members[MEMBERSHIP] or require_membership:
            if members[MEMBERSHIP] != 1 or z.getinfo(MEMBERSHIP).file_size > MAX_MEMBERSHIP:
                errors["missing_duplicate_or_oversized_membership"] += 1
            elif z.read(MEMBERSHIP) != encode(valid):
                errors["membership_mismatch"] += 1
        errors["missing_catalog_audio"] = len(shas.keys() - valid.keys())
        if no_sha:
            errors["catalog_rows_without_sha256"] = no_sha
        for pack, value in packs.items():
            if not value:
                errors["empty_or_absent_" + pack] += 1
        return {"catalog_lessons": rows, "catalog_unique_audio": len(shas), "pack_entries": packs,
                "verified_payloads": len(valid), "errors": +errors}


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("bundle", type=Path)
    parser.add_argument("--require-membership", action="store_true")
    args = parser.parse_args()
    try:
        report = inspect(args.bundle, require_membership=args.require_membership)
    except (ValueError, KeyError, TypeError, EOFError, RuntimeError, OSError, zipfile.BadZipFile) as e:
        print(json.dumps({"invalid_bundle_or_catalog": str(e)}, ensure_ascii=False))
        return 2
    print(json.dumps(report, sort_keys=True, ensure_ascii=False, indent=2))
    return 1 if report["errors"] else 0


if __name__ == "__main__":
    sys.exit(main())
