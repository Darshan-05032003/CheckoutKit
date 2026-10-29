package com.darshan.checkoutkit.core.error

/** What the payer can do about an error; the payment sheet picks its button from this. */
public enum class RecoveryAction {
    /** Try the same payment again. */
    RETRY,

    /** Pick another payment method or account. */
    CHANGE_METHOD,

    /** Fix the entered details (UPI id, card number, PIN). */
    EDIT_DETAILS,

    /** Nothing the payer can fix; contact the merchant. */
    CONTACT_SUPPORT,
}

/** Text for the payer. Contains no codes, stack traces or gateway internals. */
public data class UserMessage(
    val title: String,
    val message: String,
    val action: RecoveryAction,
)

/** Maps errors to user-facing messages. Provide your own to localise or re-word them. */
public fun interface CheckoutMessages {
    /** The message for [error]. */
    public fun messageFor(error: CheckoutError): UserMessage

    public companion object {
        /** Built-in English messages. */
        public val Default: CheckoutMessages = CheckoutMessages { it.toUserMessage() }
    }
}

/** The default English message for this error. */
public fun CheckoutError.toUserMessage(): UserMessage = when (this) {
    CheckoutError.Network -> UserMessage("No connection", "Check your internet connection and try again.", RecoveryAction.RETRY)
    CheckoutError.Timeout -> UserMessage("This is taking too long", "The bank didn't respond in time. If money was debited it will be refunded automatically.", RecoveryAction.RETRY)
    CheckoutError.Declined -> UserMessage("Payment declined", "Your bank declined this payment. Try another payment method.", RecoveryAction.CHANGE_METHOD)
    CheckoutError.InsufficientFunds -> UserMessage("Insufficient balance", "Your account doesn't have enough balance. Try another account or method.", RecoveryAction.CHANGE_METHOD)
    CheckoutError.InvalidVpa -> UserMessage("Check your UPI ID", "This UPI ID doesn't look right. Check it and try again.", RecoveryAction.EDIT_DETAILS)
    CheckoutError.InvalidCard -> UserMessage("Check your card details", "The card details are not valid. Check them and try again.", RecoveryAction.EDIT_DETAILS)
    CheckoutError.CardExpired -> UserMessage("Card expired", "This card has expired. Use another card.", RecoveryAction.EDIT_DETAILS)
    CheckoutError.LimitExceeded -> UserMessage("Limit reached", "This payment is over your bank's limit. Try a smaller amount or another method.", RecoveryAction.CHANGE_METHOD)
    CheckoutError.AuthenticationFailed -> UserMessage("Verification failed", "The PIN or OTP was incorrect. Try again.", RecoveryAction.EDIT_DETAILS)
    is CheckoutError.RateLimited -> UserMessage("Too many attempts", "Please wait a moment and try again.", RecoveryAction.RETRY)
    is CheckoutError.GatewayError -> UserMessage("Payment service unavailable", "We couldn't reach the payment service. Try again in a moment.", RecoveryAction.RETRY)
    is CheckoutError.InvalidRequest -> UserMessage("Something went wrong", "This payment can't be processed. Please contact the merchant.", RecoveryAction.CONTACT_SUPPORT)
    CheckoutError.ResponseMismatch -> UserMessage("Payment not confirmed", "We couldn't verify this payment. Please contact the merchant before paying again.", RecoveryAction.CONTACT_SUPPORT)
    is CheckoutError.Unknown -> UserMessage("Something went wrong", "An unexpected error occurred. Please contact the merchant.", RecoveryAction.CONTACT_SUPPORT)
}
