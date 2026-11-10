# Firebase sync setup and acceptance

Status: rules and indexes deployed to the owner's private project on 8 November 2026 after installation-claim backfill and verification. Signed two-device acceptance of this audit release remains pending. Ordinary builds keep sync disabled; local tracking and password backups work independently.

## Owner-controlled project setup

1. Create a project in the [Firebase console](https://console.firebase.google.com/). Keep Spark, with no linked billing account. Do not enable Functions, Cloud Storage, paid backups or automatic billing upgrades.
2. Register Android app `app.kinjar`. Add SHA-1 and SHA-256 fingerprints for the actual signing certificate used by each test/release build. Register debug fingerprints only for development.
3. Enable Google only under Authentication, Sign-in method. Configure support email and OAuth consent/test users in [Google Auth Platform](https://console.cloud.google.com/auth/overview). Use the Web OAuth client ID for Credential Manager, not the Android client ID.
4. Create the default Firestore database in production mode, choosing its location deliberately. Deploy this repository's rules and indexes before enabling any client. Do not use permissive test-mode rules.
5. Register App Check. Release builds use Play Integrity; debug builds use the debug provider. For APK distribution outside Play, follow [the provider's distribution settings](https://firebase.google.com/docs/app-check/android/play-integrity-provider), then verify valid requests with the actual signed APK before enforcement. Do not assume emulator attestation proves release attestation. Register debug tokens only in a development project and keep them secret, following [debug-provider instructions](https://firebase.google.com/docs/app-check/android/debug-provider).

You retain account sign-ins and console control. No service-account key belongs in the app or repository.

### Workspace admission

Only accounts with an admission entry can create a cloud workspace. After a person signs in once, copy their UID from Authentication, Users, then create document `admission/{uid}` with the boolean field `enabled` set to `true`, using the Firestore console or the Admin SDK on your own machine. Clients can read their own entry but rules refuse every client write. Never commit UIDs, emails or admission data to this repository.

- Approve only the people who create a workspace. Someone joining by invitation needs no entry.
- Cloud setup by an unapproved account fails before anything is written to the cloud.
- Setting `enabled` to `false` or deleting the entry blocks further workspace creation only. Existing workspaces, memberships, invitations and sync continue. Remove a member from their family to revoke access.

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
npm --prefix firebase ci --ignore-scripts
make lint-sync test-sync
make lint-workflows
```

Tests use only project `demo-kinjar`, Firestore port 8088, and synthetic accounts. They do not contact production. Deployment is a separate project-owner action after review:

```bash
cd firebase
npx firebase deploy --only firestore:rules,firestore:indexes --project YOUR_PROJECT_ID
```

Do not deploy to an inferred/default project. Wait for the invitation collection-group index to finish building before testing invitations.

### Installation claim backfill and rollout order

Rules now require an immutable claim `families/{fid}/claims/{installId}` with field `uid` before an account can register or upload from an installation. Older rules and APKs did not write claims. Deploying without a backfill lets the first account that registers an unclaimed ID own it, which is exactly the spoofing these rules prevent. Rolling out in a different order makes sync fail with permission errors; pending edits stay on the phones, but the change is not silent and must not be attempted. Follow this order:

1. Export both phones' finances and retain a read-only Admin SDK snapshot of the project's documents. Do not enable billing just to use managed exports.
2. Stop syncing new installations: do not register another phone until step 5.
3. Backfill with Admin SDK credentials on your own machine. For every family document, whatever its status, read `installs/0` to `installs/5` and every operation in the datasets named by `activeDataset` and `otherDataset`. For each `installId`, collect the `uid` values from both sources.
   - One UID: create `families/{fid}/claims/{installId}` with `uid` set to it and `createdAt` set to the server time.
   - Several UIDs: stop the rollout and investigate the conflicting accounts and original devices. Never infer ownership from the earliest operation or overwrite a claim automatically. Preserve both devices' pending changes and verified backups before deciding how to separate installation identities.
   - Verify that every installation slot and every operation installation ID in active families now has a claim with a matching UID.
4. Create admission entries for approved creators (see above).
5. Deploy the rules and immediately repeat claim verification to catch any installation registered during rollout. Investigate conflicting owners rather than overwriting a claim. Then install the updated APK on both phones.

After deployment, old APKs keep uploading from installations whose claims were backfilled, but cannot register a new installation or create a workspace without admission. Updated APKs claim a new installation ID atomically when registering it, and report an installation already claimed by another account instead of retrying. New APKs against old rules cannot read claims and do not sync, which is why rules deploy first.

Use the updated APK for family deletion. Old APK deletion does not remove installation claims; a project administrator must remove any orphaned `claims` documents left beneath an already deleted family. New client claims require a matching installation slot, preventing standalone claim creation.

One installation belongs to one account per family. Signing in to a different member's Google account on the same installation cannot upload in that family. Return to the original account to synchronize pending changes. Use a separate installation for the other account; never clear app data before verified recovery and backup.

## Family flow

1. First launch offers Google sign-in or local setup with a name and accounts. A new Google account chooses accounts before enabling personal cloud sync; signing in from an existing local profile uploads its finances. Sharing is optional.
2. The personal workspace owner chooses Share with family and invites the other person's Google email. The invitee will see all existing finances in that workspace.
3. The other phone signs in, opens its invitation and confirms replacement before downloading and activating family finances. Its previous local data is preserved separately for export.
4. A fresh phone signed in to an existing cloud account downloads its finances without creating duplicate accounts. If local finances already exist, replacement requires confirmation.
5. Offline edits stay in the local outbox. Foreground sync and hourly background work retry online. Android may delay background work; it is not instant. Automatic runs use an overlapping incremental cursor; contextual retries reconcile the operation history.
6. Conflicted transactions have a red rounded outline and Needs review label in Recent and Activity. Open one to compare versions and keep the desired value. Non-transaction conflicts remain in Family. Independent changes keep syncing.

Signing out keeps the local copy and pauses cloud access. Confirmed membership removal retains that phone's history, including pending edits and staged recovery history, separately for export under **Backup → Previous local copies**, then detaches the active session. Previous saved copies remain available. Device-slot removal frees capacity but does not revoke the member's Google identity.

Members and administrators can choose **Leave family** from the family actions menu while the family is active and no local recovery is pending. Confirmation removes their membership and phone slots and cancels an administrator's pending invitations. Shared history, including their contributions, stays in the old family. Leaving requires no history download, upload, or prior export. Owners must transfer ownership first. Fresh-family setup is explicit and starts without old transactions; any standalone budget is retained separately before initializing fresh accounts. Saved copies never upload automatically. Creating another family still requires workspace admission and an available slot. After release authorization, deploy updated Firestore rules before the APK. Upgrade all departing member's phones first: older APKs still delete local copies.

Invitations remain valid only while their inviter is a family administrator. Rules refuse to show the family to the invitee, or to let them join, once the inviter is removed or demoted, and the app reports the invitation as no longer valid. Removing or demoting an administrator also cancels their pending invitations in the same commit. A current administrator can invite the same person again.

An account with its own personal workspace can still accept an invitation. The personal workspace is archived, not deleted, in the same commit as the join. Its cloud history stays readable and exportable by its owner, and the phone keeps its previous local copy for export. Archives keep their admission slot, so ten archived or active workspaces exhaust public capacity until the project owner intervenes. A shared workspace cannot be archived this way. If the join is interrupted, local editing stays paused and the next synchronization resumes it; an expired or cancelled invitation leaves the personal workspace active. Another phone still holding the personal copy asks to replace it with the family data.

Joining uses one confirmation before accepting an invitation and replacing finances. If initial cloud setup cannot finish, its contextual Stop recovery action unlocks local editing without changing the cloud. An abandoned initial family can be deleted by its creator. An interrupted restore resumes automatically, with retry and cancellation shown only when needed. An administrator can cancel an expired shared restore; there is no automatic lease takeover.

Malformed operations are held separately, preserved in backups and excluded from financial replay. Independent valid operations continue. Newer protocol versions require an app update rather than being silently skipped. Invitations and restore leases start at server time. Daily quota writes use server timestamps and retry the alternate day counter when the phone clock disagrees; rules remain authoritative.

## Backup and recovery

Settings, Backup and restore exports a password-protected file using Android's file picker. Use a unique passphrase, save it separately and verify the file on a spare installation. Local exports include pending changes but may lack another phone's unsent edits.

Import authenticates the file before showing its date and asking to replace finances. Without an enrolled family it restores locally. Within a family, an administrator's restore replaces the shared dataset and keeps memberships. Interrupted work resumes automatically, with contextual retry or cancellation in Family when needed; another administrator can cancel after the lease expires. Retired history cleanup runs automatically in bounded batches of at most 32 documents, each removed with its exact counter update, and resumes in background work until done. It must finish before another restore. Old local copies and personal cloud archives are available for separate export, never automatically reapplied.

New backups, enrollment and recovery stream individual records through SQLite staging tables. They no longer share the version 1 format's 64 MiB or 100,000-record ceiling. A staged history becomes usable only after its complete stream is verified. Backups authenticate 64 KiB segments with their position, final marker and header. Cloud snapshots use 512 KiB chunks and a complete-stream digest. Version 1 files remain readable; existing large legacy database blobs are read in 256 KiB slices and converted lazily. Migration 11 adds staging tables and references without resetting finances.

## Capacity and maintenance

The private deployment has ten workspace slots, available only to admitted creators, and six members/installations/invitations per family. Each active dataset allows 200 operations and 8 MiB of charged usage per UTC day. Charged usage is the payload size plus 32 bytes per parent or rejection reference, including compressed references. Downloads check a document's references against what its stored cost paid for before decoding them, so an undercharged operation is held separately without being expanded. These budgets renew; pending edits wait locally when exhausted. The former 1,000-operation and 10 MiB cumulative limits are removed. Per-operation payloads remain bounded to 512 KiB, and count/byte counters remain in place for safe cleanup. History is retained so old offline changes can still be evaluated against their original parents.

This is not unlimited storage. Device disk, replay memory and processing time, Firebase quotas and individual-record bounds still apply. A restore starts a new dataset and therefore a fresh daily budget; these safeguards are not a project-wide spending or abuse firewall. Large retired datasets can take multiple days to delete under Spark's delete quota, blocking another restore until cleanup finishes. A restore must complete within its existing 25-minute lease or be cancelled and retried.

Firebase's project-wide free quotas still apply: 50,000 document reads/day, 20,000 writes/day, 20,000 deletes/day, 1 GiB stored and 10 GiB outbound transfer/month. A fresh recovery reads every operation document; 10,000 operations on two fresh phones require at least 20,000 reads, plus metadata, chunks and rule reads. Exceeding quota can pause sync while local data and pending changes remain. Keep the project on Spark without a billing account to avoid paid overages; enabling Blaze changes that guarantee. Admission stops unapproved accounts from creating workspaces and uploading bootstrap history, but app limits cannot prevent repeated reads or denied requests from exhausting shared quota. Monitor [Firestore usage](https://firebase.google.com/docs/firestore/monitor-usage), [free quotas](https://firebase.google.com/docs/firestore/quotas) and [Spark behavior](https://firebase.google.com/docs/projects/billing/firebase-pricing-plans).

Each operation upload evaluates the family, dataset state before and after, the device slot, the installation claim and the new operation document: well inside Firestore's per-request access-call limits. Member removal with six device slots and six invitation cancellations is tested as one commit.

Roll out the new rules and both updated APKs together, after an export and upgrade test. Old APK uploads lack the new usage fields and are rejected after rules deployment; their pending edits remain local until upgrade. New APK writes require the new rules. Larger causal-reference operations require protocol version 2; restoring a deleted receipt by rescanning requires version 3. Older APKs report these as an app-update requirement. Old clients may show a generic error for new history streams, so update both phones before using them. Existing version 1 operations retain their bytes and meaning, and unfinished version 1 bootstraps can finish with their original manifest. No data reset or Firebase billing change is needed.

Income categories, selected category icons and cross-currency transfers require operation version 4. New snapshots and backups use history stream version 3 so previous readers stop before interpreting these fields. The updated reader also accepts version 2; unfinished uploads resume with their original header and bytes. Content digests remain the canonical version 2 digest, while cloud manifests hash the actual uploaded bytes. Deploy the rules that accept history version 3 and update both phones before using these features. Existing profiles keep their categories; income defaults are seeded only during fresh setup. Transfers store the sent and received amounts, so later NBS refreshes do not change the transfer's account balances.

The creator can delete a family. Leave the app open until cleanup finishes. Interrupted deletion is resumable after sign-in; abandoned deletion requires project-owner cleanup of that family's datasets, installation claims, invitations, installations, removed membership references and admission slot. Never release an admission slot while its family still exists. Google Auth user deletion and `admission` entries are separate account-administration operations.

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
