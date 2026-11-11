# Firebase key restriction rollout

Status: prepared, not applied. Read-only checks on 11 November 2026 confirmed the release workflow uses the Android key ending `00e3b66f-9230-4428-84de-e2f00c890d32`. Both project keys currently allow 27 APIs. Billing is disabled. App Check enforcement remains off.

## 1. Restrict the Android key's API allowlist

Apply `firebase-android-api-restrictions.json` to the existing Android key using `updateMask=restrictions.apiTargets`. This retains the key value and current application restrictions. Do not rotate the key or change the release build variable.

The candidate retains nine services:

| Services | Reason |
|---|---|
| `identitytoolkit.googleapis.com`, `securetoken.googleapis.com` | Google sign-in through Firebase Auth and session refresh |
| `firestore.googleapis.com`, `datastore.googleapis.com`, `firebaserules.googleapis.com` | Firestore and conservative rules/REST compatibility |
| `firebaseappcheck.googleapis.com` | App Check challenges and token exchange |
| `firebase.googleapis.com`, `logging.googleapis.com` | Firebase's documented shared service allowances |
| `firebaseinstallations.googleapis.com` | Retained conservatively for SDK compatibility during the first rollout |

The remaining 18 entries are removed, including AI Logic, Cloud SQL, App Hosting, Data Connect, Phone Number Verification, messaging, remote config and ML services. Kinjar's app dependencies use Auth, Firestore and App Check; unused services must not be added merely because they are enabled on the project. This is a conservative candidate, not a claim that every retained API is called by Kinjar.

Do not modify the Browser key yet. Release configuration was confirmed to use the Android key, but the Browser key's other consumers have not been inventoried. Before narrowing it, check registered web apps, hosted Auth helpers, any direct REST tools and recent key-attributed requests. No observed traffic alone is not proof that a key is unused.

## 2. Test application restrictions separately

After API-only restrictions pass acceptance, the proposed Android restriction is:

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
6. Inspect errors for `API_KEY_SERVICE_BLOCKED`, Android-client restrictions and sign-in/refresh failures. App Check success is a separate gate: its baseline is already failing, so distinguish a new restriction error from the existing attestation issue.
7. On regression, restore only the changed restriction field from the saved snapshot, using a newly read etag. Verify the rollback operation and repeat bidirectional sync. Do not loosen Firestore rules or enable billing as a workaround.

Billing, quotas and App Check enforcement stay unchanged throughout these stages. Rate-limit tuning needs observed normal traffic and headroom; it is not bundled with key restrictions. No live compatibility test has been completed for these proposed settings.

## Diagnose App Check without USB

Live provider settings allow unrecognized app versions, do not require Play licensing, and require device integrity, matching Firebase's outside-Play guidance. Recent metrics show challenge creation but no observed token exchanges. Metrics do not reveal the phone-side cause or prove both phones passed.

Version 0.4.1 adds **Settings > Check app verification**. It requests a fresh App Check token and displays success or a sanitized error category/numeric Play Integrity code, app version and UTC check time. The diagnostic does not display, log or export the token, debug secret, account identifier or raw exception message. Requests time out after 30 seconds and are limited to one manual attempt per minute while the app process remains alive. Opening Settings makes no diagnostic request. Debug builds cannot produce a successful release-verification result.

Both phones can install that release through the existing updater and report the displayed result without USB or developer mode. Enforcement remains off until both phones produce valid tokens and fresh server-backed sync succeeds. A successful check is not an audit of family permissions or proof that enforcement is enabled. Wireless ADB is another option, but still requires developer mode and a trusted computer connection; it is not necessary for the in-app check.

## Sources

- [Firebase API-key restrictions and required services](https://firebase.google.com/docs/projects/api-keys)
- [Google Cloud API-key client restrictions](https://docs.cloud.google.com/api-keys/docs/add-restrictions-api-keys)
- [App Check distribution settings and enforcement guidance](https://firebase.google.com/docs/app-check/android/play-integrity-provider)
