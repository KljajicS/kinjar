# Firebase sync setup and acceptance

Status: implementation under validation. Production deployment and signed two-device acceptance have not been performed. Ordinary builds keep sync disabled; local tracking and password backups work independently.

## Owner-controlled project setup

1. Create a project in the [Firebase console](https://console.firebase.google.com/). Keep Spark, with no linked billing account. Do not enable Functions, Cloud Storage, paid backups or automatic billing upgrades.
2. Register Android app `app.kinjar`. Add SHA-1 and SHA-256 fingerprints for the actual signing certificate used by each test/release build. Register debug fingerprints only for development.
3. Enable Google only under Authentication, Sign-in method. Configure support email and OAuth consent/test users in [Google Auth Platform](https://console.cloud.google.com/auth/overview). Use the Web OAuth client ID for Credential Manager, not the Android client ID.
4. Create the default Firestore database in production mode, choosing its location deliberately. Deploy this repository's rules and indexes before enabling any client. Do not use permissive test-mode rules.
5. Register App Check. Release builds use Play Integrity; debug builds use the debug provider. For APK distribution outside Play, follow [the provider's distribution settings](https://firebase.google.com/docs/app-check/android/play-integrity-provider), then verify valid requests with the actual signed APK before enforcement. Do not assume emulator attestation proves release attestation. Register debug tokens only in a development project and keep them secret, following [debug-provider instructions](https://firebase.google.com/docs/app-check/android/debug-provider).

You retain account sign-ins and console control. No service-account key belongs in the app or repository.

## Build inputs

Provide these environment variables from the registered Firebase app:

| Variable | Value |
|---|---|
| `KINJAR_FIREBASE_PROJECT_ID` | Firebase project ID |
| `KINJAR_FIREBASE_APP_ID` | Firebase Android app ID |
| `KINJAR_FIREBASE_API_KEY` | Firebase API key |
| `KINJAR_FIREBASE_WEB_CLIENT_ID` | Web OAuth client ID |
| `ORG_GRADLE_PROJECT_firebaseSyncEnabled` | `true` only for deliberately enabled builds |

These client identifiers are packaged in the APK and are not authorization secrets. Security comes from rules, authentication and App Check. Keep signing passwords and debug App Check tokens secret. No `google-services.json` plugin is required: initialization uses explicit build inputs. Missing configuration fails an enabled build.

```bash
make lint test build
```

Release signing still follows [releasing](releasing.md). GitHub repository variables with the same names supply client configuration; `KINJAR_FIREBASE_SYNC_ENABLED=true` enables it in the tag workflow. Absence defaults to local-only. The workflow runs only when a new `v*` tag is created. Do not enable public sync until acceptance below passes.

## Rules verification and deployment

Android checks use JDK 17. Rules tests use Node 24 and Java 21:

```bash
npm --prefix firebase install --ignore-scripts --no-package-lock
make lint-sync test-sync
make lint-workflows
```

Tests use only project `demo-kinjar`, Firestore port 8088, and synthetic accounts. They do not contact production. Deployment is a separate project-owner action after review:

```bash
cd firebase
npx firebase deploy --only firestore:rules,firestore:indexes --project YOUR_PROJECT_ID
```

Do not deploy to an inferred/default project. Wait for the invitation collection-group index to finish building before testing invitations.

## Family flow

1. First phone: Settings, Family, Google sign-in, Create family. Existing local finances become the initial shared history.
2. Administrator enters the other person's Google email and sends an invitation.
3. Other phone signs in with that account, opens the invitation, downloads the history, then confirms replacement. Local data is preserved separately for export.
4. New phone signs in with an existing member account and chooses Get family data. It receives the same warning and validation before replacement.
5. Offline edits stay in the local outbox. Foreground sync and scheduled background work retry online. Android may delay background work; it is not instant.
6. Conflicting complete commands wait in Family. Review both changes and discard the unwanted command and its dependents. Independent changes keep syncing.

Signing out keeps the local copy and pauses cloud access. Confirmed membership removal clears that family's local copy. Device-slot removal frees capacity but does not revoke the member's Google identity.

## Backup and recovery

Settings, Backup and restore exports a password-protected file using Android's file picker. Use a unique passphrase, save it separately and verify the file on a spare installation. Local exports include pending changes but may lack another phone's unsent edits.

Import authenticates the file before showing its date and asking to replace finances. Without an enrolled family it restores locally. Within a family, an administrator's restore replaces the shared dataset and keeps memberships. If interrupted, resume or cancel from Family; another administrator can cancel after the lease expires. Retired history cleanup must finish before another restore. Old local copies are available for separate export, never automatically reapplied.

The app's file format accepts at most 64 MiB of serialized local history. Firebase enrollment/restore accepts at most 10 MiB. Reaching a cloud limit pauses uploads without deleting local data.

## Capacity and maintenance

The initial public beta has ten family slots, six members/installations/invitations per family, 1,000 operation documents per dataset and 200 operations per family per UTC day. There is no paid overage path in the app. These limits cannot prevent repeated reads, denied requests or multiple-account abuse from exhausting shared project quota. Monitor [Firestore usage](https://firebase.google.com/docs/firestore/monitor-usage) and [Spark behavior](https://firebase.google.com/docs/projects/billing/firebase-pricing-plans).

The creator can delete a family. Leave the app open until cleanup finishes. Interrupted deletion is resumable after sign-in; abandoned deletion requires project-owner cleanup of that family's datasets, invitations, installations, removed membership references and admission slot. Never release an admission slot while its family still exists. Google Auth user deletion is a separate account-administration operation.

## Acceptance gates

Automated checks cover raw-byte retries, local replacement rollback, duplicate/conflicting operations, linked loan commands, backup corruption/passwords, access isolation, counters, quotas, restore fences and staged activation, lease cancellation, six-member removal and resumable deletion. The rules emulator observed that an idle financial listener need not immediately error after removal; the app also observes authoritative membership and server-checks it. A later financial update is denied to the removed member.

The following require the configured signed APK on two devices and remain pending:

- Google consent, correct account, cancellation and App Check enforcement for the actual APK.
- Create, invite, join, sign out and switch accounts; a fresh phone recovers the correct family.
- Reject local replacement, then accept; verify pending local edits remain exportable separately.
- Independent offline changes converge; concurrent edits and linked loan payments require the expected whole-command resolution.
- Force-stop at upload, bootstrap and restore stages; reopen and retry without duplicate operations or lost history.
- Wrong backup password/corruption leaves finances unchanged; export/import works through the device file provider.
- Admin transfer/removal, signed-in listener removal, expiry/cancellation and dataset replacement while another phone is offline.
- Capacity exhaustion leaves pending changes and export available. Cleanup/deletion resumes after interruption.
- English and Serbian Latin, font scaling, keyboard, TalkBack labels, rotation, and app lock around Google/file-picker screens.

Do not mark these complete from JVM or rules-emulator results alone.
