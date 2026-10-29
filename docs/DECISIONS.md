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

## Compromises
_None yet._
