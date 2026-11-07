# Security

## App lock

App lock is optional and off by default. Turn it on in Settings with the App lock switch.

### What it does

- When enabled, the app starts locked after every cold start, including after Android kills the process.
- The app locks again when it goes to the background (Home, Recents, another app, back out of the app) and when the screen turns off.
- Unlocking uses the Android system prompt only. The app never sees, collects or stores fingerprints, PINs, patterns or passwords.
  - Android 11 and newer: a Class 3 (strong) biometric or the phone PIN, pattern or password.
  - Android 8 to 10: a Class 3 biometric prompt, bound to an Android Keystore key so a weaker sensor cannot satisfy it, with a "Use PIN, pattern or password" button that opens the system credential screen. Devices without a strong biometric go straight to the credential screen.
- Turning the lock on and turning it off both require a fresh successful authentication.
- Cancelled, failed or unavailable authentication keeps the app locked. The lock is never turned off automatically. If the phone screen lock is removed while app lock is on, the app stays locked until a screen lock is set again.
- While locked, budget screens are not composed at all, so nothing is exposed to accessibility services. Navigation and unsaved form input are restored after unlocking.
- While enabled, the window uses `FLAG_SECURE`: the Recents thumbnail is blank and screenshots and screen recording are blocked.
- The enabled setting is stored only on this device, in app-private preferences. It is not part of the family event log and does not sync.

### Limits

App lock is an access gate for the user interface. It is not encryption.

- Financial data is stored in a plain, unencrypted SQLite database in the app's private storage. Protection of that file comes from the Android application sandbox and the phone's own storage encryption, not from app lock.
- Anyone who knows the phone PIN, pattern or password can unlock the app.
- Rooted or otherwise compromised devices, debuggable builds, ADB access with root, and forensic extraction of an unlocked phone are outside the scope of app lock.
- In split screen or freeform windows the app stays visible while another window has focus. It locks only when the activity stops.
- The app opts out of Android backup and framework device transfer using explicit exclusions. Vendor cloning tools outside the Android backup framework are not covered. Installation identity is stored separately in `noBackupFilesDir`.

## Optional family sync

Google sign-in identifies a family member. Firestore rules restrict access to active members and administrator actions to administrators. A Google account compromise can expose that member's family data. Cloud data is encrypted in transit and at rest by the provider, but is not end-to-end encrypted. Google and authorized project administrators can read it.

Creating a cloud workspace requires an `admission/{uid}` document with `enabled: true`, written only by the project owner with administrator credentials. Clients can read their own entry and can never write one. Other Google accounts can sign in but cannot create workspaces or consume admission slots. Joining by invitation does not need admission: a current administrator of that family vouches for the invitee. An invitation stops working as soon as its inviter is no longer an administrator; removing or demoting an administrator also cancels their pending invitations.

Each installation ID is claimed permanently, per family, by the first account that registers it. Operation IDs are derived from installation IDs, so rules refuse uploads and device registrations that use an installation ID claimed by another account. Claims survive device and member removal and are deleted only with the family.

SQLDelight stores local finances and pending operations. Firestore uses memory-only caching. Signing out pauses cloud access; it does not erase the local financial copy. App lock remains optional. Confirmed online removal clears that family's local data; an offline phone or exported copy cannot be remotely erased. Permission errors alone never trigger deletion.

Creator-authorized family deletion first removes member access, then deletes remote content. Cleanup runs in the client and can resume after interruption. Closing every client can leave a deletion unfinished; the project owner must finish abandoned cleanup. Removing a device slot only frees capacity: an authorized member can connect that phone again.

Release builds use Play Integrity App Check. Debug builds use the debug provider and must never be distributed as production releases. Enforcement requires verification with the actual signed sideloaded APK; see [setup](firebase-sync.md). App Check supplements admission and membership rules and does not prevent every form of quota abuse, such as repeated denied requests from any signed-in account.

## Backup files

Backups contain financial history, including receipts and pending changes. They exclude Google credentials, family authorization, reusable installation credentials, app lock and appearance settings. Historical author identifiers remain for attribution and deduplication; importing them does not grant cloud membership.

Files use a versioned envelope, random salt and nonce, PBKDF2-HMAC-SHA256 with 600,000 iterations, and AES-256-GCM. The envelope header is authenticated. Passwords require at least 12 characters; a long unique passphrase is recommended. There is no password reset for an encrypted file. Keep the password separately, for example in a password manager.

Import checks authentication, size, format and financial consistency before replacement. Local exports may lack changes still on another phone. An administrator's family restore replaces shared finances while preserving membership. Other phones retain their previous local copy for separate export and require confirmation before adopting the replacement. Retained copies remain sensitive local data.

### Phone acceptance steps

Run on a real phone, ideally one on Android 11+ and one on Android 8 to 10.

1. Settings, turn App lock on. The system prompt appears. Cancel it: the switch stays off. Turn it on again and authenticate: the switch is on.
2. Press Home, reopen the app: the lock screen shows and the prompt opens once. Cancel it: the app stays locked and the prompt does not reopen until Unlock is tapped.
3. Unlock, open Add, type an amount, press Home, reopen and unlock: the Add screen and the typed amount are still there.
4. While the prompt is open, press Home, then reopen from Recents: the app is locked. The Recents thumbnail is blank.
5. Try a screenshot inside the app: it is blocked.
6. Turn the screen off and on while the app is open: the app is locked.
7. Rotate the phone and switch the language in Settings while unlocked: the app stays unlocked. Rotate while the prompt is open: the prompt stays usable.
8. Android 8 to 10: tap "Use PIN, pattern or password", press Home on the credential screen, reopen and cancel: the app stays locked.
9. Force stop the app (or `adb shell am kill app.kinjar` while in the background) and reopen: the app is locked.
10. Settings, turn App lock off: authentication is required, and cancelling keeps it on. On Android 8 to 10, cancelling the separate credential screen also leaves the app locked.
11. With App lock on, remove the phone screen lock in phone settings, reopen the app: it stays locked with a message to set a screen lock.
