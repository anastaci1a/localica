package io.github.anastaci1a.localica

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Color
import android.os.Build
import android.os.SystemClock
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.asAndroidPath
import androidx.test.platform.app.InstrumentationRegistry
import io.github.anastaci1a.localica.ui.*
import io.github.anastaci1a.localica.browse.BrowseStyle
import io.github.anastaci1a.localica.browse.BrowseCellGeometry
import io.github.anastaci1a.localica.browse.ListSize
import io.github.anastaci1a.localica.browse.Presentation
import org.junit.Assert.*
import org.junit.Test
import java.io.File
import kotlin.math.*

/** Subpixel connect/disconnect proofs, including both directions of the drag motion adapters. */
class SelectionMergeSequenceTest {
    private val output: File by lazy {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        File(InstrumentationRegistry.getArguments().getString("additionalTestOutputDir")
            ?.takeIf { it.isNotBlank() }?.let(::File) ?: requireNotNull(context.getExternalFilesDir(null)),"selection-merge")
            .apply { mkdirs() }
    }
    private fun coverage(regions: List<SelectionRegion>,density: Float,strength: Float): IntArray {
        return coverage(regions,density) { _,_->strength }
    }
    private fun coverage(regions: List<SelectionRegion>,density: Float,strength: (Int,Int)->Float): IntArray {
        val path = connectedSelectionPath(regions,16f*density,strength = strength)
        val bitmap = Bitmap.createBitmap(420,280,Bitmap.Config.ARGB_8888)
        Canvas(bitmap).apply {
            scale(1f/density,1f/density)
            drawPath(path.asAndroidPath(),Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.WHITE })
        }
        return IntArray(420*280).also { bitmap.getPixels(it,0,420,0,0,420,280); bitmap.recycle() }
    }
    private fun sequence(name: String,width: Float,height: Float,vertical: Boolean,drag: Boolean,
        corner: Float=12f,steps: Int=120,regionFactory: ((Rect,Float)->List<Rect>)?=null) {
        val csv = StringBuilder("density,rtl,frame,separation,progress,changedCoverage\n")
        for (density in listOf(1f,1.75f,2.625f)) for (rtl in listOf(false,true)) {
            var previous: IntArray? = null
            val frames = mutableListOf<IntArray>()
            val outbound = (0..steps).map { it/steps.toFloat() }
            val positions = outbound + outbound.reversed()
            for ((frame,t) in positions.withIndex()) {
                val separation = -4f+20f*t
                val progress = if (drag) hoverBridgeStrength(true,true,t to t,(1f-t) to (1f-t),1f to 1f) else 1f
                val a = Rect(16f,16f,16f+width,16f+height)
                val b = if (vertical) a.translate(Offset(0f,height+separation)) else a.translate(Offset(width+separation,0f))
                val regions = (regionFactory?.invoke(a,separation) ?: listOf(a,b)).map {
                    val r = if (rtl) Rect(400f-it.right,it.top,400f-it.left,it.bottom) else it
                    SelectionRegion(Rect(r.left*density,r.top*density,r.right*density,r.bottom*density),corner*density)
                }
                val pixels = coverage(regions,density,progress)
                val old = previous
                val changed = if (old == null) 0f else pixels.indices.sumOf { abs((pixels[it] ushr 24)-(old[it] ushr 24)).toDouble()/255 }.toFloat()
                val bound=(width+height)*1.2f*120/steps
                if (changed >= bound && old != null) {
                    for ((label,imagePixels) in listOf("before" to old,"after" to pixels)) {
                        val image = Bitmap.createBitmap(imagePixels,420,280,Bitmap.Config.ARGB_8888)
                        File(output,"$name-failed-$frame-$label.png").outputStream().use { image.compress(Bitmap.CompressFormat.PNG,100,it) }
                        image.recycle()
                    }
                }
                assertTrue("$name frame=$frame density=$density rtl=$rtl changed=$changed",changed < bound)
                if (frame > steps) assertArrayEquals("Reversal must reproduce its exact outline",frames[2*steps+1-frame],pixels)
                else frames += pixels
                if (density == 1f && !rtl && frame % 12 == 0) {
                    val image = Bitmap.createBitmap(pixels,420,280,Bitmap.Config.ARGB_8888)
                    File(output,"$name-$frame-api${Build.VERSION.SDK_INT}.png").outputStream().use { image.compress(Bitmap.CompressFormat.PNG,100,it) }
                    image.recycle()
                }
                csv.append("$density,$rtl,$frame,$separation,$progress,$changed\n")
                previous = pixels
            }
        }
        File(output,"$name-api${Build.VERSION.SDK_INT}.csv").writeText(csv.toString())
    }
    // 112 dp artwork, 3.5 dp padding on each side, and an 8 dp artwork gap (#573).
    @Test fun carouselConnectDisconnectAndReverse() = sequence("carousel",119f,119f,false,false)
    @Test fun listConnectDisconnectAndReverse() = sequence("list",320f,72f,true,false)
    @Test fun galleryConnectDisconnectAndReverse() = sequence("gallery",96f,96f,false,false)
    @Test fun dragStartReleaseAndCancelReverse() = sequence("drag",240f,72f,true,true)
    @Test fun overlappingLobesOnOneFaceConnectAndDisconnectWithoutChangingTopologyAbruptly() {
        sequence("forked",240f,72f,true,false,regionFactory={ a,gap -> listOf(a,
            Rect(a.left,a.bottom+gap,a.left+160,a.bottom+gap+72),
            Rect(a.left+80,a.bottom+gap,a.right,a.bottom+gap+72)) })
    }
    @Test fun everyBrowseSizeConnectsAndDisconnectsUsingItsMeasuredOutline() {
        val styles=ListSize.entries.map { BrowseStyle(listSize=it) }+
            Presentation.entries.filter { it!=Presentation.List }.map { BrowseStyle(presentation=it) }
        for(style in styles) {
            val columns=when(style.presentation) { Presentation.List->1;Presentation.Compact->6;Presentation.Standard->4;Presentation.Large->2 }
            val width=if(columns==1)320 else BrowseCellGeometry(columns).artworkWidth(360,1f)
            val metrics=BrowseItemMetrics(style,columns,width,1f,style.listSize==ListSize.Tracklist)
            val information=min(60,metrics.informationLimit)
            val placement=metrics.place(20,information,16)
            val outline=metrics.outline(placement,width,metrics.titleWidth(information),20,information,16,
                artwork=true,rtl=false,density=1f)
            sequence("measured-${style.presentation}-${style.listSize}",outline.bounds.width,outline.bounds.height,
                columns==1,false,outline.corner,40)
        }
    }

    @Test fun twelveItemFlightAndLandingPreserveItemsAndReverseEveryFrame() {
        val frames=mutableListOf<IntArray>()
        var previous: IntArray?=null
        val report=StringBuilder("frame,progress,changedCoverage\n")
        for(frame in 0..240) {
            val step=if(frame<=120)frame else 240-frame
            val p=androidx.compose.animation.core.FastOutSlowInEasing.transform(step/120f)
            val regions=liftedFlightRegions(p,1f)
            val pixels=coverage(regions,1f) { a,b ->
                hoverBridgeStrength(abs(a%3-b%3)+abs(a/3-b/3)==1,false,p to p,0f to 0f,1f to 1f)
            }
            for(item in regions) {
                val c=item.bounds.center
                if(c.x in 0f..419f && c.y in 0f..279f)
                    assertTrue("No item interior disappears: frame=$frame",pixels[c.y.toInt()*420+c.x.toInt()] ushr 24==255)
            }
            val old=previous
            val changed=if(old==null)0.0 else pixels.indices.sumOf { abs((pixels[it] ushr 24)-(old[it] ushr 24))/255.0 }
            assertTrue("Flight frame=$frame changed=$changed",changed<2500)
            if(frame<=120)frames+=pixels else assertArrayEquals("Flight/landing reversibility",frames[step],pixels)
            if(frame%12==0) {
                val image=Bitmap.createBitmap(pixels,420,280,Bitmap.Config.ARGB_8888)
                File(output,"flight12-$frame-api${Build.VERSION.SDK_INT}.png").outputStream().use { image.compress(Bitmap.CompressFormat.PNG,100,it) }
                image.recycle()
            }
            report.append("$frame,$p,$changed\n");previous=pixels
        }
        File(output,"flight12-api${Build.VERSION.SDK_INT}.csv").writeText(report.toString())
    }

    private fun liftedFlightRegions(p: Float,density: Float): List<SelectionRegion> = List(12) { i ->
        val rest=MediaItemOutline(Rect(i%3*73f*density,i/3*73f*density,
            (i%3*73f+72f)*density,(i/3*73f+72f)*density),12f*density)
        val lifted=hoverGeometry(rest,Rect(1.125f*density,1.125f*density,1.125f*density,1.125f*density),1f).lifted
        val target=Rect((80f+min(i,2)*6f)*density,(70f+min(i,2)*6f)*density,
            (154.25f+min(i,2)*6f)*density,(144.25f+min(i,2)*6f)*density)
        fun mix(a: Float,b: Float)=a+(b-a)*p
        val a=lifted.bounds
        SelectionRegion(Rect(mix(a.left,target.left),mix(a.top,target.top),mix(a.right,target.right),mix(a.bottom,target.bottom)),lifted.corner)
    }

    @Test fun api26ChangingTwelveItemDragOutlineAndCacheCost() = benchmark("nearby-grid",false)
    @Test fun api26LiftedTwelveItemFlightOutlineAndCacheCost() = benchmark("lifted-flight",true)
    @Test fun api26MixedOpacityTwelveItemFlightOutlineAndCacheCost() = benchmark("mixed-opacity-flight",true,true)
    private fun benchmark(name: String,flight: Boolean,mixedOpacity: Boolean=false) {
        val profile = SelectionOutlineProfile()
        val cache = SelectionOpacityPathCache(profile)
        val density=InstrumentationRegistry.getInstrumentation().targetContext.resources.displayMetrics.density
        fun regions(frame: Int): List<SelectionRegion> = if(flight) liftedFlightRegions(
            androidx.compose.animation.core.FastOutSlowInEasing.transform((frame%100)/99f),density) else List(12) { i ->
            val dx = if (i == 5) sin(frame*.07f)*10f else 0f
            val dy = if (i == 5) cos(frame*.09f)*8f else 0f
            SelectionRegion(Rect(i%3*73f+dx,i/3*73f+dy,i%3*73f+72f+dx,i/3*73f+72f+dy),12f)
        }
        var lastProgress=0f
        fun strength(a: Int,b: Int): Float = if(!flight) lastProgress else
            hoverBridgeStrength(abs(a%3-b%3)+abs(a/3-b/3)==1,false,
                lastProgress to lastProgress,0f to 0f,1f to 1f)
        val gap=if(flight)16f*density else 16f
        // Preserve the absolute budget, but do not let one cold/noisy window own it.
        // Every window contains the same five complete motion cycles; use the median, never the best.
        val rounds = 5
        val framesPerRound = 500
        val warmup = 500
        val iterations = warmup + rounds * framesPerRound
        val changing = Array(rounds) { LongArray(framesPerRound) }
        val cpu = Array(rounds) { LongArray(framesPerRound) }
        val timings=StringBuilder("round,frame,progress,totalNs,cpuNs,arrangementNs,roundingNs,pathNs,fillets\n")
        var last = regions(0)
        var visibility=List(12) { 1f }
        for (i in 0 until iterations) {
            last = regions(i)
            lastProgress = if(flight) androidx.compose.animation.core.FastOutSlowInEasing.transform((i%100)/99f) else .2f+.6f*(i%100)/100f
            // A missing landing destination fades one card while eleven remain present.
            // This measures both full and persistent subset path builds in actual flight.
            visibility=List(12) { member -> if(mixedOpacity && member==5)1f-lastProgress else 1f }
            val arrangement=profile.arrangementNanos;val rounding=profile.roundingNanos
            val path=profile.pathNanos;val fillets=profile.curves
            val cpuStart = android.os.Debug.threadCpuTimeNanos()
            val start = SystemClock.elapsedRealtimeNanos()
            cache.paths(last,last,gap,visibility,strength=::strength)
            val elapsed=SystemClock.elapsedRealtimeNanos()-start
            val cpuElapsed=android.os.Debug.threadCpuTimeNanos()-cpuStart
            if (i >= warmup) {
                val round=(i-warmup)/framesPerRound
                val frame=(i-warmup)%framesPerRound
                changing[round][frame] = elapsed
                cpu[round][frame] = cpuElapsed
                timings.append("$round,$i,$lastProgress,$elapsed,$cpuElapsed,${profile.arrangementNanos-arrangement},${profile.roundingNanos-rounding},${profile.pathNanos-path},${profile.curves-fillets}\n")
            }
        }
        val cached = LongArray(500)
        cache.paths(last,last,gap,visibility,strength=::strength)
        val settledRebuilds = cache.rebuilds
        repeat(500) { i ->
            val start = SystemClock.elapsedRealtimeNanos()
            cache.paths(last,last,gap,visibility,strength=::strength)
            cached[i] = SystemClock.elapsedRealtimeNanos()-start
        }
        assertEquals("Unchanged inputs must reuse the contour",settledRebuilds,cache.rebuilds)
        changing.forEach { it.sort() }; cpu.forEach { it.sort() }; cached.sort()
        val medians=changing.map { it[framesPerRound/2] }
        val tails=changing.map { it[framesPerRound*95/100] }
        val median=medians.sorted()[rounds/2]
        val tail=tails.sorted()[rounds/2]
        val gated=Build.VERSION.SDK_INT==26 && InstrumentationRegistry.getArguments().getString("selectionOutlineTimingGate")!="false"
        val renderer=runCatching {
            val fd=InstrumentationRegistry.getInstrumentation().uiAutomation.executeShellCommand("dumpsys SurfaceFlinger")
            android.os.ParcelFileDescriptor.AutoCloseInputStream(fd).bufferedReader().use { reader ->
                reader.lineSequence().filter { it.contains("GLES:") || it.contains("GL_RENDERER") }.joinToString(" | ").trim()
            }
        }.getOrDefault("unavailable").ifBlank { "unavailable" }
        val report = "workload=$name api=${Build.VERSION.SDK_INT} device-density=$density geometry-density=${if(flight)density else 1f} items=12 opacity-levels=${if(mixedOpacity)2 else 1} gated=$gated renderer=$renderer rounds=$rounds frames-per-round=$framesPerRound aggregation=median-of-window-percentiles changing-p50-ns=$median changing-p95-ns=$tail changing-max-ns=${changing.maxOf { it.last() }} window-p50-ns=${medians.joinToString(",")} window-p95-ns=${tails.joinToString(",")} window-cpu-p50-ns=${cpu.joinToString(",") { it[framesPerRound/2].toString() }} cache-p50-ns=${cached[250]} cache-p95-ns=${cached[475]} rebuilds=${cache.rebuilds} geometry-mean-ns=${profile.evaluationNanos/iterations} primitives-per-frame=${profile.samples/iterations} segments-per-frame=${profile.cells/iterations} fillets-per-frame=${profile.curves/iterations} arrangement-mean-ns=${profile.arrangementNanos/iterations} rounding-mean-ns=${profile.roundingNanos/iterations} path-mean-ns=${profile.pathNanos/iterations}"
        File(output,"performance-$name-api${Build.VERSION.SDK_INT}.txt").writeText(report)
        File(output,"performance-$name-frames-api${Build.VERSION.SDK_INT}.csv").writeText(timings.toString())
        android.util.Log.i("SelectionOutlinePerformance",report)
        if(gated) {
            assertTrue(report,median <= 1_000_000)
            assertTrue(report,tail <= 2_000_000)
            assertTrue(report,cached[250] <= 1_000_000 && cached[475] <= 2_000_000)
        }
    }
}
