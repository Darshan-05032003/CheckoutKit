package com.darshan.checkoutkit.ui.internal

import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.input.OffsetMapping
import androidx.compose.ui.text.input.TransformedText
import androidx.compose.ui.text.input.VisualTransformation
import com.darshan.checkoutkit.core.validation.CardBrand

/**
 * Shows a digits-only card number in groups (4-4-4-4…, or 4-6-5 for American Express) without
 * changing the stored value, so the cursor and the validator keep working on plain digits.
 */
internal class CardNumberTransformation(private val brand: CardBrand) : VisualTransformation {
    private val groups: List<Int> = if (brand == CardBrand.AMEX) listOf(4, 6, 5) else listOf(4, 4, 4, 4, 3)

    /** Original offsets after which a space is inserted. */
    private val breaks: List<Int> = groups.runningReduce(Int::plus).dropLast(1)

    override fun filter(text: AnnotatedString): TransformedText {
        val digits = text.text
        val out = StringBuilder()
        // transformedToOriginal[p] = original offset for a cursor before transformed char p.
        val toOriginal = ArrayList<Int>(digits.length + 6)
        digits.forEachIndexed { i, c ->
            if (i > 0 && i in breaks) {
                out.append(' ')
                toOriginal.add(i)
            }
            out.append(c)
            toOriginal.add(i)
        }
        toOriginal.add(digits.length)
        val mapping = object : OffsetMapping {
            override fun originalToTransformed(offset: Int): Int =
                (offset + breaks.count { it < offset }).coerceAtMost(out.length)

            override fun transformedToOriginal(offset: Int): Int =
                toOriginal[offset.coerceIn(0, toOriginal.lastIndex)]
        }
        return TransformedText(AnnotatedString(out.toString()), mapping)
    }

    override fun equals(other: Any?): Boolean = other is CardNumberTransformation && other.brand == brand
    override fun hashCode(): Int = brand.hashCode()
}

/** Shows stored `MMYY` digits as `MM/YY`. */
internal object ExpiryTransformation : VisualTransformation {
    override fun filter(text: AnnotatedString): TransformedText {
        val raw = text.text
        val shown = if (raw.length > 2) raw.substring(0, 2) + "/" + raw.substring(2) else raw
        val mapping = object : OffsetMapping {
            override fun originalToTransformed(offset: Int): Int = if (offset > 2 && raw.length > 2) offset + 1 else offset
            override fun transformedToOriginal(offset: Int): Int = (if (offset > 2 && raw.length > 2) offset - 1 else offset).coerceIn(0, raw.length)
        }
        return TransformedText(AnnotatedString(shown), mapping)
    }
}
