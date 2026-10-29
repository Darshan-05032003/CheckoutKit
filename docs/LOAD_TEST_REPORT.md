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
