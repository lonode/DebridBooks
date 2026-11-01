# Signed releases

GitHub Releases is the current distribution channel. F-Droid submission is
deferred until the project meets its inclusion requirements. Future F-Droid
builds should verify and reuse the same release signature.

## Signing identity

The public certificate and its SHA-256 fingerprint are in `signing/`. These are
safe to publish. The private keystore must never be committed or uploaded as a
workflow artifact. Retain a secure backup of the private signing directory;
future updates need the same key.

The key-creation script defaults to
`~/.local/share/debridbooks/release-signing/`, outside the repository. It creates
an encrypted PKCS12 keystore and an owner-only `github-secrets.env` import file.
The key has already been created for this project. Do not regenerate it.

Configure these **repository Actions secrets** on GitHub:

- `ANDROID_KEYSTORE_BASE64`
- `ANDROID_KEYSTORE_PASSWORD`
- `ANDROID_KEY_ALIAS`
- `ANDROID_KEY_PASSWORD`

The private import file contains their values. With GitHub CLI authenticated to
the upstream repository, import it without printing the contents:

```sh
gh secret set --repo lonode/DebridBooks \
  --env-file "$HOME/.local/share/debridbooks/release-signing/github-secrets.env"
```

Alternatively, enter the four values under the repository's Settings → Secrets
and variables → Actions. Do not paste passwords or the import file into issues,
pull requests or chat. No signing secret is needed for ordinary builds or PRs.

## Publish

1. Prepare a release and review the resulting changes:

   ```sh
   python3 scripts/prepare-release.py 1.0.1 --notes "Describe the changes" --dry-run
   python3 scripts/prepare-release.py 1.0.1 --notes "Describe the changes"
   ```

2. Commit the source and release metadata, then tag that exact commit `1.0.1`.
3. Push the commit and tag. Version tags do not have a `v` prefix.

The workflow tests, lints and builds unsigned APKs. A separate release job rebuilds
the same tagged source without the Gradle build cache. It checks that the APKs
match byte for byte, then signs with `apksigner` and verifies the pinned
certificate, package, version, alignment and v2 signature. Signing explicitly
preserves the APK's existing ZIP alignment. The workflow then copies the signature
onto the first job's independent APK using `apksigcopier` and verifies it, as
F-Droid does. Any failure stops
publication. The public release appears only after asset upload completes.

The stable release asset is `DebridBooks-<versionName>.apk`. Releases also contain
`SHA256SUMS`, the public certificate and fingerprint, `version.properties`, and
`source-commit.txt`. The signing job runs only on tag pushes, not pull requests
or manual builds. Existing releases are not overwritten by workflow reruns; an
incomplete draft must be reviewed before retrying.

For local signing, load the four private settings into the environment without
printing them, set `JAVA_HOME` and `ANDROID_HOME`, then run:

```sh
python3 scripts/sign-release.py \
  app/build/outputs/apk/release/app-release-unsigned.apk \
  build/release/DebridBooks-1.0.0.apk
```

Signing is separate from Gradle so F-Droid can build without any private key.

## Future F-Droid builds

The recipe contains a `Binaries` URL using the stable GitHub release filename and
an `AllowedAPKSigningKeys` fingerprint matching the committed public certificate.
When resubmitting, pin the full public release commit and test that exact release.
F-Droid must recreate the APK and verify the copied upstream signature; success
must be established, not assumed from passing tests or pinned dependencies.

For an unsigned comparison:

```sh
python3 scripts/check-reproducible.py first-unsigned.apk rebuilt-unsigned.apk
```

For F-Droid-style verification of a signed APK against an independent unsigned
build, use the FLOSS `apksigcopier` tool and Android build-tools `apksigner`:

```sh
apksigcopier compare --unsigned signed-release.apk independently-rebuilt.apk
```

Build from clean checkouts of the same commit. Keep Gradle, Android SDK/build
tools, JDK configuration and dependency locks aligned with the workflow and
recipe. Keep VCS information enabled so APKs identify their actual source commit.
