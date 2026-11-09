package io.github.anastaci1a.localica.ui.spec

/** An identity belongs to one native window's retained relocation owner. */
internal class NativeRelocationWindow

internal data class NativeRelocationFrame(val window: NativeRelocationWindow, val measure: Long,
    val nativeStep: Long, val focus: Long, val focused: Boolean,
    val currentIme: Int, val sourceIme: Int, val targetIme: Int) {
    init { require(measure > 0 && nativeStep >= 0 && focus >= 0)
        require(currentIme >= 0 && sourceIme >= 0 && targetIme >= 0) }
    val settled: Boolean get() = currentIme == targetIme && sourceIme == targetIme
}

internal data class NativeRelocationTarget(val top: Float, val bottom: Float) {
    init { require(top.isFinite() && bottom.isFinite() && bottom >= top) }
}

internal data class NativeRelocationPlacement(val frame: NativeRelocationFrame, val appliedScroll: Int,
    val viewport: Int, val target: NativeRelocationTarget?) {
    init { require(appliedScroll >= 0 && viewport >= 0) }
}

internal enum class NativeRelocationResult { Ordinary, Adjusting, AwaitingPlacement, Superseded, Unreachable, Disposed }
