package com.darshan.checkoutkit.ui.internal

import androidx.annotation.StringRes
import com.darshan.checkoutkit.core.error.CheckoutError
import com.darshan.checkoutkit.core.error.RecoveryAction
import com.darshan.checkoutkit.core.validation.FieldError
import com.darshan.checkoutkit.ui.R

/** Input fields of the sheet, used to pick a field-specific error message. */
internal enum class InputField { VPA, CARD_NUMBER, EXPIRY, CVV, WALLET }

/** Maps typed errors to localisable string resources. No gateway text is ever shown. */
internal object ErrorStrings {

    @StringRes
    fun titleOf(error: CheckoutError): Int = when (error) {
        CheckoutError.Network -> R.string.checkoutkit_error_network_title
        CheckoutError.Timeout -> R.string.checkoutkit_error_timeout_title
        CheckoutError.Declined -> R.string.checkoutkit_error_declined_title
        CheckoutError.InsufficientFunds -> R.string.checkoutkit_error_insufficient_funds_title
        CheckoutError.InvalidVpa -> R.string.checkoutkit_error_invalid_vpa_title
        CheckoutError.InvalidCard -> R.string.checkoutkit_error_invalid_card_title
        CheckoutError.CardExpired -> R.string.checkoutkit_error_card_expired_title
        CheckoutError.LimitExceeded -> R.string.checkoutkit_error_limit_exceeded_title
        CheckoutError.AuthenticationFailed -> R.string.checkoutkit_error_authentication_failed_title
        is CheckoutError.RateLimited -> R.string.checkoutkit_error_rate_limited_title
        is CheckoutError.GatewayError -> R.string.checkoutkit_error_gateway_title
        CheckoutError.ResponseMismatch -> R.string.checkoutkit_error_mismatch_title
        is CheckoutError.InvalidRequest, is CheckoutError.Unknown -> R.string.checkoutkit_error_generic_title
    }

    @StringRes
    fun messageOf(error: CheckoutError): Int = when (error) {
        CheckoutError.Network -> R.string.checkoutkit_error_network_message
        CheckoutError.Timeout -> R.string.checkoutkit_error_timeout_message
        CheckoutError.Declined -> R.string.checkoutkit_error_declined_message
        CheckoutError.InsufficientFunds -> R.string.checkoutkit_error_insufficient_funds_message
        CheckoutError.InvalidVpa -> R.string.checkoutkit_error_invalid_vpa_message
        CheckoutError.InvalidCard -> R.string.checkoutkit_error_invalid_card_message
        CheckoutError.CardExpired -> R.string.checkoutkit_error_card_expired_message
        CheckoutError.LimitExceeded -> R.string.checkoutkit_error_limit_exceeded_message
        CheckoutError.AuthenticationFailed -> R.string.checkoutkit_error_authentication_failed_message
        is CheckoutError.RateLimited -> R.string.checkoutkit_error_rate_limited_message
        is CheckoutError.GatewayError -> R.string.checkoutkit_error_gateway_message
        CheckoutError.ResponseMismatch -> R.string.checkoutkit_error_mismatch_message
        is CheckoutError.InvalidRequest -> R.string.checkoutkit_error_invalid_request_message
        is CheckoutError.Unknown -> R.string.checkoutkit_error_unknown_message
    }

    @StringRes
    fun actionLabelOf(action: RecoveryAction): Int = when (action) {
        RecoveryAction.RETRY -> R.string.checkoutkit_action_retry
        RecoveryAction.CHANGE_METHOD -> R.string.checkoutkit_action_change_method
        RecoveryAction.EDIT_DETAILS -> R.string.checkoutkit_action_edit_details
        RecoveryAction.CONTACT_SUPPORT -> R.string.checkoutkit_action_contact_support
    }

    @StringRes
    fun fieldErrorOf(error: FieldError, field: InputField): Int = when (field) {
        InputField.VPA -> when (error) {
            FieldError.EMPTY -> R.string.checkoutkit_field_vpa_empty
            FieldError.INVALID_CHARACTERS -> R.string.checkoutkit_field_vpa_characters
            FieldError.TOO_LONG -> R.string.checkoutkit_field_vpa_too_long
            else -> R.string.checkoutkit_field_vpa_format
        }
        InputField.CARD_NUMBER -> when (error) {
            FieldError.EMPTY -> R.string.checkoutkit_field_card_empty
            FieldError.INVALID_CHARACTERS -> R.string.checkoutkit_field_card_characters
            FieldError.WRONG_LENGTH, FieldError.TOO_LONG -> R.string.checkoutkit_field_card_length
            FieldError.INVALID_CHECKSUM -> R.string.checkoutkit_field_card_checksum
            FieldError.UNSUPPORTED_BRAND -> R.string.checkoutkit_field_card_brand
            else -> R.string.checkoutkit_field_generic
        }
        InputField.EXPIRY -> when (error) {
            FieldError.EMPTY -> R.string.checkoutkit_field_expiry_empty
            FieldError.INVALID_MONTH -> R.string.checkoutkit_field_expiry_month
            FieldError.EXPIRED -> R.string.checkoutkit_field_expiry_expired
            FieldError.TOO_FAR_IN_FUTURE -> R.string.checkoutkit_field_expiry_future
            else -> R.string.checkoutkit_field_expiry_format
        }
        InputField.CVV -> when (error) {
            FieldError.EMPTY -> R.string.checkoutkit_field_cvv_empty
            else -> R.string.checkoutkit_field_cvv_invalid
        }
        InputField.WALLET -> R.string.checkoutkit_field_wallet_missing
    }
}
