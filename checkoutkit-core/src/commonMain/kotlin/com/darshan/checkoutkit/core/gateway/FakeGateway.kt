package com.darshan.checkoutkit.core.gateway

import com.darshan.checkoutkit.core.PaymentMethod
import com.darshan.checkoutkit.core.error.CheckoutError
import com.darshan.checkoutkit.core.idempotency.IdempotencyKey
import com.darshan.checkoutkit.core.time.CheckoutClock
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlin.random.Random
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds

/** The scripted behaviour of a [FakeGateway]. */
public enum class FakeOutcome {
    /** Captured immediately. */
    SUCCESS,

    /** Pending, then captured after [FakeGatewayConfig.pendingPolls] status polls (a UPI collect the payer approves). */
    PENDING_THEN_SUCCESS,

    /** Pending, then declined after the polls. */
    PENDING_THEN_FAILURE,

    /** Pending, then cancelled by the payer in their UPI app. */
    PAYER_CANCELLED,

    /** Declined by the bank. */
    DECLINED,

    /** Not enough balance. */
    INSUFFICIENT_FUNDS,

    /** UPI id does not exist. */
    INVALID_VPA,

    /** HTTP 503 on every call (transient, so the SDK retries). */
    GATEWAY_ERROR,

    /** Never answers; the SDK's attempt timeout fires. */
    TIMEOUT,

    /** HTTP 429 with `Retry-After: 1 s` on the first call per key, then success. */
    RATE_LIMITED_ONCE,

    /** Captures but reports 1 paisa more than requested (a tampered response) to exercise verification. */
    AMOUNT_MISMATCH,
}

/**
 * Configuration of a [FakeGateway].
 *
 * @property outcome what every charge does (unless a magic input overrides it).
 * @property latency base network latency of every call.
 * @property latencyJitter extra random latency in `[0, latencyJitter]`.
 * @property latencySpikeChance probability (0..1) that a call takes [latencySpike] instead.
 * @property pendingPolls status polls answered with "pending" before a pending payment resolves.
 * @property transientFailures the first N charge calls per idempotency key fail with a network error.
 * @property magicInputs whether sandbox test inputs (see [FakeGateway]) override [outcome].
 */
public data class FakeGatewayConfig(
    val outcome: FakeOutcome = FakeOutcome.SUCCESS,
    val latency: Duration = 600.milliseconds,
    val latencyJitter: Duration = Duration.ZERO,
    val latencySpikeChance: Double = 0.0,
    val latencySpike: Duration = 5.seconds,
    val pendingPolls: Int = 2,
    val transientFailures: Int = 0,
    val magicInputs: Boolean = true,
) {
    init {
        require(!latency.isNegative() && latency.isFinite()) { "latency must be finite and >= 0" }
        require(!latencyJitter.isNegative() && latencyJitter.isFinite()) { "latencyJitter must be finite and >= 0" }
        require(!latencySpike.isNegative() && latencySpike.isFinite()) { "latencySpike must be finite and >= 0" }
        require(latencySpikeChance in 0.0..1.0) { "latencySpikeChance must be in 0.0..1.0" }
        require(pendingPolls >= 0) { "pendingPolls must be >= 0" }
        require(transientFailures >= 0) { "transientFailures must be >= 0" }
    }
}

/** Lifecycle of a payment inside a [FakeGateway]. */
public enum class FakePaymentStatus { PENDING, CAPTURED, FAILED, CANCELLED, REFUNDED }

/** A payment as recorded by a [FakeGateway]. */
public data class FakePayment(
    val transactionId: String,
    val idempotencyKey: IdempotencyKey,
    val orderId: String,
    val amountPaise: Long,
    val methodType: String,
    val status: FakePaymentStatus,
    val createdAtMillis: Long,
    val refundId: String? = null,
)

/**
 * An in-memory sandbox PSP. It behaves like a real gateway from the SDK's point of view: network
 * latency (with jitter and spikes), server-side idempotency per key, pending payments resolved by
 * status polls, refunds, and typed failures. Nothing leaves the device.
 *
 * Magic inputs (when [FakeGatewayConfig.magicInputs] is on and the outcome is [FakeOutcome.SUCCESS]):
 * - Cards `4000000000000002` declined, `4000000000009995` insufficient funds, `4000000000000069` expired.
 * - UPI ids `decline@…` declined, `broke@…` insufficient funds, `unknown@…` invalid VPA.
 *
 * Thread-safe. All waiting uses `delay`, so it runs on virtual time in tests.
 */
public class FakeGateway(
    config: FakeGatewayConfig = FakeGatewayConfig(),
    private val clock: CheckoutClock = CheckoutClock.System,
    private val random: Random = Random.Default,
) : PaymentGateway {

    private class Entry(
        var payment: FakePayment? = null,
        var lastFailure: GatewayResult.Failed? = null,
        var chargeCalls: Int = 0,
        var pollsServed: Int = 0,
        var resolution: FakeOutcome = FakeOutcome.SUCCESS,
        var pendingPolls: Int = 0,
    )

    private data class Counters(val charge: Int = 0, val status: Int = 0, val refund: Int = 0)

    private val mutex = Mutex()
    private val entries = HashMap<IdempotencyKey, Entry>()
    private val byTransaction = HashMap<String, IdempotencyKey>()
    private val refunds = HashMap<IdempotencyKey, RefundResult.Refunded>()
    private val _config = MutableStateFlow(config)
    private val counters = MutableStateFlow(Counters())
    private val payments = MutableStateFlow<List<FakePayment>>(emptyList())

    /** Current behaviour; may be changed at any time (affects new charges). */
    public var config: FakeGatewayConfig
        get() = _config.value
        set(value) {
            _config.value = value
        }

    /** Number of [charge] calls received (including retries and duplicates). */
    public val chargeCalls: Int get() = counters.value.charge

    /** Number of [status] calls received. */
    public val statusCalls: Int get() = counters.value.status

    /** Number of [refund] calls received. */
    public val refundCalls: Int get() = counters.value.refund

    /** Payments whose money was captured at some point (captured or later refunded). */
    public val capturedPayments: List<FakePayment>
        get() = payments.value.filter { it.status == FakePaymentStatus.CAPTURED || it.status == FakePaymentStatus.REFUNDED }

    /** Payments that were refunded. */
    public val refundedPayments: List<FakePayment>
        get() = payments.value.filter { it.status == FakePaymentStatus.REFUNDED }

    /** Every payment the gateway knows, in any status. */
    public val allPayments: List<FakePayment> get() = payments.value

    override suspend fun charge(request: ChargeRequest): GatewayResult {
        val cfg = config
        counters.update { it.copy(charge = it.charge + 1) }
        val (requestLeg, responseLeg) = nextLatency(cfg)
        delay(requestLeg)
        val answer: GatewayResult? = mutex.withLock {
            val entry = entries.getOrPut(request.idempotencyKey) { Entry() }
            entry.chargeCalls++
            entry.payment?.let { return@withLock currentResult(it, reportedAmount(entry, it)) }
            entry.lastFailure?.let { return@withLock it }
            if (entry.chargeCalls <= cfg.transientFailures) return@withLock GatewayResult.Failed(CheckoutError.Network)

            val magic = if (cfg.magicInputs && cfg.outcome == FakeOutcome.SUCCESS) magicError(request.method) else null
            if (magic != null) return@withLock recordFailure(entry, magic)

            when (cfg.outcome) {
                FakeOutcome.SUCCESS -> succeeded(record(entry, request, FakePaymentStatus.CAPTURED))
                FakeOutcome.AMOUNT_MISMATCH -> {
                    entry.resolution = FakeOutcome.AMOUNT_MISMATCH
                    val p = record(entry, request, FakePaymentStatus.CAPTURED)
                    GatewayResult.Succeeded(p.transactionId, p.amountPaise + 1)
                }
                FakeOutcome.RATE_LIMITED_ONCE ->
                    if (entry.chargeCalls == 1) GatewayResult.Failed(CheckoutError.RateLimited(retryAfterMillis = 1_000))
                    else succeeded(record(entry, request, FakePaymentStatus.CAPTURED))
                FakeOutcome.PENDING_THEN_SUCCESS, FakeOutcome.PENDING_THEN_FAILURE, FakeOutcome.PAYER_CANCELLED -> {
                    entry.resolution = cfg.outcome
                    entry.pendingPolls = cfg.pendingPolls
                    val p = record(entry, request, FakePaymentStatus.PENDING)
                    GatewayResult.Pending(p.transactionId, p.amountPaise)
                }
                FakeOutcome.DECLINED -> recordFailure(entry, CheckoutError.Declined)
                FakeOutcome.INSUFFICIENT_FUNDS -> recordFailure(entry, CheckoutError.InsufficientFunds)
                FakeOutcome.INVALID_VPA -> recordFailure(entry, CheckoutError.InvalidVpa)
                FakeOutcome.GATEWAY_ERROR -> GatewayResult.Failed(CheckoutError.GatewayError("HTTP_503"))
                FakeOutcome.TIMEOUT -> null
            }
        }
        // The answer travels back after processing: a slow response leg means the money may already
        // be captured when the caller's timeout fires, exactly like a real PSP.
        delay(responseLeg)
        return answer ?: awaitCancellation()
    }

    override suspend fun status(query: StatusQuery): GatewayResult {
        counters.update { it.copy(status = it.status + 1) }
        delay(nextLatency(config).total)
        return mutex.withLock {
            val key = query.transactionId?.let { byTransaction[it] } ?: query.idempotencyKey
            val entry = entries[key]
            val payment = entry?.payment ?: return@withLock entry?.lastFailure
                ?: GatewayResult.Failed(CheckoutError.InvalidRequest("unknown payment"))
            if (payment.status == FakePaymentStatus.PENDING) {
                if (entry.pollsServed < entry.pendingPolls) {
                    entry.pollsServed++
                } else {
                    val resolved = when (entry.resolution) {
                        FakeOutcome.PENDING_THEN_FAILURE -> FakePaymentStatus.FAILED
                        FakeOutcome.PAYER_CANCELLED -> FakePaymentStatus.CANCELLED
                        else -> FakePaymentStatus.CAPTURED
                    }
                    update(entry, payment.copy(status = resolved))
                }
            }
            currentResult(entry.payment!!, reportedAmount(entry, entry.payment!!))
        }
    }

    override suspend fun refund(request: RefundRequest): RefundResult {
        counters.update { it.copy(refund = it.refund + 1) }
        delay(nextLatency(config).total)
        return mutex.withLock {
            refunds[request.refundKey]?.let { return@withLock it }
            val entry = byTransaction[request.transactionId]?.let { entries[it] }
            val payment = entry?.payment
                ?: return@withLock RefundResult.Failed(CheckoutError.InvalidRequest("unknown transaction"))
            if (payment.status != FakePaymentStatus.CAPTURED) {
                return@withLock RefundResult.Failed(CheckoutError.InvalidRequest("payment is ${payment.status}"))
            }
            if (request.amountPaise != payment.amountPaise) {
                return@withLock RefundResult.Failed(CheckoutError.InvalidRequest("refund amount differs from capture"))
            }
            val refundId = "fake_rf_" + randomHex()
            update(entry, payment.copy(status = FakePaymentStatus.REFUNDED, refundId = refundId))
            RefundResult.Refunded(refundId, payment.amountPaise).also { refunds[request.refundKey] = it }
        }
    }

    /** Forgets all payments, refunds and counters. */
    public suspend fun reset() {
        mutex.withLock {
            entries.clear()
            byTransaction.clear()
            refunds.clear()
            payments.value = emptyList()
            counters.value = Counters()
        }
    }

    private fun record(entry: Entry, request: ChargeRequest, status: FakePaymentStatus): FakePayment {
        var txn: String
        do {
            txn = "fake_txn_" + randomHex()
        } while (txn in byTransaction)
        val payment = FakePayment(
            transactionId = txn,
            idempotencyKey = request.idempotencyKey,
            orderId = request.orderId,
            amountPaise = request.amountPaise,
            methodType = request.method.type,
            status = status,
            createdAtMillis = clock.nowMillis(),
        )
        entry.payment = payment
        byTransaction[txn] = request.idempotencyKey
        payments.update { it + payment }
        return payment
    }

    private fun update(entry: Entry, payment: FakePayment) {
        entry.payment = payment
        payments.update { list -> list.map { if (it.transactionId == payment.transactionId) payment else it } }
    }

    private fun recordFailure(entry: Entry, error: CheckoutError): GatewayResult.Failed =
        GatewayResult.Failed(error).also { entry.lastFailure = it }

    private fun reportedAmount(entry: Entry, payment: FakePayment): Long =
        if (entry.resolution == FakeOutcome.AMOUNT_MISMATCH) payment.amountPaise + 1 else payment.amountPaise

    private fun currentResult(payment: FakePayment, amount: Long): GatewayResult = when (payment.status) {
        FakePaymentStatus.PENDING -> GatewayResult.Pending(payment.transactionId, amount)
        FakePaymentStatus.CAPTURED, FakePaymentStatus.REFUNDED -> GatewayResult.Succeeded(payment.transactionId, amount)
        FakePaymentStatus.FAILED -> GatewayResult.Failed(CheckoutError.Declined)
        FakePaymentStatus.CANCELLED -> GatewayResult.Cancelled(payment.transactionId)
    }

    private fun succeeded(payment: FakePayment) = GatewayResult.Succeeded(payment.transactionId, payment.amountPaise)

    private data class Latency(val requestLeg: Duration, val responseLeg: Duration) {
        val total: Duration get() = requestLeg + responseLeg
    }

    /** Half the base latency on the way in, the rest (plus jitter, or a spike) on the way back. */
    private fun nextLatency(cfg: FakeGatewayConfig): Latency {
        val requestLeg = cfg.latency / 2
        if (cfg.latencySpikeChance > 0.0 && random.nextDouble() < cfg.latencySpikeChance) {
            return Latency(requestLeg, (cfg.latencySpike - requestLeg).coerceAtLeast(Duration.ZERO))
        }
        val jitter = if (cfg.latencyJitter == Duration.ZERO) Duration.ZERO else (cfg.latencyJitter.inWholeMilliseconds * random.nextDouble()).milliseconds
        return Latency(requestLeg, cfg.latency - requestLeg + jitter)
    }

    private fun randomHex(): String = buildString { repeat(16) { append(HEX[random.nextInt(16)]) } }

    private companion object {
        const val HEX = "0123456789abcdef"

        fun magicError(method: PaymentMethod): CheckoutError? = when (method) {
            is PaymentMethod.Card -> when (method.number.filter { it.isDigit() }) {
                "4000000000000002" -> CheckoutError.Declined
                "4000000000009995" -> CheckoutError.InsufficientFunds
                "4000000000000069" -> CheckoutError.CardExpired
                else -> null
            }
            is PaymentMethod.Upi -> when (method.vpa.substringBefore('@').lowercase()) {
                "decline" -> CheckoutError.Declined
                "broke" -> CheckoutError.InsufficientFunds
                "unknown" -> CheckoutError.InvalidVpa
                else -> null
            }
            is PaymentMethod.Wallet -> null
        }
    }
}
