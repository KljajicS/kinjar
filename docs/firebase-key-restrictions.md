# Firebase key restriction rollout

Status: applied on 11 November 2026. The release uses the Android key ending `00e3b66f-9230-4428-84de-e2f00c890d32`, restricted to the nine APIs below and the release package/certificate. Firestore App Check enforcement is on. Both phones passed verification and two-way sync after those changes; the owner also reports fresh sign-in works. Billing remains disabled.

## 1. Restrict the Android key's API allowlist

Apply `firebase-android-api-restrictions.json` to the existing Android key using `updateMask=restrictions.apiTargets`. This retains the key value and current application restrictions. Do not rotate the key or change the release build variable.

The Android allowlist retains nine services:

| Services | Reason |
|---|---|
| `identitytoolkit.googleapis.com`, `securetoken.googleapis.com` | Google sign-in through Firebase Auth and session refresh |
| `firestore.googleapis.com`, `datastore.googleapis.com`, `firebaserules.googleapis.com` | Firestore and conservative rules/REST compatibility |
| `firebaseappcheck.googleapis.com` | App Check challenges and token exchange |
| `firebase.googleapis.com`, `logging.googleapis.com` | Firebase's documented shared service allowances |
| `firebaseinstallations.googleapis.com` | Retained conservatively for SDK compatibility during the first rollout |

The remaining 18 entries are removed, including AI Logic, Cloud SQL, App Hosting, Data Connect, Phone Number Verification, messaging, remote config and ML services. Kinjar's app dependencies use Auth, Firestore and App Check; unused services must not be added merely because they are enabled on the project. This is a conservative candidate, not a claim that every retained API is called by Kinjar.

The Browser key inventory and applied restrictions are recorded below. No key was rotated or deleted.

## 2. Test application restrictions separately

The applied Android restriction is:

```json
{
  "restrictions": {
    "androidKeyRestrictions": {
      "allowedApplications": [
        {
          "packageName": "app.kinjar",
          "sha1Fingerprint": "a4282c74d68d175ba400083bf5d60870c3f53e7b"
        }
      ]
    }
  }
}
```

Use `updateMask=restrictions.androidKeyRestrictions`, retaining the API allowlist. The SHA-1 was verified from the public signed 0.4.0 APK, whose SHA-256 certificate matches the retained release certificate. It also matches Firebase's registered SHA-1. An API-key Android restriction uses SHA-1; App Check registration uses SHA-256.

Package/certificate restrictions depend on SDK request support and do not replace authentication, family rules or App Check. Verify sign-in, refresh and App Check requests carry supported Android identity headers before rollout. Different debug signing certificates and direct REST clients may fail. Keep development clients in a separate development project instead of blindly allowing extra certificates on the production key.

## Acceptance and rollback

1. Before each stage, read and save the complete current key metadata, restrictions and etag privately. Do not retrieve or save the key string. Check for concurrent changes and use the fresh etag in the update body.
2. Prefer a temporary test key and signed test APK for compatibility validation, as Firebase recommends. Creating that key or distributing a test build is a separate rollout action, not performed by this preparation.
3. Apply only one restriction stage at a time. Wait for the API operation to finish, read back the effective settings, and allow propagation before testing.
4. On both phones, restart Kinjar and send a new dummy expense in each direction. Confirm the other phone receives it. Cached screens and an existing authenticated session do not prove fresh sign-in or token refresh works.
5. Verify fresh Google sign-in on a spare/test installation and an actual Auth token refresh. Do not sign out, uninstall or clear data on either family phone merely to test restrictions. If token refresh cannot be demonstrated, mark that gate pending.
6. Inspect errors for `API_KEY_SERVICE_BLOCKED`, Android-client restrictions and sign-in/refresh failures. App Check success is a separate gate from family authorization and sync.
7. On regression, restore only the changed restriction field from the saved snapshot, using a newly read etag. Verify the rollback operation and repeat bidirectional sync. Do not loosen Firestore rules or enable billing as a workaround.

Apply independent changes separately. Do not enable billing or loosen family rules to work around a regression. Initial Android request probes accepted the correct package/certificate and rejected missing identity headers. Auth and refresh probes reached normal request validation; these probes do not themselves prove a valid sign-in or refresh. Monitoring also shows successful automatic token refreshes, but historical totals alone do not prove refresh after every restriction change.

## Browser key and quota review

The Browser key remains referenced by Firebase Auth's client configuration. Inventory found no registered web/iOS apps, Hosting releases or custom domains, and no Browser-key-attributed traffic since project creation. Firebase's reserved Auth helper pages exist, so the key was retained for compatibility.

Its API allowlist now contains `identitytoolkit.googleapis.com`, `securetoken.googleapis.com`, `firebase.googleapis.com` and `logging.googleapis.com`. The first two preserve Auth and token refresh; the latter two retain Firebase's shared allowances. HTTP referrer restrictions remain unset because the Auth-side reference is not proof of exclusively browser-originated callers. API restrictions do not authenticate users or replace family rules.

The Identity Toolkit `default` quota for `1/min/{project}/{user}` now has a consumer override of 30 requests per minute. The measured project-wide Auth peak was two requests per minute over the preceding week. Shared project, token-refresh, Firestore, App Check and Play Integrity limits remain unchanged: lowering shared caps can make denial of service easier. This review does not claim to eliminate quota exhaustion or prevent unrelated Google accounts from creating Auth records; family rules still control access to finance data.

The Browser allowlist and quota override were applied independently and verified by live readback. Fresh rollback metadata was saved before mutation. To undo Browser API restrictions, restore only its saved `apiTargets` using a fresh etag. To undo the new quota, delete its consumer override, returning to the default; do not alter other quotas. Revisit the family-sized quota and admission policy before admitting public users.

## Diagnose App Check without USB

Live provider settings allow unrecognized app versions, do not require Play licensing, and require device integrity, matching Firebase's outside-Play guidance. Before 0.4.2, metrics showed challenge creation without token exchanges. After the fix, successful Play Integrity exchanges and verification on both phones were confirmed.

Investigation of 0.4.1 found that explicit Firebase options omitted the numeric project number (`gcmSenderId`). The installed Play Integrity provider reads that option and parses it after generating a challenge. Parsing the missing value throws `NumberFormatException`, which the original diagnostic reported as `FAILED`. Version 0.4.2 derives and validates the project number from the Firebase Android app ID before initializing Firebase. Both physical phones subsequently passed attestation.

Version 0.4.2 also adds a **Verification details** panel with the observed SDK operation, fixed exception categories, safe numeric codes, app version and check time. The SDK does not expose every network substep, so the panel labels unavailable substeps explicitly. HTTP codes are extracted only from the known Firebase SDK error prefix; response bodies and arbitrary exception messages/class names are excluded.

Version 0.4.1 adds **Settings > Check app verification**. It requests a fresh App Check token and displays success or a sanitized error category/numeric Play Integrity code, app version and UTC check time. The diagnostic does not display, log or export the token, debug secret, account identifier or raw exception message. Requests time out after 30 seconds and are limited to one manual attempt per minute while the app process remains alive. Opening Settings makes no diagnostic request. Debug builds cannot produce a successful release-verification result.

Both phones can report the displayed result without USB or developer mode. Enforcement was enabled only after both phones passed verification and fresh server-backed sync. A successful check alone is not an audit of family permissions or proof that enforcement is enabled. Wireless ADB is another option, but is not necessary for the in-app check.

## Sources

- [Firebase API-key restrictions and required services](https://firebase.google.com/docs/projects/api-keys)
- [Google Cloud API-key client restrictions](https://docs.cloud.google.com/api-keys/docs/add-restrictions-api-keys)
- [App Check distribution settings and enforcement guidance](https://firebase.google.com/docs/app-check/android/play-integrity-provider)
