<img src="assets/icon.png" width="64" height="64" alt="kinjar wallet icon">

# kinjar

Offline-first Android app for family finances. Track spending, see savings and plan goals. Optional family sync uses Firebase.

[Download APK](https://github.com/KljajicS/kinjar/releases) · [Roadmap](docs/plans/roadmap.md) · [Report an issue](https://github.com/KljajicS/kinjar/issues)

## Screenshots

<p>
  <img src="docs/screenshots/home.png" width="240" alt="Home showing total savings and monthly income and spending">
  <img src="docs/screenshots/accounts.png" width="240" alt="Accounts with balances in different currencies">
  <img src="docs/screenshots/goals.png" width="240" alt="Savings goals ordered by priority">
</p>

Screenshots use demo data.

## Features

- Income, expenses, transfers and recurring bills.
- Multiple accounts and currencies, with total savings in EUR and RSD.
- Drag-to-reorder accounts and goals. Savings fill goals in priority order.
- Projects with configurable categories, expense linking, historical costs, charts, optional budgets and archive/restore. Project currency can switch between EUR and RSD using saved expense-date conversions.
- Monthly insights, spending categories and transaction history.
- Serbian fiscal receipt QR scanning and manual loan repayment tracking.
- English and Serbian Latin, selectable in Settings, with light, dark and AMOLED themes.
- Password-protected backup files, with preview and confirmation before restoring.
- Optional Google sign-in, family invitations and recovery on a new phone in configured builds.
- Signed release update checks and installation from Settings, without Play Store. See [updates](docs/updates.md).

## Privacy and status

Core tracking works offline, without an app account or dedicated backend. Receipt verification and exchange-rate updates require internet. Converted totals use the latest available rates and are estimates.

Optional app lock asks for a fingerprint or the phone screen lock to open the app. It gates access only: data stays in an unencrypted local database protected by the Android app sandbox. See [security](docs/security.md) and [reporting a vulnerability](SECURITY.md).

Sync is an experimental, capacity-limited beta and is disabled in ordinary builds. Configured builds upload family finances to Firebase without end-to-end encryption: Google and authorized project administrators can read them. Shared free capacity can run out; pending changes remain on the phone. See [setup and acceptance](docs/firebase-sync.md) before enabling it. Signed two-device acceptance is still pending. Bank statement import is deferred.

kinjar was previously called Family Budget Tracker. Releases up to 0.3.3 use application ID `rs.budget`; kinjar installs as a separate app and does not migrate their data.

## Build

Requires JDK 17, Android SDK platform 37 and Make. Runs on Android 8.0 or newer.

```bash
make check   # lint and JVM tests
make build   # debug APK
make run     # install and launch on a connected device or emulator
```

See [release signing](docs/releasing.md), [architecture decisions](docs/decisions/) and [product specifications](docs/specs/).

## License

Copyright (C) 2026 Kljajic Sinisa.

Kinjar source is licensed under the [GNU General Public License version 3 only](LICENSE) (`GPL-3.0-only`), without warranty. Third-party dependencies and bundled assets retain their own licenses and notices; see [third_party](third_party/).
