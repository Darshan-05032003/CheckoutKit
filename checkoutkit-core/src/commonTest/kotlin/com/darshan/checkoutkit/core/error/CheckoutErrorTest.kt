package com.darshan.checkoutkit.core.error

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class CheckoutErrorTest {

    private val all: List<CheckoutError> = listOf(
        CheckoutError.Network,
        CheckoutError.Timeout,
        CheckoutError.Declined,
        CheckoutError.InsufficientFunds,
        CheckoutError.InvalidVpa,
        CheckoutError.InvalidCard,
        CheckoutError.CardExpired,
        CheckoutError.LimitExceeded,
        CheckoutError.AuthenticationFailed,
        CheckoutError.RateLimited(retryAfterMillis = 1_000),
        CheckoutError.GatewayError(gatewayCode = "U30"),
        CheckoutError.InvalidRequest("bad"),
        CheckoutError.ResponseMismatch,
        CheckoutError.Unknown("x"),
    )

    @Test
    fun everyErrorHasAUniqueStableCode() {
        assertEquals(
            listOf(
                "NETWORK", "TIMEOUT", "DECLINED", "INSUFFICIENT_FUNDS", "INVALID_VPA", "INVALID_CARD",
                "CARD_EXPIRED", "LIMIT_EXCEEDED", "AUTHENTICATION_FAILED", "RATE_LIMITED", "GATEWAY_ERROR",
                "INVALID_REQUEST", "RESPONSE_MISMATCH", "UNKNOWN",
            ),
            all.map { it.code },
        )
        assertEquals(all.size, all.map { it.code }.toSet().size)
        assertEquals(all.map { it.code }.toSet(), CheckoutError.ALL_CODES)
    }

    @Test
    fun retryability_matchesWhetherTryingAgainCanHelp() {
        val retryable = all.filter { it.retryable }.map { it.code }
        assertEquals(listOf("NETWORK", "TIMEOUT", "RATE_LIMITED", "GATEWAY_ERROR"), retryable)
    }

    @Test
    fun gatewayError_canBePermanent() {
        assertTrue(CheckoutError.GatewayError("91").retryable)
        assertFalse(CheckoutError.GatewayError("401", isTransient = false).retryable)
    }

    @Test
    fun rateLimited_rejectsNegativeRetryAfter() {
        assertFailsWith<IllegalArgumentException> { CheckoutError.RateLimited(retryAfterMillis = -1) }
        assertEquals(null, CheckoutError.RateLimited().retryAfterMillis)
    }

    @Test
    fun gatewayException_carriesATypedError() {
        val e = GatewayException(CheckoutError.InsufficientFunds)
        assertEquals(CheckoutError.InsufficientFunds, e.error)
        assertTrue(e.message!!.contains("INSUFFICIENT_FUNDS"))
    }
}
