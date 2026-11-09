package io.github.anastaci1a.localica

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.*
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.*
import androidx.compose.ui.text.*
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.*
import io.github.anastaci1a.localica.ui.*
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test

/** Native ARGB/geometry comparison with the independently frozen b010 marquee. */
class MarqueeLayerEqualityTest {
    @get:Rule val compose = localicaComposeRule()
    @Test fun firstFramesScrollingReversalAndChildChangesMatchFrozenReference() = compare(false)
    @Test fun rtlFramesScrollingReversalAndChildChangesMatchFrozenReference() = compare(true)
    @Test fun firstReceiptRejectsDelayedCandidatePublicationAndKnownInkDifference() = controls()
    private data class Receipt(val bounds: Rect?, val pixels: IntArray)
    private fun receipt(): Receipt {
        val image=compose.onNodeWithTag("scene").captureNativeFrame().toPixelMap()
        val bounds=compose.onAllNodesWithTag("subject").fetchSemanticsNodes().singleOrNull()?.boundsInRoot
        return Receipt(bounds,IntArray(image.width*image.height) { image[it%image.width,it/image.width].toArgb() })
    }
    private fun assertSame(label:String,a:Receipt,b:Receipt) {
        assertEquals("$label geometry",a.bounds,b.bounds)
        assertArrayEquals("$label native ARGB",a.pixels,b.pixels)
    }
    private fun compare(rtl:Boolean) {
        var reference by mutableStateOf(true)
        var epoch by mutableIntStateOf(0)
        var width by mutableIntStateOf(120)
        var paused by mutableStateOf(false)
        var suffix by mutableFloatStateOf(1f)
        var text by mutableStateOf(if(rtl) "عنوان عربي طويل جدا لاختبار حركة النص على الشاشة" else "MMMMMMMMMMMMMMMMMMMMMMMMMMMMMMMM")
        val style=TextStyle(fontSize=24.sp,fontFamily=FontFamily.Monospace,color=Color.Black)
        compose.mainClock.autoAdvance=false
        compose.setContent { LocalicaTheme { CompositionLocalProvider(LocalLayoutDirection provides if(rtl) LayoutDirection.Rtl else LayoutDirection.Ltr) {
            Box(Modifier.requiredSize(320.dp,96.dp).background(Color.White).testTag("scene")) {
                key(reference,epoch) {
                    val title=AnnotatedString(text,spanStyles=listOf(AnnotatedString.Range(SpanStyle(color=Color.Black.copy(alpha=suffix)),text.length/2,text.length)))
                    if(reference) ReferenceMarqueeTitle(title,false,Modifier.width(width.dp).testTag("subject"),style,paused,text.length/2,suffix)
                    else MarqueeTitle(title,false,Modifier.width(width.dp).testTag("subject"),style,paused,text.length/2,suffix)
                }
            }
        } } }
        fun arm(old:Boolean):List<Pair<String,Receipt>> {
            compose.runOnIdle { reference=old; epoch++; width=120; paused=false; suffix=1f; text=if(rtl) "عنوان عربي طويل جدا لاختبار حركة النص على الشاشة" else "MMMMMMMMMMMMMMMMMMMMMMMMMMMMMMMM" }
            val frames=mutableListOf<Pair<String,Receipt>>()
            fun sample(label:String,delta:Long=16) { compose.mainClock.advanceTimeBy(delta); frames+=label to receipt() }
            sample("first receipt")
            sample("pause edge",1760); sample("first scroll",80); sample("steady scroll",640)
            compose.runOnIdle { width=180 }; sample("width reversal")
            compose.runOnIdle { paused=true }; sample("return starts"); sample("return interior",64); sample("return endpoint",400)
            compose.runOnIdle { width=120; paused=false }; sample("resume first frame"); sample("resume scroll",2048)
            compose.runOnIdle { suffix=.375f }; sample("child annotation update")
            compose.runOnIdle { suffix=0f }; sample("suffix hides")
            compose.runOnIdle { text=if(rtl) "قصير" else "Short" }; sample("new fitting content")
            compose.runOnIdle { text=if(rtl) "عنوان جديد طويل جدا لاختبار تبديل المحتوى والاتجاه" else "WWWWWWWWWWWWWWWWWWWWWWWWWWWWWWWW"; suffix=1f }; sample("new overflowing content")
            return frames
        }
        try { val a=arm(true); val b=arm(false); a.zip(b).forEach { (x,y)->assertEquals(x.first,y.first);assertSame(x.first,x.second,y.second) } }
        finally { compose.mainClock.autoAdvance=true }
    }
    private fun controls() {
        var arm by mutableIntStateOf(0)
        var published by mutableStateOf(false)
        compose.mainClock.autoAdvance=false
        compose.setContent { LocalicaTheme {
            Box(Modifier.requiredSize(320.dp,96.dp).background(Color.White).testTag("scene")) {
                if(arm!=1 || published) {
                    val style=TextStyle(fontSize=24.sp,color=if(arm==2) Color.Red else Color.Black)
                    if(arm==0) ReferenceMarqueeTitle("First receipt",false,Modifier.width(200.dp).testTag("subject"),style)
                    else MarqueeTitle("First receipt",false,Modifier.width(200.dp).testTag("subject"),style)
                }
            }
        } }
        try {
            compose.mainClock.advanceTimeByFrame(); val frozen=receipt()
            compose.runOnIdle { arm=1 }; compose.mainClock.advanceTimeByFrame(); val delayed=receipt()
            var rejected=false
            try { assertSame("candidate delayed until after receipt",frozen,delayed) } catch(expected:AssertionError) { rejected=true }
            assertTrue("The equality oracle must reject delayed first publication",rejected)
            compose.runOnIdle { published=true }; compose.mainClock.advanceTimeByFrame()
            assertSame("later equality cannot repair the rejected first receipt",frozen,receipt())
            compose.runOnIdle { arm=2 }; compose.mainClock.advanceTimeByFrame()
            rejected=false
            try { assertSame("known red-ink control",frozen,receipt()) } catch(expected:AssertionError) { rejected=true }
            assertTrue("The equality oracle must detect a known ink difference",rejected)
        } finally { compose.mainClock.autoAdvance=true }
    }
}
