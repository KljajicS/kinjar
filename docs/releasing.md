# Android releases

Tagged releases publish a signed release APK, `kinjar-release.apk`. Every release must use the same retained signing key. CI fails when signing configuration is missing instead of publishing an APK signed with a temporary debug key.

Each release also retains `kinjar-mapping.txt` from that exact minified build and `SHA256SUMS` covering both files. Keep them with the APK and tag; mapping files from another build cannot reliably retrace a crash. Application diagnostics contain only fixed operation/error categories and are rate-limited. They never include financial values, receipt URLs, account identifiers, exception messages, or stack traces.

Firestore test dependencies are pinned by `firebase/package-lock.json`. Install with `npm --prefix firebase ci --ignore-scripts`. Review lockfile changes when updating dependencies so rerunning a release uses the same resolved tooling.

The workflow runs on new version-tag pushes only. Tags must use `vMAJOR.MINOR.PATCH`, name a commit contained in main, and match the APK version. New tags run workflow validation, lint, JVM tests and the debug build. The signed-release job waits for approval in the `release` environment. Branch pushes and pull requests do not trigger it. The workflow creates the GitHub release itself. Use tags such as `v0.3.0`; the release title strips the leading `v`, giving `0.3.0`. Run `make lint-workflows` before changing workflows; it uses installed actionlint or downloads a checksum-pinned Linux x86-64 binary to temporary storage.

Configure Actions secrets only in the protected `release` environment:

- `BUDGET_KEYSTORE_BASE64`: Base64 of the retained keystore.
- `BUDGET_KEYSTORE_PASSWORD`: Keystore password.
- `BUDGET_KEY_ALIAS`: Signing key alias.
- `BUDGET_KEY_PASSWORD`: Signing key password.

The `BUDGET_` prefix predates the kinjar rename and is kept on purpose: GitHub keeps Actions secrets when a repository is renamed, so signing keeps working without re-entering them. Renaming them means adding new secrets with the same values and updating `.github/workflows/android.yml` and `app/build.gradle.kts` together.

The environment requires owner approval, restricts deployment to `v*` tags and disables administrator bypass. Self-approval remains enabled for the sole maintainer. Do not keep duplicate signing secrets at repository scope. Actions dependencies are pinned to full SHAs, Git checkout does not retain token credentials, and the signing job disables Gradle caching and persistent daemons. The temporary keystore is removed before publishing, including on failures. Only the publication step receives an explicit GitHub token. No guarantee can protect a key from deliberately approved malicious build code; review the tagged source before approving.

`scripts/verify-release.py` checks the APK signature against the retained certificate, package name, tag/version match and non-debuggable manifest before publication. Repository rules prevent changing or deleting version tags and destructive rewrites/deletion of main. This is not a substitute for source review. The full configuration checklist is [github-release-setup.md](github-release-setup.md).

Keep a separate secure backup of the keystore and credentials. Never commit them. For local `make release`, supply the same password/alias environment variables and `BUDGET_KEYSTORE_PATH` pointing to the keystore file.

Before tagging, increment `versionCode` and `versionName`, run `make lint test release`, and test an upgrade on a device containing existing transactions. Confirm that accounts, balances, receipts, goals and loan history survive the upgrade.

## Current private distribution

Private release 0.3.7 uses the retained certificate from installed 0.3.4-0.3.6 builds (SHA-256 `2f07a019e22b065033e7ccdb0f555bad1fe98b584798eb4507152cc12543dfab`). These are non-debuggable release builds; retaining the existing key allows an in-place upgrade.

The first protected Actions run for `v0.3.7` passed workflow lint, Android lint and Firestore tests, but failed a JVM test because `AddViewModelTest` left ViewModel coroutines running after resetting the main dispatcher. Signing and publication were skipped. Release 0.3.8 cancels and joins those coroutines before resetting the dispatcher and closing the test database; application behavior is unchanged. Keep `v0.3.7` intact and publish the correction under its new tag.

The public v0.3.3 GitHub artifact has a different certificate (SHA-256 `783475a96dc98a6cb2ec0a683d36a2f987dbf1d3363daaa95d57eca1ec23ebb3`). On 9 November 2026, the retained private-distribution key was verified locally and configured in protected `release` environment secrets; old repository-level signing secrets were removed. Firebase build variables were configured from the existing `app.kinjar` project configuration. The hardened workflow must be merged into main before tagging. Verify the downloaded APK certificate after the first Actions build; a normal branch push does not publish an APK.

Earlier CI releases used temporary debug keys. A new signing key cannot update those installations. Recover the exact original private key before promising an in-place upgrade; an APK contains its certificate, not its private key. Do not uninstall an existing installation containing financial data to bypass a signature mismatch. Current kinjar builds support password-protected backups and cloud recovery; verify an export can be restored on a spare installation before changing distribution keys.

Releases up to 0.3.3 shipped as Family Budget Tracker with application ID `rs.budget`. kinjar uses `app.kinjar`, so it installs as a separate app: it does not upgrade those installations and does not read their data.
