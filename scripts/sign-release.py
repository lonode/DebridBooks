#!/usr/bin/env python3
"""Validate and sign an unsigned APK using private environment variables.

Required: ANDROID_KEYSTORE_BASE64, ANDROID_KEYSTORE_PASSWORD,
ANDROID_KEY_ALIAS, ANDROID_KEY_PASSWORD. No secrets are printed.
"""

import argparse
import base64
import hashlib
import os
from pathlib import Path
import re
import subprocess
import tempfile


ROOT = Path(__file__).resolve().parent.parent


def tool(name):
    sdk = os.environ.get("ANDROID_HOME") or os.environ.get("ANDROID_SDK_ROOT")
    if not sdk:
        raise ValueError("Set ANDROID_HOME to the Android SDK directory")
    path = Path(sdk) / "build-tools/35.0.0" / name
    if not path.is_file():
        raise ValueError(f"Android build-tools 35.0.0 {name} is missing")
    return str(path)


def run(command):
    env = os.environ.copy()
    if env.get("JAVA_HOME"):
        env["PATH"] = str(Path(env["JAVA_HOME"]) / "bin") + os.pathsep + env.get("PATH", "")
    result = subprocess.run(command, capture_output=True, text=True, env=env)
    if result.returncode:
        # Tool errors may expose private file paths or keystore information.
        raise ValueError(f"{Path(command[0]).name} failed (exit {result.returncode})")
    return result.stdout + result.stderr


def fingerprint(report):
    values = re.findall(r"^Signer #\d+ certificate SHA-256 digest: ([a-fA-F0-9]+)$", report, re.M)
    if len(values) != 1:
        raise ValueError("Expected exactly one APK signer")
    return values[0].lower()


def sign(unsigned, output):
    required = ["ANDROID_KEYSTORE_BASE64", "ANDROID_KEYSTORE_PASSWORD", "ANDROID_KEY_ALIAS", "ANDROID_KEY_PASSWORD"]
    if any(not os.environ.get(key) for key in required):
        raise ValueError("Configure all four Android signing secrets before releasing")
    expected = (ROOT / "signing/release-certificate.sha256").read_text().strip().lower()
    if not re.fullmatch(r"[a-f0-9]{64}", expected):
        raise ValueError("Invalid pinned signing-certificate fingerprint")
    properties = dict(line.split("=", 1) for line in (ROOT / "version.properties").read_text().splitlines() if "=" in line)
    manifest = run([tool("aapt"), "dump", "badging", str(unsigned)])
    match = re.search(r"^package: name='([^']+)' versionCode='([^']+)' versionName='([^']+)'", manifest, re.M)
    if not match or match.groups() != ("io.github.lonode.debridbooks", properties["versionCode"], properties["versionName"]):
        raise ValueError("APK package/version does not match this release")
    run([tool("zipalign"), "-c", "-P", "16", "4", str(unsigned)])
    if output.exists() or output.resolve() == unsigned.resolve():
        raise ValueError("Refusing to overwrite an existing APK")
    output.parent.mkdir(parents=True, exist_ok=True)
    with tempfile.TemporaryDirectory(prefix="debridbooks-signing-") as directory:
        key = Path(directory) / "release.p12"
        data = base64.b64decode(os.environ[required[0]], validate=True)
        fd = os.open(key, os.O_WRONLY | os.O_CREAT | os.O_EXCL, 0o600)
        with os.fdopen(fd, "wb") as stream:
            stream.write(data)
        try:
            run([tool("apksigner"), "sign", "--ks", str(key), "--ks-key-alias", os.environ["ANDROID_KEY_ALIAS"],
                 "--ks-pass", "env:ANDROID_KEYSTORE_PASSWORD", "--key-pass", "env:ANDROID_KEY_PASSWORD",
                 "--v1-signing-enabled", "false", "--v2-signing-enabled", "true",
                 "--v3-signing-enabled", "true", "--v4-signing-enabled", "false",
                 "--alignment-preserved", "true",
                 "--out", str(output), str(unsigned)])
            report = run([tool("apksigner"), "verify", "--verbose", "--print-certs", str(output)])
            if fingerprint(report) != expected:
                raise ValueError("APK signer does not match the pinned release certificate")
            if "Verified using v2 scheme (APK Signature Scheme v2): true" not in report:
                raise ValueError("APK must have a valid v2 signature")
        except Exception:
            output.unlink(missing_ok=True)
            raise
    print(f"Signed and verified {output.name}")
    return hashlib.sha256(output.read_bytes()).hexdigest()


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("unsigned", type=Path)
    parser.add_argument("output", type=Path)
    args = parser.parse_args()
    try:
        digest = sign(args.unsigned, args.output)
    except (ValueError, OSError) as failure:
        parser.exit(1, f"Signing failed: {failure}\n")
    print(f"SHA-256: {digest}")


if __name__ == "__main__":
    main()
