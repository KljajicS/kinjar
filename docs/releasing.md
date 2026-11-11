# Android releases

Tagged releases publish a signed APK named `kinjar-<version>.apk` (for example, `kinjar-0.4.1.apk`), an identical `kinjar-release.apk` for existing in-app updaters, and `update.json`. Metadata retains the compatibility filename because installed versions 0.3.11 and 0.4.0 require that exact URL. Metadata is derived from the verified APK rather than from the tag alone. Every release must use the same retained signing key. CI fails when signing configuration is missing instead of publishing an APK signed with a temporary debug key. See [app updates](updates.md) for initial installation and acceptance.

Each release also retains `kinjar-mapping.txt` from that exact minified build and `SHA256SUMS` covering the APK, mapping and update metadata. Keep them with the APK and tag; mapping files from another build cannot reliably retrace a crash. Application diagnostics contain only fixed operation/error categories and are rate-limited. They never include financial values, receipt URLs, account identifiers, exception messages, or stack traces.

Firestore test dependencies are pinned by `firebase/package-lock.json`. Install with `npm --prefix firebase ci --ignore-scripts`. Review lockfile changes when updating dependencies so rerunning a release uses the same resolved tooling.

The release workflow runs on new version-tag pushes only. Tags must use `vMAJOR.MINOR.PATCH`, name a commit contained in main, and match the APK version. New tags run workflow validation, lint, JVM tests and the debug build. The signed-release job uses the `release` environment; it waits for approval only when that environment has a required reviewer. Branch pushes and pull requests do not publish releases. The workflow creates the GitHub release itself. Use tags such as `v0.3.0`; the release title strips the leading `v`, giving `0.3.0`. Run `make lint-workflows` before changing workflows; it uses installed actionlint or downloads a checksum-pinned Linux x86-64 binary to temporary storage.

Configure Actions secrets only in the `release` environment:

- `BUDGET_KEYSTORE_BASE64`: Base64 of the retained keystore.
- `BUDGET_KEYSTORE_PASSWORD`: Keystore password.
- `BUDGET_KEY_ALIAS`: Signing key alias.
- `BUDGET_KEY_PASSWORD`: Signing key password.

The `BUDGET_` prefix predates the kinjar rename and is kept on purpose: GitHub keeps Actions secrets when a repository is renamed, so signing keeps working without re-entering them. Renaming them means adding new secrets with the same values and updating `.github/workflows/android.yml` and `app/build.gradle.kts` together.

The release environment requires owner approval, restricts deployment to `v*` tags and disables administrator bypass. Self-approval remains enabled for the sole maintainer. These protections were verified after public publication on 10 November 2026. Verify live protections before each release; the environment name alone does not enforce approval. Do not keep duplicate signing secrets at repository scope. Actions dependencies are pinned to full SHAs, Git checkout does not retain token credentials, and the signing job disables Gradle caching and persistent daemons. The temporary keystore is removed before publishing, including on failures. Only the publication step receives an explicit GitHub token. Review the tagged source before pushing the tag or approving deployment.

`scripts/verify-release.py` checks the APK signature against the retained certificate, package name, tag/version match and non-debuggable manifest before publication. Active repository rules restrict version-tag creation to the owner, prevent version-tag changes/deletion, preserve main history and require passing PR checks. Release 0.3.11 and subsequent releases are immutable; earlier releases remain mutable. Tag protection and release immutability are separate settings. The full configuration checklist is [github-release-setup.md](github-release-setup.md).

Keep a separate secure backup of the keystore and credentials. Never commit them. For local `make release`, supply the same password/alias environment variables and `BUDGET_KEYSTORE_PATH` pointing to the keystore file.

Before tagging, increment `versionCode` and `versionName`, run `make lint test release`, and test an upgrade on a device containing existing transactions. Confirm that accounts, balances, receipts, goals and loan history survive the upgrade.

## Current private distribution

Release 0.3.10 adds leaving a family while retaining each phone's history and pending edits separately for export. Deploy the updated Firestore rules before pushing the release tag. Upgrade every departing member's phone before leaving: older APKs delete their local copies. Two-emulator acceptance covered leaving, encrypted saved-copy export and restore, fresh-family creation, and stale-phone recovery. Production signed-APK Play Integrity/App Check remains a release verification step.

The `v0.3.9` workflow passed Android lint and all 52 Firestore tests, but a JVM test failed with an uncaught coroutine exception before `FamilyRecoveryUiTest` started. Signing and publication were skipped. Family and backup test fixtures now cancel and join their ViewModel jobs before resetting Main, closing a lifecycle race with background IO. The failed tag remains intact; 0.3.10 carries the test fix with unchanged application behavior.

Private release 0.3.7 uses the retained certificate from installed 0.3.4-0.3.6 builds (SHA-256 `2f07a019e22b065033e7ccdb0f555bad1fe98b584798eb4507152cc12543dfab`). These are non-debuggable release builds; retaining the existing key allows an in-place upgrade.

The first protected Actions run for `v0.3.7` passed workflow lint, Android lint and Firestore tests, but failed a JVM test because `AddViewModelTest` left ViewModel coroutines running after resetting the main dispatcher. Signing and publication were skipped. Release 0.3.8 cancels and joins those coroutines before resetting the dispatcher and closing the test database; application behavior is unchanged. Keep `v0.3.7` intact and publish the correction under its new tag.

The public v0.3.3 GitHub artifact has a different certificate (SHA-256 `783475a96dc98a6cb2ec0a683d36a2f987dbf1d3363daaa95d57eca1ec23ebb3`). On 9 November 2026, the retained private-distribution key was verified locally and configured in protected `release` environment secrets; old repository-level signing secrets were removed. Firebase build variables were configured from the existing `app.kinjar` project configuration. The hardened workflow must be merged into main before tagging. Verify the downloaded APK certificate after the first Actions build; a normal branch push does not publish an APK.

Earlier CI releases used temporary debug keys. A new signing key cannot update those installations. Recover the exact original private key before promising an in-place upgrade; an APK contains its certificate, not its private key. Do not uninstall an existing installation containing financial data to bypass a signature mismatch. Current kinjar builds support password-protected backups and cloud recovery; verify an export can be restored on a spare installation before changing distribution keys.

Releases up to 0.3.3 shipped as Family Budget Tracker with application ID `rs.budget`. kinjar uses `app.kinjar`, so it installs as a separate app: it does not upgrade those installations and does not read their data.
