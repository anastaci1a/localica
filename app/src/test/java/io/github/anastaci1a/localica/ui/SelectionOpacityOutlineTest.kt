package io.github.anastaci1a.localica.ui

import org.junit.Assert.*
import org.junit.Test
import kotlin.math.abs
import kotlin.random.Random

class SelectionOpacityOutlineTest {
    @Test fun equalVisibilitiesUseOneSliceAndZeroHasNoSurface() {
        assertTrue(selectionOpacitySlices(List(12) { 0f }).isEmpty())
        for(p in listOf(.001f,.4f,1f)) {
            val slices=selectionOpacitySlices(List(12) { p })
            assertEquals(1,slices.size)
            assertEquals((0..11).toList(),slices.single().members)
            assertEquals(p,slices.single().weight,0f)
        }
    }
    @Test fun everyMemberReceivesItsExactOpacityIncludingTwelveDistinctLevels() {
        val random=Random(556)
        repeat(300) {
            val values=List(12) { random.nextFloat() }
            val slices=selectionOpacitySlices(values)
            for(i in values.indices) assertEquals(values[i],slices.filter { i in it.members }.sumOf { it.weight.toDouble() }.toFloat(),1e-6f)
            assertTrue(slices.all { it.weight>0f })
            assertEquals(values.max(),slices.sumOf { it.weight.toDouble() }.toFloat(),1e-6f)
        }
    }
    @Test fun tiesAndBirthOrRemovalHaveContinuousPremultipliedWeights() {
        fun render(p: Float,q: Float)=selectionOpacitySlices(listOf(1f,p,q)).sumOf { slice ->
            val arbitrarySurface=if(1 in slice.members && 2 in slice.members).3 else if(1 in slice.members).8 else .5
            arbitrarySurface*slice.weight
        }
        for(p in listOf(0f,.3f,1f)) {
            val low=(p-1e-5f).coerceAtLeast(0f);val high=(p+1e-5f).coerceAtMost(1f)
            assertTrue(abs(render(low,p)-render(high,p))<3e-5)
        }
        for(i in 0..100) {
            val p=i/100f
            val slices=selectionOpacitySlices(listOf(1f,p))
            val full=slices.filter { it.members.size==2 }.sumOf { it.weight.toDouble() }
            val persistent=slices.filter { it.members==listOf(0) }.sumOf { it.weight.toDouble() }
            assertEquals(p.toDouble(),full,1e-7)
            assertEquals((1f-p).toDouble(),persistent,1e-7)
        }
    }
}
