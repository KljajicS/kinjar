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

1. First launch offers Google sign-in or local setup with a name and accounts. A new Google account chooses accounts before enabling personal cloud sync; signing in from an existing local profile uploads its finances. Sharing is optional.
2. The personal workspace owner chooses Share with family and invites the other person's Google email. The invitee will see all existing finances in that workspace.
3. The other phone signs in, opens its invitation and confirms replacement before downloading and activating family finances. Its previous local data is preserved separately for export.
4. A fresh phone signed in to an existing cloud account downloads its finances without creating duplicate accounts. If local finances already exist, replacement requires confirmation.
5. Offline edits stay in the local outbox. Foreground sync and hourly background work retry online. Android may delay background work; it is not instant. Automatic runs use an overlapping incremental cursor; contextual retries reconcile the operation history.
6. Conflicted transactions have a red rounded outline and Needs review label in Recent and Activity. Open one to compare versions and keep the desired value. Non-transaction conflicts remain in Family. Independent changes keep syncing.

Signing out keeps the local copy and pauses cloud access. Confirmed membership removal clears that family's local copy. Device-slot removal frees capacity but does not revoke the member's Google identity.

An account with its own personal workspace can still accept an invitation. The personal workspace is archived, not deleted, in the same commit as the join. Its cloud history stays readable and exportable by its owner, and the phone keeps its previous local copy for export. Archives keep their admission slot, so ten archived or active workspaces exhaust public capacity until the project owner intervenes. A shared workspace cannot be archived this way. If the join is interrupted, local editing stays paused and the next synchronization resumes it; an expired or cancelled invitation leaves the personal workspace active. Another phone still holding the personal copy asks to replace it with the family data.

Joining uses one confirmation before accepting an invitation and replacing finances. If initial cloud setup cannot finish, its contextual Stop recovery action unlocks local editing without changing the cloud. An abandoned initial family can be deleted by its creator. An interrupted restore resumes automatically, with retry and cancellation shown only when needed. An administrator can cancel an expired shared restore; there is no automatic lease takeover.

Malformed operations are held separately, preserved in backups and excluded from financial replay. Independent valid operations continue. Newer protocol versions require an app update rather than being silently skipped. Invitations and restore leases start at server time. Daily quota writes use server timestamps and retry the alternate day counter when the phone clock disagrees; rules remain authoritative.

## Backup and recovery

Settings, Backup and restore exports a password-protected file using Android's file picker. Use a unique passphrase, save it separately and verify the file on a spare installation. Local exports include pending changes but may lack another phone's unsent edits.

Import authenticates the file before showing its date and asking to replace finances. Without an enrolled family it restores locally. Within a family, an administrator's restore replaces the shared dataset and keeps memberships. Interrupted work resumes automatically, with contextual retry or cancellation in Family when needed; another administrator can cancel after the lease expires. Retired history cleanup runs automatically and must finish before another restore. Old local copies and personal cloud archives are available for separate export, never automatically reapplied.

New backups, enrollment and recovery stream individual records through SQLite staging tables. They no longer share the version 1 format's 64 MiB or 100,000-record ceiling. A staged history becomes usable only after its complete stream is verified. Backups authenticate 64 KiB segments with their position, final marker and header. Cloud snapshots use 512 KiB chunks and a complete-stream digest. Version 1 files remain readable; existing large legacy database blobs are read in 256 KiB slices and converted lazily. Migration 11 adds staging tables and references without resetting finances.

## Capacity and maintenance

The initial public beta has ten workspace slots and six members/installations/invitations per family. Each active dataset allows 200 operations and 8 MiB of charged usage per UTC day. Charged usage is the payload size plus 32 bytes per parent or rejection reference, including compressed references. These budgets renew; pending edits wait locally when exhausted. The former 1,000-operation and 10 MiB cumulative limits are removed. Per-operation payloads remain bounded to 512 KiB, and count/byte counters remain in place for safe cleanup. History is retained so old offline changes can still be evaluated against their original parents.

This is not unlimited storage. Device disk, replay memory and processing time, Firebase quotas and individual-record bounds still apply. A restore starts a new dataset and therefore a fresh daily budget; these safeguards are not a project-wide spending or abuse firewall. Large retired datasets can take multiple days to delete under Spark's delete quota, blocking another restore until cleanup finishes. A restore must complete within its existing 25-minute lease or be cancelled and retried.

Firebase's project-wide free quotas still apply: 50,000 document reads/day, 20,000 writes/day, 20,000 deletes/day, 1 GiB stored and 10 GiB outbound transfer/month. A fresh recovery reads every operation document; 10,000 operations on two fresh phones require at least 20,000 reads, plus metadata, chunks and rule reads. Exceeding quota can pause sync while local data and pending changes remain. Keep the project on Spark without a billing account to avoid paid overages; enabling Blaze changes that guarantee. App limits cannot prevent repeated reads, denied requests or multiple-account abuse from exhausting shared quota. Monitor [Firestore usage](https://firebase.google.com/docs/firestore/monitor-usage), [free quotas](https://firebase.google.com/docs/firestore/quotas) and [Spark behavior](https://firebase.google.com/docs/projects/billing/firebase-pricing-plans).

Roll out the new rules and both updated APKs together, after an export and upgrade test. Old APK uploads lack the new usage fields and are rejected after rules deployment; their pending edits remain local until upgrade. New APK writes require the new rules. Larger causal-reference operations require protocol version 2; restoring a deleted receipt by rescanning requires version 3. Older APKs report these as an app-update requirement. Old clients may show a generic error for new history streams, so update both phones before using them. Existing version 1 operations retain their bytes and meaning, and unfinished version 1 bootstraps can finish with their original manifest. No data reset or Firebase billing change is needed.

The creator can delete a family. Leave the app open until cleanup finishes. Interrupted deletion is resumable after sign-in; abandoned deletion requires project-owner cleanup of that family's datasets, invitations, installations, removed membership references and admission slot. Never release an admission slot while its family still exists. Google Auth user deletion is a separate account-administration operation.

## Acceptance gates

Automated checks cover raw-byte retries, local replacement rollback, duplicate/conflicting operations, linked loan commands, backup corruption/passwords, access isolation, counters, quotas, restore fences and staged activation, lease cancellation, six-member removal and resumable deletion. Coordinator tests use real local storage with mocked cloud boundaries for response loss, account switching and recovery interruption. They do not replace testing the Android Firebase SDK against the configured project. The rules emulator observed that an idle financial listener need not immediately error after removal; the app also observes authoritative membership and server-checks it. A later financial update is denied to the removed member.

A fresh Android 15 emulator verified local setup, Settings navigation, disabled-sync behavior, masked passphrases, file-picker export, wrong-password rejection, authenticated preview, confirmed local restore, retained-copy export entry and Serbian Latin layout. No Google account was added. This is local UI evidence, not signed cloud acceptance.

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
