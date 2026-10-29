package com.darshan.checkoutkit.core.testing

import com.darshan.checkoutkit.core.gateway.ChargeRequest
import com.darshan.checkoutkit.core.gateway.GatewayResult
import com.darshan.checkoutkit.core.gateway.PaymentGateway
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * Test gateway that answers charge calls from a script (one step per call, then [fallback]).
 * Thread-safe; records every request it receives.
 */
class ScriptedGateway(
    vararg steps: suspend (ChargeRequest) -> GatewayResult,
) : PaymentGateway {
    private val mutex = Mutex()
    private val script = ArrayDeque(steps.toList())
    private val _requests = mutableListOf<ChargeRequest>()

    var fallback: suspend (ChargeRequest) -> GatewayResult = { succeeded(it) }

    val requests: List<ChargeRequest> get() = _requests.toList()
    val chargeCount: Int get() = _requests.size

    override suspend fun charge(request: ChargeRequest): GatewayResult {
        val step = mutex.withLock {
            _requests += request
            script.removeFirstOrNull()
        } ?: fallback
        return step(request)
    }

    companion object {
        fun succeeded(request: ChargeRequest): GatewayResult =
            GatewayResult.Succeeded(transactionId = "txn_${request.idempotencyKey.value.take(8)}", amountPaise = request.amountPaise)
    }
}
