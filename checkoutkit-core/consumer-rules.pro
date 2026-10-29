# Consumer R8 rules for checkoutkit-core (applied to apps that depend on the SDK).
# The core uses no reflection, serialization, JNI or ServiceLoader, so R8 may shrink and obfuscate
# it freely. Only the names of the typed errors are kept, so `CheckoutError.toString()` and crash
# reports stay readable in hosts' release builds (codes are stable strings anyway).
-keepnames class com.darshan.checkoutkit.core.error.CheckoutError
-keepnames class com.darshan.checkoutkit.core.error.CheckoutError$*
