package com.darshan.checkoutkit.core.error

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNull

class GatewayErrorCodesTest {

    @Test
    fun upiResponseCodes_mapToTypedErrors() {
        val table = mapOf(
            "Z9" to CheckoutError.InsufficientFunds,
            "ZM" to CheckoutError.AuthenticationFailed,
            "Z6" to CheckoutError.AuthenticationFailed,
            "Z8" to CheckoutError.LimitExceeded,
            "Z7" to CheckoutError.LimitExceeded,
            "ZA" to CheckoutError.Declined,
            "U16" to CheckoutError.Declined,
            "ZH" to CheckoutError.InvalidVpa,
            "XH" to CheckoutError.InvalidVpa,
            "U69" to CheckoutError.Timeout,
            "BT" to CheckoutError.Timeout,
            "U90" to CheckoutError.Timeout,
            "XY" to CheckoutError.GatewayError("XY"),
            "U28" to CheckoutError.GatewayError("U28"),
        )
        table.forEach { (code, expected) -> assertEquals(expected, GatewayErrorCodes.fromCode(code), code) }
    }

    @Test
    fun cardIsoResponseCodes_mapToTypedErrors() {
        val table = mapOf(
            "05" to CheckoutError.Declined,
            "51" to CheckoutError.InsufficientFunds,
            "54" to CheckoutError.CardExpired,
            "14" to CheckoutError.InvalidCard,
            "55" to CheckoutError.AuthenticationFailed,
            "61" to CheckoutError.LimitExceeded,
            "91" to CheckoutError.GatewayError("91"),
            "96" to CheckoutError.GatewayError("96"),
        )
        table.forEach { (code, expected) -> assertEquals(expected, GatewayErrorCodes.fromCode(code), code) }
    }

    @Test
    fun httpStatuses_mapWhenThereIsNoCode() {
        assertEquals(CheckoutError.RateLimited(retryAfterMillis = 2_000), GatewayErrorCodes.fromCode(null, httpStatus = 429, retryAfterMillis = 2_000))
        assertEquals(CheckoutError.GatewayError("HTTP_503"), GatewayErrorCodes.fromCode(null, httpStatus = 503))
        assertEquals(CheckoutError.GatewayError("HTTP_401", isTransient = false), GatewayErrorCodes.fromCode(null, httpStatus = 401))
        assertIs<CheckoutError.InvalidRequest>(GatewayErrorCodes.fromCode(null, httpStatus = 400))
        assertEquals(CheckoutError.Timeout, GatewayErrorCodes.fromCode(null, httpStatus = 504))
        assertEquals(CheckoutError.Timeout, GatewayErrorCodes.fromCode(null, httpStatus = 408))
    }

    @Test
    fun codeWinsOverHttpStatus() {
        assertEquals(CheckoutError.InsufficientFunds, GatewayErrorCodes.fromCode("Z9", httpStatus = 500))
    }

    @Test
    fun codesAreNormalised() {
        assertEquals(CheckoutError.InsufficientFunds, GatewayErrorCodes.fromCode("  z9 "))
        assertEquals(CheckoutError.RateLimited(), GatewayErrorCodes.fromCode("rate_limited"))
    }

    @Test
    fun unknownCodes_becomePermanentGatewayErrors() {
        assertEquals(CheckoutError.GatewayError("QQ7", isTransient = false), GatewayErrorCodes.fromCode("QQ7"))
        assertEquals(CheckoutError.Unknown(null), GatewayErrorCodes.fromCode(null))
        assertEquals(CheckoutError.Unknown(null), GatewayErrorCodes.fromCode("   "))
    }

    @Test
    fun hostileCodes_areSanitisedBeforeTheyReachLogs() {
        val hostile = listOf("<script>alert(1)</script>", "Z9\nINJECTED LOG LINE", "x".repeat(10_000), "‮Z9", "ज़9")
        hostile.forEach { code ->
            val error = GatewayErrorCodes.fromCode(code)
            val gatewayError = assertIs<CheckoutError.GatewayError>(error, code.take(20))
            assertNull(gatewayError.gatewayCode, "unsafe code kept: ${code.take(20)}")
        }
    }
}
