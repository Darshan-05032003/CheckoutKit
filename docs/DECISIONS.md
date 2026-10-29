# Decisions

Decisions taken during the build, with the reasoning, so nothing is a surprise later.

## Toolchain (Phase 0)
- **AGP 9.5.0** (latest stable on maven.google.com at scaffold time) with AGP's **built-in Kotlin** support, so the `org.jetbrains.kotlin.android` plugin is not applied.
- **Kotlin 2.4.21**, **KSP 2.3.12**, **Compose BOM 2026.09.00**, **Hilt 2.60.1** (KSP, not kapt).
- **Gradle 9.6.0** wrapper instead of the newest 9.8.2: 9.6.0 was already cached on the build machine and the disk had less than 7 GB free; every new Gradle version adds ~2 GB of per-version caches. 9.6.0 is fully compatible with AGP 9.5.0 (verified by building). Upgrading later is a one-line change in `gradle/wrapper/gradle-wrapper.properties`.
- **JDK toolchain 17** (`jvmToolchain(17)`), built on a JDK 21 host. JitPack is configured for JDK 17.
- **compileSdk / targetSdk 37** (latest stable platform installed), **minSdk 26**.
- Robolectric tests pin `@Config(sdk = [35])` because Robolectric 4.17 doesn't ship an SDK 37 runtime yet.

## Feature 1: state machine
- **Pure transition function + CAS holder.** `PaymentStateMachine.transition` is a stateless function; `PaymentStateHolder` publishes state with `MutableStateFlow.compareAndSet`. This works in common code without locks or atomicfu and gives "exactly one winner" under races.
- **Cancel is legal only from Created and Pending.** Cancelling while the charge request is in flight (Processing) would hide a payment that may already be captured; instead the outcome is reconciled through the gateway status check.
- **Success is not terminal** because it can move to Refunded; `isOutcome` tells hosts the money outcome is final.
- **`CheckoutError` starts small** (Network, Timeout, Declined, Unknown) because `Failed` needs an error type; feature 3 completes it.
- **History is capped at 32 entries** after the load test showed quadratic copying under retry storms.
- **Stress tests** live in `jvmTest` package `...core.stress`, are filtered out of `jvmTest`, and run via a dedicated `stressTest` Test task with a 1 GB heap.

## Feature 2: retry, timeout, idempotency
- **Jitter model:** delay = `base * (1 - jitterFactor * U[0,1))`, so `jitterFactor = 1.0` is AWS-style "full jitter" and `0.0` is plain exponential. Default 0.5 keeps retries reasonably quick for one user; full jitter is recommended for fleets.
- **`maxAttempts` hard cap of 10**, and all timeouts must be finite, so a misconfiguration can't create unbounded retry storms.
- **Exceptions:** `IOException` → `Network`, `SocketTimeoutException` → `Timeout` (via `expect/actual` for jvm and android), everything else → `Unknown(className)`. Messages are dropped because they can contain card data.
- **What is replayed:** Success, Refunded and non-retryable failures are cached per key. Pending, cancelled, rejected and transient failures are not, so a "Try again" with the same key re-executes (the gateway dedupes by key). Concurrent duplicates always share the in-flight result.
- **Owner cancellation:** if the coroutine running the charge is cancelled, a waiting duplicate takes over with the same key; the session stays `Processing` until the outcome is known.
- **Key reuse with a different request** is rejected (fingerprint check), following Stripe's idempotency semantics.
- **Minimal `PaymentGateway` (charge only) in feature 2** so the session could be built and tested end to end; status and refund come in feature 4.

## Feature 3: typed errors
- **Sealed class with 14 cases**, each with a stable `code` and `retryable` flag; payload-free cases are `data object`s so they compare by identity and print nicely.
- **Two layers of messages:** core ships English `toUserMessage()` plus a `CheckoutMessages` hook for hosts; the Android UI maps errors to string resources for localisation. Messages never interpolate gateway data.
- **`RecoveryAction`** (retry / change method / edit details / contact support) lets the sheet choose the right button without string matching.
- **Code table** covers common NPCI UPI codes (Z9, ZM, Z8, ZA, ZH, U69, …), ISO 8583 card codes (05, 51, 54, 14, 55, 61, 91, 96) and HTTP statuses. Unknown codes are *permanent* gateway errors (fail closed, no retry storm).
- **`GatewayException(error)`** lets gateway adapters throw typed errors from deep inside networking code.

## Feature 4: gateway, FakeGateway, polling, refunds
- **`PaymentGateway` has three suspend calls** (charge, status, refund) and a written contract: idempotent per key, typed errors, truthful amounts, cancellable. `status` and `refund` are abstract (no defaults) because a gateway without them can't resolve pending payments or refund safely.
- **Pending payments are polled** under `PendingPolicy` (2 s → 10 s, x1.5, 20 polls). When polling is exhausted the session stays `Pending` and `checkStatus()` resolves it later. A failed *status query* never fails the payment.
- **Reconcile before failing** (added after the load test): unknown outcomes are looked up by key; if the gateway can't be reached the result is the new `CheckoutResult.Unresolved` rather than `Failed`.
- **Every gateway answer is verified** against the request (amount, transaction id, id format) → `ResponseMismatch`.
- **`cancel()` only from `Created`** (atomic `applyIf`); payer cancellation reaches `Cancelled` only through the gateway's status answer.
- **Full refunds only**, single-flight per session, refund key = `<key>-rf`. A failed refund leaves the session `Success`.
- **FakeGateway lives in `commonMain`** (shipped in the SDK) so hosts can use it in their own tests and demos, the way Stripe ships test cards. It simulates latency split into request and response legs (spikes on the response leg, i.e. "captured but the answer is late"), server-side idempotency, pending resolution, refunds, NPCI-style failures and magic test inputs.

## Compromises
_None yet._
