package com.darshan.checkoutkit.core.money

/** Money helpers. Amounts are always `Long` paise (1 rupee = 100 paise); never floating point. */
public object Money {
    private val RUPEE_TEXT = Regex("^[0-9]{1,13}(\\.[0-9]{1,2})?$")

    /** Formats paise as rupees with Indian digit grouping, e.g. `₹1,23,456.78`. */
    public fun formatInr(paise: Long): String {
        val negative = paise < 0
        // Work with negative values so Long.MIN_VALUE doesn't overflow.
        val neg = if (negative) paise else -paise
        val rupees = -(neg / 100)
        val rest = -(neg % 100)
        val grouped = groupIndian(rupees.toString())
        val fraction = rest.toString().padStart(2, '0')
        return (if (negative) "-₹" else "₹") + grouped + "." + fraction
    }

    /** Parses typed rupees (`499`, `499.5`, `1,23,456.78`) to paise; `null` if not a valid positive amount. */
    public fun parseInrToPaise(text: String): Long? {
        val cleaned = text.trim().replace(",", "")
        if (!RUPEE_TEXT.matches(cleaned)) return null
        val parts = cleaned.split('.')
        val rupees = parts[0].toLong()
        val paise = if (parts.size == 2) parts[1].padEnd(2, '0').toLong() else 0L
        return rupees * 100 + paise
    }

    /** Returns [paise] if it is positive, otherwise throws [IllegalArgumentException]. */
    public fun requirePositive(paise: Long): Long {
        require(paise > 0) { "amount must be positive, was $paise paise" }
        return paise
    }

    private fun groupIndian(digits: String): String {
        if (digits.length <= 3) return digits
        val last3 = digits.takeLast(3)
        val head = digits.dropLast(3)
        val groups = mutableListOf<String>()
        var end = head.length
        while (end > 0) {
            val start = maxOf(0, end - 2)
            groups.add(0, head.substring(start, end))
            end = start
        }
        return groups.joinToString(",") + "," + last3
    }
}
