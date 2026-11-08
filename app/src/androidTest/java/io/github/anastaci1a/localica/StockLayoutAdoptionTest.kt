package io.github.anastaci1a.localica

import androidx.compose.foundation.layout.*
import androidx.compose.material3.HorizontalDivider
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import io.github.anastaci1a.localica.ui.LocalicaTheme
import io.github.anastaci1a.localica.ui.ReturnToPositionButton
import io.github.anastaci1a.localica.ui.layout.FloatingControlSlot
import io.github.anastaci1a.localica.ui.layout.SettingsChooserDivider
import io.github.anastaci1a.localica.ui.layout.SettingsChooserFooter
import io.github.anastaci1a.localica.ui.layout.SettingsReviewStack
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test

/** Native layout parity for inherited stock geometry; no replacement motion or input owner. */
class StockLayoutAdoptionTest {
    @get:Rule val compose = localicaComposeRule()

    @Composable private fun Body(prefix: String) {
        Box(Modifier.size(60.dp, 17.dp).testTag("$prefix-0"))
        Box(Modifier.size(108.dp, 25.dp).testTag("$prefix-1"))
    }

    private fun compare(children: Int, content: @Composable (Boolean, String) -> Unit) {
        compose.setContent { LocalicaTheme {
            Column(Modifier.width(240.dp)) {
                Box(Modifier.testTag("before")) { content(true, "before") }
                Box(Modifier.testTag("after")) { content(false, "after") }
            }
        } }
        compose.waitForIdle()
        fun bounds(tag: String) = compose.onNodeWithTag(tag, useUnmergedTree = true).fetchSemanticsNode().boundsInRoot
        val before = bounds("before")
        val after = bounds("after")
        assertEquals("Stock host size", before.size, after.size)
        fun relative(child: Rect, root: Rect) = Rect(child.left - root.left, child.top - root.top,
            child.right - root.left, child.bottom - root.top)
        repeat(children) { index ->
            assertEquals("Child $index keeps its bounds and spacing",
                relative(bounds("before-$index"), before), relative(bounds("after-$index"), after))
        }
    }

    @Test fun reviewStackRetainsVerticalInsetWidthAndChildGap() = compare(2) { reference, prefix ->
        if (reference) Column(Modifier.fillMaxWidth().padding(vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp)) { Body(prefix) }
        else SettingsReviewStack { Body(prefix) }
    }

    @Test fun chooserFooterRetainsWrappingWidthDividerInsetAndChildGap() = compare(3) { reference, prefix ->
        if (reference) Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
            HorizontalDivider(Modifier.padding(vertical = 4.dp).testTag("$prefix-2"))
            Body(prefix)
        } else SettingsChooserFooter {
            SettingsChooserDivider { HorizontalDivider(it.testTag("$prefix-2")) }
            Body(prefix)
        }
    }

    @Test fun returnHostRetainsExistingControlBoundsAndModifierOrder() = compare(1) { reference, prefix ->
        val modifier = Modifier.padding(5.dp).offset { IntOffset(0, -7) }
        val control: @Composable BoxScope.() -> Unit = {
            Box(Modifier.testTag("$prefix-0")) {
                ReturnToPositionButton(up = true, label = "Return to top", onClick = {})
            }
        }
        if (reference) Box(modifier, content = control)
        else FloatingControlSlot(modifier, content = control)
    }
}
