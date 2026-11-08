package io.github.anastaci1a.localica

import androidx.compose.foundation.layout.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.layout.LookaheadScope
import androidx.compose.ui.layout.layout
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.*
import androidx.compose.ui.unit.*
import io.github.anastaci1a.localica.ui.*
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test

class ScopedSearchPlaneGeometryTest {
    @get:Rule val compose=localicaComposeRule()

    @Test fun planeNestingPreservesTightActualAndLookaheadConstraintsAndHitBounds() {
        var vacancy by mutableFloatStateOf(0f)
        val constraints=mutableMapOf<Pair<Boolean,Boolean>,Constraints>()
        @Composable fun slot(adopted: Boolean) {
            val tag=if(adopted)"plane" else "old"
            val modifier=Modifier.width(280.dp).layout { measurable,parent ->
                val height=(56.dp.toPx()*(1f-vacancy)).toInt().coerceIn(parent.minHeight,parent.maxHeight)
                val child=measurable.measure(parent.copy(minHeight=height,maxHeight=height))
                layout(child.width,height) { child.placeRelative(0,0) }
            }.testTag("slot-$tag")
            @Composable fun pill() {
                ListSearchPill("Search fixture",{},Modifier.fillMaxWidth().padding(horizontal=12.dp,vertical=4.dp)
                    .layout { measurable,limits ->
                        constraints[adopted to isLookingAhead]=limits
                        val child=measurable.measure(limits)
                        layout(child.width,child.height) { child.placeRelative(0,0) }
                    }.testTag("pill-$tag"))
            }
            if(adopted) GlassShadowScope(modifier) { Box(Modifier.fillMaxWidth().clipToBounds()) { pill() } }
            else Box(modifier.clipToBounds()) { pill() }
        }
        compose.setContent { LocalicaTheme {
            LookaheadScope { Column { slot(false); Spacer(Modifier.height(60.dp)); slot(true) } }
        } }
        for(value in listOf(0f,.5f,.98f,1f,0f)) {
            compose.runOnIdle { vacancy=value }
            compose.waitForIdle()
            for(lookahead in listOf(false,true)) {
                val old=constraints[false to lookahead]
                val actual=constraints[true to lookahead]
                assertNotNull("Observe the ${if(lookahead) "lookahead" else "actual"} pass",old)
                assertEquals("Nested plane keeps tight limits at vacancy=$value lookahead=$lookahead",old,actual)
            }
            if(value<1f) {
                fun bounds(tag: String): androidx.compose.ui.geometry.Rect {
                    val host=compose.onNodeWithTag("slot-$tag").fetchSemanticsNode().boundsInRoot
                    return compose.onNodeWithTag("pill-$tag",useUnmergedTree=true).fetchSemanticsNode().boundsInRoot.translate(-host.topLeft)
                }
                assertEquals("The measured pill/hit geometry stays identical at vacancy=$value",bounds("old"),bounds("plane"))
            }
        }
    }
}
