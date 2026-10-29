package com.darshan.checkoutkit.core.idempotency

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class IdempotencyKeyTest {

    @Test
    fun generatedKeys_areUniqueAndWellFormed() {
        val keys = (1..10_000).map { IdempotencyKey.generate() }
        assertEquals(10_000, keys.toSet().size)
        keys.forEach { assertTrue(IdempotencyKey.isValid(it.value), it.value) }
    }

    @Test
    fun persistedKey_canBeRestored() {
        val original = IdempotencyKey.generate()
        assertEquals(original, IdempotencyKey(original.value))
    }

    @Test
    fun malformedKeys_areRejected() {
        val bad = listOf(
            "",
            "short",
            "a".repeat(65),
            "has space-in-it",
            "key;DROP TABLE payments",
            "key\nnewline-123",
            "ключ-оплаты-1234", // Cyrillic look-alikes
            "‮key-rtl-override",
            "../../etc/passwd",
        )
        bad.forEach { value ->
            assertFailsWith<IllegalArgumentException>(value) { IdempotencyKey(value) }
        }
    }

    @Test
    fun allowedAlphabet_isAccepted() {
        IdempotencyKey("order_42-attempt-1")
        IdempotencyKey("A1b2C3d4")
        IdempotencyKey("x".repeat(64))
    }
}
