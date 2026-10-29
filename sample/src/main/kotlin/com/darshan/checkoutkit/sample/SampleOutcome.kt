package com.darshan.checkoutkit.sample

import androidx.annotation.StringRes
import com.darshan.checkoutkit.core.gateway.FakeGatewayConfig
import com.darshan.checkoutkit.core.gateway.FakeOutcome
import kotlin.time.Duration

/** The outcomes the sample can demonstrate, each a FakeGateway configuration. */
enum class SampleOutcome(@StringRes val title: Int, @StringRes val description: Int) {
    SUCCESS(R.string.outcome_success, R.string.outcome_success_desc),
    PENDING_THEN_SUCCESS(R.string.outcome_pending, R.string.outcome_pending_desc),
    NETWORK_RETRY(R.string.outcome_retry, R.string.outcome_retry_desc),
    DECLINED(R.string.outcome_declined, R.string.outcome_declined_desc),
    INSUFFICIENT_FUNDS(R.string.outcome_insufficient, R.string.outcome_insufficient_desc),
    INVALID_VPA(R.string.outcome_invalid_vpa, R.string.outcome_invalid_vpa_desc),
    TIMEOUT(R.string.outcome_timeout, R.string.outcome_timeout_desc),
    GATEWAY_ERROR(R.string.outcome_gateway_error, R.string.outcome_gateway_error_desc),
    CANCEL(R.string.outcome_cancel, R.string.outcome_cancel_desc),
    REFUND(R.string.outcome_refund, R.string.outcome_refund_desc),
    ;

    /** The FakeGateway behaviour for this outcome, with network [latency]. */
    fun gatewayConfig(latency: Duration): FakeGatewayConfig = when (this) {
        SUCCESS, REFUND -> FakeGatewayConfig(outcome = FakeOutcome.SUCCESS, latency = latency)
        PENDING_THEN_SUCCESS -> FakeGatewayConfig(outcome = FakeOutcome.PENDING_THEN_SUCCESS, latency = latency, pendingPolls = 2)
        NETWORK_RETRY -> FakeGatewayConfig(outcome = FakeOutcome.SUCCESS, latency = latency, transientFailures = 1)
        DECLINED -> FakeGatewayConfig(outcome = FakeOutcome.DECLINED, latency = latency)
        INSUFFICIENT_FUNDS -> FakeGatewayConfig(outcome = FakeOutcome.INSUFFICIENT_FUNDS, latency = latency)
        INVALID_VPA -> FakeGatewayConfig(outcome = FakeOutcome.INVALID_VPA, latency = latency)
        TIMEOUT -> FakeGatewayConfig(outcome = FakeOutcome.TIMEOUT, latency = latency)
        GATEWAY_ERROR -> FakeGatewayConfig(outcome = FakeOutcome.GATEWAY_ERROR, latency = latency)
        CANCEL -> FakeGatewayConfig(outcome = FakeOutcome.PAYER_CANCELLED, latency = latency, pendingPolls = 1)
    }
}
