package com.darshan.checkoutkit.core.retry

import com.darshan.checkoutkit.core.error.CheckoutError
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.currentTime
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs
import kotlin.test.assertTrue
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds

class RetryExecutorTest {

    private val noJitter = RetryPolicy(
        maxAttempts = 4,
        initialDelay = 100.milliseconds,
        maxDelay = 1.seconds,
        multiplier = 2.0,
        jitterFactor = 0.0,
        attemptTimeout = 5.seconds,
        totalTimeout = 60.seconds,
    )

    private fun executor(policy: RetryPolicy = noJitter) = RetryExecutor(policy, Random(1))

    @Test
    fun successOnFirstAttempt_doesNotDelay() = runTest {
        val outcome = executor().execute { AttemptResult.Success("ok") }
        assertEquals(RetryOutcome.Success("ok", attempts = 1), outcome)
        assertEquals(0, currentTime)
    }

    @Test
    fun retryableFailures_areRetriedWithExponentialBackoff() = runTest {
        val startedAt = mutableListOf<Long>()
        val retries = mutableListOf<Pair<Int, Duration>>()
        val outcome = executor().execute(onRetry = { next, wait, _ -> retries += next to wait }) { attempt ->
            startedAt += currentTime
            if (attempt < 3) AttemptResult.Failure(CheckoutError.Network) else AttemptResult.Success(attempt)
        }
        assertEquals(RetryOutcome.Success(3, attempts = 3), outcome)
        assertEquals(listOf(0L, 100L, 300L), startedAt)
        assertEquals(listOf(2 to 100.milliseconds, 3 to 200.milliseconds), retries)
    }

    @Test
    fun nonRetryableFailure_stopsImmediately() = runTest {
        var calls = 0
        val outcome = executor().execute<Unit> { calls++; AttemptResult.Failure(CheckoutError.Declined) }
        assertEquals(RetryOutcome.Failure(CheckoutError.Declined, attempts = 1), outcome)
        assertEquals(1, calls)
    }

    @Test
    fun exhaustingAttempts_returnsTheLastError() = runTest {
        var calls = 0
        val outcome = executor().execute<Unit> {
            calls++
            AttemptResult.Failure(if (calls < 4) CheckoutError.Network else CheckoutError.Timeout)
        }
        assertEquals(RetryOutcome.Failure(CheckoutError.Timeout, attempts = 4), outcome)
        assertEquals(4, calls)
        assertEquals(100L + 200 + 400, currentTime)
    }

    @Test
    fun slowAttempt_timesOutAndIsRetried() = runTest {
        val policy = noJitter.copy(attemptTimeout = 2.seconds)
        var calls = 0
        val outcome = executor(policy).execute { attempt ->
            calls++
            if (attempt == 1) delay(10.seconds)
            AttemptResult.Success(attempt)
        }
        assertEquals(RetryOutcome.Success(2, attempts = 2), outcome)
        assertEquals(2_000L + 100, currentTime)
    }

    @Test
    fun everyAttemptHanging_endsWithTimeoutError() = runTest {
        val policy = noJitter.copy(maxAttempts = 3, attemptTimeout = 1.seconds)
        val outcome = executor(policy).execute<Unit> { awaitCancellation() }
        assertEquals(RetryOutcome.Failure(CheckoutError.Timeout, attempts = 3), outcome)
        assertEquals(3_000L + 100 + 200, currentTime)
    }

    @Test
    fun totalTimeout_capsTheWholeRun() = runTest {
        val policy = noJitter.copy(maxAttempts = 10, attemptTimeout = 4.seconds, totalTimeout = 5.seconds)
        var calls = 0
        val outcome = executor(policy).execute<Unit> { calls++; awaitCancellation() }
        val failure = assertIs<RetryOutcome.Failure>(outcome)
        assertEquals(CheckoutError.Timeout, failure.error)
        assertEquals(2, calls)
        assertEquals(5_000L, currentTime)
    }

    @Test
    fun cancellationDuringBackoff_stopsFurtherAttempts() = runTest {
        var calls = 0
        val job = launch {
            executor().execute<Unit> { calls++; AttemptResult.Failure(CheckoutError.Network) }
        }
        runCurrent()
        assertEquals(1, calls)
        advanceTimeBy(50) // inside the first 100 ms backoff
        job.cancel()
        advanceTimeBy(10_000)
        assertEquals(1, calls)
        assertTrue(job.isCancelled)
    }

    @Test
    fun cancellationDuringAnAttempt_propagatesAndIsNotReportedAsTimeout() = runTest {
        var finished = false
        val job = launch {
            try {
                executor().execute<Unit> { awaitCancellation() }
            } finally {
                finished = true
            }
        }
        runCurrent()
        job.cancel()
        runCurrent()
        assertTrue(finished)
        assertTrue(job.isCancelled)
    }

    @Test
    fun cancellationExceptionThrownByTheBlock_isRethrown() = runTest {
        assertFailsWith<CancellationException> {
            executor().execute<Unit> { throw CancellationException("caller gave up") }
        }
    }

    @Test
    fun gatewaysOwnWithTimeout_isATimeoutError_notACancellationOfTheCaller() = runTest {
        var calls = 0
        val outcome = executor(noJitter.copy(maxAttempts = 2)).execute<Unit> {
            calls++
            kotlinx.coroutines.withTimeout(1.seconds) { delay(5.seconds) }
            AttemptResult.Success(Unit)
        }
        assertEquals(RetryOutcome.Failure(CheckoutError.Timeout, attempts = 2), outcome)
        assertEquals(2, calls)
    }

    @Test
    fun unexpectedException_becomesUnknownAndIsNotRetried() = runTest {
        var calls = 0
        val outcome = executor().execute<Unit> { calls++; error("boom") }
        val failure = assertIs<RetryOutcome.Failure>(outcome)
        assertIs<CheckoutError.Unknown>(failure.error)
        assertEquals(1, calls)
    }

    @Test
    fun noRetryPolicy_runsExactlyOnce() = runTest {
        var calls = 0
        executor(RetryPolicy.NoRetry).execute<Unit> { calls++; AttemptResult.Failure(CheckoutError.Network) }
        assertEquals(1, calls)
    }
}
