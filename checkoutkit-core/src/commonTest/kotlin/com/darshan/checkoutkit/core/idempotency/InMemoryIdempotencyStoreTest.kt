package com.darshan.checkoutkit.core.idempotency

import com.darshan.checkoutkit.core.testing.TestClock
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.hours
import kotlin.time.Duration.Companion.minutes

class InMemoryIdempotencyStoreTest {

    private val key = IdempotencyKey("key-0001")
    private val clock = TestClock()
    private val store = InMemoryIdempotencyStore<String>(ttl = 1.hours, maxEntries = 3, clock = clock)

    @Test
    fun firstCall_executes_secondCall_replaysCachedValue() = runTest {
        var executions = 0
        val first = store.getOrExecute(key, "fp") { executions++; "result-1" }
        val second = store.getOrExecute(key, "fp") { executions++; "result-2" }
        assertEquals(IdempotentResult("result-1", replayed = false), first)
        assertEquals(IdempotentResult("result-1", replayed = true), second)
        assertEquals(1, executions)
    }

    @Test
    fun concurrentDuplicates_shareOneExecution() = runTest {
        val gate = CompletableDeferred<Unit>()
        var executions = 0
        val results = (1..100).map {
            async { store.getOrExecute(key, "fp") { executions++; gate.await(); "paid" } }
        }
        runCurrent()
        gate.complete(Unit)
        val values = results.awaitAll()
        assertEquals(1, executions)
        assertTrue(values.all { it.value == "paid" })
        assertEquals(1, values.count { !it.replayed })
        assertEquals(99, values.count { it.replayed })
    }

    @Test
    fun differentKeys_areIndependent() = runTest {
        assertEquals("a", store.getOrExecute(IdempotencyKey("key-aaaa"), "fp") { "a" }.value)
        assertEquals("b", store.getOrExecute(IdempotencyKey("key-bbbb"), "fp") { "b" }.value)
    }

    @Test
    fun uncacheableValue_isSharedWithConcurrentWaitersButNotStored() = runTest {
        val gate = CompletableDeferred<Unit>()
        var executions = 0
        val a = async { store.getOrExecute(key, "fp", shouldCache = { false }) { executions++; gate.await(); "transient" } }
        val b = async { store.getOrExecute(key, "fp", shouldCache = { false }) { executions++; "other" } }
        runCurrent()
        gate.complete(Unit)
        assertEquals("transient", a.await().value)
        assertEquals("transient", b.await().value)
        assertEquals(1, executions)
        // Not stored: the next call executes again.
        assertEquals("fresh", store.getOrExecute(key, "fp") { executions++; "fresh" }.value)
        assertEquals(2, executions)
    }

    @Test
    fun cancelledOwner_handsOverToAWaiter() = runTest {
        var executions = 0
        val owner = launch { store.getOrExecute(key, "fp") { executions++; awaitCancellation() } }
        runCurrent()
        val waiter = async { store.getOrExecute(key, "fp") { executions++; "recovered" } }
        runCurrent()
        owner.cancel()
        assertEquals(IdempotentResult("recovered", replayed = false), waiter.await())
        assertEquals(2, executions)
        assertFalse(store.isInFlight(key))
    }

    @Test
    fun failingOwner_propagatesTheSameErrorToWaiters_andForgetsTheKey() = runTest {
        val gate = CompletableDeferred<Unit>()
        val owner = async { runCatching { store.getOrExecute(key, "fp") { gate.await(); error("gateway exploded") } } }
        runCurrent()
        val waiter = async { runCatching { store.getOrExecute(key, "fp") { "never" } } }
        runCurrent()
        gate.complete(Unit)
        assertEquals("gateway exploded", owner.await().exceptionOrNull()?.message)
        assertEquals("gateway exploded", waiter.await().exceptionOrNull()?.message)
        assertEquals("retried", store.getOrExecute(key, "fp") { "retried" }.value)
    }

    @Test
    fun sameKeyWithDifferentRequest_isAConflict() = runTest {
        store.getOrExecute(key, "amount=100") { "paid" }
        assertFailsWith<IdempotencyConflictException> {
            store.getOrExecute(key, "amount=999999") { "should not run" }
        }
    }

    @Test
    fun sameKeyWithDifferentRequest_whileInFlight_isAConflict() = runTest {
        val gate = CompletableDeferred<Unit>()
        val owner = async { store.getOrExecute(key, "amount=100") { gate.await(); "paid" } }
        runCurrent()
        assertFailsWith<IdempotencyConflictException> { store.getOrExecute(key, "amount=1") { "x" } }
        gate.complete(Unit)
        assertEquals("paid", owner.await().value)
    }

    @Test
    fun entriesExpireAfterTtl() = runTest {
        store.getOrExecute(key, "fp") { "old" }
        clock.advanceBy(59.minutes.inWholeMilliseconds)
        assertEquals("old", store.getOrExecute(key, "fp") { "new" }.value)
        clock.advanceBy(2.minutes.inWholeMilliseconds)
        assertEquals("new", store.getOrExecute(key, "fp") { "new" }.value)
    }

    @Test
    fun oldestEntriesAreEvictedBeyondMaxEntries() = runTest {
        val keys = (1..4).map { IdempotencyKey("key-000$it") }
        keys.forEach { k -> store.getOrExecute(k, "fp") { k.value } }
        assertEquals(3, store.size())
        assertFalse(store.contains(keys[0]))
        assertTrue(store.contains(keys[3]))
    }

    @Test
    fun clear_forgetsEverything() = runTest {
        store.getOrExecute(key, "fp") { "v" }
        store.clear()
        assertEquals(0, store.size())
    }

    @Test
    fun invalidConfiguration_isRejected() {
        assertFailsWith<IllegalArgumentException> { InMemoryIdempotencyStore<String>(maxEntries = 0) }
        assertFailsWith<IllegalArgumentException> { InMemoryIdempotencyStore<String>(ttl = kotlin.time.Duration.ZERO) }
    }
}
