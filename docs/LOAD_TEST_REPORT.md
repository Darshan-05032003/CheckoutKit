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
