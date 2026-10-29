package com.darshan.checkoutkit.core.integration

import com.darshan.checkoutkit.core.CheckoutConfig
import com.darshan.checkoutkit.core.CheckoutKit
import com.darshan.checkoutkit.core.CheckoutRequest
import com.darshan.checkoutkit.core.CheckoutResult
import com.darshan.checkoutkit.core.PaymentMethod
import com.darshan.checkoutkit.core.error.CheckoutError
import com.darshan.checkoutkit.core.error.GatewayErrorCodes
import com.darshan.checkoutkit.core.error.GatewayException
import com.darshan.checkoutkit.core.error.RecoveryAction
import com.darshan.checkoutkit.core.error.toUserMessage
import com.darshan.checkoutkit.core.gateway.GatewayResult
import com.darshan.checkoutkit.core.retry.RetryPolicy
import com.darshan.checkoutkit.core.state.PaymentState
import com.darshan.checkoutkit.core.testing.ScriptedGateway
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runTest
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.time.Duration.Companion.milliseconds

/** Raw gateway codes → typed errors → retry decisions → session state → user message. */
class ErrorMappingIntegrationTest {

    private fun TestScope.kit(gateway: ScriptedGateway) = CheckoutKit(
        gateway,
        CheckoutConfig(
            retryPolicy = RetryPolicy(maxAttempts = 3, initialDelay = 100.milliseconds, jitterFactor = 0.0),
            dispatcher = StandardTestDispatcher(testScheduler),
            random = Random(1),
        ),
    )

    private val upi = PaymentMethod.Upi("dev@okbank")
    private val request = CheckoutRequest("order-err", 25_000)

    @Test
    fun npciInsufficientFunds_failsOnce_andTellsThePayerToChangeMethod() = runTest {
        val gateway = ScriptedGateway({ throw GatewayException(GatewayErrorCodes.fromCode("Z9")) })
        val session = kit(gateway).createSession(request)
        val result = assertIs<CheckoutResult.Failed>(session.submit(upi))
        assertEquals(CheckoutError.InsufficientFunds, result.error)
        assertEquals(PaymentState.Failed(CheckoutError.InsufficientFunds), session.state.value)
        assertEquals(1, gateway.chargeCount)
        assertEquals(RecoveryAction.CHANGE_METHOD, result.error.toUserMessage().action)
    }

    @Test
    fun http503Twice_isRetriedThenSucceeds() = runTest {
        val gateway = ScriptedGateway(
            { GatewayResult.Failed(GatewayErrorCodes.fromCode(null, httpStatus = 503)) },
            { GatewayResult.Failed(GatewayErrorCodes.fromCode(null, httpStatus = 503)) },
        )
        assertIs<CheckoutResult.Success>(kit(gateway).createSession(request).submit(upi))
        assertEquals(3, gateway.chargeCount)
    }

    @Test
    fun http429WithRetryAfter_isHonoured() = runTest {
        val gateway = ScriptedGateway({ GatewayResult.Failed(GatewayErrorCodes.fromCode(null, 429, retryAfterMillis = 3_000)) })
        assertIs<CheckoutResult.Success>(kit(gateway).createSession(request).submit(upi))
        assertEquals(3_000L, testScheduler.currentTime)
    }

    @Test
    fun wrongUpiPin_isNotRetried_soThePinIsNeverResentAutomatically() = runTest {
        val gateway = ScriptedGateway({ GatewayResult.Failed(GatewayErrorCodes.fromCode("ZM")) })
        val result = assertIs<CheckoutResult.Failed>(kit(gateway).createSession(request).submit(upi))
        assertEquals(CheckoutError.AuthenticationFailed, result.error)
        assertEquals(1, gateway.chargeCount)
        assertEquals(RecoveryAction.EDIT_DETAILS, result.error.toUserMessage().action)
    }

    @Test
    fun unknownGatewayCode_failsPermanently_withoutLeakingItToThePayer() = runTest {
        val gateway = ScriptedGateway({ GatewayResult.Failed(GatewayErrorCodes.fromCode("QX42")) })
        val result = assertIs<CheckoutResult.Failed>(kit(gateway).createSession(request).submit(upi))
        assertEquals(CheckoutError.GatewayError("QX42", isTransient = false), result.error)
        assertEquals(1, gateway.chargeCount)
        assertEquals(false, result.error.toUserMessage().message.contains("QX42"))
    }
}
