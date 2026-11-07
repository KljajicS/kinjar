# Firebase sync acceptance follow-ups

Recorded 2026-11-06. User requested deferring fixes while testing sync.

- Invitation send has no clear success confirmation. A repeated send produced generic failure; recipient subsequently joined successfully. Duplicate-pending rejection is plausible but not verified.
- Generic action failure hides the cause and next action. Preserve safe diagnostics and distinguish duplicate invitation, permission failure, and connectivity.
- Before joining, Family showed "Family data is available" beside Create family, without a visible invitation. Correct the misleading status.
- Creator screen retained the old member list after recipient joined until refresh. A previous action error remained beside Up to date. Review refresh and error lifecycle.
- After successful family join, Back returned the phone to Welcome with an empty name and default account choices. Successful join should exit onboarding, use the signed-in member's Google name, and show synced family accounts. Do not require local setup or create duplicate default accounts. Temporary path: Family -> Continue to Home. Verify back-stack cleanup and restored onboarding state after process restart.

- Automatic exchange-rate retrieval failed on emulator: Exchange rates showed no EUR or USD rate and refresh-failed feedback. Phone also showed EUR conversion unavailable. RSD totals remained correct. Provider is National Bank of Serbia; exact failure cause not diagnosed. Investigate separately from financial sync.
- User requires transaction conflicts to appear on the affected transaction, not in Family settings. Flag the transaction in Recent/Activity and show competing values and authors in its details. Proposed resolution actions: keep one version or enter a corrected value, rather than two unrelated discard cards. Preserve atomic handling of linked operations when implementing.
- Backup flow shows stale "Backup saved" after a failed restore attempt. Generic error conflates wrong password, file failure, network failure, and authorization.
- Non-admin family member can open and confirm family-wide Restore, then receives generic failure. SyncCoordinator.restoreFamily requires admin membership before creating a restore journal. Gate family replacement UI by role and explicitly explain administrator requirement; retain server-side checks. Correct password opened the preview on the member phone, but subsequent replacement failed, consistent with this role restriction.
- Blocking restore failure persists after granting phone administrator access (role verified from creator's refreshed Family screen). Phone still shows generic failure inside replacement confirmation. Emulator subsequently switched from Up to date to replacement-required text and Get family data, so shared state may already have changed. Do not assume the attempt was atomic or left cloud data untouched. Investigate restore journal, cloud dataset status/activation, and post-activation local installation before retrying or claiming success. No peer replacement accepted yet.

## Acceptance evidence

- Recipient joined successfully; Google display name populated. Welcome back-stack issue remains.
- Emulator-created income and expenses reached phone. Phone-created income reached emulator.
- User reports phone uploads occurred automatically throughout testing. Earlier emulator checks explicitly triggered sync, so those alone did not establish automatic receiving.
- Offline-created expense reached emulator after phone reconnected.
- Later edit arrived on emulator without pressing Sync now; edited amount and balance matched.
- Deleting TEST Coffee on phone automatically removed it from emulator. Balance matched at 60,998 RSD, with eight transactions remaining.
- Concurrent edits tested: emulator disconnected, same transaction edited from 1,200 to 1,400 RSD there and to 1,300 RSD on online phone. After reconnecting emulator, both devices automatically showed both changes held for resolution. Detection passed; resolution and convergence still untested. User rejected placement of conflict controls in Family settings.
- New-install recovery and backup/restore remain untested on devices.
- Conflict resolution passed on emulator: discarding 1,400 RSD edit on phone automatically cleared conflicts and activated 1,300 RSD, balance 60,898 RSD.
- Backup export reported successful by user. Wrong-password attempt rejected; correct-password attempt opened backup preview. Actual family restore has not passed (member role blocked).
- Follow-up after admin retry: emulator Get family data successfully downloaded/validated a snapshot, then Use family data confirmed local replacement and returned Up to date. All eight transactions were present, including resolved groceries at 1,300 RSD; balance 60,898 RSD. This proves an active restored cloud snapshot was available, not that the originating phone finished successfully.
- Emulator cold restart preserved restored totals and membership. Added TEST After Restore expense of 1 RSD; automatic sync returned Up to date. Expected new balance 60,897 RSD. Phone receipt and originating restore-journal state still require phone-side observation.

## Implementation follow-up

Fixes are now being validated on the sync branch. No account identifiers or tokens are recorded here.

- NBS parser accepts single-digit days and months, with strict calendar validation. A canceled refresh no longer starts the failure cooldown.
- Transaction conflicts appear on Recent and Activity rows. Opening the transaction shows complete competing versions and their authors; keeping a version queues one atomic resolution. Other held financial changes remain accessible in Family.
- Join and recovery use one replacement confirmation and leave onboarding after completion. Existing local finances remain protected by the stale-confirmation check and retained-copy export.
- Family membership refreshes after sync state changes. Invitation send reports success, and a repeated unexpired invitation is idempotent.
- File restore is restricted to administrators for shared families. Backup feedback distinguishes file/open, permission, connectivity, and changed-local-data failures; old export-success messages are cleared.
- An interrupted approved restore resumes through ordinary synchronization. Once local replacement commits, later sync or cleanup failure no longer reports the restoration itself as failed. Cleanup runs automatically and retries without creating another restore journal.
- The restore screen shows progress and a contextual retry instead of setup, recovery, and cleanup controls. Confirmation closes once its durable restore journal exists. A failed unfinished restore retains a contextual cancellation action.
- The original phone failure cannot be attributed conclusively without its journal and diagnostic evidence. Code review identified a reproducible secondary trap: a post-activation failure could leave confirmation open, and a second confirmation could create another journal while cleanup was pending. Regression tests cover the completion boundary and cleanup preflight.

Independent review found no restore, permission, or resolver blocker. Follow-up fixes prevent Back from canceling an active Family action, renew stale replacement previews after a new confirmation, preserve cancellation for an administrator whose restoring phone is unavailable, and identify recovery owned by another signed-in account. Database reads in the foreground listener run on IO.

Validation: `make lint` and all 315 JVM tests pass. `make lint-sync test-sync` passes all 25 rules tests using a temporary Java 21 runtime. No rules deployment was needed. `make build` produced the Firebase-enabled debug APK, installed over the existing emulator app without clearing its data or sign-in.

Emulator acceptance on the updated APK:

- NBS refresh returned the official 2026-11-06 EUR and USD rates. Home showed 517.79 EUR alongside the unchanged 60,897 RSD balance, without the missing-rate warning.
- Exported current synthetic finances with a temporary passphrase, opened that backup, and confirmed shared replacement once. The app reported success and returned to Home without extra recovery or cleanup actions.
- Restored totals remained 64,500 RSD income, 3,603 RSD spending, and 60,897 RSD balance. Family returned to Up to date automatically.
- A cold app restart retained the restored totals, cached rates, and Home onboarding state.
- Removed the disposable encrypted export after acceptance; the temporary passphrase was not retained.
- Live two-device conflict review on the new UI still needs the phone updated. Existing concurrent-edit and offline convergence evidence above predates this UI change; new resolution behavior has JVM regression coverage.

The resolver correction changes how rejected dependent changes are replayed. Both test devices must use the updated APK before testing further concurrent edits.

## November 7 usability follow-up

- User clarified Google sign-in should enable personal cloud sync before optional family sharing. Local-only setup remains available with a name and accounts. Existing remote finances require confirmation before replacing local finances.
- Settings now links to Profile for name, theme and language. Accounts remains in bottom navigation only.
- Family groups sync status, sharing and membership; connected phones are collapsed initially. Secondary actions use contextual menus.
- Conflicted transaction rows now have a red rounded outline and a text label in both Recent and Activity.
- Serbian resources were split between plain Serbian and Latin-script folders. Resources now use one Latin folder; Serbian locale variants normalize to Latin while explicit other-language choices remain respected.
- Emulator acceptance: Serbian Cyrillic system language plus an empty app-language override opened Home and Profile in Serbian Latin. Profile still displayed System default. Explicit English overrode the Serbian system setting. Original English system language was restored afterward.
- Isolated temporary emulator user verified first-run Google/local choices, local name/accounts setup, Family Back to Home, and cold restart staying on Home. NBS rates downloaded automatically on that fresh install without pressing Refresh. The original signed-in emulator user's finances were not changed by these checks.
- Independent UI review identified a stale sync-status account-switch race. Home completion now checks session account, family, dataset and generation; tests include an old READY status from another account.
- Personal-to-family joining now uses an atomic cloud archive and membership switch, with a durable local journal and retained local history. Owner-only archives can be exported from Backup and restore. The earlier deletion-based proposal was rejected before execution; no cloud workspace was deleted.
- Local rules validation passes all 30 tests, including archive privacy, immutable archived finances, invalid joins and atomic membership switching. New rules have not been deployed to the live project, and the new personal-cloud handoff has not been accepted on two devices.
- Final combined Android validation: `make lint`, all 335 JVM tests and `make build` passed with Firebase enabled. Added regressions verify foreground sign-out clears stale status and a stale switch journal cannot commit the cloud handoff. The APK was installed over the original emulator app without clearing its account or finances; cold launch retained 60,897 RSD and Family returned to Up to date automatically.
