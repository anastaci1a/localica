package io.github.anastaci1a.localica.ui.engine

import androidx.compose.ui.geometry.Rect
import io.github.anastaci1a.localica.ui.spec.*
import org.junit.Assert.*
import org.junit.Test
import java.io.File
import java.util.Random
import kotlin.math.*

/** Independent Double equations, bisection inversion, and a public synthetic corpus only. */
internal object ReferenceExpansion {
    fun bezier(u: Double,derivative: Boolean=false): Double {
        if(u<=0) return if(derivative) .15/.15 else 0.0
        if(u>=1) return if(derivative) 0.0 else 1.0
        var low=0.0; var high=1.0
        repeat(64) { val t=(low+high)/2; val s=1-t; val x=3*s*s*t*.15+3*s*t*t*.5+t*t*t; if(x<u) low=t else high=t }
        val t=(low+high)/2; val s=1-t
        return if(derivative) (s*s*.15+2*s*t*(1.0-.15)+t*t*(1-1.0))/(s*s*.15+2*s*t*(.5-.15)+t*t*(1-.5)) else 3*s*s*t*.15+3*s*t*t*1.0+t*t*t
    }
    fun duration(p: Double,v: Double,q: Double,sigma: Double=1.0,hz: Int=120): Double = max(sigma*.36*(.500401615+.499598385*abs(q-p))/max(1.0,abs(v*sigma)/1.41952128).pow(.73607278),2.0/hz)
    fun sample(p: Double,v: Double,q: Double,T: Double,t: Double,terminal: Boolean=true,sigma: Double=1.0): Double {
        if(t<=0) return p; if(t>=T) return q
        val d=q-p; val B=abs(d)/T*1.6330212; val room=if(v>0) 1.05-p else p+.05
        val start=minOf(sigma/120,T/4,if(v==0.0) Double.POSITIVE_INFINITY else room/(.149*(abs(v)+B)+if(v*d>0) B else 0.0)); val end=min(T/8,sigma*.064)
        fun h(a: Double,va: Double,b: Double,vb: Double,tau: Double,z: Double): Double { val u=z/tau; return (2*u*u*u-3*u*u+1)*a+(u*u*u-2*u*u+u)*tau*va+(-2*u*u*u+3*u*u)*b+(u*u*u-u*u)*tau*vb }
        return when {
            t<start -> h(p,v,p+d*bezier(start/T),d*bezier(start/T,true)/T,start,t)
            terminal && t>=T-end -> h(p+d*bezier(1-end/T),d*bezier(1-end/T,true)/T,q,0.0,end,t-T+end)
            else -> p+d*bezier(t/T)
        }
    }
}
class ExpansionReplayTest {
    @Test fun deterministicPublicTwentyEightEventReplayMatchesDoubleOracle() {
        val random=Random(600204)
        repeat(28) { index ->
            val p=when(index%4) { 0 -> 0.0; 1 -> 1.0; else -> random.nextDouble() }
            val v=if(index%4<2) 0.0 else (random.nextDouble()-.5)*50
            EngineHarness().use { h ->
                val c=controller(h,p)
                val decision=c.contactRelease((v*1000).toFloat(),h.clock.stampNow); val q=if(decision.expanded) 1.0 else 0.0
                val T=ReferenceExpansion.duration(p,v,q)
                if(p!=q || v!=0.0) assertEquals(T,decision.durationSeconds.toDouble(),1e-7) else assertEquals(0f,decision.durationSeconds,0f)
                for(j in 0..100) { val t=T*j/100; val expected=ReferenceExpansion.sample(p,v,q,T,t); val stamp=FrameStamp(h.now+(t*1e9).roundToLong(),0); assertEquals("synthetic$index frame$j",expected,c.phase.valueAt(stamp).toDouble(),1e-5) }
                c.dispose()
            }
        }
    }
    private fun controller(h: EngineHarness,p: Double): ExpandCollapse {
        val c=ExpandCollapse(h.clock,StockUiSpec.expansion,false)
        c.setBasis(ExpansionBasis(Rect(0f,0f,100f,100f),Rect(0f,0f,1100f,100f),1f))
        c.contactBegin(h.clock.stampNow); c.contactMove((p*1000).toFloat(),h.clock.stampNow)
        return c
    }
    /** Invoked only by an external local JUnit source set; private recordings never enter Git/CI. */
    fun recordedCorpus(path: File) {
        val grouped=path.readLines().drop(1).filter { it.isNotBlank() }.map { it.split('\t') }.groupBy { it[0] }
        assertEquals(28,grouped.size); val active=mutableListOf<Double>(); val full=mutableListOf<Double>(); val oldActive=mutableListOf<Double>(); val allCases=mutableListOf<Double>(); val oldCases=mutableListOf<Double>(); var maxDeviation=0.0; var missingPointer=0
        for((id,rows) in grouped) {
            val first=rows.first(); val recording=first[1].toInt(); val p=first[2].toDouble(); val v=first[3].toDouble(); val q=first[4].toDouble(); val onset=first[5].toDouble(); val errors=mutableListOf<Double>(); val oldErrors=mutableListOf<Double>()
            EngineHarness().use { h ->
                val c=controller(h,p)
                if(recording==0) { c.target(q==1.0); c.contactCancel() } else assertEquals(id,q==1.0,c.contactRelease((v*1000).toFloat(),h.clock.stampNow).expanded)
                val T=ReferenceExpansion.duration(p,v,q)
                for(row in rows) {
                    val relative=row[6].toDouble(); val observed=row[7].toDouble(); val isActive=row[8].toBoolean(); val pts=row[9].toDouble(); val release=row[10].toDouble(); assertEquals(relative,pts-release,1e-10)
                    if(row[11]=="nan") missingPointer++
                    val t=relative-onset; val actual=c.phase.valueAt(FrameStamp(h.now+(t*1e9).roundToLong(),0)).toDouble()
                    val oracle=ReferenceExpansion.sample(p,v,q,T,t); val startup=ReferenceExpansion.sample(p,v,q,T,t,false)
                    assertEquals("$id PTS$pts",oracle,actual,1e-5); maxDeviation=max(maxDeviation,abs(oracle-startup))
                    if(observed.isFinite()) { val error=(actual-observed)*2014; full.add(error); if(isActive) { active.add(error); oldActive.add((startup-observed)*2014); oldErrors.add((startup-observed)*2014); errors.add(error) } }
                }
                c.dispose()
            }
            allCases.add(rms(errors)); oldCases.add(rms(oldErrors))
        }
        println("Recorded28 events: terminal active=${rms(active)}px full=${rms(full)}px startup active=${rms(oldActive)}px maxCase=${allCases.max()}px terminalDeviation=$maxDeviation missingPointers=$missingPointer")
        assertTrue(rms(oldActive)<=18); assertTrue(oldCases.max()<=62); assertTrue(rms(active)<=rms(oldActive)+2); assertTrue(maxDeviation<=.001); assertEquals(1216,missingPointer)
    }
    private fun rms(values: List<Double>)=sqrt(values.sumOf { it*it }/values.size)
}
