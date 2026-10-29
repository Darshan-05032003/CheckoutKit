package com.darshan.checkoutkit.core.retry

import com.darshan.checkoutkit.core.error.CheckoutError
import java.io.IOException
import java.net.SocketTimeoutException

internal actual fun classifyThrowable(t: Throwable): CheckoutError = when (t) {
    is SocketTimeoutException -> CheckoutError.Timeout
    is IOException -> CheckoutError.Network
    // Only the class name: exception messages can contain card data or URLs with tokens.
    else -> CheckoutError.Unknown(detail = t::class.simpleName)
}
