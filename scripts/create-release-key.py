#!/usr/bin/env python3
"""Create a private release keystore once; only its certificate belongs in Git."""

import argparse
import base64
import hashlib
import os
from pathlib import Path
import secrets
import shutil
import subprocess


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--directory", type=Path,
                        default=Path.home() / ".local/share/debridbooks/release-signing")
    args = parser.parse_args()
    root = Path(__file__).resolve().parent.parent
    directory = args.directory.expanduser().resolve()
    public = root / "signing"
    if directory.exists() or public.exists():
        parser.exit(1, "Refusing to replace existing signing material. Reuse and back up the existing key.\n")
    try:
        directory.relative_to(root)
    except ValueError:
        pass
    else:
        parser.exit(1, "The private signing directory must be outside the repository.\n")
    keytool = shutil.which("keytool")
    if not keytool:
        parser.exit(1, "keytool is required; use the project's JDK 17.\n")
    directory.mkdir(parents=True, mode=0o700)
    directory.chmod(0o700)
    password = secrets.token_urlsafe(48)
    env = {**os.environ, "DB_NEW_KEY_PASSWORD": password}
    keystore = directory / "debridbooks-release.p12"
    command = [keytool, "-genkeypair", "-storetype", "PKCS12", "-keystore", str(keystore),
               "-alias", "debridbooks", "-keyalg", "RSA", "-keysize", "4096",
               "-sigalg", "SHA256withRSA", "-validity", "10000", "-dname", "CN=DebridBooks",
               "-storepass:env", "DB_NEW_KEY_PASSWORD", "-keypass:env", "DB_NEW_KEY_PASSWORD"]
    result = subprocess.run(command, env=env, capture_output=True)
    if result.returncode:
        parser.exit(1, "Key creation failed. Inspect the private directory before retrying.\n")
    keystore.chmod(0o600)
    command = [keytool, "-exportcert", "-keystore", str(keystore), "-alias", "debridbooks",
               "-storepass:env", "DB_NEW_KEY_PASSWORD"]
    certificate = subprocess.run(command, env=env, capture_output=True, check=True).stdout
    public.mkdir()
    (public / "release-certificate.sha256").write_text(hashlib.sha256(certificate).hexdigest() + "\n")
    encoded = base64.b64encode(certificate).decode()
    pem = "-----BEGIN CERTIFICATE-----\n" + "\n".join(encoded[i:i+64] for i in range(0, len(encoded), 64))
    (public / "release-certificate.pem").write_text(pem + "\n-----END CERTIFICATE-----\n")
    values = {
        "ANDROID_KEYSTORE_BASE64": base64.b64encode(keystore.read_bytes()).decode(),
        "ANDROID_KEYSTORE_PASSWORD": password,
        "ANDROID_KEY_ALIAS": "debridbooks",
        "ANDROID_KEY_PASSWORD": password,
    }
    settings = directory / "github-secrets.env"
    # Create with owner-only permissions before writing any secret data.
    fd = os.open(settings, os.O_WRONLY | os.O_CREAT | os.O_EXCL, 0o600)
    with os.fdopen(fd, "w") as stream:
        stream.write("".join(f"{key}={value}\n" for key, value in values.items()))
    print(f"Private keystore and GitHub secret-import file: {directory}")
    print("Back up that directory securely. Never commit or upload it as an artifact.")
    print("Only the public certificate and fingerprint were added under signing/.")


if __name__ == "__main__":
    main()
