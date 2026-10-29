package com.darshan.checkoutkit.core.stress

import kotlin.time.Duration
import kotlin.time.measureTimedValue

/** Measures wall time and approximate heap growth of [block] and prints a one-line report. */
internal inline fun <T> measureStress(name: String, block: () -> T): StressMeasurement<T> {
    val runtime = Runtime.getRuntime()
    System.gc()
    val before = runtime.totalMemory() - runtime.freeMemory()
    val timed = measureTimedValue(block)
    val after = runtime.totalMemory() - runtime.freeMemory()
    val m = StressMeasurement(timed.value, timed.duration, (after - before).coerceAtLeast(0) / (1024 * 1024))
    println("STRESS [$name] time=${timed.duration.inWholeMilliseconds} ms heapDelta=${m.heapDeltaMb} MB maxHeap=${runtime.maxMemory() / (1024 * 1024)} MB")
    return m
}

internal data class StressMeasurement<T>(val value: T, val duration: Duration, val heapDeltaMb: Long)
