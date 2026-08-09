# Build & release

- Standard Gradle Android build; CI builds and signs on tag push.
- Version: bump `versionCode` + `versionName` in `app/build.gradle.kts`
  every release. Skipping numbers is fine; never reuse.
- Tags `vX.Y.Z`; releases are marked PRERELEASE for testers. The in-app
  updater follows the `github.com/<repo>/releases/latest` 302 redirect, which
  ignores prereleases — promoting a build to users = unchecking pre-release
  on GitHub.
- Pre-commit ritual (do all of it): xmllint every XML; diff R.string refs vs
  strings.xml (only `cancel`/`ok` may be missing); brace-count edited .kt
  files; scripted edits assert their anchors.
- Field debugging: DiagLog ring buffer, exported from Settings. The buffer is
  finite — noisy diagnostics must log once per subject per process.

## Forking
Rename points: `applicationId` in build.gradle.kts, `io.github.
theonionsarewatching.nova` package, `UpdateChecker.REPO`, FileProvider
authority in the manifest. Carrier specifics (UA strings, size limits,
SMS→MMS threshold) live in `util/CarrierMms.kt`.
