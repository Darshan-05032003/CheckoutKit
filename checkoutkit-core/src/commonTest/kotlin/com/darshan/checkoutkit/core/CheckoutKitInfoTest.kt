package com.darshan.checkoutkit.core

import kotlin.test.Test
import kotlin.test.assertTrue

class CheckoutKitInfoTest {
    @Test
    fun versionIsNotBlank() {
        assertTrue(CheckoutKitInfo.VERSION.isNotBlank())
    }
}
