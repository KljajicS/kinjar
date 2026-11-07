# 14. Optional Firebase sync and password-protected recovery

Date: 2026-11-04. Status: accepted, rollout gated by device acceptance.

Supersedes decisions 0004 and 0005. Updates 0008 for synchronized financial commands.

## Context

Drive folder selection did not grant automatic access to another account's files. The approved replacement uses existing Google accounts and Firebase, without cloud end-to-end encryption, a custom server or paid services. Local tracking remains independent of sign-in.

## Decision

SQLDelight owns the event log, durable outbox, conflict records and recovery journal. Each immutable cloud operation contains one complete command, including linked loan payments and expenses. Raw operation bytes are preserved. Causal dependencies and deterministic replay hold conflicting commands and dependents for explicit resolution. Unrelated commands continue. Display ordering may resolve automatically.

One Firestore memory-cache client uses Google authentication and membership rules. Writes are online transactions. Foreground listeners and WorkManager share one coordinator. A fresh installation receives a new writer identity. Joining or recovering downloads and validates the family history before asking to replace local finances.

The creator can appoint administrators, transfer ownership and delete the family. Creating a workspace requires an administrator-provisioned `admission/{uid}` entry with `enabled: true`; clients cannot write admission. Ten slots, six members and six installations per family remain. Invitations are trusted only while their inviter is a current administrator. Rules enforce immutable operations and coupled content/counters. Limits are 200 operations per family per UTC day, 512 KiB per payload and one additional staged or retired dataset. There is no cumulative operation-count or active-history byte cap. Count and byte counters remain authoritative for cleanup and deletion.

Operation IDs stay `installId:sequence`, so existing bytes and IDs are unchanged. Instead, each installation ID is bound by an immutable per-family claim to the first account that registers it. Uploads and device registrations require the caller's claim. Installations registered before claims existed must be backfilled by the project owner before the rules are deployed; otherwise the first account to claim an ID would own it. Consequently, one installation cannot upload for two accounts in the same family.

Initial history is transferred in 512 KiB chunks, with no 128-chunk ceiling for version 2 streams. A cold download reads those chunks plus every subsequent operation document, metadata and rule reads. For example, 10,000 operations cost at least 10,000 document reads per phone; repeated recoveries can exhaust the project's daily quota. Payloads are exempt from indexes. Spark without billing prevents paid overages, not service exhaustion.

The original 1,000-operation and 10 MiB cumulative caps were removed because routine use eventually stopped synchronizing even for two people. History is retained: deleting causal ancestors would strand old offline edits or change conflict decisions. Version 2 histories stage individual records in SQLite, and journals and retained copies reference completed staging rows. Legacy blobs are read in bounded slices and converted lazily. This removes the previous 64 MiB and 100,000-record whole-history format ceilings, without claiming unlimited device or Firebase capacity.

Operations with more than 1,000 parent or rejection references use protocol version 2. References are encoded as exact contiguous sequence ranges per installation, without adding causal dependencies or discarding history. Version 1 bytes are unchanged; older readers stop with an update requirement on version 2. Each reference set expands to at most 100,000 IDs, and encoded payloads still fit 512 KiB. Highly fragmented references can reach that payload limit earlier. Conflict replay indexes resource readers and writers to avoid comparing unrelated transactions; conflict and ancestry semantics are unchanged. Deploy rules first and update both phones before using the expanded limits.

An account belongs to exactly one active workspace. A personal workspace (owned, sole member) joins an invited family by archiving itself in the same transaction that accepts the invitation and moves the account reference. Rules allow the archive only for the sole owner, only together with a valid join, and only from an active workspace. Archives are never deleted or reactivated: uploads, devices, invitations and role or status changes are frozen, while the owner keeps read access and can list them. An archive keeps its admission slot; slots are not reclaimed automatically. The phone records the switch durably before the transaction, freezes local edits, resumes after lost responses or process death, and retains its previous local copy for export.

An administrator's restore acquires an expiring server-checked lease, stages complete chunks and activates a new dataset atomically. Old uploads are fenced. Other phones preserve their old copy and confirm replacement. Retired data cleanup must finish before another restore. Family deletion uses a tombstone and resumable client cleanup.

Independent password backups use PBKDF2-HMAC-SHA256 and AES-256-GCM, with authenticated envelope metadata. They carry finances, not cloud authority or installation credentials. Version 2 authenticates 64 KiB segments with their order and final marker; a truncated or corrupt stream cannot activate. Version 1 backup readers retain their original bounds for compatibility. Each dataset also charges payload bytes plus 32 bytes per causal reference against an 8 MiB UTC-day budget. Count and byte budgets reset together, and cleanup cannot reset either. This budget renews and does not replace project-wide Firebase quotas.

## Consequences

- Google and authorized project administrators can read synchronized finances. No family key or QR pairing is needed.
- Unapproved accounts cannot claim admission slots or bootstrap storage, but any signed-in account can still spend read quota on denied requests. Local work and export remain available.
- Claims, admission and inviter checks are not backward compatible with older rules: deployment follows the backfill and precedes the updated APK.
- Rules enforce access and bounds; clients validate financial contents and hashes. A malicious authorized member can still disrupt their family.
- Membership removal cannot erase offline/exported copies. Deletion cleanup requires a running client or project-owner maintenance.
- History growth can pause cloud uploads. Pending changes are retained; no automatic pruning or paid upgrade occurs.
- Release builds stay disabled until configuration, rules deployment and signed two-device acceptance are complete.

Implementation and acceptance procedure: [Firebase sync](../firebase-sync.md).
