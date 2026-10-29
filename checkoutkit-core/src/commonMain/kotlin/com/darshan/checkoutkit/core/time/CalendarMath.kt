package com.darshan.checkoutkit.core.time

/** A calendar year and month (1..12). */
internal data class YearMonth(val year: Int, val month: Int) : Comparable<YearMonth> {
    override fun compareTo(other: YearMonth): Int = compareValuesBy(this, other, { it.year }, { it.month })
}

/**
 * Converts epoch millis to a calendar year/month at a fixed UTC offset, without platform date
 * libraries (Howard Hinnant's `civil_from_days`, proleptic Gregorian calendar).
 */
internal fun yearMonthOf(epochMillis: Long, utcOffsetMinutes: Int): YearMonth {
    val days = Math.floorDiv(epochMillis + utcOffsetMinutes * 60_000L, 86_400_000L)
    val z = days + 719_468
    val era = Math.floorDiv(z, 146_097L)
    val doe = z - era * 146_097
    val yoe = (doe - doe / 1_460 + doe / 36_524 - doe / 146_096) / 365
    val doy = doe - (365 * yoe + yoe / 4 - yoe / 100)
    val mp = (5 * doy + 2) / 153
    val month = (if (mp < 10) mp + 3 else mp - 9).toInt()
    val year = (yoe + era * 400 + if (month <= 2) 1 else 0).toInt()
    return YearMonth(year, month)
}

private object Math {
    fun floorDiv(a: Long, b: Long): Long {
        val q = a / b
        return if ((a % b != 0L) && ((a xor b) < 0)) q - 1 else q
    }
}
