package com.darshan.checkoutkit.core.idempotency

/** The value returned by [IdempotencyStore.getOrExecute]. */
public data class IdempotentResult<out V>(
    /** The result of the single execution for this key. */
    val value: V,
    /** `true` if this caller did not run the block itself (cached, or joined an in-flight call). */
    val replayed: Boolean,
)

/** Thrown when an idempotency key is reused for a different request (a replay or a host bug). */
public class IdempotencyConflictException(public val key: IdempotencyKey) :
    IllegalStateException("Idempotency key $key was already used for a different request")

/**
 * Makes an operation idempotent per key: for one key the block runs at most once at a time, and
 * once it has produced a cacheable value every later call gets that value back without running.
 */
public interface IdempotencyStore<V : Any> {
    /**
     * Returns the stored value for [key], joins the call already running for it, or runs [block].
     *
     * @param fingerprint describes the request; a different fingerprint for a known key throws
     *   [IdempotencyConflictException].
     * @param shouldCache whether a finished value may be replayed later. Values that are not cached
     *   are still shared with callers that were waiting concurrently.
     */
    public suspend fun getOrExecute(
        key: IdempotencyKey,
        fingerprint: String,
        shouldCache: (V) -> Boolean = { true },
        block: suspend () -> V,
    ): IdempotentResult<V>
}
