package com.darshan.checkoutkit.core.idempotency

import kotlin.jvm.JvmInline
import kotlin.uuid.ExperimentalUuidApi
import kotlin.uuid.Uuid

/**
 * Identifies one checkout attempt. It is sent with every charge request (including retries), so the
 * gateway and the SDK can recognise duplicates and return the original result instead of charging
 * twice.
 *
 * Keys are 8 to 64 characters from `[A-Za-z0-9_-]`. Persist [value] if you need to resume a payment
 * after process death, and restore it with the constructor.
 */
@JvmInline
public value class IdempotencyKey(public val value: String) {
    init {
        require(isValid(value)) { "Idempotency key must match ${PATTERN.pattern}" }
    }

    override fun toString(): String = value

    public companion object {
        private val PATTERN = Regex("^[A-Za-z0-9_-]{8,64}$")

        /** `true` if [value] is a well-formed key. */
        public fun isValid(value: String): Boolean = PATTERN.matches(value)

        /** A new random key (UUID v4 from a cryptographically secure source, prefixed `ck_`). */
        @OptIn(ExperimentalUuidApi::class)
        public fun generate(): IdempotencyKey = IdempotencyKey("ck_" + Uuid.random().toHexString())
    }
}
