package io.github.anastaci1a.localica

import io.github.anastaci1a.localica.ui.components.TriggerRole


import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.Alignment
import androidx.compose.foundation.background
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.ui.platform.testTag
import io.github.anastaci1a.localica.navigation.GlassRendererMode
import kotlin.math.*
import androidx.compose.ui.test.*
import io.github.anastaci1a.localica.localicaComposeRule as createComposeRule
import androidx.compose.ui.unit.dp
import io.github.anastaci1a.localica.ui.*
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test

class SplitPillContactTest {
    @get:Rule val compose = createComposeRule()

    @Test fun bothActionsPressTheirSharedShellAndCancelWithoutActivation() {
        lateinit var utility: GlassAnchor
        lateinit var options: GlassAnchor
        var clicks = 0
        var reduced by mutableStateOf(false)
        compose.setContent { EngineTheme(reduced = reduced) {
            CompositionLocalProvider(LocalReducedMotion provides reduced) {
                val source = rememberFrostedBackdrop(Unit, dark = false)
                utility = rememberGlassAnchor(); options = rememberGlassAnchor()
                Box(Modifier.fillMaxSize()) {
                    Canvas(Modifier.fillMaxSize().recordFrostedBackdrop(source)) { drawRect(Color.White) }
                    Column(Modifier.padding(40.dp), verticalArrangement = Arrangement.spacedBy(32.dp)) {
                        PopupActionPill(utility, source, Modifier.size(104.dp, 64.dp),
                            onSearch = { clicks++ }, onMore = { clicks++ }, role = io.github.anastaci1a.localica.ui.components.TriggerRole.Navigation)
                        PopupActionPill(options, modifier = Modifier.height(CompactControlSize), role = TriggerRole.BrowseOptions, leadingIcon = AppIcon.ActionSort, trailingIcon = AppIcon.ActionEye, leadingLabel = "Sort", trailingLabel = "View options", tag = "browse-options-trigger", onSearch = { clicks++ }, onMore = { clicks++ })
                    }
                }
            }
        } }
        compose.mainClock.autoAdvance = false
        var held: SemanticsNodeInteraction? = null
        try {
            for ((label, anchor) in listOf("Search library" to utility, "More" to utility,
                "Sort" to options, "View options" to options)) {
                val node = compose.onNodeWithContentDescription(label)
                held = node
                node.performTouchInput { down(center) }
                compose.mainClock.advanceTimeBy(300)
                compose.runOnIdle {
                    assertTrue("$label must reach the shared shell contact", anchor.contact.pressed)
                    assertTrue("$label contact flattens the shared body", anchor.contact.body.value.depth < -.01f)
                    assertEquals("Contact must not activate", 0, clicks)
                }
                node.performTouchInput { moveBy(Offset(width * 3f, 0f)) }
                compose.mainClock.advanceTimeBy(64)
                compose.runOnIdle { assertTrue("Drag cancellation retains pressure until release", anchor.contact.pressed) }
                node.performTouchInput { up() }; held = null
                compose.mainClock.advanceTimeByFrame()
                compose.runOnIdle {
                    assertFalse("Release clears $label pressure", anchor.contact.pressed)
                    assertEquals("Dragging away cancels activation", 0, clicks)
                }
                compose.mainClock.advanceTimeBy(2200)
                compose.runOnIdle { assertEquals("Shared body settles after $label", 0f, anchor.contact.body.value.depth, .002f) }
            }
            compose.runOnIdle { reduced = true }
            compose.mainClock.advanceTimeByFrame()
            held = compose.onNodeWithContentDescription("More")
            held.performTouchInput { down(center) }
            compose.mainClock.advanceTimeBy(300)
            compose.runOnIdle {
                assertTrue(utility.contact.pressed)
                assertEquals("Reduced motion keeps the shell undeformed", GlassShape(), utility.contact.body.value)
            }
            compose.onNodeWithContentDescription("More").performTouchInput { up() }; held = null
            compose.mainClock.advanceTimeByFrame()
            compose.runOnIdle { assertEquals("Valid release activates once", 1, clicks) }
            val pill = compose.onNodeWithTag("browse-options-trigger").fetchSemanticsNode().boundsInRoot
            assertTrue("Sort/View retains one wide divided pill", pill.width > pill.height * 1.8f)
        } finally {
            try { held?.performTouchInput { cancel() } }
            finally { compose.mainClock.autoAdvance = true }
        }
    }

    @Test fun utilityPressHandsTheSameBodyToAnInterruptedPopupAndBack() {
        lateinit var anchor: GlassAnchor
        var open by mutableStateOf(false)
        compose.setContent { LocalicaTheme {
            val source = rememberFrostedBackdrop(Unit, dark = false)
            anchor = rememberGlassAnchor()
            Box(Modifier.fillMaxSize()) {
                Canvas(Modifier.fillMaxSize().recordFrostedBackdrop(source)) { drawRect(Color.White) }
                PopupActionPill(anchor, source,
                    Modifier.align(androidx.compose.ui.Alignment.BottomEnd).padding(32.dp).size(104.dp, 64.dp),
                    onSearch = {}, onMore = { open = true }, role = io.github.anastaci1a.localica.ui.components.TriggerRole.Navigation)
                GlassMenu(open, anchor, { open = false }) {
                    ActionMenuItem("Settings", icon = AppIcon.ActionSettings, onClick = {})
                    ActionMenuItem("Scan music", icon = AppIcon.TabFolders, onClick = {})
                }
            }
        } }
        compose.mainClock.autoAdvance = false
        var held: SemanticsNodeInteraction? = null
        try {
            val more = compose.onNodeWithContentDescription("More")
            held = more
            more.performTouchInput { down(center) }
            compose.mainClock.advanceTimeBy(160)
            compose.runOnIdle {
                assertFalse("Press feedback precedes popup visibility", anchor.modalPresent)
                assertTrue(anchor.contact.pressed)
                assertTrue(anchor.contact.body.value.depth < -.01f)
            }
            more.performTouchInput { up() }; held = null
            compose.mainClock.advanceTimeBy(176)
            compose.runOnIdle {
                assertTrue(anchor.modalPresent)
                assertTrue(anchor.contact.modal)
                assertFalse("Release cannot leave stuck pressure in the popup", anchor.contact.pressed)
            }
            // Stage 3 returns scale with measured travel: this two-item menu retires within about
            // four frames, so a fixed 64 ms delay no longer interrupts a live return (#858).
            // Reverse on the first return frame and step frames with idle, so the native
            // admission receives the real draw receipts it waits for, as on device.
            compose.runOnIdle { open = false }
            compose.mainClock.advanceTimeByFrame()
            compose.runOnIdle {
                assertTrue("This interrupts a live return", anchor.modalPresent && anchor.contact.expansion.value < 1f)
                open = true
            }
            repeat(112) { compose.mainClock.advanceTimeByFrame(); compose.waitForIdle() }
            compose.onNodeWithText("Settings").assertIsDisplayed()
            compose.runOnIdle { open = false }
            compose.mainClock.advanceTimeUntil(2500) { !anchor.modalPresent }
            compose.mainClock.advanceTimeByFrame()
            compose.runOnIdle {
                assertFalse(anchor.hidden)
                assertFalse(anchor.modalPresent)
                assertFalse(anchor.contact.modal)
                assertFalse(anchor.contact.pressed)
            }
            held = compose.onNodeWithContentDescription("More")
            held.performTouchInput { down(center) }
            compose.mainClock.advanceTimeBy(300)
            compose.runOnIdle { assertTrue("Returned shell still observes contact", anchor.contact.pressed) }
            compose.onNodeWithContentDescription("More").performTouchInput { cancel() }; held = null
            compose.mainClock.advanceTimeByFrame()
            compose.runOnIdle {
                assertFalse("Native cancellation clears shell pressure", anchor.contact.pressed)
                assertFalse(open)
            }
        } finally {
            try { held?.performTouchInput { cancel() } }
            finally { compose.mainClock.autoAdvance = true }
        }
    }

    @Test fun productionPillPlayAndSearchRenderTheSharedSizeAndTwoAxisDragLaw() {
        var item by mutableIntStateOf(0)
        var clicks=0
        val pill=GlassAnchor(); val search=GlassAnchor()
        val runtime=GlassRendererRuntime(GlassRendererMode.FlatNone)
        lateinit var host: android.view.View
        compose.setContent { host=androidx.compose.ui.platform.LocalView.current; CompositionLocalProvider(LocalGlassRendererRuntime provides runtime) { EngineTheme(clear=true) {
            MaterialTheme(colorScheme=lightColorScheme()) {
                Box(Modifier.size(360.dp,560.dp).background(Color.Black).testTag("production-press-scene")) {
                    when(item) {
                        0 -> PopupActionPill(pill,source=null,modifier=Modifier.align(Alignment.Center).height(CompactControlSize),
                            role=TriggerRole.BrowseOptions,leadingIcon=AppIcon.ActionSort,trailingIcon=AppIcon.ActionEye,
                            leadingLabel="Sort",trailingLabel="View options",tag="real-options",onSearch={clicks++},onMore={clicks++})
                        1 -> CollectionHeader("Album",null,PlayerVisual(),false,true,{clicks++},{})
                        else -> Box(Modifier.align(Alignment.Center).width(328.dp)) { ListSearchPill("Search album",{clicks++},anchor=search) }
                    }
                }
            }
        } } }
        compose.mainClock.autoAdvance=false
        var active: SemanticsNodeInteraction?=null
        try {
            val scene=compose.onNodeWithTag("production-press-scene")
            val density=compose.density.density
            for (index in 0..2) {
                compose.runOnIdle { item=index }
                compose.mainClock.advanceTimeBy(1500)
                val labels=when(index) { 0 -> listOf("Sort","View options"); 1 -> listOf("Play album"); else -> listOf("Search album") }
                for(label in labels) for(axis in 0..1) for(direction in listOf(-1f,1f)) {
                    val node=if(index==2) compose.onNodeWithTag("list-search-pill") else compose.onNodeWithContentDescription(label)
                    val screenOrigin=compose.runOnIdle { IntArray(2).also(host::getLocationOnScreen).let { Offset(it[0].toFloat(),it[1].toFloat()) } }
                    val material=when(index) {
                        0 -> pill.bounds.translate(-screenOrigin)
                        2 -> search.bounds.translate(-screenOrigin)
                        else -> node.fetchSemanticsNode().boundsInRoot
                    }
                    val size=material.size
                    val long=size.maxDimension/density; val short=size.minDimension/density
                    val p=if(long<120) 16.0 else 16-12*((short.toDouble()-44)/116).coerceIn(0.0,1.0)
                    val d=if(long<120) 2000.0 else 6000+18000*((short.toDouble()-44)/116).coerceIn(0.0,1.0)
                    fun span(): Pair<Int,Int> {
                        val image=scene.captureToImage()
                        val pixels=IntArray(image.width*image.height)
                        image.asAndroidBitmap().getPixels(pixels,0,image.width,0,0,image.width,image.height)
                        val origin=scene.fetchSemanticsNode().boundsInRoot.topLeft
                        val center=material.center-origin
                        val start=maxOf(0,((if(axis==0) material.left-origin.x else material.top-origin.y)-32*density).roundToInt())
                        val end=minOf((if(axis==0) image.width else image.height)-1,((if(axis==0) material.right-origin.x else material.bottom-origin.y)+32*density).roundToInt())
                        val fixed=(if(axis==0) center.y else center.x).roundToInt()
                        val lit=(start..end).filter { n ->
                            val color=pixels[if(axis==0) fixed*image.width+n else n*image.width+fixed]
                            (color ushr 16 and 255)>128 && (color ushr 8 and 255)>128 && (color and 255)>128
                        }
                        assertTrue("$label has material pixels axis=$axis",lit.isNotEmpty())
                        return lit.first() to lit.last()
                    }
                    val rest=span()
                    active=node
                    node.performTouchInput { down(center) }
                    compose.mainClock.advanceTimeBy(2500)
                    val held=span()
                    val contact=when(index) { 0 -> pill.contact; 2 -> search.contact; else -> null }
                    val origin=compose.runOnIdle { contact?.state?.momentum?.origin }
                    if(index==0 && label=="Sort" && axis==0 && direction<0) compose.runOnIdle {
                        val snapshot=pill.contact.body.value
                        val raw=rawGlassBody(pill.contact)
                        for ((drawSize,rho) in listOf(Size(104f,48f) to 2f,Size(240f,104f) to 2f,Size(240f,104f) to 3f)) {
                            val points=if(drawSize.maxDimension<120) 16.0 else 284.0/29
                            val transform=snapshot.bodyTransform(Size(drawSize.width*rho,drawSize.height*rho),rho)
                            assertEquals(points*rho,(drawSize.maxDimension*rho*(transform.scale.x-1)).toDouble(),1e-3*rho)
                            assertEquals(snapshot,pill.contact.body.value)
                            assertEquals(raw,rawGlassBody(pill.contact))
                            assertTrue(pill.contact.pressed)
                        }
                    }
                    val length=if(axis==0) size.width else size.height
                    val growth=(held.second-held.first)-(rest.second-rest.first)
                    assertEquals("$label held size law axis=$axis",p*density*length/size.maxDimension,growth.toDouble(),1.5)
                    if(index==2) assertTrue("Search measures its material, below the outer touch target",size.height<node.fetchSemanticsNode().boundsInRoot.height)
                    node.performTouchInput { moveBy(if(axis==0) Offset(direction*150*density,0f) else Offset(0f,direction*150*density)) }
                    compose.mainClock.advanceTimeBy(2500)
                    val dragged=span()
                    val a=length/density
                    val o=(if(axis==0) origin?.x else origin?.y)?.toDouble() ?: .5
                    val target=o+direction*150/a
                    val travel=target-o; val u=abs(target.coerceIn(0.0,1.0)-o)
                    val f=sign(travel)*(a/d)*(u/4+abs(travel)-u)
                    val e=abs(f); val m=1+p/long
                    val expectedNear=(e*a+m*a*expm1(e)/2)*density
                    val expectedFar=(e*a-m*a*expm1(e)/2)*density
                    val near=if(direction>0) dragged.second-held.second else held.first-dragged.first
                    val far=if(direction>0) dragged.first-held.first else held.second-dragged.second
                    android.util.Log.i("Localica1080","production=$label axis=$axis sign=$direction material=$size rest=$rest held=$held dragged=$dragged near=$near far=$far expectedNear=$expectedNear expectedFar=$expectedFar")
                    assertEquals("$label near edge axis=$axis sign=$direction",expectedNear,near.toDouble(),1.5)
                    assertEquals("$label far edge axis=$axis sign=$direction",expectedFar,far.toDouble(),1.5)
                    assertTrue("$label unequal edges axis=$axis sign=$direction",near>far)
                    node.performTouchInput { cancel() }; active=null
                    compose.mainClock.advanceTimeBy(5000)
                    compose.runOnIdle {
                        assertEquals(0,clicks)
                        if(contact!=null) { assertFalse(contact.pressed); assertTrue(contact.state.momentum.settled) }
                    }
                }
            }
        } finally {
            try { active?.performTouchInput { cancel() } } finally { compose.mainClock.autoAdvance=true }
        }
    }

}
