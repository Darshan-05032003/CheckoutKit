package com.darshan.checkoutkit.core

import com.darshan.checkoutkit.core.error.CheckoutError
import com.darshan.checkoutkit.core.gateway.PaymentGateway
import com.darshan.checkoutkit.core.gateway.RefundRequest
import com.darshan.checkoutkit.core.gateway.RefundResult
import com.darshan.checkoutkit.core.retry.AttemptResult
import com.darshan.checkoutkit.core.retry.RetryExecutor
import com.darshan.checkoutkit.core.retry.RetryOutcome
import com.darshan.checkoutkit.core.state.PaymentState

/** Runs one refund under the retry policy and verifies the gateway's answer. */
internal suspend fun executeRefund(
    gateway: PaymentGateway,
    executor: RetryExecutor,
    request: RefundRequest,
): CheckoutResult {
    val outcome = executor.execute {
        when (val r = gateway.refund(request)) {
            is RefundResult.Refunded -> AttemptResult.Success(r)
            is RefundResult.Failed -> AttemptResult.Failure(r.error)
        }
    }
    return when (outcome) {
        is RetryOutcome.Failure -> CheckoutResult.Failed(outcome.error)
        is RetryOutcome.Success -> {
            val refunded = outcome.value
            val idOk = refunded.refundId.isNotBlank() && refunded.refundId.length <= PaymentState.MAX_ID_LENGTH
            if (!idOk || refunded.amountPaise != request.amountPaise) {
                CheckoutResult.Failed(CheckoutError.ResponseMismatch)
            } else {
                CheckoutResult.Refunded(request.transactionId, refunded.refundId)
            }
        }
    }
}
