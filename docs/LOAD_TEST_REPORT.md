# Load / stress test report

All numbers come from `./gradlew stressTest` on the build machine: Ubuntu 24.04, JDK 21 host
running JDK 17 toolchain, 15 GB RAM, stress JVM capped at `-Xmx1g`, `Dispatchers.Default`
(one thread per core). Stress tests live in `checkoutkit-core/src/jvmTest/.../core/stress` and are
excluded from `allTests`.

## Feature: Payment state machine
- Scenario and scale:
  - 10,000 concurrent sessions on `Dispatchers.Default`, each driven through a full lifecycle (submit, retry, then success / pending→success→refund / failure / pending→cancel), 37,500 transitions in total.
  - 1,000 coroutines racing one session: 1,000 submits, then 500 `Succeed` vs 500 `Fail`.
  - 8 writer coroutines pushing one session from attempt 1 to attempt 2,000 while a live `StateFlow` collector watches.
- Results (numbers):
  - 10k sessions: 71 to 95 ms wall time, about 8 MB retained heap for 10,000 live holders (about 0.8 KB per session). Exactly 2,500 sessions in each final state.
  - 1,000 racers: 56 to 74 ms; exactly 1 submit and 1 outcome accepted, 2 history entries.
  - 2,000-attempt storm: 30 ms before the fix (62 MB transient heap), 68 ms after the fix (4 MB transient heap); the collector saw 346 to 427 conflated states, always in increasing order.
- What broke:
  - Memory, not correctness. History was copied on every transition, so a session with N transitions cost O(N²) copying and unbounded memory: 62 MB of garbage for one session hammered to 2,000 attempts.
- Failure flow (step by step):
  1. A misconfigured host sets a huge retry count, or a hostile gateway keeps answering "retry".
  2. Every `Retry` event copies the whole history list and appends one entry.
  3. Memory and CPU per transition grow linearly, total cost quadratically, until GC pressure stalls the app.
- Fix applied / recommendation:
  - History is capped at `PaymentStateHolder.MAX_HISTORY = 32` entries (oldest dropped), regression test `history_isBoundedSoRetryStormsCannotGrowMemoryWithoutLimit`. Transient heap fell from 62 MB to 4 MB.
  - The retry policy (feature 2) also caps attempts, so this cap is a second line of defence.

## Feature: Retry/timeout handling and idempotency
- Scenario and scale:
  - Same idempotency key fired 1,000x concurrently: (a) 1,000 submits on one session, (b) 1,000 sessions restored with one key. Gateway latency 20 ms, real threads.
  - Retry storm: 10,000 sessions against a gateway that fails each attempt 30 % network, 15 % timeout, 5 % decline (seeded per order); up to 3 attempts, full jitter.
  - Hung gateway: 2,000 sessions whose first attempt hangs 10 s, with a 200 ms attempt timeout.
  - 10,000 finished results kept in the idempotency store, then all 10,000 keys replayed.
  - Thundering herd: 10,000 clients fail at t = 0 and retry after a 1 s backoff, without and with full jitter (virtual time).
- Results (numbers):
  - Same key x1,000: exactly **1** gateway call in both variants; one distinct result; all 1,000 sessions synced to the same state; 22 to 29 ms.
  - Retry storm: 565 ms wall time, 37 MB heap growth, 16,436 gateway calls (1.64 per session), 8,298 successes / 1,702 failures, max 3 calls per key (never above `maxAttempts`).
  - Hung gateway: all 2,000 succeeded on attempt 2 in 282 ms total (the 10 s hangs were cut at 200 ms); exactly 2 calls per key.
  - 10,000 cached results: about 34 MB (≈3.4 KB per session plus its cached entry); the replay of 10,000 keys made **0** extra gateway calls.
  - Thundering herd: peak retries in one 10 ms window fell from **10,000** (no jitter) to **106** (full jitter).
- What broke:
  - No correctness failure. Weak point: the store is guarded by one `Mutex`, which serialises bookkeeping (not gateway calls). At 10,000 sessions that cost under 0.6 s in total, so it was left as is.
  - Retention trade-off: with the default `maxIdempotencyEntries = 1,000`, keys older than the newest 1,000 results drop out of the in-memory replay window.
- Failure flow (step by step):
  1. A busy host finishes more than 1,000 checkouts within the 24 h TTL.
  2. The oldest cached results are evicted.
  3. A late duplicate submit for an evicted key from a new session runs the charge again with the same key.
  4. Safety then depends on the gateway deduplicating by key (the contract requires it, and `FakeGateway` does it). The same session still replays from its own terminal state.
- Fix applied / recommendation:
  - The bound stays, for memory safety (≈3.4 MB at the default). It is configurable through `CheckoutConfig.maxIdempotencyEntries`, and the gateway dedup contract is documented on `PaymentGateway`.
  - Production hosts should use full jitter (`jitterFactor = 1.0`) when many clients can fail together (for example after a gateway outage). The default is 0.5.

## Feature: Typed error codes
- Scenario and scale:
  - 1,000,000 gateway-code → `CheckoutError` → `UserMessage` mappings on 8 coroutines (`Dispatchers.Default`), mixing UPI, ISO and unknown codes.
  - 100,000 random hostile codes (1 % of them up to 4 KB) built from markup, control, RTL, Devanagari, emoji and ASCII characters.
- Results (numbers):
  - 1M mappings: 54 ms, about 18.5 million mappings per second, 23 MB transient heap. Mapping can't be a bottleneck.
  - 100k hostile codes: 144 ms, 94,717 dropped as unsafe; every code that was kept matched `[A-Z0-9_]{1,24}`.
- What broke:
  - Nothing functionally. Profiling the hostile run showed every long code being copied twice (`trim` + `uppercase`) before it was rejected.
- Failure flow (step by step):
  1. A compromised or buggy gateway answers with a multi-megabyte "code".
  2. `fromCode` trims and upper-cases it, which allocates two more copies.
  3. Under a polling storm this multiplies garbage and GC pauses on the UI process.
- Fix applied / recommendation:
  - Codes longer than 64 chars are now rejected before any copy (constant time). Gateways should also cap response body sizes at the HTTP layer.

## Feature: Pluggable gateway + FakeGateway
- Scenario and scale:
  - 10,000 concurrent sessions against `FakeGateway` (2 to 5 ms latency, 5 % of calls spiking to 300 ms, the first attempt of every key failing with a network error), mixed payers via magic inputs (50 % ok, the rest declined / insufficient funds / invalid VPA), 100 ms attempt timeout, 4 attempts with full jitter.
  - Pending polling storm: 5,000 UPI-collect-style payments, each pending for 3 polls (10 → 40 ms backoff).
  - Latency-spike sweep to find the breaking point: 2,000 sessions per run, 8 s response spikes vs a 3 s attempt timeout, spike probability 0 % → 90 % (virtual time).
- Results (numbers):
  - 10k mixed: 568 to 617 ms wall time, 70 to 115 MB transient heap, 20,505 charge calls; exactly 5,000 successes = 5,000 captures (no double capture, one capture per key).
  - Polling storm: 863 to 1,045 ms; exactly 20,000 status calls (4.0 per payment, as configured); all 5,000 resolved to Success.
  - Spike sweep after the fix:

    | Spike rate | Success | Unresolved | Failed but captured | Charge calls | Status calls |
    |---|---|---|---|---|---|
    | 0 % | 2,000 | 0 | 0 | 2,000 | 0 |
    | 10 % | 2,000 | 0 | 0 | 2,186 | 2 |
    | 30 % | 1,998 | 2 | 0 | 2,734 | 79 |
    | 50 % | 1,968 | 32 | 0 | 3,532 | 444 |
    | 70 % | 1,747 | 253 | 0 | 4,368 | 1,516 |
    | 90 % | 948 | 1,052 | 0 | 5,418 | 3,968 |
  - Before the fix the sweep failed its "no failed-but-captured" assertion at the first spiky rate: **2 of 2,000** payers at 10 % spikes were told "failed" although the gateway had captured their money (the test stops at the first failing rate). A first fix with a single status lookup cleared 10 % but still left **20 of 2,000** at 30 %. The final fix (lookup retried under the policy, plus `Unresolved`) gives 0 at every rate.
- What broke:
  - **Correctness under latency spikes.** When the gateway captured the money but the response arrived after the attempt timeout, the SDK retried, timed out again and declared the payment failed.
- Failure flow (step by step):
  1. The payer taps Pay. The charge request reaches the gateway, which captures ₹ and sends the answer.
  2. The answer is delayed (8 s spike) past the 3 s attempt timeout; the SDK cancels the attempt.
  3. The retries hit the same slow response leg and time out too.
  4. Old behaviour: `Failed(Timeout)` → "Payment failed". The payer pays again → double charge.
- Fix applied / recommendation:
  - Reconcile before failing: look the payment up by idempotency key (retried under the same policy). Then report Success / Pending if found, Failed if unknown to the gateway, and `Unresolved` (state kept `Processing`, never cached) if the gateway is unreachable.
  - Breaking point: success stays at 98 % or more up to 50 % spikes. Beyond about 70 % the gateway is effectively down and `Unresolved` grows, but the outcome is never wrongly reported as failed.
  - Recommendation for hosts: show `Unresolved` as "We're confirming your payment, don't pay again" and call `checkStatus()` later.

## Feature: Compose payment sheet
- Scope: these are on-device (JVM) simulations of a heavy user and of a long-lived host screen. UI stress tests live in `checkoutkit-ui/src/test/.../ui/stress`, are excluded from `testDebugUnitTest`, and run with `./gradlew stressTest`.
- Scenario and scale:
  - 100,000 random inputs (1 in 500 of them 10 to 100 KB long; markup, control, RTL, Devanagari, Cyrillic, Arabic-Indic digits, emoji) through all five validators (card, VPA, expiry, CVV, money parser).
  - 1,000,000 Luhn checks on generated 16-digit numbers.
  - One sheet: 10,000 keystrokes followed by 1,000 Pay taps while the gateway takes 500 ms.
  - 1,000 sheets opened, paid and closed in a row on one view model (leak check: jobs, results channel, saved state, idempotency entries).
- Results (numbers):
  - Validators: 304 ms for 100k inputs x 5 validators (19 MB transient); 126 random inputs happened to be valid.
  - Luhn: 156 ms for 1M checks; 100,219 passed (the expected ~10 %).
  - Keystroke + tap storm: 20 ms of processing, exactly **1** gateway charge, final phase Success.
  - 1,000 sequential sheets: 652 ms, heap delta about 0 MB after GC, 1,000 results delivered exactly once, 1,000 captures, 0 saved-state keys left behind.
- What broke:
  - Nothing. Headroom is large: validation costs about 3 µs per input even with 100 KB strings, because the view model caps field lengths before validating and validators check length before running regexes.
- Failure flow (step by step):
  - N/A. The pre-existing protections held: input caps → validators → `phase == Form` guard → idempotency store.
- Fix applied / recommendation:
  - None needed. Keep the length caps in the view model if fields are added; they are what keeps a 100 KB paste cheap.

## Feature: Sample app and docs
- Scenario and scale:
  - The sample's outcome matrix: all 11 `FakeOutcome`s x 100 concurrent checkouts each, with the sample's production timings (800 ms latency, 3 attempts, 3 s attempt timeout, 1.5 s pending polls), on virtual time. Every success is then refunded concurrently through `CheckoutKit.refund`.
  - 1,000 sample-style sheets opened, paid and closed in a row (reported under feature 5).
- Results (numbers):
  - 1,100 checkouts + all refunds: 82 ms wall time (58.9 s of simulated time). Each outcome produced exactly its expected result type 100/100 times.
  - Gateway calls per 100 checkouts: 100 for single-shot outcomes, 200 for `RATE_LIMITED_ONCE` (one 429, then success), 300 for `GATEWAY_ERROR` and `TIMEOUT` (3 attempts each), confirming `maxAttempts` holds under load.
  - Refunds: one refund per captured payment, `refundedPayments == successes` for every outcome.
- What broke:
  - Nothing. One UX cost was measured: with the sample's timings the `TIMEOUT` outcome keeps a payer waiting about 3 x 3 s plus 1 to 3 s of backoff plus a status lookup, roughly 11 to 13 s, before the failure screen.
- Failure flow (step by step):
  1. The gateway never answers.
  2. Each of 3 attempts waits the full 3 s attempt timeout, with jittered backoff in between.
  3. Reconciliation asks the gateway by key, learns it never saw the charge, and the sheet shows "This is taking too long".
- Fix applied / recommendation:
  - Kept as is in the sample: it demonstrates the retry policy. Hosts that prefer a faster answer should lower `attemptTimeout`/`maxAttempts`. Reconciliation keeps that safe, because a shorter timeout can no longer produce a wrong "failed".
