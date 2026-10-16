package io.github.anastaci1a.localica.ui

/** Diagnostic-only, disabled unless the original instrumentation test explicitly arms it.
 * One UI-thread writer; detach on that thread before reading. Never logs during input. */
internal object BufferedChoiceTrace {
    @Volatile var active: ChoiceTraceBuffer? = null
    const val DRAFT_CREATED = 1
    const val DRAFT_COMPOSED = 2
    const val YEAR_DOWN = 3
    const val YEAR_EVENT = 4
    const val YEAR_CLICK = 5
    const val DRAFT_ASSIGN = 6
    const val MORPH_COMPOSED = 7
    const val GATE_CONSUME = 8
}

/** Fixed capacity, first events retained; overflow makes absence of later events inconclusive. */
internal class ChoiceTraceBuffer(private val capacity: Int = 512) {
    private val codes = IntArray(capacity)
    private val identities = IntArray(capacity)
    private val values = IntArray(capacity)
    private val flags = IntArray(capacity)
    private val eventTimes = LongArray(capacity)
    private val recordedNanos = LongArray(capacity)
    private val xs = FloatArray(capacity)
    private val ys = FloatArray(capacity)
    var size: Int = 0; private set
    var dropped: Int = 0; private set
    val overflow: Boolean get() = dropped != 0

    fun record(code: Int, identity: Int = 0, value: Int = 0, bits: Int = 0,
        eventTime: Long = -1, x: Float = Float.NaN, y: Float = Float.NaN) {
        if (size == capacity) { dropped++; return }
        val index = size
        codes[index] = code; identities[index] = identity; values[index] = value
        flags[index] = bits; eventTimes[index] = eventTime; recordedNanos[index] = System.nanoTime()
        xs[index] = x; ys[index] = y
        size = index + 1
    }

    /** Call only after detaching the recorder. Allocation is deliberately confined to export. */
    fun export(): List<String> = buildList {
        val count = this@ChoiceTraceBuffer.size
        add("BUFFER version=1 count=$count capacity=$capacity overflow=$overflow dropped=$dropped")
        for (index in 0 until count) add("event=$index code=${codes[index]} identity=${identities[index]} " +
            "value=${values[index]} bits=${flags[index]} eventTime=${eventTimes[index]} " +
            "recordedNanos=${recordedNanos[index]} x=${xs[index]} y=${ys[index]}")
        add("BUFFER-END count=$count overflow=$overflow dropped=$dropped")
    }
}
