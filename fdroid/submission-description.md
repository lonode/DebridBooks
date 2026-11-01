New App: DebridBooks (io.github.lonode.debridbooks)

DebridBooks is a native Android audiobook player and comic reader built with
Kotlin, Jetpack Compose and AndroidX Media3. It supports direct source searches,
AllDebrid resolution, offline listening, bookmarks, sleep timers, MangaDex and
offline comic reading.

- Upstream: https://github.com/lonode/DebridBooks
- Licence: AGPL-3.0-only, with third-party licence notices retained upstream
- Version: 1.0.0 (versionCode 1)
- Build: Gradle 8.13, Android SDK 36, build-tools 35.0.0, OpenJDK 17
- Store metadata: upstream Fastlane directory, including six screenshots
- Anti-feature: NonFreeNet, for the proprietary AllDebrid service

The app contacts configured services directly. No DebridBooks server is required.
Users provide their own credentials; no provider keys or media are bundled.
The app uses FLOSS dependencies and does not require Google Play Services or
Firebase. Release APKs are unsigned for F-Droid to sign.

The maintainer requests inclusion. Intended use is public-domain, openly licensed
or otherwise authorized content. Comic screenshots include the CC BY 4.0
Pepper&Carrot credit in the store description and full attribution upstream.

Validation performed with F-Droid's official tooling and buildserver container:

- Metadata lint and formatting checks passed.
- Source scan, release build and APK binary scan passed for the published app
  source at `f6704890ed3872f0ccb2fafe94b510c099a51bc7` (`1.0.0`).
- The store icon, title and initial changelog were subsequently prepared for the
  `v1.0.0` submission tag without changing app code. Please use the merge-request
  pipeline to validate that final tag.
