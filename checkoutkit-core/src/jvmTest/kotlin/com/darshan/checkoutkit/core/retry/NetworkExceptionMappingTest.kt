package com.darshan.checkoutkit.core.retry

import com.darshan.checkoutkit.core.error.CheckoutError
import kotlinx.coroutines.test.runTest
import java.io.IOException
import java.net.SocketTimeoutException
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.time.Duration

class NetworkExceptionMappingTest {
    private val policy = RetryPolicy(maxAttempts = 3, initialDelay = Duration.ZERO, maxDelay = Duration.ZERO)

    @Test
    fun ioExceptions_areNetworkErrorsAndRetried() = runTest {
        var calls = 0
        val outcome = RetryExecutor(policy, Random(0)).execute<Unit> { calls++; throw IOException("connection reset") }
        assertEquals(RetryOutcome.Failure(CheckoutError.Network, attempts = 3), outcome)
        assertEquals(3, calls)
    }

    @Test
    fun socketTimeouts_areTimeoutErrors() = runTest {
        val outcome = RetryExecutor(policy.copy(maxAttempts = 1), Random(0)).execute<Unit> { throw SocketTimeoutException() }
        assertEquals(RetryOutcome.Failure(CheckoutError.Timeout, attempts = 1), outcome)
    }

    @Test
    fun unknownExceptions_keepOnlyTheClassName_neverTheMessage() = runTest {
        val outcome = RetryExecutor(policy, Random(0)).execute<Unit> { throw IllegalStateException("card 4242424242424242 cvv 123") }
        assertEquals(RetryOutcome.Failure(CheckoutError.Unknown("IllegalStateException"), attempts = 1), outcome)
    }
}
