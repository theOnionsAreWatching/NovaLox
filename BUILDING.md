# Building Nova

Nova builds entirely on GitHub Actions. You do not need a local Android SDK.

## Debug builds (every push)

Push to `main` (or open a PR) and the **Build debug APK** workflow runs. When it finishes,
open the run → **Artifacts** → download `Nova-debug`. That APK is installable on any device
with "install from unknown sources" enabled.

## Release builds (on a tag)

```bash
git tag v0.1.0
git push --tags
```

The **Release** workflow builds an APK and attaches it to a GitHub Release named after the tag.

## Release signing

Signing is optional. Without any signing configured, tagged builds fall back to the debug
signing key — the APK still installs, it just isn't release-signed.

To produce a properly release-signed APK, provide a keystore through GitHub Actions secrets.
**Nothing sensitive is ever committed to the repo** — the workflow reads these at build time
from GitHub's encrypted secret store, and the app's Gradle config reads them from environment
variables. There are no keys, passwords, or keystores anywhere in the source tree.

### 1. Create a keystore (once, on your own machine)

```bash
keytool -genkeypair -v \
  -keystore nova-release.jks \
  -alias nova \
  -keyalg RSA -keysize 2048 -validity 10000
```

Keep `nova-release.jks` and its passwords somewhere safe and private. Do **not** add it to git
(it's already covered by `.gitignore`).

### 2. Add repository secrets

On GitHub: **Settings → Secrets and variables → Actions → New repository secret**. Add four:

| Secret name | What to put in it |
|---|---|
| `KEYSTORE_BASE64` | output of `base64 -w0 nova-release.jks` (macOS: `base64 -i nova-release.jks`) |
| `KEYSTORE_PASSWORD` | the keystore password you chose |
| `KEY_ALIAS` | the alias (`nova` above) |
| `KEY_PASSWORD` | the key password you chose |

These live only inside GitHub's secret store; they are masked in logs and never appear in the
repository or in build output.

### 3. Tag a release

`git tag v0.1.0 && git push --tags` — the workflow decodes the keystore into a temporary file,
signs the APK, and discards it when the runner is torn down.

## Building locally (optional)

If you'd rather build on your own machine, install the Android SDK and JDK 17, then:

```bash
./gradlew assembleDebug        # debug APK -> app/build/outputs/apk/debug/
```

For a locally signed release build, set the same four values as environment variables before
running `./gradlew assembleRelease`:

```bash
export SIGNING_KEYSTORE_PATH=/absolute/path/to/nova-release.jks
export SIGNING_KEYSTORE_PASSWORD=...
export SIGNING_KEY_ALIAS=nova
export SIGNING_KEY_PASSWORD=...
./gradlew assembleRelease
```
