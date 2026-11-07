package io.github.anastaci1a.localica.ui

import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.*
import io.github.anastaci1a.localica.navigation.InterfaceSettings
import io.github.anastaci1a.localica.ui.engine.*
import io.github.anastaci1a.localica.ui.spec.*
import org.junit.Assert.*
import org.junit.Test

/** U03.01c (D17, B1): the split pill is one persistent shape following the preparation phase. */
class SplitPillShapeTest {
    private val spec=(uiSpec(InterfaceSettings(),null,ExpansionGeometryDomain(1000f,2000f,1f/60)) as SpecResult.Accepted).spec
    private val c=132f

    /** The header width for a full reveal or a fast source tap (reveal 0), as LyricsHeaderActions lays it out. */
    private fun size(p: Float,reveal: Float): Size { val gap=((p-.5f)*2f).coerceIn(0f,1f); return Size(c*(1f+maxOf(reveal,(p*2f).coerceIn(0f,1f))+gap/6f),c) }

    /** Pre-B1 oracle: one body at rest, then the same halves; the optics formulas as GlassSilhouette had them. */
    private fun legacyBodies(size: Size,p: Float)=splitPillBodies(size,p).let { if(p==0f) it.take(1) else it }
    private fun legacyOptics(size: Size,p: Float): GlassSilhouetteGeometry { val bodies=legacyBodies(size,p); val raw=if(p<.25f) p*4f else (2f-p*4f).coerceAtLeast(0f); val rim=(p*2f).coerceIn(0f,1f)
        fun RoundRect.center()=Offset((left+right)/2f,(top+bottom)/2f)
        return GlassSilhouetteGeometry(bodies,raw*raw*(3f-2f*raw),if(p>.25f) bodies.first().center() to bodies.last().center() else null,rim*rim*(3f-2f*rim),if(p>0f && p<.5f) size.height*p else null) }
    private fun corners(b: RoundRect)=floatArrayOf(b.left,b.top,b.right,b.bottom,b.topLeftCornerRadius.x,b.topLeftCornerRadius.y,b.topRightCornerRadius.x,b.topRightCornerRadius.y,b.bottomRightCornerRadius.x,b.bottomRightCornerRadius.y,b.bottomLeftCornerRadius.x,b.bottomLeftCornerRadius.y)

    @Test fun regrabAtTheNeckKeepsOneShapeIdentityAndTheLegacyFrames() = EngineHarness(spec=spec).use { h ->
        for(reveal in listOf(1f,0f)) {
            var p=0f; val shape=PersistentShape(h.clock,ShapeTarget(emptyList()),"split $reveal"); val pill=SplitPill(shape,Modifier) { p }
            fun follow(q: Float) { p=q; h.now+=8_000_000; shape.follow(ShapeTarget(splitPillBodies(size(q,reveal),q),JoinPolicy.Union)) }
            follow(0f); val ids=shape.slotIds(); assertEquals("Both bodies exist from rest",2,ids.size)
            // Out, a regrab at the neck, back out and home: D17's phases plus the reversal; the fast tap starts below one height per half.
            for(q in listOf(.1f,.25f,.4f,.5f,.75f,1f,.6f,.5f,.45f,.3f,.1f,.0001f,0f,.4f,1f)) {
                follow(q); assertEquals("One identity through split and rejoin (p=$q)",ids,shape.slotIds())
                val published=shape.outline(); follow(q); assertSame("Re-following the same phase publishes nothing (no layout feedback loop)",published,shape.outline())
                assertEquals("The followed phase owns all motion: the shape keeps no frame loop awake (p=$q)",0,h.clock.activeCount)
                for(scale in listOf(1f,.5f)) {
                    val s=size(q,reveal)*scale; val actual=splitPillOptics(pill.bodies(s),s,q); val expected=legacyOptics(s,q)
                    assertEquals(expected.bodies.size,actual.bodies.size); expected.bodies.zip(actual.bodies).forEach { (a,b) -> assertArrayEquals("p=$q reveal=$reveal scale=$scale",corners(a),corners(b),1e-3f) }
                    assertEquals(expected.fieldWeight,actual.fieldWeight,1e-6f); assertEquals(expected.rimSplit,actual.rimSplit,1e-6f); assertEquals(expected.splitInner,actual.splitInner)
                    assertEquals(expected.circles==null,actual.circles==null); expected.circles?.let { (a,b) -> val (x,y)=actual.circles!!; assertEquals(a.x,x.x,1e-3f); assertEquals(b.x,y.x,1e-3f); assertEquals(a.y,x.y,1e-3f) }
                }
            }
            shape.dispose()
        }
    }
}
