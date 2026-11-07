# Audit follow-up

Baseline: `2917cd5`. Scope: the whole-repository review of 7 November 2026.

## Acceptance checklist

- A stale transaction form cannot overwrite or delete a correction it did not display, including after recreation. Reload is explicit.
- A failed conflict lookup retains known warnings and offers retry; it never presents failure as resolution.
- Demotion or a superseding restore cannot strand a local RESTORE journal. Local finances and the staged backup remain recoverable.
- Rejoining with a new member identity offers replacement recovery on older installations and preserves pending history.
- Duplicate-only cloud pages advance checkpoints without rebuilding financial projections.
- Retired cleanup performs bounded work and continues later without monopolizing the coordinator lock.
- Private workspace admission rejects unapproved creators. Installation identities cannot be claimed by another account. Invitations require a currently authorized inviter.
- Downloaded reference ranges are checked against their charged allocation budget before expansion.
- Cancelling export or import does not leave an unreferenced live history spool behind.
- Retained copies can be deleted individually with confirmation and exact ownership checks. Copies are never silently pruned.
- Editing a profile name during save leaves the newer draft unsaved.
- Spending-change percentages do not wrap negative on overflow.
- Diagnostics retain only fixed operation/error categories, are rate-limited, and do not log personal or financial values.
- Published APKs retain their exact minification mapping and checksums. Rules tooling installs from a committed lockfile.

## Validation

Integration checks on 8 November 2026:

- `make lint test build`: passed. 449 JVM tests, no failures, errors or skipped tests. Debug APK built successfully.
- `make lint-sync test-sync`: passed. 47 tests executed against the local `demo-kinjar` Firestore emulator, including admission, immutable installation claims, revoked invitations and coupled quota counters.
- `make lint-workflows`: passed. The rules dependency lockfile was verified with `npm ci --ignore-scripts`.
- Three independent reviews (editing/UI, security, recovery/lifecycle) found no blocking defects. Their smaller findings were addressed: reload feedback, untouched editor drafts, immediate registration after recovery, slot-backed claim creation, rollout verification, and consistent cleanup failure status. The last change has a regression test proving background cleanup still requests retry without presenting healthy synchronization as failed.

Earlier Android emulator attempts exited with signal 11 before boot. A subsequent disposable API 34 Google APIs device (`kinjar-audit-check`, port 5558) booted successfully on emulator 37.2.12 using `-gpu lavapipe -feature -Vulkan -no-window -no-snapshot`. This identifies a working test configuration, not the root cause of the earlier crashes. Existing emulator data was untouched.

On 8 November, the current debug APK passed these manual Android UI checks on the disposable device, using synthetic local finances without Google sign-in:

- Local onboarding opened Home with the selected accounts. Automatic NBS fetching populated the rate dated 6 November.
- A 125 RSD grocery expense was edited to 150 RSD. Adding 1,000 RSD income produced an 850 RSD balance.
- Encrypted export produced a backup file. A wrong passphrase showed an error; retrying with the correct passphrase opened the replacement confirmation and cleared the prior error. Confirming restore removed the later income and returned the balance to -150 RSD.
- Restore retained the previous local copy. Cancelling its deletion preserved it; confirming deletion removed only that copy, leaving current finances unchanged.
- Profile name and finances survived a cold start. Explicit Serbian selection worked. With the app back on System default, restarting Android with device locale `sr-RS` displayed Serbian Latin automatically.
- With the editor showing 150 RSD, a direct test-only projection change to 175 RSD simulated a competing correction. Save was refused, the stored amount and event count remained unchanged, and explicit Reload displayed 175 RSD. A second projection change to 180 RSD similarly blocked stale deletion. These are local race checks, not evidence of cloud delivery. The injected projection change was removed afterward.
- The Android crash log was empty after the checks. Screenshots were captured at `/tmp/kinjar-audit-restored.png`, `/tmp/kinjar-audit-stale.png`, and `/tmp/kinjar-audit-serbian.png`.

Live Google sign-in, App Check, multi-phone conflict delivery, restore demotion/supersession, and background cloud cleanup were not exercised by this Android run. Their automated coverage remains separate from real-device acceptance. No production finances or Firebase configuration were changed.

## Rollout

Code validation does not deploy Firebase rules or modify live admission. Follow the [Firebase rollout procedure](firebase-sync.md#installation-claim-backfill-and-rollout-order) before deploying the coordinated APK/rules changes. Backfill and verify installation ownership with trusted administrator credentials, provision approved creators, deploy rules, verify again, then update both phones. Conflicting ownership stops rollout. The newly built APK must not be installed against the old rules.

Preserve both phones' pending changes and verify backups. Real-device sign-in and App Check remain separate acceptance checks. Keep existing signing keys and install updates without uninstalling. One installation can upload for only one account per family; another member uses a separate installation.

### Authorized private rollout, 8 November 2026

- Version 0.3.6, versionCode 9, built with Firebase enabled and the retained certificate used by existing private installations. `make lint test release` passed with 449 JVM tests.
- Before deployment, saved a permission-restricted administrative snapshot under the ignored `build/private-rollout-20261108` directory. The existing family had two installation owners and no conflicting ownership. Created two immutable claims and admission for the existing creator, without modifying financial documents.
- Deployed Firestore rules and indexes to `kinjar-dbf6e`. Repeated ownership verification afterward: both claims present, no missing or conflicting claims. All nine original cloud documents remained byte-for-byte equivalent as returned by the API; only the three new administrative documents were added.
- Installed the signed release over the disposable emulator's existing app without uninstalling. All 30 local database tables were unchanged immediately after upgrade. This does not substitute for checking physical-phone pending changes or release App Check.
- No billing, authentication-provider or App Check enforcement changes were made. Existing registered installations retain upload access through their backfilled claims. Two-phone acceptance is deferred at the owner's request.
