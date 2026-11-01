# DebridBooks project direction

- Native Android app using Kotlin, Jetpack Compose and AndroidX Media3.
- Product name: **DebridBooks**. Keep application ID/namespace `io.github.lonode.debridbooks` and debug application ID `io.github.lonode.debridbooks.debug` stable.
- GitHub Releases distributes signed release APKs. F-Droid submission will use reproducible builds with the same signing identity when eligible. Keep the private release key out of Git, logs and artifacts; do not add an in-app updater without explicit authorization.
- Standalone architecture: configure AllDebrid and optional source API keys in the app. Contact providers, trackers and MangaDex directly; no DebridBooks server is required.
- Support audiobooks, torrent comics/CBZ/images, MangaDex chapters, reading history and offline listening/reading. Preserve existing library data, progress, offline content and encrypted configuration compatibility.
- Keep local reference trees out of commits and build dependencies.
- Root `README.md` is explicitly human-managed: do not edit it. Preserve unrelated user changes.
- Project-owned code and assets use GNU AGPL version 3 only (`AGPL-3.0-only`); preserve third-party licenses and notices.
- Use FLOSS dependencies and inspect their actual licenses. No proprietary player, Google Play Services, Firebase, JavaScript OTA updates, committed secrets, or signing keys.
- Playback belongs to a MediaSessionService; UI attaches through a controller. Use transactional Room persistence, versioned migrations, DataStore preferences and Keystore-backed secret storage. Keep credentials, signed URLs and sensitive resolver IDs out of backups and diagnostics.
- Compile actual APKs and run checks appropriate to each change. Require device qualification before release; report verification results and remaining gaps accurately.
