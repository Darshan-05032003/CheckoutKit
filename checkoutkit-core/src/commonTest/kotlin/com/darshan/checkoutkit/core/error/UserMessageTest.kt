package com.darshan.checkoutkit.core.error

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class UserMessageTest {

    private val all: List<CheckoutError> = listOf(
        CheckoutError.Network, CheckoutError.Timeout, CheckoutError.Declined, CheckoutError.InsufficientFunds,
        CheckoutError.InvalidVpa, CheckoutError.InvalidCard, CheckoutError.CardExpired, CheckoutError.LimitExceeded,
        CheckoutError.AuthenticationFailed, CheckoutError.RateLimited(5_000), CheckoutError.GatewayError("U30"),
        CheckoutError.InvalidRequest("orderId=../../x"), CheckoutError.ResponseMismatch, CheckoutError.Unknown("NullPointerException"),
    )

    @Test
    fun everyErrorMapsToANonBlankMessage() {
        all.forEach { error ->
            val m = error.toUserMessage()
            assertTrue(m.title.isNotBlank(), error.code)
            assertTrue(m.message.isNotBlank(), error.code)
            assertTrue(m.message.length <= 160, "${error.code} message too long for a sheet")
        }
    }

    @Test
    fun recoveryActions_matchTheError() {
        val actions = all.associate { it.code to it.toUserMessage().action }
        assertEquals(RecoveryAction.RETRY, actions["NETWORK"])
        assertEquals(RecoveryAction.RETRY, actions["TIMEOUT"])
        assertEquals(RecoveryAction.RETRY, actions["RATE_LIMITED"])
        assertEquals(RecoveryAction.RETRY, actions["GATEWAY_ERROR"])
        assertEquals(RecoveryAction.CHANGE_METHOD, actions["DECLINED"])
        assertEquals(RecoveryAction.CHANGE_METHOD, actions["INSUFFICIENT_FUNDS"])
        assertEquals(RecoveryAction.CHANGE_METHOD, actions["LIMIT_EXCEEDED"])
        assertEquals(RecoveryAction.EDIT_DETAILS, actions["INVALID_VPA"])
        assertEquals(RecoveryAction.EDIT_DETAILS, actions["INVALID_CARD"])
        assertEquals(RecoveryAction.EDIT_DETAILS, actions["CARD_EXPIRED"])
        assertEquals(RecoveryAction.EDIT_DETAILS, actions["AUTHENTICATION_FAILED"])
        assertEquals(RecoveryAction.CONTACT_SUPPORT, actions["RESPONSE_MISMATCH"])
        assertEquals(RecoveryAction.CONTACT_SUPPORT, actions["INVALID_REQUEST"])
        assertEquals(RecoveryAction.CONTACT_SUPPORT, actions["UNKNOWN"])
    }

    @Test
    fun messagesNeverEchoInternalDetailsOrGatewayCodes() {
        val leaky = listOf(
            CheckoutError.Unknown("NullPointerException at com.acme.Secret"),
            CheckoutError.InvalidRequest("orderId=../../x token=abc"),
            CheckoutError.GatewayError("U30"),
        )
        leaky.forEach { e ->
            val m = e.toUserMessage()
            listOf("NullPointerException", "com.acme", "token", "../", "U30").forEach { secret ->
                assertFalse(m.message.contains(secret) || m.title.contains(secret), "${e.code} leaks '$secret'")
            }
        }
    }

    @Test
    fun hostsCanOverrideMessages_forLocalisation() {
        val hindi = CheckoutMessages { error ->
            if (error == CheckoutError.InsufficientFunds) UserMessage("भुगतान विफल", "खाते में पर्याप्त राशि नहीं है", RecoveryAction.CHANGE_METHOD)
            else CheckoutMessages.Default.messageFor(error)
        }
        assertEquals("भुगतान विफल", hindi.messageFor(CheckoutError.InsufficientFunds).title)
        assertEquals(CheckoutError.Network.toUserMessage(), hindi.messageFor(CheckoutError.Network))
    }
}
