# Security review

One entry per feature. Each attack that could be written as a test has a regression test; the test
name is given in brackets.

## Feature: Payment state machine
- Attacks tried:
  - Illegal moves an attacker or buggy host could force: refund before capture, success after cancel, submit twice, retry after failure, any event on a terminal state.
  - Spoofed confirmation: a `Succeed` for a different transaction id while a payment is `Pending`.
  - Retry-counter tampering: jumping from attempt 3 to attempt 5, or replaying attempt 3.
  - Huge transaction / refund ids (memory, log flooding) and blank ids.
  - Race conditions: 200 to 1,000 coroutines submitting the same payment at once (double tap), a late gateway success racing a payer cancel.
  - Negative or zero attempt numbers.
- What broke:
  - Nothing reached an illegal state in the tests. Design-time finding: a naive table that accepts any `Succeed` from `Pending` would let a spoofed or misrouted confirmation for another transaction mark this payment paid (High). The guard was built in from the first commit and is covered by a test.
  - Unbounded id length would let a hostile gateway response push megabyte strings into state and logs (Medium).
  - Clock changes (user moves the device clock back) make history timestamps non-monotonic (Low: timestamps are informational only; no decision depends on them).
- Fixed now:
  - `Pending + Succeed` must carry the same transaction id [`pendingConfirmation_mustMatchTheSameTransactionId`].
  - Retry must be exactly `attempt + 1` [`retry_mustIncreaseAttemptByExactlyOne`].
  - Ids must be non-blank and at most 128 chars [`transactionIds_mustNotBeBlank`, `transactionIds_haveABoundedLength`].
  - Every transition is a compare-and-set, so exactly one of N racing events wins [`concurrentSubmits_exactlyOneWins`, `lateGatewaySuccessRacingPayerCancel_onlyOneOutcomeIsRecorded`].
  - Exhaustive 7 x 7 table: 10 legal moves, 39 rejected [`exhaustiveTable_everyStateEventPairMatchesTheSpec`].
- Scope for improvement:
  - The holder lives in memory. A host that must survive process death mid-payment should persist the idempotency key and reconcile through the gateway status check (added in features 2 and 4).
  - History timestamps could also store a monotonic clock reading to make clock-change forensics easier.

## Feature: Retry/timeout handling and idempotency
- Attacks tried:
  - Idempotency replay: reuse a captured key with a different amount (₹499 → ₹1) or a different payment method.
  - Double spend: 50 to 1,000 concurrent submits of one checkout (double taps, retry button mashing), and 1,000 sessions restored with the same key.
  - Malformed keys: empty, 65+ chars, spaces, SQL (`;DROP TABLE`), newline, Cyrillic look-alikes, RTL override `U+202E`, path traversal.
  - Predictable keys (guess another checkout's key).
  - Retry storm by misconfiguration: `maxAttempts = 11+`, infinite total timeout, NaN multiplier, negative delays.
  - Cancellation masking: a gateway that throws `CancellationException` or its own `withTimeout`; the caller cancelled during a backoff delay.
  - PII leakage: gateway exceptions whose messages contain card numbers.
  - Process death mid-payment (coroutine cancelled while the charge is in flight).
  - Memory exhaustion of the idempotency store; clock moved forward/back.
  - Zero, negative, `Long.MAX_VALUE` amounts; hostile order ids (`javascript:`, RTL, `../`).
- What broke:
  - Design-time finding (High): without a request fingerprint, a stolen or reused key could replay a cached success for a different amount, so the host would show "paid" for an order that was never charged at that amount. Built in from the start: the fingerprint check below.
  - Coroutine pitfall (Medium): a `TimeoutCancellationException` thrown by the gateway's own `withTimeout` looks like a cancellation and would escape as one, aborting the checkout silently. Found while writing the executor tests and fixed before commit.
  - Clock changes (Low): the store's TTL uses wall-clock time, so moving the clock forward shortens the replay window. Gateway-side dedup on the same key still prevents double charges.
- Fixed now:
  - Every key has a request fingerprint (order, amount, currency, method; no PAN/CVV). Reusing a key for a different request returns `Rejected(InvalidRequest)` and never charges [`sameKeyReusedWithADifferentAmount_isRejected`, `sameKeyWithDifferentRequest_isAConflict`, `…whileInFlight_isAConflict`].
  - Single-flight per key: exactly 1 charge for 1,000 concurrent submits [`doubleTap_concurrentSubmits_chargeOnce`, `concurrentDuplicates_shareOneExecution`, stress `sameIdempotencyKeyFired1000TimesConcurrently_*`].
  - Keys must match `[A-Za-z0-9_-]{8,64}`; generated keys are UUID v4 from a secure random source [`malformedKeys_areRejected`, `generatedKeys_areUniqueAndWellFormed`].
  - `maxAttempts` capped at 10; timeouts must be finite; multiplier ≥ 1 and finite [`invalidPolicies_areRejected`].
  - `CancellationException` is always rethrown; the gateway's own timeouts become `Timeout` only while the caller is still active [`cancellationExceptionThrownByTheBlock_isRethrown`, `gatewaysOwnWithTimeout_isATimeoutError_notACancellationOfTheCaller`, `cancellationDuringBackoff_stopsFurtherAttempts`].
  - Unknown exceptions keep only the class name, never the message [`unknownExceptions_keepOnlyTheClassName_neverTheMessage`].
  - An interrupted submit leaves the session in `Processing`; resubmitting resumes with the same key [`interruptedSubmit_staysProcessing_andResubmitResumesWithTheSameKey`].
  - The store has a TTL (24 h) and a size bound (1,000), oldest evicted first [`entriesExpireAfterTtl`, `oldestEntriesAreEvictedBeyondMaxEntries`].
  - Amount must be 1..₹1 crore in paise; order ids `[A-Za-z0-9._-]{1,64}`; descriptions ≤ 140 chars, no control chars [`CheckoutRequestTest`].
  - `PaymentMethod.Card.toString()` masks PAN and CVV [`card_toString_neverLeaksPanOrCvv`].
- Scope for improvement:
  - The store is in memory. A host that needs exactly-once across process death should persist the key before submitting (PayFlow does this in Room) and rely on gateway-side dedup.
  - Card fingerprints use last4 + expiry. Two cards sharing both would not be told apart for conflict detection (they are still charged correctly).

## Feature: Typed error codes
- Attacks tried:
  - Hostile gateway codes: `<script>`, embedded newlines (log injection), 10,000-char strings, RTL override, Devanagari and other non-ASCII look-alikes, 100,000 random strings of up to 4 KB.
  - Information leakage: errors carrying stack-trace class names, file paths, tokens or raw gateway codes, checked against what the payer would see.
  - Wrong-PIN loops: would a retryable mapping resend a UPI PIN automatically?
  - Hostile `Retry-After`: `Long.MAX_VALUE` or negative values to stall or break the retry loop.
  - Misclassification: a permanent failure (401, unknown code) marked transient would trigger retry storms.
- What broke:
  - A huge hostile code was trimmed and upper-cased (copied) before validation, an avoidable allocation per response (Low). Fixed: codes over 64 chars are dropped before any copy.
  - Nothing else broke. Design-time choices are recorded below.
- Fixed now:
  - Raw codes must be a short `[A-Z0-9_]{1,24}` token or they are dropped (`gatewayCode = null`) [`hostileCodes_areSanitisedBeforeTheyReachLogs`, stress `hundredThousandHostileCodes_neverLeakIntoTheError`].
  - User messages are fixed strings per error type; no detail, code or exception text is ever interpolated [`messagesNeverEchoInternalDetailsOrGatewayCodes`, `unknownGatewayCode_failsPermanently_withoutLeakingItToThePayer`].
  - `AuthenticationFailed` (wrong PIN/OTP), declines, limits and invalid details are non-retryable, so credentials are never resent automatically [`wrongUpiPin_isNotRetried_soThePinIsNeverResentAutomatically`, `retryability_matchesWhetherTryingAgainCanHelp`].
  - `Retry-After` is honoured but capped at the policy's total timeout; negative values are rejected [`hostileRetryAfter_isBoundedByTheTotalTimeout`, `rateLimited_rejectsNegativeRetryAfter`].
  - Unknown codes and HTTP 401/403 are permanent `GatewayError`s, so a misconfigured merchant key can't cause a retry storm [`unknownCodes_becomePermanentGatewayErrors`, `httpStatuses_mapWhenThereIsNoCode`].
- Scope for improvement:
  - The code table covers the common NPCI and ISO 8583 codes, not every bank-specific code. Unrecognised ones fail safely as permanent gateway errors.
  - The built-in English messages are generic. The Android UI maps errors to `strings.xml` (feature 5), so they can be translated.

## Feature: Pluggable gateway + FakeGateway
- Attacks tried:
  - Spoofed and tampered gateway answers: a capture for 1 paisa more than requested, a pending answer with a different amount, a status confirmation for another transaction id, a cancel for another transaction id.
  - Malformed ids from a hostile gateway: empty, blank and 10,000-char transaction ids, which would have crashed the event constructors.
  - Double refund: 20 concurrent refund calls; a refund before capture; a refund for a different amount; a refund of an unknown or pending transaction.
  - Hiding money in flight: the host calling `cancel()` while the payment is pending.
  - Lost responses: the gateway captures the money but the answer arrives after the client's timeout ("debited but shown as failed").
  - Status-query failures during polling (network exceptions, adapter bugs) and gateways that never answer status calls.
  - Gateway reporting "cancelled" for a charge that was never pending.
- What broke:
  - **High, found by the load test and fixed:** when every attempt timed out after the gateway had already captured, the session reported `Failed(Timeout)`. Under 10 % latency spikes, 2 of 2,000 payers were told "failed" although they had paid (they would likely pay again: a double charge). A single status lookup still left 20 of 2,000 at 30 % spikes. Root cause: no (or not robust enough) reconciliation after an unknown outcome.
  - **Medium, design-time:** a gateway returning a blank or huge transaction id would make `PaymentEvent.Succeed` throw, crashing the checkout instead of failing it.
- Fixed now:
  - After a retryable final error (timeout/network), the session looks the payment up by idempotency key (retried under the policy) before deciding. If found: Success / Pending. If the gateway says it doesn't know the payment: Failed. If the gateway is unreachable: new outcome `CheckoutResult.Unresolved`, with the state left in `Processing` and nothing replayed, so the host must reconcile with `checkStatus()` [`timedOutButCapturedAtTheGateway_isReportedAsSuccess`, `gatewayUnreachableForTheLookupToo_isUnresolved_notFailed`, `checkStatusWhileProcessing_andTheGatewayNeverSawTheCharge_failsWithTimeout`, stress `latencySpikeSweep_findTheBreakingPoint` now asserts 0 "failed but captured" at every spike rate].
  - Every captured, pending, confirmed and refunded amount and id is checked against the request; mismatches become `Failed(ResponseMismatch)` and never `Success` [`capturedAmountDifferentFromRequest_failsAsResponseMismatch`, `pendingAmountDifferentFromRequest_…`, `spoofedConfirmationForAnotherTransaction_…`, `refundForADifferentAmount_isAResponseMismatch`, `tamperedAmount_isNeverReportedAsSuccess`].
  - Malformed ids fail safely [`malformedTransactionIds_failSafelyInsteadOfCrashing`].
  - Refunds: single-flight per session (mutex) plus a derived refund idempotency key, so concurrent taps refund once. Only `Success` can be refunded, and a failed refund leaves the session `Success` [`concurrentRefunds_refundOnce`, `refund_beforeSuccess_isRejected`, `failedRefund_keepsTheSessionPaid`].
  - `cancel()` works only before submission (atomic precondition check), so a pending, possibly paid order can't be hidden [`cancelWhilePending_isRefused_soAPossiblyPaidOrderIsNeverHidden`]. Payer cancellation comes only from the gateway's status answer.
  - Status-query failures never fail a payment; only a definite gateway answer does [`statusCallErrors_duringPolling_areTolerated`].
- Scope for improvement:
  - `Unresolved` puts the final reconciliation on the host. A production backend would also receive gateway webhooks.
  - Only full refunds are supported. Partial refunds need amount tracking per refund.
  - `FakeGateway` is a sandbox only. A real gateway adapter must also enforce TLS pinning and request signing on its side.

## Feature: Compose payment sheet
- Attacks tried:
  - Rapid double taps on Pay (1,000 taps), and Pay pressed again during processing.
  - Closing mid-payment: back press, swipe down, tap outside, `dismiss()` from the host.
  - Process death mid-payment: the request reached the gateway, the process was killed before the answer arrived, and the activity was recreated from saved state.
  - Rotation while processing (the view model survives; `launch()` again is ignored).
  - Card data leakage: saved instance state (process-death bundle), `toString()`/logs, fields left filled after payment, screenshots, screen recording, recent-apps thumbnail, IME learning/autocorrect, clipboard copy of the CVV.
  - Tapjacking: an overlay window obscuring the sheet.
  - Malicious input: 100 KB pastes, Arabic-Indic digits, Cyrillic homoglyph VPAs, RTL override, newlines/tabs in the VPA, letters in card fields, `javascript:` VPAs.
  - SDK misuse: empty or duplicated method lists, launching twice, offering a method the host disabled.
- What broke:
  - Nothing reached a double charge or a leak in the tests. Design-time findings, handled before commit:
  - (High) Restoring after process death by calling `submit()` again would need the card details, which are deliberately not saved. Instead the sheet resumes with the saved key and reconciles via the new `CheckoutKit.resumeSession()` + `checkStatus()`, without ever charging again.
  - (Medium) A visible sheet re-launched after rotation would start a new attempt with a new key, a possible double charge. `launch()` is ignored while the sheet is open.
- Fixed now:
  - One charge for 1,000 taps; Pay only works in the form phase [`doubleTapOnPay_chargesOnce`, stress `thousandPayTapsAndTenThousandKeystrokes_chargeOnce`].
  - The sheet can't be closed while processing: back, swipe and outside-tap are disabled and the Close button is hidden [`closeWhileProcessing_isIgnored`, `whileProcessing_thePayButtonIsGone_andCloseIsHidden`].
  - Process death: request, key, tab, VPA and wallet are saved; the card number, expiry and CVV never are. In-flight payments are reconciled by key [`processDeathMidPayment_isReconciledWithTheSavedKey_withoutChargingAgain`, `savedState_neverContainsCardNumberOrCvv`, `resumedSessionAfterProcessDeath_reconcilesByTheSavedKey`].
  - Card fields are wiped as soon as an attempt ends [`validCard_pays_andCardDataIsWipedAfterwards`]; `PaymentMethod.Card.toString()` is masked.
  - `FLAG_SECURE` is on the sheet's window by default (`CheckoutSheetOptions.secureWindow`) [`sheetWindow_isSecureByDefault`, `secureWindow_canBeTurnedOffByTheHost`].
  - `filterTouchesWhenObscured` is set on the sheet's view [`sheetContent_filtersTouchesWhenObscured`].
  - CVV: `PasswordVisualTransformation` (Compose then disables copy/cut) and `KeyboardType.NumberPassword`. All payment fields have autocorrect off [`cardFlow_masksTheCvv_andPays` asserts the `Password` semantics].
  - Inputs are filtered to ASCII digits and capped (card 19, CVV 4, expiry 4, VPA 255, no control chars); validators reject homoglyphs and RTL [`cardInputs_areFilteredAndCapped`, `VpaValidatorTest`, `CardValidatorTest`, stress `hundredThousandHostileInputs_throughEveryValidator`].
  - Card expiry is judged in IST from the injected clock, so a device in another timezone can't pass an October card on 1 November IST [`monthBoundary_usesIndianStandardTime`].
  - The SDK writes no logs at all (no `android.util.Log`, no `println` in main sources).
- Scope for improvement:
  - The card number field still allows copy (useful for the payer). A stricter mode could disable it.
  - `FLAG_SECURE` covers only the sheet window. Hosts should also secure their own payment screens (PayFlow does).
  - Card details reach the gateway in memory as `PaymentMethod.Card`. A production SDK would tokenise them in a PCI-compliant iframe/SDK and never touch the PAN.
