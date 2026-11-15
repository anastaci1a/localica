package io.github.anastaci1a.localica

import android.os.Looper
import android.view.animation.AnimationUtils
import androidx.test.platform.app.InstrumentationRegistry
import io.github.anastaci1a.localica.ui.GlassTransitionReceipt
import io.github.anastaci1a.localica.ui.glassDrawProbe
import org.junit.runner.Description
import org.junit.runner.Result
import org.junit.runner.notification.RunListener
import java.io.File
import java.util.concurrent.LinkedBlockingQueue
import java.util.zip.GZIPOutputStream

/**
 * #920 step 3 glass-input receipt recorder (instrumentation argument `listener`). Writes every glass draw receipt, in order,
 * to glass-receipts/<Class#method>.jsonl.gz, with `frame` = the ordinal of the vsync time Choreographer locks for the whole
 * frame. Transition receipts from the popup writer (candidate only) are written as "state" markers that never shift frame or order.
 * It registers no snapshot observers: they hung the gate (#920 bisect). Records only; never fails or waits on a test. Serialization runs off the UI thread.
 */
class GlassReceiptListener : RunListener() {
    private class Item(val test: String?, val frame: Int, val order: Long, val kind: String, val value: Any?)
    private val queue = LinkedBlockingQueue<Item>()
    private val end = Item(null, 0, 0, "end", null)
    private var frame = 0; private var vsync = Long.MIN_VALUE; private var order = 0L
    private val writer = Thread({ write() }, "glass-receipts")

    override fun testRunStarted(description: Description) {
        writer.start()
        glassDrawProbe = { enqueue(if (it is GlassTransitionReceipt) "state" else "draw", it) }
    }

    override fun testStarted(description: Description) = synchronized(this) {
        frame = 0; vsync = Long.MIN_VALUE; order = 0L
        queue.put(Item("${description.className.substringAfterLast('.')}#${description.methodName}", 0, 0, "start", null))
    }

    override fun testFinished(description: Description) = queue.put(Item(null, 0, 0, "finish", null))

    override fun testRunFinished(result: Result) {
        glassDrawProbe = null
        queue.put(end)
        writer.join(60_000)
    }

    /** Only draws advance frame and order, so markers (3a only) never shift the compared draw stream; a marker sits after draw order-1. */
    private fun enqueue(kind: String, value: Any?) = synchronized(this) {
        val draw = kind == "draw"
        if (draw && Looper.myLooper() == Looper.getMainLooper()) AnimationUtils.currentAnimationTimeMillis().let { if (it != vsync) { vsync = it; frame++ } }
        queue.put(Item(null, frame, if (draw) order++ else order, kind, value))
    }

    private fun write() {
        val root = File(InstrumentationRegistry.getArguments().getString("additionalTestOutputDir")
            ?: InstrumentationRegistry.getInstrumentation().targetContext.filesDir.path, "glass-receipts").apply { mkdirs() }
        var out: java.io.Writer? = null
        while (true) {
            val item = queue.take()
            when (item.kind) {
                "end" -> { out?.close(); return }
                "start" -> { out?.close(); out = GZIPOutputStream(File(root, "${item.test!!.replace('/', '_')}.jsonl.gz").outputStream()).bufferedWriter() }
                "finish" -> { out?.close(); out = null }
                else -> out?.apply {
                    val line = StringBuilder().append("{\"frame\":").append(item.frame).append(",\"order\":").append(item.order)
                        .append(",\"kind\":\"").append(item.kind).append("\",\"v\":")
                    json(line, item.value); write(line.append("}\n").toString())
                }
            }
        }
    }

    /** Data classes become objects ({"@": class, fields in declaration order}); identities stay raw hashes (the comparator ordinalizes). */
    private fun json(b: StringBuilder, v: Any?) {
        when (v) {
            null -> b.append("null")
            is Boolean, is Int, is Long -> b.append(v)
            is Float -> if (v.isFinite()) b.append(v) else b.append('"').append(v).append('"')
            is Double -> if (v.isFinite()) b.append(v) else b.append('"').append(v).append('"')
            is String -> b.append('"').append(v.replace("\\", "\\\\").replace("\"", "\\\"")).append('"')
            is List<*> -> { b.append('['); v.forEachIndexed { i, e -> if (i > 0) b.append(','); json(b, e) }; b.append(']') }
            else -> {
                b.append("{\"@\":\"").append(v.javaClass.simpleName).append('"')
                v.javaClass.declaredFields.filter { !java.lang.reflect.Modifier.isStatic(it.modifiers) && !it.isSynthetic }.forEach {
                    it.isAccessible = true; b.append(",\"").append(it.name).append("\":"); json(b, it.get(v))
                }
                b.append('}')
            }
        }
    }
}
