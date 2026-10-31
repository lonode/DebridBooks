# F-Droid submission

[The build recipe](metadata/io.github.lonode.debridbooks.yml) targets the `v1.0.0`
tag in `https://github.com/lonode/DebridBooks`. The app version is defined in
[`version.properties`](../version.properties).

Commit the source, recipe and store assets together, then tag that commit
`v1.0.0`. Publish the commit and tag to the repository named in the recipe before
submitting it to F-Droid. The tag does not need to exist when writing this file.

Copy the recipe into `metadata/io.github.lonode.debridbooks.yml` in a fork of
`fdroid/fdroiddata` on GitLab. Validate it with F-Droid's tools and open a merge
request. F-Droid fetches the tagged source from GitHub and builds the release APK.

Store descriptions and screenshots live in
[`fastlane/metadata/android/en-US`](../fastlane/metadata/android/en-US).
Complete the store icon and versionCode changelog before submission. Preserve the
[screenshot attribution](../fastlane/SCREENSHOTS.md) when publishing the listing.

For later releases, increase `versionCode`, update `versionName`, and publish a
matching `v<versionName>` tag. Update checks read `version.properties`.
