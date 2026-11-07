package io.github.anastaci1a.localica.ui

import androidx.compose.ui.geometry.Rect
import io.github.anastaci1a.localica.ui.engine.*
import org.junit.Assert.*
import org.junit.Test
import java.io.File
import kotlin.math.*

/** Supported host allocation counter for the exact analytic core, independent of native timing. */
class SelectionOutlineAllocationTest {
    @Test fun nearbyGridCoreAllocations() = measure("nearby-grid",false)
    @Test fun movingFlightCoreAllocations() = measure("lifted-flight",true)
    private fun measure(name: String,flight: Boolean) {
        val bean=Class.forName("java.lang.management.ManagementFactory").getMethod("getThreadMXBean").invoke(null)
        val api=Class.forName("com.sun.management.ThreadMXBean")
        check(api.getMethod("isThreadAllocatedMemorySupported").invoke(bean)==true)
        api.getMethod("setThreadAllocatedMemoryEnabled",Boolean::class.javaPrimitiveType).invoke(bean,true)
        val counter=api.getMethod("getThreadAllocatedBytes",Long::class.javaPrimitiveType)
        val thread=Thread.currentThread().id
        fun allocated()=counter.invoke(bean,thread) as Long
        repeat(64) { allocated() }
        val model=AnalyticSelectionOutline()
        fun frame(index: Int) {
            val p=(index%100)/99f
            val regions=List(12) { i ->
                val x=i%3*73f;val y=i/3*73f
                val dx=if(!flight && i==5)sin(index*.07f)*10f else 0f
                val dy=if(!flight && i==5)cos(index*.09f)*8f else 0f
                val tx=if(flight)x+(80f+min(i,2)*6f-x)*p else x+dx
                val ty=if(flight)y+(70f+min(i,2)*6f-y)*p else y+dy
                SelectionRegion(Rect(tx,ty,tx+72f,ty+72f),12f)
            }
            blackHole=model.inspect(regions,16f) { _,_->if(flight)1f-p else .2f+.6f*p }
        }
        repeat(300) { frame(it) }
        val before=allocated();repeat(300) { frame(it+300) };val bytes=allocated()-before
        assertTrue(bytes>0)
        // Keep headroom for host JIT variation while catching the original grid allocation cost.
        assertTrue("$name allocated ${bytes/300.0} bytes/frame",bytes/300.0 < if(flight)450_000.0 else 170_000.0)
        val output=File("build/reports/selection-outline-allocation").apply { mkdirs() }
        File(output,"$name.json").writeText("{\"workload\":\"$name\",\"frames\":300,\"bytes\":$bytes,\"bytesPerFrame\":${bytes/300.0},\"scope\":\"analytic inspect; no native path emission\"}\n")
    }
    companion object { @Volatile private var blackHole: Any?=null }
}
