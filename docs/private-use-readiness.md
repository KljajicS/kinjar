# Capacity update and private-use rollout

## Implemented

- Removed the cumulative 1,000-operation and 10 MiB dataset stops. Raw history and causal references remain available for old offline edits.
- Added record streaming for capture, cloud bootstrap, recovery, retained copies and encrypted backups. New histories no longer share the legacy 64 MiB / 100,000-record format limits.
- Added renewable usage safeguards: 200 operations and 8 MiB of charged usage per dataset per UTC day. Pending edits stay local when these budgets are exhausted.
- Added migration 11, completed-history references in recovery journals, restart cleanup, and legacy backup/bootstrap compatibility.
- Removed copied ancestor closures, full financial-map copies on replay, full-history conflict-screen reads, and rescanning pagination.

## Validation on 2026-11-07

- `make lint`, `make test`, `make build`: passed with Firebase sync enabled. 394 JVM tests, no failures or skips.
- `make lint-sync test-sync`: 39 tests passed against `demo-kinjar`, including a 129-chunk restore, daily budget resets, access isolation and unfinished legacy bootstrap completion.
- Actual local-store test: 100,001 records exceeding 64 MiB, encrypted export/import, restart with a pending recovery, validation and activation passed. Entire case took about 54 seconds on the development machine.
- Full database replay: 10,000 transactions, an account rename, then another 10,000 transactions passed in about 4 seconds.
- Android API 35 synthetic crypto test: 120,001 records, 89,760,825 stream bytes, encrypted and verified in 10,838 ms; reported maximum heap 201,326,592 bytes. The new APK was used as a classpath, not installed over the signed-in app. This verifies Android crypto and streaming, not Firebase SDK or two-phone acceptance.
- Existing Android SQLite probe confirmed a full 3 MiB row exceeds CursorWindow while bounded slices preserve the bytes. New spool migration and large activation were tested with the JVM SQLite driver, not an API 26 device.

### Additional emulator acceptance on 2026-11-07

- Installed the new debug APK over the signed-in emulator app after verifying identical signing certificates. The database migrated from version 11 to 12. Every pre-existing column value in every table matched the private pre-upgrade snapshot; all transactions and balances survived.
- Exercised the actual Android SQLDelight driver with a separate synthetic database: staged 100,001 member events totaling 88,466,966 stream bytes, exported and decrypted a backup, and saved a recovery journal. A second Android process resumed that journal, swept orphan histories, validated, activated, and recaptured the identical history digest. Staging/export/import took 30.6 seconds, restart/validation/activation took 50.2 seconds, with a 192 MiB maximum heap. This tests large record storage and restore, not a 100,001-transaction replay workload.
- Exported an encrypted backup through the signed-in app's Android file picker while offline. The file completed and the app displayed “Backup saved.”
- In a separate temporary Android user, completed first-run setup without Google, saved a 100 RSD expense offline, exported a backup, then added a 50 RSD expense. Restoring the backup through the normal confirmation returned the balance from -150 to -100 RSD, retained the replaced history as an exportable local copy, and survived a cold restart. The original family was not restored or edited.
- Tested Android per-app locales `sr-RS` and `sr-Cyrl-RS`: both displayed Serbian Latin translations. Explicit `en-US` displayed English. Physical-device system-locale selection remains a separate check.
- Returned to the original Android user, restored networking, and observed the existing family report “Up to date.” New-format uploads against the new rules and two-phone convergence still require the coordinated rollout below. No live rules were changed during these checks.
- Final comparison preserved all financial rows, operations, session data and retained drafts. Export lazily converted the original baseline blob into spool records; decoded baseline events matched exactly. Removed the temporary Android user and synthetic large database after testing.

Validation did not deploy live rules, update either physical phone, or restore production finances.

## Before using it for real finances

1. **Back up current data and retain the signing key.** Export from the current app, retain the passphrase separately, and verify that backup on a spare installation. Confirm the release certificate can update both installed apps. Do not uninstall to work around a signature mismatch.
2. **Prepare the private release.** Increment the release version, build with the retained signing key and the correct Firebase identifiers, and test an in-place upgrade. Check Google sign-in and App Check with that actual signed APK. Emulator debug tokens do not prove release attestation.
3. **Confirm project configuration.** Keep `kinjar-dbf6e` on Spark without a billing account. Verify Authentication, required signing fingerprints, deployed invitation indexes and App Check settings. Existing client identifiers need not change just because two people use the app. Live billing/App Check configuration was not inspected in this run.
4. **Coordinate rules and APK rollout.** Deploy reviewed rules and update both phones together. New writes need the new rules; old APK writes are denied after that deployment, with their outbox retained. Backups and local editing remain available. Avoid a mixed-version testing period.
5. **Run a short two-phone acceptance check.** Sign in and join the same family; add and edit from each phone; make one offline edit, reconnect and check automatic sync; resolve a deliberate test conflict; check Serbian language, fetched exchange rates, restart and app lock. Verify backup preview and restore on an isolated test workspace, not over real finances.

After those gates, private sideloading is sufficient; a public store listing is optional. Remove synthetic transactions only after preserving a verified backup.

## Remaining physical limits

Firebase free-tier quotas, device storage, replay memory and processing time still apply. A fresh cloud recovery reads the entire operation history, so large or repeatedly restarted recoveries can exhaust the daily read quota. The daily budget is per dataset, not project-wide, and a new restore dataset receives a fresh budget. Retired cleanup may span multiple days under Firebase delete quotas. Restore leases remain 25 minutes. Individual operations retain size/reference limits; highly fragmented causal references can still reach them. No history is silently compacted or dropped, and no automatic paid upgrade was introduced.

## Private release readiness follow-up

### Release candidate 0.3.5

This candidate keeps application ID `app.kinjar` and advances versionCode to 8. Build it with Firebase sync enabled and the retained signing key matching the installed 0.3.4 APK. The current installations use the local Android development key; a non-debug release can retain that certificate for an in-place update. It is not a new production signing identity. Keep a secure separate copy of the key before relying on future updates. Never uninstall to bypass a signature mismatch.

The release build uses Play Integrity rather than the debug App Check provider. For private APK installation outside Google Play, configure App Check to allow unrecognized app versions, not require Play licensing, and require device integrity. Verify valid tokens on both actual phones before enabling Firestore enforcement. An emulator does not prove physical-device integrity. See [Firebase's distribution guidance](https://firebase.google.com/docs/app-check/android/play-integrity-provider).

### Recovery changes

- Recovery screen database reads and conflict decisions run off the main thread.
- Copies retained during local restore remain listed and exportable after Google sign-in. Other Google accounts' retained copies remain isolated.
- A failed backup export attempts to remove its destination only when the provider reported it initially empty. Unknown-size destinations and files that already contained data are not deleted. A provider can refuse cleanup, so a leftover file is not evidence of a successful backup. Only an authenticated restore proves usability.

### Phone acceptance

Install the same release on both phones over the existing app. No USB debugging is required.

1. Confirm Google identity, family membership, existing accounts and balances survive the update. Verify valid App Check requests before enabling enforcement.
2. Add and edit a small test transaction on each phone. Verify automatic delivery in both directions. Repeat with one phone offline, then reconnect and reopen it.
3. Test normal screen-off/background delivery. The fallback worker runs hourly subject to Android scheduling; this is not an instant background-delivery guarantee. Force-stopping Android apps prevents their work until reopened.
4. Scan a real receipt, check original items and totals, and verify it appears on the other phone. Resolve one conflicting edit from the affected transaction and confirm both balances agree.
5. Export an encrypted backup to storage outside the phone, keep its passphrase separately, and verify it on a spare installation. Do not test family-wide replacement against real finances unnecessarily.

Exports are manual. Keep a weekly backup and another before upgrades or major changes. Cloud sync is not an independent historical backup. Remove synthetic test transactions only after preserving a verified backup, then enter real starting balances.

### Validation record

Validated on 2026-11-07:

- `make lint`, `make test`, `make build` and `make release` passed with Firebase enabled. The final JVM run completed 412 tests with no failures, errors or skips.
- Independent review found no introduced blocker. Its proposed interrupted-restore data-loss scenario was checked against the spool reader and transactional rollback, then covered by a passing regression test: released input cannot silently replace finances with missing history.
- Release APK is minified, non-debuggable, version 0.3.5/code 8, signed with the same certificate as the installed 0.3.4 APK. The retained key file is owner-readable only. Its private key is machine-specific; the standard development-key password does not make that private key public.
- Installed release over the original API 35 emulator app without uninstalling. Before/after database comparisons found no changed tables, both immediately after upgrade and after all tests. Transactions, balances, membership and retained copies survived. Family reported Up to date under the then-unenforced App Check settings.
- In a separate temporary Android user, rejected a wrong passphrase, restored a synthetic encrypted receipt backup, exported it through DocumentsUI, reopened and restored that export. Original item, fractional quantity 0.345, price and 34.50 RSD total survived. Receipt details and edit screens worked.
- Camera permission and scan-screen startup passed. Cold restart with per-app `sr-RS` rendered Serbian Latin correctly. The fresh test profile fetched NBS conversion rates. Real QR capture and physical-phone system-language selection remain phone checks.
- Removed the temporary Android user and its temporary passphrase; returned to the original emulator profile. No real family restore or financial edit was performed.
- Read live App Check settings: unrecognized versions not allowed, minimum device level NO_INTEGRITY, Firestore enforcement unconfigured. Automatic approval review rejected the proposed off-Play configuration change because that exact live security-policy change needs explicit approval. No live settings were changed.

Physical-phone acceptance, approval of the App Check configuration, and secure off-machine key/backup custody remain owner tasks. Do not enable enforcement until both real phones obtain valid App Check tokens.
