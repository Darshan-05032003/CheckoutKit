package com.darshan.checkoutkit.ui

import com.darshan.checkoutkit.core.CheckoutKitInfo

/** Entry point of the CheckoutKit Compose UI layer. */
public object CheckoutKitUi {
    /** Version of the core the UI layer was built against. */
    public val coreVersion: String get() = CheckoutKitInfo.VERSION
}
