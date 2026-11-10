# App updates

Release builds check for updates only when requested from Settings. Opening the app or returning to the foreground does not check for updates, and there are no scheduled checks. Checks do not require Firebase sign-in and do not upload financial data. Debug builds do not offer installation of release updates.

An available update is optional. Download starts only after confirmation. Android may ask the user to allow installations from Kinjar, then asks for installation approval. Denying permission or cancelling keeps the existing app and data. Updates use the same application ID and retained signing key, so they install over Kinjar without uninstalling it.

## Release source

The public GitHub release is the update source. The latest release must contain `update.json` and `kinjar-release.apk`. The release workflow creates metadata from the verified signed APK, including its version code, version name, minimum Android version, size and SHA-256 hash. Publish all assets together before exposing a release as latest. Increment both version code and version name for each release.

The app accepts only its configured repository's release URLs, bounds metadata and APK sizes, validates downloads, and checks the APK package, version and signing certificates before handing it to Android. Android performs final installation and signature verification. Checksums detect mismatched downloads; they are not a substitute for the signing key. Signing-key rotation needs a separately designed compatibility change.

No GitHub access token is packaged in the app. A private repository or a release without metadata cannot serve anonymous update checks. The first updater-enabled release must be installed manually on both phones; older versions cannot acquire this feature by themselves.

## Acceptance before rollout

1. Build a signed release with the retained key and verify its certificate using the existing release verifier.
2. On a spare installation, install an older signed version containing the updater. Publish or provide the next complete release through the configured public release source.
3. Verify manual checks, dismissal, download progress, cancellation and retry. Confirm that startup and foreground return do not trigger checks, and that an offline check never blocks local app use.
4. Deny installation permission, retry, grant it, return through app lock and install explicitly. Cancelling the Android installer must remain retryable. Check rotation and app restart during download and permission handling.
5. Verify refusal of truncated/corrupted downloads, mismatched package/version and wrong signing certificates. Confirm same/older versions are not offered.
6. Complete an in-place update and confirm version, settings, local data and family synchronization remain intact. Repeat on both physical phones. App Check acceptance is a separate gate before enforcement.

If a release is faulty, publish a corrected build with a higher version code. Do not uninstall or force a downgrade to recover. Existing releases are not rewritten as part of normal rollback.
