# Publish KinJar releases on GitHub

This checklist configures the existing workflow to publish a signed APK under [KinJar Releases](https://github.com/KljajicS/kinjar/releases). A normal branch push does not publish an APK. A new version tag, such as `v0.3.8`, starts the release workflow.

Setup status, 9 November 2026: protected environment, signing secrets, Firebase variables and repository rules are configured. The hardened workflow is on main and compatible Firebase rules are deployed. Tag `v0.3.7` failed a JVM test cleanup race before signing; no APK was published. The test lifecycle fix ships as `v0.3.8`, preserving the immutable failed tag.

## 1. Protect signing secrets

Open [repository environments](https://github.com/KljajicS/kinjar/settings/environments), then select **release**.

Navigation: **Settings > Environments > release > Environment secrets**. Add or update the four secrets below here, not under repository secrets. The release job explicitly selects this environment. It requires approval from `KljajicS`, allows only `v*` tags, and disables administrator bypass. Self-approval is allowed because this is a single-maintainer repository. [GitHub instructions](https://docs.github.com/en/actions/how-tos/deploy/configure-and-manage-deployments/manage-environments).

| Secret name | What to enter |
|---|---|
| `BUDGET_KEYSTORE_BASE64` | Base64-encoded contents of the retained signing keystore, not its filename or certificate. |
| `BUDGET_KEYSTORE_PASSWORD` | Password for that keystore. |
| `BUDGET_KEY_ALIAS` | Alias of the signing key inside that keystore. The current retained key uses `androiddebugkey`. |
| `BUDGET_KEY_PASSWORD` | Password for that signing key. |

The retained file on this PC is `/var/home/sinisa/.android/debug.keystore`. It signed the current private releases, including the non-debuggable 0.3.7 APK. Its filename does not determine whether the APK is a debug build. Do not generate a replacement key or use an unrelated old release key.

Expected certificate SHA-256:

```text
2f07a019e22b065033e7ccdb0f555bad1fe98b584798eb4507152cc12543dfab
```

Keep the keystore and passwords backed up privately. Do not paste passwords or Base64 key contents into this document, chat, issues, or commits. GitHub cannot display an existing secret's value; if uncertain, replace all four entries together from the verified retained key.

For someone configuring this through an authenticated GitHub CLI, the keystore can be uploaded without printing it:

```bash
base64 -w0 /var/home/sinisa/.android/debug.keystore | gh secret set BUDGET_KEYSTORE_BASE64 --repo KljajicS/kinjar --env release
```

The other three entries can be entered through the environment settings form. No `BUDGET_KEYSTORE_PATH` secret is needed; the workflow supplies its own temporary path. Remove repository-level signing secrets after the protected environment is configured, so another workflow cannot bypass its approval gate.

## 2. Add Firebase variables

Open [repository Actions variables](https://github.com/KljajicS/kinjar/settings/variables/actions).

Navigation: **Settings > Secrets and variables > Actions > Variables > New repository variable**. These must be variables, since the workflow reads `vars`, not `secrets`, for Firebase configuration. [GitHub instructions](https://docs.github.com/en/actions/how-tos/write-workflows/choose-what-workflows-do/use-variables).

Download the existing Android app's `google-services.json` from Firebase project settings. Select package **`app.kinjar`** in the existing project; do not create a new project or app. Use the matching entry in the JSON's `client` array.

| Variable name | Value or source |
|---|---|
| `KINJAR_FIREBASE_SYNC_ENABLED` | Exactly `true`, without quotes. Otherwise the workflow defaults to sync disabled. |
| `KINJAR_FIREBASE_PROJECT_ID` | `project_info.project_id` |
| `KINJAR_FIREBASE_APP_ID` | Matching client's `client_info.mobilesdk_app_id` |
| `KINJAR_FIREBASE_API_KEY` | Matching client's `api_key[].current_key` |
| `KINJAR_FIREBASE_WEB_CLIENT_ID` | Web OAuth `client_id`, with `client_type: 3`, in the matching client's `oauth_client` list. Do not use the Android OAuth client, type 1. |

These are client identifiers packaged in the APK, not service-account credentials. No service-account JSON, Firebase login token, Google password, or App Check debug token belongs in these variables. This build uses explicit configuration; it does not require committing `google-services.json`.

If the Web OAuth client is missing, verify Google sign-in configuration in the existing Firebase project before proceeding. See [Firebase authentication troubleshooting](https://firebase.google.com/docs/auth/faq-and-troubleshooting).

## 3. Prepare Firebase for this release

The GitHub workflow tests Firestore rules but does not deploy them. This feature release writes history version 3, so deploy the compatible rules from the release commit before using its new cloud features.

From the repository, with Firebase CLI authenticated as the project owner, replace `YOUR_EXISTING_PROJECT_ID` with the project ID from step 2:

```bash
cd firebase
npx firebase deploy --only firestore:rules --project YOUR_EXISTING_PROJECT_ID
```

This feature batch needs no new index. Keep the existing project, memberships, data, authentication and App Check configuration. Do not clear the family or disable security rules. Release App Check uses Play Integrity; an emulator debug token is not release configuration. Full deployment details are in [firebase-sync.md](firebase-sync.md).

## 4. Publish the version

Once the settings are complete:

1. Merge the tested feature and hardened workflow into main. The workflow rejects tags whose commits are not contained in main. Feature commit `49fe80c` and the subsequent workflow changes must be included before publishing this release.
2. Verify `app/build.gradle.kts` has version name `0.3.8` and code `11`.
3. Create a new tag on the selected release commit and push it. Do not move an existing published tag.

```bash
git tag -a v0.3.8 RELEASE_COMMIT_SHA -m 'KinJar 0.3.8'
git push origin v0.3.8
```

Replace `RELEASE_COMMIT_SHA` with the verified full commit SHA. This is a release operation; branch push alone is insufficient.

Watch [GitHub Actions](https://github.com/KljajicS/kinjar/actions). The `android` workflow must complete `build` and `sync-rules`. Then review the tagged source and select **Review deployments > release > Approve and deploy**. Only then can the release job access signing secrets. The assistant must not approve its own release on your behalf. It creates the release automatically with:

- `kinjar-release.apk`
- `kinjar-mapping.txt`
- `SHA256SUMS`

No manual APK upload or personal access token secret is required by the workflow. Its release job already requests `contents: write`.

Actions are pinned to full commit SHAs and the Gradle distribution has a checksum. Release builds do not restore or save Gradle caches. Before publication, the workflow verifies the package, tag/version match, non-debuggable manifest and retained signing certificate, then deletes the temporary keystore. Repository rules restrict version-tag creation to the owner and prevent version-tag updates/deletion. Main cannot be deleted or force-pushed; these rules do not impose pull-request review requirements.

## 5. Verify the downloadable APK

Download the APK from [Releases](https://github.com/KljajicS/kinjar/releases), then verify its version, non-debuggable manifest and signing certificate. Its SHA-256 signing certificate must match step 1. GitHub builds may have different APK file hashes from local builds; certificate compatibility is the upgrade requirement.

Install as an update, without uninstalling or clearing data. Update both family phones before using income-category icons or cross-currency transfers. Confirm Google sign-in, automatic sync and existing balances on both phones.

## Completion checklist

- [x] Four environment signing secrets configured from the retained key, with no repository-level copies.
- [x] Five repository Firebase variables configured, sync enabled.
- [x] Compatible Firebase rules deployed to the existing project.
- [ ] Release source commit selected and new version tag pushed.
- [ ] Release approved manually after reviewing the tagged source.
- [ ] All three Actions jobs green.
- [ ] APK, mapping and checksums visible on GitHub Releases.
- [ ] Downloaded APK signing certificate verified.
- [ ] Both phones updated and automatic sync checked.
