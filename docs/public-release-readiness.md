# Public-release readiness

Source publication and wider APK distribution are separate decisions. Keep repository visibility unchanged until the owner approves publication. This checklist tracks remaining decisions and live configuration gates; passing code tests does not complete them.

- **License:** GPL-3.0-only selected by the owner; full text is in [LICENSE](../LICENSE). Preserve third-party licenses and notices.
- **Signed phones:** verify Google sign-in, valid release App Check tokens and synchronization on both physical phones before enabling Firestore App Check enforcement. A read-only live check on 10 November 2026 found Firestore enforcement and replay protection both off. Review production debug tokens and compatible API-key application restrictions. Follow [Firebase acceptance](firebase-sync.md#acceptance-gates).
- **GitHub protections:** vulnerability alerts, automated security fixes and immutability for future releases were enabled and verified on 10 November 2026. Existing releases remain mutable. After selecting supported repository visibility or plan, verify required release approval, main and version-tag guards, and required validation checks. The same day's check found no required release reviewer; rulesets were unavailable under the private repository's current plan. Adding a code-scanning workflow is not evidence that its analysis passed. See [release setup](github-release-setup.md).
- **Existing artifacts:** decide whether to remove historical debug APK assets before public distribution. Preserve compatible signed upgrades for existing users. Removing assets does not erase downloaded copies.
- **History:** current-document cleanup does not remove earlier attribution or validation details from Git history. Any history rewrite requires separate approval and coordination with published tags, releases and existing clones.
- **Private reporting:** establish and verify a private vulnerability-reporting route; current fallback is described in [SECURITY.md](../SECURITY.md). Private reporting availability has not been confirmed.

Complete code and dependency fixes with their regression checks before wider APK distribution. Recheck live Firebase and GitHub settings after changes, then record evidence here without secrets or household data.

## Approved audience

The hosted Firebase project is for approved family accounts only. Source publication does not grant workspace admission. Existing administrators can invite family members; no public signup or self-service admission is planned.

Bootstrap/restore uploads do not share the normal daily operation budget, and permanent installation claims can accumulate as device slots are replaced. These are residual availability risks within the trusted family, not authorization to access another family's finances. Do not delete historical claims: they protect installation ownership. Before admitting unrelated users, design durable import and registration accounting that preserves large restores, retry behavior and existing installations; deploy it with compatible clients and rules.

## Dependency follow-up

The parser uses jsoup 1.24.1. Firebase test tooling uses firebase-tools 15.35.0 with a scoped `@firebase/firestore` override to `@grpc/grpc-js` 1.14.7. Firebase 13.2.0 still requests an older vulnerable gRPC range; remove the override when its supported dependency range includes a patched release. The emulator suite validates the overridden client's rules-test paths, not every Firebase SDK feature.

On 10 November 2026, a fresh npm audit reported 14 moderate entries, zero high and zero critical, down from 19 entries including five high. Four affected transitive packages remain: OpenTelemetry core, glob, minimatch and UUID; the other entries propagate those advisories through dependents. Patched versions require major upgrades outside their consumers' declared ranges. A non-forced lockfile refresh did not resolve them. Track compatible upstream fixes instead of forcing unrelated major changes. These packages belong to development/test tooling, not the Android runtime; avoid running that tooling on untrusted inputs outside isolated CI.

## Validation and rollout

On 10 November 2026, `make lint`, `make lint-workflows`, `make test`, `make build`, `make lint-sync` and `make test-sync` passed. The suites completed 536 JVM tests, 11 release-metadata tests and 54 Firestore emulator tests without failures or skips. Coverage includes update download integrity/cancellation, version/signing policy, updater state, denied slot disclosure, approved allocation, ownership transfer, existing member access and large restores.

A disposable API 35 emulator completed local onboarding and opened Settings on the 0.3.11 debug build. Settings showed the correct installed version and disabled release updates as expected; the crash buffer was empty. This smoke check does not exercise signed APK installation or physical-device attestation. See [update acceptance](updates.md#acceptance-before-rollout).

The slot rule change is not yet deployed. After reviewing and committing the tested change, deploy only Firestore rules to the existing project and verify normal family access. It requires no data migration or new client protocol. Do not combine that deployment with App Check enforcement before signed-phone acceptance. The signed/minified release build, hosted PR checks and hosted CodeQL analysis still need their normal release/CI runs; a local debug build does not prove those gates passed.
