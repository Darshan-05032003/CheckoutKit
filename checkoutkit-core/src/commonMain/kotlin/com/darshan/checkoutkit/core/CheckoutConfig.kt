package com.darshan.checkoutkit.core

import com.darshan.checkoutkit.core.retry.RetryPolicy
import com.darshan.checkoutkit.core.time.CheckoutClock
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlin.random.Random
import kotlin.time.Duration
import kotlin.time.Duration.Companion.hours

/**
 * SDK configuration. Every dependency on time, threads and randomness is injectable so that hosts
 * can test their checkout deterministically.
 *
 * @property retryPolicy retry, backoff and timeout rules for charge and refund calls.
 * @property pendingPolicy how pending payments are polled.
 * @property dispatcher where payment work runs.
 * @property clock wall-clock source.
 * @property random randomness for backoff jitter.
 * @property idempotencyTtl how long a finished result is replayed for a repeated key.
 * @property maxIdempotencyEntries how many finished results are kept in memory.
 */
public data class CheckoutConfig(
    val retryPolicy: RetryPolicy = RetryPolicy.Default,
    val pendingPolicy: PendingPolicy = PendingPolicy(),
    val dispatcher: CoroutineDispatcher = Dispatchers.Default,
    val clock: CheckoutClock = CheckoutClock.System,
    val random: Random = Random.Default,
    val idempotencyTtl: Duration = 24.hours,
    val maxIdempotencyEntries: Int = 1_000,
)
