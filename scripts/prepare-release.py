#!/usr/bin/env python3
"""Prepare version.properties, the F-Droid recipe and release notes without Git changes.

Usage: python3 scripts/prepare-release.py 1.0.1 --notes "Fix search cancellation"
Use --dry-run to preview changes. Requires only the Python standard library.
"""

import argparse
import difflib
from pathlib import Path
import re


MAX_VERSION_CODE = 2_100_000_000


def version_tuple(value):
    if not re.fullmatch(r"(0|[1-9][0-9]*)\.(0|[1-9][0-9]*)\.(0|[1-9][0-9]*)", value):
        raise ValueError("Use a version such as 1.0.1, without a v prefix or leading zeros")
    return tuple(map(int, value.split(".")))


def field(text, key, separator=":", indent=""):
    matches = re.findall(rf"^{re.escape(indent + key + separator)}\s*([^\n]+)$", text, re.M)
    if len(matches) != 1:
        raise ValueError(f"Expected exactly one {key} field")
    return matches[0].strip()


def replace_field(text, key, value, separator=":", indent=""):
    field(text, key, separator, indent)
    return re.sub(
        rf"^{re.escape(indent + key + separator)}[^\n]*$",
        lambda _: f"{indent}{key}{separator}{' ' if separator == ':' else ''}{value}",
        text, flags=re.M,
    )


def prepare(root, version, notes):
    requested = version_tuple(version)
    notes = notes.strip() + "\n"
    if not notes.strip() or len(notes) > 500:
        raise ValueError("Release notes must contain 1–499 characters, plus the final newline")

    properties = root / "version.properties"
    recipe = root / "fdroid/metadata/io.github.lonode.debridbooks.yml"
    props_text = properties.read_text()
    recipe_text = recipe.read_text()
    current = field(props_text, "versionName", "=")
    code = int(field(props_text, "versionCode", "="))
    if requested <= version_tuple(current):
        raise ValueError(f"New version must be higher than {current}")
    if not 1 <= code < MAX_VERSION_CODE:
        raise ValueError("Cannot increment versionCode within Android's supported range")
    if field(recipe_text, "CurrentVersion") != current or int(field(recipe_text, "CurrentVersionCode")) != code:
        raise ValueError("The F-Droid current version must match version.properties first")

    builds = re.search(r"^Builds:\n(?P<body>.*?)(?=^\S|\Z)", recipe_text, re.M | re.S)
    if builds is None:
        raise ValueError("Cannot find the F-Droid Builds section")
    blocks = re.findall(r"^  - versionName:[^\n]*\n.*?(?=^  - versionName:|\Z)", builds["body"], re.M | re.S)
    if not blocks:
        raise ValueError("Cannot find an F-Droid build to use as a template")
    latest = max(blocks, key=lambda block: int(field(block, "versionCode", indent="    ")))
    if field(latest, "versionName", indent="  - ") != current or int(field(latest, "versionCode", indent="    ")) != code:
        raise ValueError("The latest F-Droid build must match version.properties first")
    if re.search(r"^    disable:", latest, re.M):
        raise ValueError("The latest F-Droid build is disabled; review it before preparing a release")

    next_code = code + 1
    changelog = root / f"fastlane/metadata/android/en-US/changelogs/{next_code}.txt"
    if changelog.exists():
        raise ValueError(f"Refusing to overwrite existing changelog {next_code}.txt")
    new_build = replace_field(latest.rstrip(), "versionName", version, indent="  - ")
    new_build = replace_field(new_build, "versionCode", next_code, indent="    ")
    new_build = replace_field(new_build, "commit", version, indent="    ")
    recipe_text = recipe_text[:builds.end()].rstrip() + "\n\n" + new_build + "\n\n" + recipe_text[builds.end():]
    recipe_text = replace_field(recipe_text, "CurrentVersion", version)
    recipe_text = replace_field(recipe_text, "CurrentVersionCode", next_code)
    props_text = replace_field(props_text, "versionName", version, "=")
    props_text = replace_field(props_text, "versionCode", next_code, "=")
    return next_code, {properties: props_text, recipe: recipe_text, changelog: notes}


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("version", help="New version, for example 1.0.1")
    parser.add_argument("--notes", required=True, help="Release notes (maximum 499 characters)")
    parser.add_argument("--dry-run", action="store_true", help="Show changes without writing files")
    args = parser.parse_args()
    root = Path(__file__).resolve().parent.parent
    try:
        code, changes = prepare(root, args.version, args.notes)
        for path, content in changes.items():
            relative = str(path.relative_to(root))
            if args.dry_run:
                before = path.read_text() if path.exists() else ""
                print("".join(difflib.unified_diff(before.splitlines(True), content.splitlines(True),
                                                 fromfile=relative, tofile=relative)), end="")
            else:
                path.parent.mkdir(parents=True, exist_ok=True)
                path.write_text(content)
                print(f"Updated {relative}")
    except (ValueError, OSError) as failure:
        parser.exit(1, f"Release preparation failed: {failure}\n")
    print(f"{'Preview' if args.dry_run else 'Prepared'}: {args.version} (versionCode {code}), tag {args.version}")
    print("No commit, tag or push was performed.")


if __name__ == "__main__":
    main()
