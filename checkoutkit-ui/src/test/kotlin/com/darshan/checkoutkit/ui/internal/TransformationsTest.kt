package com.darshan.checkoutkit.ui.internal

import androidx.compose.ui.text.AnnotatedString
import com.darshan.checkoutkit.core.validation.CardBrand
import com.google.common.truth.Truth.assertThat
import org.junit.Test

class TransformationsTest {

    @Test
    fun cardNumber_isGroupedInFours() {
        val t = CardNumberTransformation(CardBrand.VISA).filter(AnnotatedString("4242424242424242"))
        assertThat(t.text.text).isEqualTo("4242 4242 4242 4242")
        assertThat(t.offsetMapping.originalToTransformed(4)).isEqualTo(4)
        assertThat(t.offsetMapping.originalToTransformed(5)).isEqualTo(6)
        assertThat(t.offsetMapping.originalToTransformed(16)).isEqualTo(19)
        assertThat(t.offsetMapping.transformedToOriginal(6)).isEqualTo(5)
        assertThat(t.offsetMapping.transformedToOriginal(19)).isEqualTo(16)
    }

    @Test
    fun amex_isGrouped4_6_5() {
        val t = CardNumberTransformation(CardBrand.AMEX).filter(AnnotatedString("378282246310005"))
        assertThat(t.text.text).isEqualTo("3782 822463 10005")
        assertThat(t.offsetMapping.originalToTransformed(15)).isEqualTo(17)
        assertThat(t.offsetMapping.transformedToOriginal(17)).isEqualTo(15)
    }

    @Test
    fun offsetMapping_isConsistentForEveryPosition() {
        listOf(CardBrand.VISA to "4242424242424242424", CardBrand.AMEX to "378282246310005", CardBrand.VISA to "42").forEach { (brand, digits) ->
            val t = CardNumberTransformation(brand).filter(AnnotatedString(digits))
            for (i in 0..digits.length) {
                val transformed = t.offsetMapping.originalToTransformed(i)
                assertThat(transformed).isAtMost(t.text.length)
                assertThat(t.offsetMapping.transformedToOriginal(transformed)).isEqualTo(i)
            }
            for (j in 0..t.text.length) assertThat(t.offsetMapping.transformedToOriginal(j)).isIn(0..digits.length)
        }
    }

    @Test
    fun expiry_insertsASlash() {
        val t = ExpiryTransformation.filter(AnnotatedString("1230"))
        assertThat(t.text.text).isEqualTo("12/30")
        assertThat(ExpiryTransformation.filter(AnnotatedString("1")).text.text).isEqualTo("1")
        assertThat(ExpiryTransformation.filter(AnnotatedString("12")).text.text).isEqualTo("12")
        assertThat(ExpiryTransformation.filter(AnnotatedString("123")).text.text).isEqualTo("12/3")
        assertThat(t.offsetMapping.originalToTransformed(3)).isEqualTo(4)
        assertThat(t.offsetMapping.transformedToOriginal(3)).isEqualTo(2)
        assertThat(t.offsetMapping.transformedToOriginal(5)).isEqualTo(4)
    }
}
