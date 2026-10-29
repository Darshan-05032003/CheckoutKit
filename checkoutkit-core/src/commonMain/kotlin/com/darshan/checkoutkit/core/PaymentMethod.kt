package com.darshan.checkoutkit.core

/** How the payer pays. */
public sealed interface PaymentMethod {
    /** Stable method name: `UPI`, `CARD` or `WALLET`. */
    public val type: String

    /**
     * A description of the method that is safe to compare and store (no full card number, no CVV).
     * Used to detect an idempotency key being reused for a different payment.
     */
    public val fingerprint: String

    /** UPI with the payer's virtual payment address, e.g. `alice@okbank`. */
    public data class Upi(val vpa: String) : PaymentMethod {
        override val type: String get() = "UPI"
        override val fingerprint: String get() = "UPI:${vpa.trim().lowercase()}"
    }

    /**
     * A card. [toString] masks the number and CVV, so the card can't leak into logs or crash reports.
     * Hold instances only as long as the payment needs them.
     */
    public class Card(
        /** Card number, digits only (spaces and dashes are tolerated by the validators). */
        public val number: String,
        /** Expiry month, 1..12. */
        public val expiryMonth: Int,
        /** Four-digit expiry year. */
        public val expiryYear: Int,
        /** Card verification value (3 digits, 4 for American Express). Never logged or persisted. */
        public val cvv: String,
    ) : PaymentMethod {
        override val type: String get() = "CARD"

        /** Last four digits of the card number. */
        public val last4: String get() = number.filter { it.isDigit() }.takeLast(4)

        override val fingerprint: String get() = "CARD:$last4:$expiryMonth/$expiryYear"

        override fun equals(other: Any?): Boolean =
            other is Card && other.number == number && other.expiryMonth == expiryMonth &&
                other.expiryYear == expiryYear && other.cvv == cvv

        override fun hashCode(): Int {
            var h = number.hashCode()
            h = 31 * h + expiryMonth
            h = 31 * h + expiryYear
            h = 31 * h + cvv.hashCode()
            return h
        }

        override fun toString(): String = "Card(number=•••• $last4, expiry=$expiryMonth/$expiryYear, cvv=•••)"
    }

    /** A prepaid wallet. */
    public data class Wallet(val provider: WalletProvider) : PaymentMethod {
        override val type: String get() = "WALLET"
        override val fingerprint: String get() = "WALLET:${provider.name}"
    }
}

/** Wallets the payment sheet offers. */
public enum class WalletProvider(public val displayName: String) {
    PAYTM("Paytm"),
    PHONEPE("PhonePe"),
    AMAZON_PAY("Amazon Pay"),
    MOBIKWIK("MobiKwik"),
}
