package com.darshan.checkoutkit.core.retry

import com.darshan.checkoutkit.core.error.CheckoutError
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.coroutines.cancellation.CancellationException
import kotlin.random.Random
import kotlin.time.Duration

/** What one attempt produced. */
public sealed interface AttemptResult<out T> {
    /** The attempt produced [value]; no more retries. */
    public data class Success<out T>(val value: T) : AttemptResult<T>

    /** The attempt failed with [error]; retried only if `error.retryable`. */
    public data class Failure(val error: CheckoutError) : AttemptResult<Nothing>
}

/** The final answer of [RetryExecutor.execute] after all attempts. */
public sealed interface RetryOutcome<out T> {
    /** How many attempts were started. */
    public val attempts: Int

    /** One attempt succeeded. */
    public data class Success<out T>(val value: T, override val attempts: Int) : RetryOutcome<T>

    /** Every allowed attempt failed (or a non-retryable error stopped early); [error] is the last one. */
    public data class Failure(val error: CheckoutError, override val attempts: Int) : RetryOutcome<Nothing>
}

/**
 * Runs an operation under a [RetryPolicy].
 *
 * - Each attempt is bounded by [RetryPolicy.attemptTimeout]; a slow attempt becomes [CheckoutError.Timeout].
 * - The whole run is bounded by [RetryPolicy.totalTimeout].
 * - Only `retryable` errors are retried, after an exponential, jittered delay.
 * - Cancellation-safe: cancelling the caller stops the current attempt and any pending delay,
 *   and the [CancellationException] is always rethrown, never turned into an error.
 * - All waiting uses `delay`, so tests run it on virtual time with `runTest`.
 */
public class RetryExecutor(
    private val policy: RetryPolicy,
    private val random: Random = Random.Default,
) {
    /**
     * Executes [block] (receiving the 1-based attempt number) until it succeeds, fails with a
     * non-retryable error, or the policy is exhausted. [onRetry] is called before each delay with
     * the next attempt number, the delay, and the error that caused the retry.
     */
    public suspend fun <T> execute(
        onRetry: suspend (nextAttempt: Int, delay: Duration, cause: CheckoutError) -> Unit = { _, _, _ -> },
        block: suspend (attempt: Int) -> AttemptResult<T>,
    ): RetryOutcome<T> {
        var attempt = 0
        var lastError: CheckoutError = CheckoutError.Timeout
        val outcome = withTimeoutOrNull(policy.totalTimeout) {
            while (true) {
                attempt++
                val result = runAttempt(attempt, block)
                when (result) {
                    is AttemptResult.Success -> return@withTimeoutOrNull RetryOutcome.Success(result.value, attempt)
                    is AttemptResult.Failure -> {
                        lastError = result.error
                        if (!result.error.retryable || attempt >= policy.maxAttempts) {
                            return@withTimeoutOrNull RetryOutcome.Failure(result.error, attempt)
                        }
                        val wait = policy.backoffDelay(retryNumber = attempt, random = random)
                        onRetry(attempt + 1, wait, result.error)
                        delay(wait)
                    }
                }
            }
            @Suppress("UNREACHABLE_CODE")
            error("unreachable")
        }
        return outcome ?: RetryOutcome.Failure(if (lastError.retryable) CheckoutError.Timeout else lastError, attempt)
    }

    private suspend fun <T> runAttempt(attempt: Int, block: suspend (Int) -> AttemptResult<T>): AttemptResult<T> =
        try {
            withTimeoutOrNull(policy.attemptTimeout) { block(attempt) } ?: AttemptResult.Failure(CheckoutError.Timeout)
        } catch (e: TimeoutCancellationException) {
            // A timeout raised inside the gateway's own code. If we (the caller) are still active it
            // is just a slow attempt, not a cancellation of the payment.
            currentCoroutineContext().ensureActive()
            AttemptResult.Failure(CheckoutError.Timeout)
        } catch (e: CancellationException) {
            throw e
        } catch (t: Throwable) {
            AttemptResult.Failure(classifyThrowable(t))
        }
}

/** Maps an exception thrown by a gateway to a typed error (platform-specific: IOException etc.). */
internal expect fun classifyThrowable(t: Throwable): CheckoutError
