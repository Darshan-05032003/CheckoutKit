package com.darshan.checkoutkit.core.stress

import com.darshan.checkoutkit.core.error.CheckoutError
import com.darshan.checkoutkit.core.error.GatewayErrorCodes
import com.darshan.checkoutkit.core.error.toUserMessage
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.runBlocking
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class ErrorMappingStressTest {

    private val codes = listOf("Z9", "ZM", "Z8", "ZA", "ZH", "U69", "BT", "XY", "05", "51", "54", "14", "91", "96", "QQ1", "rate_limited", " z9 ")

    @Test
    fun oneMillionGatewayCodes_mapFastOnEightThreads() {
        val perWorker = 125_000
        val m = measureStress("F3 1,000,000 gateway code mappings (8 workers)") {
            runBlocking(Dispatchers.Default) {
                (0 until 8).map { w ->
                    async {
                        var retryable = 0
                        for (i in 0 until perWorker) {
                            val e = GatewayErrorCodes.fromCode(codes[(i + w) % codes.size])
                            if (e.retryable) retryable++
                            e.toUserMessage()
                        }
                        retryable
                    }
                }.awaitAll().sum()
            }
        }
        println("STRESS [F3 1M mappings] retryable=${m.value} throughput=${1_000_000L * 1000 / m.duration.inWholeMilliseconds.coerceAtLeast(1)} per second")
        assertTrue(m.duration.inWholeSeconds < 5)
    }

    @Test
    fun hundredThousandHostileCodes_neverLeakIntoTheError() {
        val random = Random(99)
        val alphabet = "AZaz09_<>\"'\n\r\t ‮\u0000ज़ä€💳;{}".toList()
        var dropped = 0
        val m = measureStress("F3 100k random hostile codes (up to 4 KB each)") {
            repeat(100_000) {
                val len = random.nextInt(0, if (it % 100 == 0) 4_096 else 32)
                val code = buildString { repeat(len) { append(alphabet[random.nextInt(alphabet.size)]) } }
                val e = GatewayErrorCodes.fromCode(code)
                if (e is CheckoutError.GatewayError) {
                    val kept = e.gatewayCode
                    if (kept == null) dropped++ else assertTrue(Regex("^[A-Z0-9_]{1,24}$").matches(kept), "unsafe code kept: $kept")
                }
            }
        }
        println("STRESS [F3 hostile codes] dropped=$dropped of 100000")
        assertEquals(true, dropped > 50_000)
        assertTrue(m.duration.inWholeSeconds < 5)
    }
}
