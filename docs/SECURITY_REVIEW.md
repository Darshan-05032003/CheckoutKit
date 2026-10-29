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
