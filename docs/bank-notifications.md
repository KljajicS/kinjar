# Review bank notifications

1. Open **Accounts**, edit the account and pick **Raiffeisen** or **Yettel Bank** under **Bank notifications**. Repeat for each RSD and EUR account you want to use.
2. Tap **Allow notification access**. Grant access to Kinjar in Android settings. Bank settings must allow transaction notifications.
3. Open **Bank notifications** on Home when alerts are pending, or open the inbox from the configured account.
4. Check the amount and account. Pick a category for expenses and income. For a transfer, choose both accounts and enter the actual amount on each side if currencies differ.
5. Tap **Save transaction**, **Link existing transaction**, or **Skip**. When both banks report the same transfer, save it from either alert and link the other to the saved transaction.

ATM withdrawals are transfers to cash. An incoming payment from your own account can also be changed to a transfer. Kinjar suggests the configured account by bank and currency when the match is unique. For a card that draws from multiple currencies, check which account was actually charged.

Android can hide notification text it classifies as sensitive, even after you grant access. If an alert stays visible in Android but Kinjar receives no readable transaction details, enter the transaction manually.

## Account balances

For a recognized Raiffeisen card alert with an exact bank time, compare the reported balance with the bank app before choosing **Use verified bank balance**. The reported balance is rounded to the account's displayed precision. If the alert is older than the verified balance, choose whether it was already included. The app keeps the newer balance when a covered movement is recorded later. Each side of a transfer is reconciled independently.

Yettel's available balance is displayed for reference. Date-only alerts cannot establish the order of transactions on the same day. If you edit or delete a covered transaction, Kinjar flags the checkpoint in account settings and shows the balance derived from transactions until you reconcile again.

Settings and pending alerts are local to this phone. Accepted transactions and verified balances follow existing backup and family sync rules. Set the bank to **Off** on each account to stop capture. Update all family devices before processing alerts with new balance records. For a sync-enabled release, deploy Firestore rules accepting history version 12 before distributing the APK.

## Check on a phone

- Confirm the next ordinary purchase from each bank appears once. Dismiss or reopen the notification before reviewing it to check for duplicates.
- Review a real incoming payment from each bank. Where possible, check a transfer with both alerts and a withdrawal to cash. Test transfers in both review orders. Compare both account balances with the bank apps.
- Check a EUR example and a newer balance reviewed before an older alert. Where exact ordering is unavailable, check that the app asks for review or leaves the balance as a reference.
- Verify the inbox remains accessible after restart. Revoke notification access and confirm no new alerts arrive.
