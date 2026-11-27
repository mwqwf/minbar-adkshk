"""Generate a deterministic Minbar membership input from frozen staging, not network metadata."""
import argparse
from pathlib import Path
import re

HEADER = b"MINBAR-PAD-MEMBERSHIP-1\n"
MEMBERSHIP = "base/assets/catalog/pad-membership.v1"
MAX_MEMBERSHIP = 512 * 1024
PACKS = ("core", "rest", "more")
FILE = re.compile(r"([0-9a-f]{64})\.ogg\Z")


def encode(mapping):
    data = HEADER + "".join(f"{sha} {pack}\n" for sha, pack in sorted(mapping.items())).encode("ascii")
    if len(data) > MAX_MEMBERSHIP:
        raise ValueError("membership input is too large")
    return data


def staged_membership(tree):
    mapping = {}
    for pack in PACKS:
        folder = tree / f"{pack}_pack" / "src/main/assets/serving"
        if folder.is_symlink():
            raise ValueError(f"symlinked serving directory: {pack}")
        entries = 0
        for p in folder.iterdir():
            if not p.is_symlink() and p.is_file() and p.stat().st_size == 0 and p.name == ".gitkeep":
                continue
            m = FILE.fullmatch(p.name)
            if not m or p.is_symlink() or not p.is_file() or p.stat().st_size <= 0 or m[1] in mapping:
                raise ValueError(f"invalid or duplicate staged entry: {pack}/{p.name}")
            entries += 1
            mapping[m[1]] = pack
        if not entries:
            raise ValueError(f"empty staged pack: {pack}")
    return mapping


if __name__ == "__main__":
    cli = argparse.ArgumentParser(description=__doc__)
    cli.add_argument("tree", type=Path)
    cli.add_argument("output", type=Path, help="new output (not overwritten)")
    args = cli.parse_args()
    try:
        contents = encode(staged_membership(args.tree))
        with args.output.open("xb") as dest:
            dest.write(contents)
    except (ValueError, OSError) as error:
        cli.error(str(error))
