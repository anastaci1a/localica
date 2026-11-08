package io.github.anastaci1a.localica.ui.engine

import io.github.anastaci1a.localica.ui.spec.*
import kotlinx.coroutines.*
import kotlinx.coroutines.channels.Channel
import kotlin.math.roundToLong

internal class EngineHarness(access: MotionAccess=MotionAccess.Standard,spec: UiSpec=StockUiSpec) : AutoCloseable {
    var now=1_000_000_000L
    var awaits=0
    private val frames=Channel<Long>(Channel.UNLIMITED)
    val clock=UiClock(object: FrameSource { override suspend fun awaitFrame(): Long { awaits++; return frames.receive() } },{ now },{})
    private val scope=CoroutineScope(Dispatchers.Unconfined+SupervisorJob())
    init { clock.configure(EngineContext(1f/120,1000f,access),spec); scope.launch { clock.run() } }
    fun advance(seconds: Double) { now+=(seconds*1e9).roundToLong(); frames.trySend(now) }
    override fun close() { scope.cancel() }
}
