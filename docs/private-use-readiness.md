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

- Installed the new debug APK over the signed-in emulator app after verifying identical signing certificates. The database migrated from version 11 to 12. Every pre-existing column value in every table matched the private pre-upgrade snapshot; all nine transactions and the 60,897 RSD balance survived.
- Exercised the actual Android SQLDelight driver with a separate synthetic database: staged 100,001 member events totaling 88,466,966 stream bytes, exported and decrypted a backup, and saved a recovery journal. A second Android process resumed that journal, swept orphan histories, validated, activated, and recaptured the identical history digest. Staging/export/import took 30.6 seconds, restart/validation/activation took 50.2 seconds, with a 192 MiB maximum heap. This tests large record storage and restore, not a 100,001-transaction replay workload.
- Exported an encrypted backup through the signed-in app's Android file picker while offline. The file completed and the app displayed “Backup saved.”
- In a separate temporary Android user, completed first-run setup without Google, saved a 100 RSD expense offline, exported a backup, then added a 50 RSD expense. Restoring the backup through the normal confirmation returned the balance from -150 to -100 RSD, retained the replaced history as an exportable local copy, and survived a cold restart. The original family was not restored or edited.
- Tested Android per-app locales `sr-RS` and `sr-Cyrl-RS`: both displayed Serbian Latin translations. Explicit `en-US` displayed English. This does not establish which system locale the wife's phone reports.
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
