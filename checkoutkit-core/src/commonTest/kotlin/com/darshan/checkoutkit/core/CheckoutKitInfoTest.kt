package com.darshan.checkoutkit.core

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class CheckoutKitInfoTest {
    @Test
    fun versionIsNotBlank() {
        assertTrue(CheckoutKitInfo.VERSION.isNotBlank())
    }

    @Test
    fun versionIsSemVer_andMatchesTheFirstPublishedRelease() {
        assertTrue(Regex("^\\d+\\.\\d+\\.\\d+$").matches(CheckoutKitInfo.VERSION))
        assertEquals(1, CheckoutKitInfo.VERSION.substringBefore('.').toInt())
    }
}
