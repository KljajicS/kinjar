# Public-release readiness

The repository became public on 10 November 2026 with the owner's approval. Signed release 0.3.11 includes manual update checks; 0.4.0 advances the version code for the first updater-driven upgrade. This checklist tracks completed safeguards and remaining physical-device acceptance.

- **License:** GPL-3.0-only selected by the owner; full text is in [LICENSE](../LICENSE). Preserve third-party licenses and notices.
- **Signed phones:** verify Google sign-in, valid release App Check tokens and synchronization on both physical phones before enabling Firestore App Check enforcement. Play Integrity API was enabled on 10 November 2026 after finding it disabled. The release certificate and sideload-compatible provider settings are verified; successful physical-device attestation remains unconfirmed and enforcement remains off. Review production debug tokens before enforcement. Follow [Firebase acceptance](firebase-sync.md#acceptance-gates).
- **GitHub protections:** owner release approval, owner-only version-tag creation, immutable version tags, protected main history and required PR checks are active. External-contributor workflows require approval. Vulnerability alerts, automated security fixes, secret scanning, push protection and immutable future releases are enabled. Release 0.3.11 is immutable; older releases remain mutable. Public CodeQL analysis passed with no open findings. See [release setup](github-release-setup.md).
- **Existing artifacts:** obsolete debug APK assets were backed up locally and removed from releases 0.1.0, 0.2.0, 0.3.0 and 0.3.1 before publication. Compatible signed releases remain available. Removing assets does not erase downloaded copies.
- **History:** current-document cleanup does not remove earlier attribution or validation details from Git history. Any history rewrite requires separate approval and coordination with published tags, releases and existing clones.
- **Private reporting:** GitHub private vulnerability reporting is enabled and linked in [SECURITY.md](../SECURITY.md).

Complete code and dependency fixes with their regression checks before wider APK distribution. Recheck live Firebase and GitHub settings after changes, then record evidence here without secrets or household data.

## Approved audience

The hosted Firebase project is for approved family accounts only. Source publication does not grant workspace admission. Existing administrators can invite family members; no public signup or self-service admission is planned.

Bootstrap/restore uploads do not share the normal daily operation budget, and permanent installation claims can accumulate as device slots are replaced. These are residual availability risks within the trusted family, not authorization to access another family's finances. Do not delete historical claims: they protect installation ownership. Before admitting unrelated users, design durable import and registration accounting that preserves large restores, retry behavior and existing installations; deploy it with compatible clients and rules.

## Dependency follow-up

The parser uses jsoup 1.24.1. Firebase test tooling uses firebase-tools 15.35.0 with a scoped `@firebase/firestore` override to `@grpc/grpc-js` 1.14.7. Firebase 13.2.0 still requests an older vulnerable gRPC range; remove the override when its supported dependency range includes a patched release. The emulator suite validates the overridden client's rules-test paths, not every Firebase SDK feature.

Release 0.4.0 applies scoped overrides under firebase-tools: glob 14.0.2 with minimatch 11.0.1, UUID 11.1.1 under gaxios 6, and OpenTelemetry core 2.8.0 under Pub/Sub. These cross upstream major-version ranges. Compatibility regressions exercise Firebase file discovery and cancellation, multipart requests and UUID buffer bounds, and Pub/Sub trace propagation. The CLI emulator suite covers the actual Firestore test workflow. These checks do not establish compatibility with every Firebase CLI feature; review the overrides when adding new CLI uses and remove them when upstream ranges include the fixes.

The resulting npm audit reports zero findings, down from 14 moderate dependency entries affecting four underlying packages. PR and release CI run `make audit-sync` and reject moderate-or-higher findings. These dependencies belong to development/test tooling, not the Android APK.

## Validation and rollout

Local 0.4.0 validation passed `make lint`, `make lint-workflows`, `make test`, `make build`, `make lint-sync`, `make audit-sync` and `make test-sync`: 536 JVM tests, 11 release-metadata tests and 57 Firestore/tooling tests. A clean `npm ci --ignore-scripts` reproduced zero audit findings and the passing Firestore/tooling suite. Hosted checks and signed artifact verification are still required for the 0.4.0 release.

On 10 November 2026, `make lint`, `make lint-workflows`, `make test`, `make build`, `make lint-sync` and `make test-sync` passed. The suites completed 536 JVM tests, 11 release-metadata tests and 54 Firestore emulator tests without failures or skips. Coverage includes update download integrity/cancellation, version/signing policy, updater state, denied slot disclosure, approved allocation, ownership transfer, existing member access and large restores.

A disposable API 35 emulator completed local onboarding and opened Settings on the 0.3.11 debug build. Settings showed the correct installed version and disabled release updates as expected; the crash buffer was empty. This smoke check does not exercise signed APK installation or physical-device attestation. See [update acceptance](updates.md#acceptance-before-rollout).

The slot rule change was deployed on 10 November 2026 and read back byte-for-byte against tested source. Release 0.3.11 passed hosted PR checks, public CodeQL and signed release CI; anonymous APK downloads, signing identity and update metadata were verified. Its physical-phone installer and attestation checks remain outstanding. An additional local emulator attempt failed before Android boot and provided no signed-release UI evidence.

For 0.4.0, keep 0.3.11 installed on a phone and use Settings to check, download and install the newer release. Confirm version 0.4.0, retained data and family synchronization on both phones. No database migration or Firestore rule change is introduced by this tooling update. Do not enable App Check enforcement until both phones have verified release attestation.
