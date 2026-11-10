# 5. One shared family key, AES-GCM via Tink

Date: 2026-10-14. Status: superseded by [0014: Optional Firebase sync and password-protected recovery](0014-firebase-family-sync.md).

This record describes the original design. Current cloud sync is not end-to-end encrypted; exported backups use password-based encryption.

## Context

Drive must never see plaintext. Per-user keys with key exchange would add protocol complexity for a two-person family with full mutual trust.

## Decision

One 256-bit AES-GCM family key generated on the first device, stored in Android Keystore-backed encrypted preferences, shared to the second device in person by QR code, and backed up as a 24-word BIP-39 phrase kept in a password manager. Tink provides the primitives. Event files are encrypted whole.

## Consequences

- Simple: one key, one algorithm, no key rotation in the MVP.
- Anyone with the key reads everything; acceptable within a family.
- Losing every device and the phrase loses the data permanently. The app says so during setup.
- Rotation or removing a member requires re-encrypting all logs with a new key; deferred.
