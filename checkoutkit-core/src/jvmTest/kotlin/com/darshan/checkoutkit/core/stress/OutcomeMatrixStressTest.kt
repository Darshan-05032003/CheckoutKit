package com.darshan.checkoutkit.core.stress

import com.darshan.checkoutkit.core.CheckoutConfig
import com.darshan.checkoutkit.core.CheckoutKit
import com.darshan.checkoutkit.core.CheckoutRequest
import com.darshan.checkoutkit.core.CheckoutResult
import com.darshan.checkoutkit.core.PaymentMethod
import com.darshan.checkoutkit.core.PendingPolicy
import com.darshan.checkoutkit.core.gateway.FakeGateway
import com.darshan.checkoutkit.core.gateway.FakeGatewayConfig
import com.darshan.checkoutkit.core.gateway.FakeOutcome
import com.darshan.checkoutkit.core.retry.RetryPolicy
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.runTest
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds
import kotlin.time.measureTime

/** The sample app's outcome matrix (every FakeOutcome) x 100 concurrent checkouts, then refunds. */
class OutcomeMatrixStressTest {

    @Test
    fun everyOutcome100Times_resultsAreDeterministic_andRefundsBalance() = runTest {
        val expected = mapOf(
            FakeOutcome.SUCCESS to "Success", FakeOutcome.PENDING_THEN_SUCCESS to "Success", FakeOutcome.RATE_LIMITED_ONCE to "Success",
            FakeOutcome.PENDING_THEN_FAILURE to "Failed", FakeOutcome.DECLINED to "Failed", FakeOutcome.INSUFFICIENT_FUNDS to "Failed",
            FakeOutcome.INVALID_VPA to "Failed", FakeOutcome.GATEWAY_ERROR to "Failed", FakeOutcome.TIMEOUT to "Failed",
            FakeOutcome.AMOUNT_MISMATCH to "Failed", FakeOutcome.PAYER_CANCELLED to "Cancelled",
        )
        val summary = mutableListOf<String>()
        val wall = measureTime {
            for ((outcome, type) in expected) {
                val gateway = FakeGateway(FakeGatewayConfig(outcome = outcome, latency = 800.milliseconds, pendingPolls = 2), random = Random(outcome.ordinal))
                val kit = CheckoutKit(
                    gateway,
                    CheckoutConfig(
                        retryPolicy = RetryPolicy(maxAttempts = 3, initialDelay = 1.seconds, attemptTimeout = 3.seconds, totalTimeout = 20.seconds),
                        pendingPolicy = PendingPolicy(initialInterval = 1500.milliseconds, maxPolls = 15),
                        dispatcher = StandardTestDispatcher(testScheduler),
                        random = Random(1),
                    ),
                )
                val results = (1..100).map { i -> async { kit.createSession(CheckoutRequest("m-${outcome.name}-$i", 49_900)).submit(PaymentMethod.Upi("u$i@okbank")) } }.awaitAll()
                val types = results.groupingBy { it::class.simpleName }.eachCount()
                assertEquals<Map<String?, Int>>(mapOf(type to 100), types, outcome.name)
                val refunds = results.filterIsInstance<CheckoutResult.Success>().map { async { kit.refund(it.transactionId, it.amountPaise) } }.awaitAll()
                assertEquals(refunds.size, refunds.count { it is CheckoutResult.Refunded })
                assertEquals(refunds.size, gateway.refundedPayments.size)
                summary += "${outcome.name}=$type/${gateway.chargeCalls}calls"
            }
        }
        println("STRESS [F6 outcome matrix 11 outcomes x 100 + refunds] wall=${wall.inWholeMilliseconds} ms virtual=${testScheduler.currentTime} ms :: ${summary.joinToString(", ")}")
    }
}
