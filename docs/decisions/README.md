# Architecture decisions

One file per decision, numbered, never edited after acceptance. A reversed decision gets a new file that supersedes the old one.

| # | Decision |
|---|---|
| [0001](0001-native-android-kotlin.md) | Native Android with Kotlin and Jetpack Compose |
| [0002](0002-single-module-layered-packages.md) | Single Gradle module with layered packages |
| [0003](0003-event-sourced-core.md) | Event-sourced core |
| [0004](0004-drive-per-device-event-logs.md) | Sync through Google Drive with one event log per device |
| [0005](0005-family-key-encryption.md) | One shared family key, AES-GCM via Tink |
| [0006](0006-sqldelight-storage.md) | SQLDelight for local storage |
| [0007](0007-safe-to-spend-formula.md) | Safe-to-spend formula |
| [0008](0008-deterministic-replay.md) | Deterministic replay, logical clock, delete wins |
| [0009](0009-bills-and-budget-reserve.md) | Unpaid bills and the budget reserve are not subtracted twice |
| [0010](0010-receipt-scanning.md) | Serbian receipt scanning with local QR decoding |
| [0011](0011-exchange-rates.md) | Cached NBS rates and event-backed manual overrides |
| [0012](0012-simplified-finances.md) | All-account savings, ordered goals and simplified screens |
| [0013](0013-manual-loans.md) | Manual loans and repayments |
| [0014](0014-firebase-family-sync.md) | Optional Firebase sync and password-protected recovery |
