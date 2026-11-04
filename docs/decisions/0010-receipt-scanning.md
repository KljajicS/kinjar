# 10. Fiscal receipt scanning

Status: accepted. Date: 2026-10-15.

Use CameraX and the official ZXing-C++ Android decoder. The Apache-2.0 decoder avoids ML Kit usage telemetry. Add jsoup for HTML parsing instead of regular expressions over HTML, and OkHttp for bounded cancellable requests.

Verified the public example from https://github.com/turanjanin/serbian-fiscal-receipts-parser on the production verification service. GET /v/?vl=... returned a verified ordinary-sale page. HTML includes labeled issuer, merchant, total, date, invoice number and script data with a temporary token. The official /js/invoice-verify.js uses POST /specifications with invoiceNumber and token. That request returned success and three item rows whose totals matched the page. No JavaScript execution or authentication was needed. This is an observed public website contract, not a versioned API.

Accept only exact HTTPS suf.purs.gov.rs verification URLs. Reject redirects. Parse supported verified ordinary sales; reject invalid, unsupported or incomplete receipts. Retain only parsed receipt fields, never page HTML or lookup tokens. Test fixtures are synthetic equivalents of observed fields, without actual receipt identifiers or URLs.

Store receipt metadata in TransactionAdded with an optional default for old events. Derive stable receipt and transaction identities from issuer and full fiscal invoice number. Save only after confirmation. Receipt identity survives transaction deletion. Since 2026-11-04, a deliberate rescan may restore a deleted expense: TransactionAdded carries an optional receiptDeletionId referencing the latest deletion observed by the scanner. Active expenses still reject duplicates. Replay permits restoration only when that reference matches the latest preceding deletion in event order; old duplicate scans and stale restorations cannot revive a subsequently deleted expense. Original receipt metadata remains unchanged. Older clients ignore the new field and may hide restored expenses until upgraded; projector version 7 rebuilds the view on upgrade. Roll back UI using a forward build that retains schema and event support.

Original receipt total and items remain immutable when the user adjusts the expense. Currency is RSD; foreign-currency payments and refunds are outside this milestone. Source date is the receipt's Serbian calendar date, without conversion to the phone's timezone.
