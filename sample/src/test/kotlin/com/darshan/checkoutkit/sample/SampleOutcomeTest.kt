package com.darshan.checkoutkit.sample

import com.darshan.checkoutkit.core.gateway.FakeOutcome
import com.google.common.truth.Truth.assertThat
import org.junit.Test
import kotlin.time.Duration.Companion.milliseconds

class SampleOutcomeTest {

    @Test
    fun everyOutcomeFromTheSpecIsOffered() {
        assertThat(SampleOutcome.entries.map { it.name }).containsAtLeast(
            "SUCCESS", "PENDING_THEN_SUCCESS", "DECLINED", "INSUFFICIENT_FUNDS", "INVALID_VPA",
            "TIMEOUT", "GATEWAY_ERROR", "CANCEL", "REFUND",
        )
    }

    @Test
    fun outcomesMapToFakeGatewayBehaviour() {
        val latency = 10.milliseconds
        assertThat(SampleOutcome.SUCCESS.gatewayConfig(latency).outcome).isEqualTo(FakeOutcome.SUCCESS)
        assertThat(SampleOutcome.PENDING_THEN_SUCCESS.gatewayConfig(latency).outcome).isEqualTo(FakeOutcome.PENDING_THEN_SUCCESS)
        assertThat(SampleOutcome.DECLINED.gatewayConfig(latency).outcome).isEqualTo(FakeOutcome.DECLINED)
        assertThat(SampleOutcome.INSUFFICIENT_FUNDS.gatewayConfig(latency).outcome).isEqualTo(FakeOutcome.INSUFFICIENT_FUNDS)
        assertThat(SampleOutcome.INVALID_VPA.gatewayConfig(latency).outcome).isEqualTo(FakeOutcome.INVALID_VPA)
        assertThat(SampleOutcome.TIMEOUT.gatewayConfig(latency).outcome).isEqualTo(FakeOutcome.TIMEOUT)
        assertThat(SampleOutcome.GATEWAY_ERROR.gatewayConfig(latency).outcome).isEqualTo(FakeOutcome.GATEWAY_ERROR)
        assertThat(SampleOutcome.CANCEL.gatewayConfig(latency).outcome).isEqualTo(FakeOutcome.PAYER_CANCELLED)
        assertThat(SampleOutcome.REFUND.gatewayConfig(latency).outcome).isEqualTo(FakeOutcome.SUCCESS)
        assertThat(SampleOutcome.NETWORK_RETRY.gatewayConfig(latency).transientFailures).isEqualTo(1)
        SampleOutcome.entries.forEach { assertThat(it.gatewayConfig(latency).latency).isEqualTo(latency) }
    }
}
