#!/usr/bin/env python3
"""Fail unless two independently built unsigned APKs are byte-for-byte identical."""

import argparse
import hashlib
from pathlib import Path
import zipfile


def digest(path):
    with path.open("rb") as stream:
        return hashlib.file_digest(stream, "sha256").hexdigest()


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("reference", type=Path)
    parser.add_argument("rebuilt", type=Path)
    args = parser.parse_args()
    try:
        expected, actual = digest(args.reference), digest(args.rebuilt)
        if expected != actual:
            with zipfile.ZipFile(args.reference) as first, zipfile.ZipFile(args.rebuilt) as second:
                names = sorted(set(first.namelist()) | set(second.namelist()))
                different = [name for name in names if name not in first.namelist() or
                             name not in second.namelist() or first.read(name) != second.read(name)]
            print("Different ZIP entries: " + (", ".join(different) or "none; ZIP metadata differs"))
            parser.exit(1, "Release rebuild differs. Signing and publication must stop.\n")
    except (OSError, zipfile.BadZipFile) as failure:
        parser.exit(1, f"Could not compare APKs: {failure}\n")
    print(f"Independent APKs match exactly: {actual}")


if __name__ == "__main__":
    main()
