# Comic archive bridge

The app uses libarchive for RAR4, RAR5, 7z and TAR image archives, and Android PdfRenderer for PDFs. ZIP continues to use the existing bounded Java reader.

This module compiles the three Apache-2.0 Java binding sources from the pinned `me.zhanghai.android.libarchive:library:1.1.7:sources` artifact. It copies the corresponding upstream native libraries and consumer shrinking rules from the pinned AAR. Both artifacts are covered by Gradle dependency verification and lockfiles; no local reference checkout or NDK installation is required.

The upstream AAR declares compile SDK 37.2, while its JNI bindings use Java 11 and Android system APIs available since API 21 and its manifest declares minimum API 21. Compiling the bindings here allows the app to retain compile/target SDK 36 and minimum API 26 with the current libarchive 3.8.8 decoder. ARM32, ARM64, x86 and x86_64 binaries are retained unchanged, including their native build IDs. ARM64 load segments are aligned to 16 KiB.

The wrapper is Apache-2.0. Libarchive and bundled codecs use the permissive terms reproduced in [the APK-bundled notices](../app/src/main/assets/licenses/libarchive-android.txt). No Junrar or proprietary UnRAR decoder is used.

## Limits

- Libarchive does not support solid RAR4 archives. These receive an explicit reader message; ordinary RAR4 and RAR5 use the native decoder.
- Password-protected and split archives are unsupported.
- Archives must contain image pages, rather than another layer of comic archives.
- Existing byte, entry, storage and path limits apply. Native symlinks and hardlinks are rejected.
- PDF pages are rasterized for the existing image reader/offline store, bounded to 3000 pixels on the long edge and approximately six megapixels. Zooming beyond that is raster zoom.
- Existing library identities and the generated page manifest layout are retained.
