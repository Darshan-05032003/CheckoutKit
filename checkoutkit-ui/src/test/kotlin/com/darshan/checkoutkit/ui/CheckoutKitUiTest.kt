package com.darshan.checkoutkit.ui

import com.google.common.truth.Truth.assertThat
import org.junit.Test

class CheckoutKitUiTest {
    @Test
    fun exposesCoreVersion() {
        assertThat(CheckoutKitUi.coreVersion).isNotEmpty()
    }
}
