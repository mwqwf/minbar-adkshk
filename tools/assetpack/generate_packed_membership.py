"""Build the app's membership asset from frozen, locally staged Opus serving files."""
import argparse
import hashlib
import os
from pathlib import Path
from tempfile import NamedTemporaryFile

from pad_membership import MAX_MEMBERSHIP, encode, staged_membership
from validate_pad import MAX_CATALOG, expected, has_first_opus_packet

# Keep headroom below the store's separate per-pack limit.
PACK_MAX_BYTES = 492 * 1024 * 1024

def generate(tree, catalog, out):
    mapping = staged_membership(tree)
    if catalog.is_symlink() or catalog.stat().st_size > MAX_CATALOG:
        raise ValueError("invalid frozen snapshot")
    _, inventory, missing = expected(catalog.read_bytes())
    if missing or inventory.keys() != mapping.keys():
        raise ValueError("staged inventory does not match frozen snapshot")
    totals = dict.fromkeys(("core", "rest", "more"), 0)
    for digest, pack in mapping.items():
        p = tree / f"{pack}_pack/src/main/assets/serving/{digest}.ogg"
        length = p.stat().st_size
        if inventory[digest] is None or inventory[digest] != length:
            raise ValueError(f"catalog length mismatch: {pack}/{digest}")
        totals[pack] += length
        if totals[pack] > PACK_MAX_BYTES:
            raise ValueError(f"staged pack exceeds local size budget: {pack}")
        with p.open("rb") as src:
            if not has_first_opus_packet(src.read(1024)):
                raise ValueError(f"invalid Ogg/Opus header: {pack}/{digest}")
            src.seek(0)
            if hashlib.file_digest(src, "sha256").hexdigest() != digest:
                raise ValueError(f"staged bytes do not match SHA-256: {pack}/{digest}")
    value = encode(mapping)
    assert len(value) <= MAX_MEMBERSHIP
    out.parent.mkdir(parents=True, exist_ok=True)
    with NamedTemporaryFile(dir=out.parent, delete=False) as temp:
        temp.write(value)
    try:
        os.replace(temp.name, out)
    finally:
        Path(temp.name).unlink(missing_ok=True)
    return {p: (sum(value == p for value in mapping.values()), n) for p, n in totals.items()}

if __name__ == "__main__":
    cli = argparse.ArgumentParser(description=__doc__)
    cli.add_argument("tree", type=Path)
    cli.add_argument("catalog", type=Path)
    cli.add_argument("output", type=Path)
    args = cli.parse_args()
    try:
        print("Verified staged Minbar membership:", generate(args.tree, args.catalog, args.output))
    except (ValueError, OSError, KeyError, TypeError, EOFError) as exc:
        cli.error(str(exc))
