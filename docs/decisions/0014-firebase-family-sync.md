# 14. Optional Firebase sync and password-protected recovery

Date: 2026-11-04. Status: accepted, rollout gated by device acceptance.

Supersedes decisions 0004 and 0005. Updates 0008 for synchronized financial commands.

## Context

Drive folder selection did not grant automatic access to another account's files. The approved replacement uses existing Google accounts and Firebase, without cloud end-to-end encryption, a custom server or paid services. Local tracking remains independent of sign-in.

## Decision

SQLDelight owns the event log, durable outbox, conflict records and recovery journal. Each immutable cloud operation contains one complete command, including linked loan payments and expenses. Raw operation bytes are preserved. Causal dependencies and deterministic replay hold conflicting commands and dependents for explicit resolution. Unrelated commands continue. Display ordering may resolve automatically.

One Firestore memory-cache client uses Google authentication and membership rules. Writes are online transactions. Foreground listeners and WorkManager share one coordinator. A fresh installation receives a new writer identity. Joining or recovering downloads and validates the family history before asking to replace local finances.

The creator can appoint administrators, transfer ownership and delete the family. Admission is public while ten slots are available, with six members and six installations per family. Rules enforce immutable operations and coupled content/counters. Limits are 200 operations per family per UTC day, 1,000 operation documents per dataset, 512 KiB per payload, 10 MiB active history and one additional staged or retired dataset.

Initial history and backups are transferred in chunks, at most 20. A cold download is at most 20 chunk documents plus 1,000 operation documents, before metadata and rule reads. Six phones can therefore consume over 6,000 document reads per full recovery. This is a bound on useful content, not on repeated requests or project-wide quota. Payloads are exempt from indexes. Spark without billing prevents paid overages, not service exhaustion.

An administrator's restore acquires an expiring server-checked lease, stages complete chunks and activates a new dataset atomically. Old uploads are fenced. Other phones preserve their old copy and confirm replacement. Retired data cleanup must finish before another restore. Family deletion uses a tombstone and resumable client cleanup.

Independent password backups use PBKDF2-HMAC-SHA256 and AES-256-GCM, with authenticated envelope metadata. They carry finances, not cloud authority or installation credentials. Local backup history has a 64 MiB bound; cloud enrollment and restore retain the stricter 10 MiB bound.

## Consequences

- Google and authorized project administrators can read synchronized finances. No family key or QR pairing is needed.
- Public clients can exhaust free quota or admission slots. Local work and export remain available.
- Rules enforce access and bounds; clients validate financial contents and hashes. A malicious authorized member can still disrupt their family.
- Membership removal cannot erase offline/exported copies. Deletion cleanup requires a running client or project-owner maintenance.
- History growth can pause cloud uploads. Pending changes are retained; no automatic pruning or paid upgrade occurs.
- Release builds stay disabled until configuration, rules deployment and signed two-device acceptance are complete.

Implementation and acceptance procedure: [Firebase sync](../firebase-sync.md).
