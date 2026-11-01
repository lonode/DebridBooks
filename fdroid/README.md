# F-Droid submission

Submission is deferred following the development-age policy rejection. GitHub
Releases currently distributes signed APKs. See [RELEASING.md](../RELEASING.md)
for signing and reproducibility checks. The recipe is prepared to verify the
same upstream signing identity when the app becomes eligible; it is not an
active F-Droid distribution.

[The build recipe](metadata/io.github.lonode.debridbooks.yml) pins the full public
commit hash of a tagged release in `https://github.com/lonode/DebridBooks`. The app version is defined in
[`version.properties`](../version.properties).

Commit the source, recipe and store assets together, then tag that commit
`<versionName>`. Publish the commit and tag to the repository named in the recipe before
submitting it to F-Droid. After publishing the tag, resolve it to a full hash with
`git rev-parse <versionName>^{commit}` and use that hash in the initial submission
recipe. The tagged commit does not need to contain a recipe pointing to itself.

Copy the recipe into `metadata/io.github.lonode.debridbooks.yml` in a fork of
`fdroid/fdroiddata` on GitLab. Validate it with F-Droid's tools and open a merge
request. F-Droid fetches the tagged source from GitHub and builds the release APK.

Store descriptions, icon, screenshots and release notes live in
[`fastlane/metadata/android/en-US`](../fastlane/metadata/android/en-US).
Publish those files with the release tag. Preserve the
[screenshot attribution](../fastlane/SCREENSHOTS.md) when publishing the listing.

To prepare a later release, run from the repository root:

```sh
python3 scripts/prepare-release.py 1.0.1 --notes "Fix search cancellation" --dry-run
python3 scripts/prepare-release.py 1.0.1 --notes "Fix search cancellation"
```

The script increments `versionCode`, updates `versionName`, appends an F-Droid
build using the latest build as its template, updates the recipe's current
version, and writes an English changelog named after the new versionCode. Earlier
build entries and changelogs are preserved. Notes must fit within 500 characters
including the final newline. Review any build configuration changes before use.

It does not commit, create tags or push. Review and commit the changes, then
publish the matching `<versionName>` tag. Ordinary development commits do not
need a release version bump. Update checks read `version.properties`.
