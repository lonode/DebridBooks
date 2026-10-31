#!/usr/bin/env python3
"""Check the version source used by Gradle and require matching release tags."""
import os
from pathlib import Path
import re

root = Path(__file__).resolve().parent.parent
values = dict(line.split("=", 1) for line in (root / "version.properties").read_text().splitlines() if line and not line.startswith("#"))
version = values["versionName"]
if not re.fullmatch(r"\d+\.\d+\.\d+", version):
    raise SystemExit("versionName must be a three-part release version")
code = int(values["versionCode"])
if not 1 <= code <= 2_100_000_000:
    raise SystemExit("versionCode is outside Android's supported release range")
if os.environ.get("GITHUB_REF_TYPE") == "tag":
    if os.environ.get("GITHUB_REF_NAME") != f"v{version}":
        raise SystemExit("Release tag does not match version.properties")
print(f"Version checked: {version} ({code})")
