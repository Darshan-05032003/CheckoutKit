package com.darshan.checkoutkit.ui.integration

import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.darshan.checkoutkit.core.CheckoutKit
import com.darshan.checkoutkit.core.CheckoutRequest
import com.darshan.checkoutkit.core.gateway.FakeGateway
import com.darshan.checkoutkit.ui.CheckoutSheet
import com.darshan.checkoutkit.ui.CheckoutSheetOptions
import com.darshan.checkoutkit.ui.CheckoutSheetTestTags
import com.darshan.checkoutkit.ui.rememberCheckoutSheet
import com.google.common.truth.Truth.assertThat
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

/** The sheet's own window must block screenshots (FLAG_SECURE) and obscured touches (tapjacking). */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [35], qualifiers = "w411dp-h891dp")
class SheetWindowSecurityTest {

    @get:Rule
    val rule = createComposeRule()

    private fun open(options: CheckoutSheetOptions) {
        val kit = CheckoutKit(FakeGateway())
        rule.setContent {
            MaterialTheme {
                val sheet = rememberCheckoutSheet(kit, options) {}
                Button(onClick = { sheet.launch(CheckoutRequest("order-sec", 100)) }) { Text("Open") }
                CheckoutSheet(sheet)
            }
        }
        rule.onNodeWithText("Open").performClick()
        rule.waitUntil(5_000) { rule.onAllNodes(hasTestTag(CheckoutSheetTestTags.SHEET)).fetchSemanticsNodes().isNotEmpty() }
        rule.waitForIdle()
    }

    /** Window views and their layout params, read from the real framework WindowManagerGlobal. */
    private fun windows(): List<Pair<View, WindowManager.LayoutParams>> {
        val globalClass = Class.forName("android.view.WindowManagerGlobal")
        val global = globalClass.getMethod("getInstance").invoke(null)
        fun field(name: String): List<*> = globalClass.getDeclaredField(name).apply { isAccessible = true }.get(global) as List<*>
        val views = field("mViews").map { it as View }
        val params = field("mParams").map { it as WindowManager.LayoutParams }
        return views.zip(params)
    }

    private fun View.anyInTree(predicate: (View) -> Boolean): Boolean {
        if (predicate(this)) return true
        if (this is ViewGroup) for (i in 0 until childCount) if (getChildAt(i).anyInTree(predicate)) return true
        return false
    }

    @Test
    fun sheetWindow_isSecureByDefault() {
        open(CheckoutSheetOptions())
        val secureWindows = windows().filter { (_, p) -> p.flags and WindowManager.LayoutParams.FLAG_SECURE != 0 }
        assertThat(secureWindows).isNotEmpty()
    }

    @Test
    fun secureWindow_canBeTurnedOffByTheHost() {
        open(CheckoutSheetOptions(secureWindow = false))
        val secureWindows = windows().filter { (_, p) -> p.flags and WindowManager.LayoutParams.FLAG_SECURE != 0 }
        assertThat(secureWindows).isEmpty()
    }

    @Test
    fun sheetContent_filtersTouchesWhenObscured() {
        open(CheckoutSheetOptions())
        val filtered = windows().any { (view, _) -> view.anyInTree { it.filterTouchesWhenObscured } }
        assertThat(filtered).isTrue()
    }
}
