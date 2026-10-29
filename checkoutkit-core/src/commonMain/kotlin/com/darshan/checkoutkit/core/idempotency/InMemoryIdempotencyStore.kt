package com.darshan.checkoutkit.core.idempotency

import com.darshan.checkoutkit.core.time.CheckoutClock
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlin.coroutines.cancellation.CancellationException
import kotlin.time.Duration
import kotlin.time.Duration.Companion.hours

/**
 * Process-local [IdempotencyStore] with single-flight execution, a time-to-live and a size bound.
 *
 * - Concurrent calls for one key share one execution (a `CompletableDeferred` per key).
 * - If the executing caller is cancelled, one waiting caller takes over and runs the block again
 *   (with the same key, so a well-behaved gateway still deduplicates).
 * - If the block throws, waiting callers get the same exception and the key is forgotten.
 * - Finished values expire after [ttl]; at most [maxEntries] are kept (oldest evicted first).
 */
public class InMemoryIdempotencyStore<V : Any>(
    private val ttl: Duration = 24.hours,
    private val maxEntries: Int = 1_000,
    private val clock: CheckoutClock = CheckoutClock.System,
) : IdempotencyStore<V> {

    init {
        require(ttl.isPositive()) { "ttl must be positive" }
        require(maxEntries >= 1) { "maxEntries must be >= 1" }
    }

    private class Done<V>(val value: V, val fingerprint: String, val storedAtMillis: Long)
    private class Running<V>(val deferred: CompletableDeferred<V>, val fingerprint: String)
    private class OwnerCancelled : Exception()

    private val mutex = Mutex()
    private val completed = LinkedHashMap<IdempotencyKey, Done<V>>()
    private val running = HashMap<IdempotencyKey, Running<V>>()

    override suspend fun getOrExecute(
        key: IdempotencyKey,
        fingerprint: String,
        shouldCache: (V) -> Boolean,
        block: suspend () -> V,
    ): IdempotentResult<V> {
        while (true) {
            var cached: Done<V>? = null
            var joined: Running<V>? = null
            var owned: Running<V>? = null
            mutex.withLock {
                evictExpired()
                cached = completed[key]
                if (cached == null) {
                    joined = running[key]
                    if (joined == null) {
                        owned = Running(CompletableDeferred<V>(), fingerprint).also { running[key] = it }
                    }
                }
            }
            cached?.let {
                if (it.fingerprint != fingerprint) throw IdempotencyConflictException(key)
                return IdempotentResult(it.value, replayed = true)
            }
            joined?.let {
                if (it.fingerprint != fingerprint) throw IdempotencyConflictException(key)
                try {
                    return IdempotentResult(it.deferred.await(), replayed = true)
                } catch (_: OwnerCancelled) {
                    continue // the owner was cancelled: try to become the owner ourselves
                }
            }
            return runAsOwner(key, owned!!, shouldCache, block)
        }
    }

    private suspend fun runAsOwner(
        key: IdempotencyKey,
        slot: Running<V>,
        shouldCache: (V) -> Boolean,
        block: suspend () -> V,
    ): IdempotentResult<V> {
        val value = try {
            block()
        } catch (t: Throwable) {
            withContext(NonCancellable) { mutex.withLock { running.remove(key) } }
            slot.deferred.completeExceptionally(if (t is CancellationException) OwnerCancelled() else t)
            throw t
        }
        withContext(NonCancellable) {
            mutex.withLock {
                running.remove(key)
                if (shouldCache(value)) {
                    completed.remove(key)
                    completed[key] = Done(value, slot.fingerprint, clock.nowMillis())
                    while (completed.size > maxEntries) completed.remove(completed.keys.first())
                }
            }
        }
        slot.deferred.complete(value)
        return IdempotentResult(value, replayed = false)
    }

    private fun evictExpired() {
        val now = clock.nowMillis()
        val ttlMs = ttl.inWholeMilliseconds
        val iterator = completed.entries.iterator()
        while (iterator.hasNext()) {
            val entry = iterator.next()
            if (now - entry.value.storedAtMillis >= ttlMs) iterator.remove() else break
        }
    }

    /** Number of finished values currently stored. */
    public suspend fun size(): Int = mutex.withLock { evictExpired(); completed.size }

    /** `true` if a finished value is stored for [key]. */
    public suspend fun contains(key: IdempotencyKey): Boolean = mutex.withLock { evictExpired(); key in completed }

    /** `true` if a block is currently running for [key]. */
    public suspend fun isInFlight(key: IdempotencyKey): Boolean = mutex.withLock { key in running }

    /** Forgets every finished value (running calls are not affected). */
    public suspend fun clear(): Unit = mutex.withLock { completed.clear() }
}
