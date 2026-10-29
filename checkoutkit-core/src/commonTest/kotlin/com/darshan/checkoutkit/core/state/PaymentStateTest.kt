package com.darshan.checkoutkit.core.state

import kotlin.test.Test
import kotlin.test.assertFailsWith

class PaymentStateTest {
    @Test
    fun processingAttempt_mustBePositive() {
        assertFailsWith<IllegalArgumentException> { PaymentState.Processing(attempt = 0) }
        assertFailsWith<IllegalArgumentException> { PaymentEvent.Retry(attempt = -1) }
    }

    @Test
    fun transactionIds_mustNotBeBlank() {
        assertFailsWith<IllegalArgumentException> { PaymentState.Pending(" ") }
        assertFailsWith<IllegalArgumentException> { PaymentState.Success("") }
        assertFailsWith<IllegalArgumentException> { PaymentState.Refunded("txn", "") }
        assertFailsWith<IllegalArgumentException> { PaymentEvent.Succeed("") }
        assertFailsWith<IllegalArgumentException> { PaymentEvent.AwaitConfirmation("") }
        assertFailsWith<IllegalArgumentException> { PaymentEvent.Refund(" ") }
    }

    @Test
    fun transactionIds_haveABoundedLength() {
        val huge = "t".repeat(PaymentState.MAX_ID_LENGTH + 1)
        assertFailsWith<IllegalArgumentException> { PaymentState.Success(huge) }
        assertFailsWith<IllegalArgumentException> { PaymentEvent.Succeed(huge) }
    }
}
