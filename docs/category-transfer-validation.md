# Categories and currency transfers

Validated on 2026-11-09, branch `feature/categories-currency-ui`.

## Automated checks

- `make lint`: passed.
- `make test`: 489 JVM tests, no failures.
- `make build`: debug APK built for local emulator validation. The initial helper did not forward Firebase build variables through the container; release preparation corrects the helper and repeats validation with Firebase enabled.
- Repeated `make lint test release` with Firebase enabled: all 489 tests passed and signed, minified release 0.3.7 (version code 10) built. Manifest is non-debuggable. Signing certificate matches the retained private-release certificate documented in `releasing.md`.
- `make lint-sync test-sync`: 48 local Firestore rules tests, no failures.
- Tests cover legacy serialization, schema 13 migration, operation version 4, history version 2 upload compatibility, history version 3 backup round trips, category rules, duplicate names, received-amount editing and transfer balances.

## Android emulator

Used a disposable API 34 Google APIs emulator with synthetic local finances and no Google account. The existing family emulator and live cloud data were not changed.

- Installed release 0.3.6, created a local profile and 150 RSD expense, then upgraded in place. Profile, accounts, expense and balance survived.
- Created an income category from the income empty state. Income was preselected; chosen Work icon persisted. Income picker excluded expense categories.
- Lowercase `groceries` showed a duplicate-name error and disabled Save. Palette contains 20 unique values; icon catalog has 29 unique keys.
- Added 5,000 RSD income. Home showed the selected category icon with green income styling and a single monthly summary card.
- For a transfer of 1,170 RSD to EUR, fetched NBS data suggested 9.95 EUR. Overrode received amount to 10 EUR. Cash changed from 4,850 to 3,680 RSD, Euro became 10 EUR. Monthly income stayed 5,000 RSD and spending stayed 150 RSD.
- Transfer details displayed both 1,170 RSD sent and 10 EUR received.
- Inspected light and dark themes. At font scale 1.5, monthly figures stacked without clipping. Existing bottom-navigation labels remain cramped at that scale; outside this change.
- Exported a backup before upgrade and another after adding the new category and transfer. Restored each through the UI. Legacy restore returned the original 150 RSD expense; new-format restore recovered income, category and both transfer amounts. Cold restart preserved recovered totals.
- Installed final signed release 0.3.7 in place. Release startup succeeded, preserving 5,000 RSD income, 150 RSD spending and the 1,170 RSD to 10 EUR transfer.

## Rollout still required

Firebase rules were not deployed during this validation. Release 0.3.7 (version code 10) carries these changes. Rules accepting history version 3 must be deployed before new snapshots are uploaded. Update both family phones before using the new fields, then check live automatic sync and recovery between them. This emulator run does not establish two-phone cloud acceptance. Public release tagging is held until CI signing and Firebase configuration are verified against the retained private release.

Existing profiles receive no automatic income-category seed writes; create the desired categories in Settings. Fresh setup receives configurable income defaults. Existing category colors remain unchanged, including any historical reuse of a color.
