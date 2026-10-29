package com.darshan.checkoutkit.core.testing

import com.darshan.checkoutkit.core.error.CheckoutError
import com.darshan.checkoutkit.core.gateway.ChargeRequest
import com.darshan.checkoutkit.core.gateway.GatewayResult
import com.darshan.checkoutkit.core.gateway.PaymentGateway
import com.darshan.checkoutkit.core.gateway.RefundRequest
import com.darshan.checkoutkit.core.gateway.RefundResult
import com.darshan.checkoutkit.core.gateway.StatusQuery
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * Test gateway that answers calls from scripts (one step per call, then a fallback).
 * Thread-safe; records every request it receives.
 */
class ScriptedGateway(
    vararg steps: suspend (ChargeRequest) -> GatewayResult,
) : PaymentGateway {
    private val mutex = Mutex()
    private val script = ArrayDeque(steps.toList())
    private val statusScript = ArrayDeque<suspend (StatusQuery) -> GatewayResult>()
    private val refundScript = ArrayDeque<suspend (RefundRequest) -> RefundResult>()
    private val _requests = mutableListOf<ChargeRequest>()
    private val _statusQueries = mutableListOf<StatusQuery>()
    private val _refunds = mutableListOf<RefundRequest>()

    var fallback: suspend (ChargeRequest) -> GatewayResult = { succeeded(it) }
    /** By default a lookup by key alone finds nothing (the charge never landed); a known txn stays pending. */
    var statusFallback: suspend (StatusQuery) -> GatewayResult = { q ->
        q.transactionId?.let { GatewayResult.Pending(it, 0) } ?: GatewayResult.Failed(CheckoutError.InvalidRequest("unknown payment"))
    }
    var refundFallback: suspend (RefundRequest) -> RefundResult = { RefundResult.Refunded("rf_${it.transactionId}", it.amountPaise) }

    val requests: List<ChargeRequest> get() = _requests.toList()
    val chargeCount: Int get() = _requests.size
    val statusQueries: List<StatusQuery> get() = _statusQueries.toList()
    val refunds: List<RefundRequest> get() = _refunds.toList()

    fun onStatus(vararg steps: suspend (StatusQuery) -> GatewayResult) = apply { statusScript.addAll(steps) }
    fun onRefund(vararg steps: suspend (RefundRequest) -> RefundResult) = apply { refundScript.addAll(steps) }

    override suspend fun charge(request: ChargeRequest): GatewayResult {
        val step = mutex.withLock {
            _requests += request
            script.removeFirstOrNull()
        } ?: fallback
        return step(request)
    }

    override suspend fun status(query: StatusQuery): GatewayResult {
        val step = mutex.withLock {
            _statusQueries += query
            statusScript.removeFirstOrNull()
        } ?: statusFallback
        return step(query)
    }

    override suspend fun refund(request: RefundRequest): RefundResult {
        val step = mutex.withLock {
            _refunds += request
            refundScript.removeFirstOrNull()
        } ?: refundFallback
        return step(request)
    }

    companion object {
        fun succeeded(request: ChargeRequest): GatewayResult =
            GatewayResult.Succeeded(transactionId = "txn_${request.idempotencyKey.value.take(8)}", amountPaise = request.amountPaise)
    }
}
