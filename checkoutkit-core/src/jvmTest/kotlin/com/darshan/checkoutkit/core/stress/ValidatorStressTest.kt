package com.darshan.checkoutkit.core.stress

import com.darshan.checkoutkit.core.money.Money
import com.darshan.checkoutkit.core.validation.CardValidator
import com.darshan.checkoutkit.core.validation.CvvValidator
import com.darshan.checkoutkit.core.validation.ExpiryValidator
import com.darshan.checkoutkit.core.validation.VpaValidator
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertTrue

class ValidatorStressTest {

    private val alphabet = ("0123456789 -/@._abcdefghijklmnopqrstuvwxyzABCZ" + "‮\u0000\nज़аä€💳٤").toList()

    private fun randomInput(random: Random, i: Int): String {
        val len = if (i % 500 == 0) random.nextInt(10_000, 100_000) else random.nextInt(0, 40)
        return buildString { repeat(len) { append(alphabet[random.nextInt(alphabet.size)]) } }
    }

    @Test
    fun hundredThousandHostileInputs_throughEveryValidator() {
        val random = Random(2026)
        val expiry = ExpiryValidator()
        var valid = 0
        val m = measureStress("F5 100k random/huge/unicode inputs x 5 validators") {
            repeat(100_000) { i ->
                val input = randomInput(random, i)
                if (CardValidator.validateNumber(input).isValid) valid++
                if (VpaValidator.validate(input).isValid) valid++
                expiry.validateText(input)
                CvvValidator.validate(input, CardValidator.brandOf(input))
                Money.parseInrToPaise(input)
            }
        }
        println("STRESS [F5 validators] accepted=$valid (of 200k card+vpa checks)")
        assertTrue(m.duration.inWholeSeconds < 10, "took ${m.duration}")
    }

    @Test
    fun luhnThroughput_oneMillionGeneratedCards() {
        val random = Random(7)
        var passing = 0
        val m = measureStress("F5 1M Luhn checks on 16-digit numbers") {
            repeat(1_000_000) {
                val digits = buildString { append('4'); repeat(15) { append(('0' + random.nextInt(10))) } }
                if (CardValidator.passesLuhn(digits)) passing++
            }
        }
        println("STRESS [F5 luhn] passing=$passing (~10% expected)")
        assertTrue(passing in 95_000..105_000)
        assertTrue(m.duration.inWholeSeconds < 10)
    }
}
