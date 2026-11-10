# 4. Sync through Google Drive with one event log per device

Date: 2026-10-14. Status: superseded by [0014: Optional Firebase sync and password-protected recovery](0014-firebase-family-sync.md).

This record describes the original design. Current sync uses Firebase, not Google Drive.

## Context

Devices must sync across different networks with no server of our own. Considered: peer-to-peer (both devices must be online at once), self-hosted Nextcloud or WebDAV (a server to run), Firebase (Google can read the data), a single shared database file on Drive (write conflicts).

## Decision

Each device owns exactly one file in the Drive `appDataFolder`: `events-<deviceId>.jsonl.enc`. Only its owner writes it; every device reads all of them and applies unseen events. WorkManager runs sync periodically and after each local write.

## Consequences

- No write conflicts by construction. Concurrent edits of one record resolve by event timestamp.
- Both users need a Google account; they already have one. Free, no infrastructure.
- Drive is a dumb blob store and can be swapped for any file store behind the same interface.
- Files only grow. Compaction is deferred until it matters.
