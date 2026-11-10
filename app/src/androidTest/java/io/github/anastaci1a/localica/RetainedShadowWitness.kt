package io.github.anastaci1a.localica

import androidx.compose.runtime.State
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.ComposeTestRule
import androidx.test.platform.app.InstrumentationRegistry
import io.github.anastaci1a.localica.ui.RetainedGlassShadow
import java.io.File
import java.lang.reflect.Modifier as JavaModifier
import kotlinx.coroutines.Job
import org.json.JSONArray
import org.json.JSONObject

private fun readShadowField(value: Any, name: String): Any? {
    var type: Class<*>? = value.javaClass
    while (type != null) {
        type.declaredFields.firstOrNull { it.name == name || it.name.startsWith("$name\$") }?.let {
            it.isAccessible = true; return it.get(value)
        }
        type = type.superclass
    }
    return null
}
private fun retainedShadowFor(layout: Any): RetainedGlassShadow? {
    var current: Any? = layout
    repeat(16) {
        val owner = current ?: return null
        var node = readShadowField(requireNotNull(readShadowField(owner, "nodes")), "head")
        while (node != null) {
            var type: Class<*>? = node.javaClass
            while (type != null) {
                for (field in type.declaredFields.filterNot { JavaModifier.isStatic(it.modifiers) }) {
                    field.isAccessible = true
                    val callback = field.get(node)
                    if (callback is Function<*>) for (capture in callback.javaClass.declaredFields.filterNot { JavaModifier.isStatic(it.modifiers) }) {
                        capture.isAccessible = true
                        (capture.get(callback) as? RetainedGlassShadow)?.let { return it }
                    }
                }
                type = type.superclass
            }
            node = readShadowField(node, "child")
        }
        val getter = owner.javaClass.methods.firstOrNull { it.name.startsWith("getParentInfo") && it.parameterCount == 0 }
        current = getter?.invoke(owner)
    }
    return null
}

/** Read-only witness of completed preparation; never uses pixels to select a completion time. */
internal fun ComposeTestRule.awaitRetainedShadows(label: String, vararg tags: String): List<JSONObject> {
    var result = emptyList<JSONObject>()
    waitUntil(10000) {
        val nodes = tags.map { onNodeWithTag(it, useUnmergedTree = true).fetchSemanticsNode() }
        result = runOnIdle {
            nodes.map { node ->
                val cache = retainedShadowFor(node.layoutInfo)
                val rasterState = cache?.let { readShadowField(it, "raster") as? State<*> }
                val raster = rasterState?.value
                val requested = cache?.let { readShadowField(it, "requested") }
                val rasterKey = raster?.let { readShadowField(it, "key") }
                val job = cache?.let { readShadowField(it, "preparation") as? Job }
                JSONObject().put("cache", cache?.let(System::identityHashCode) ?: 0)
                    .put("rasterizations", cache?.rasterizations ?: 0)
                    .put("jobComplete", job?.isCompleted == true)
                    .put("preparedKeyMatchesRequested", raster != null && requested == rasterKey)
            }
        }
        result.size == tags.size && result.map { it.getInt("cache") }.toSet().size == tags.size && result.all { it.getInt("rasterizations") > 0 && it.getBoolean("jobComplete") && it.getBoolean("preparedKeyMatchesRequested") }
    }
    mainClock.advanceTimeByFrame()
    waitForIdle()
    val frame = java.util.concurrent.CountDownLatch(1)
    InstrumentationRegistry.getInstrumentation().runOnMainSync {
        android.view.Choreographer.getInstance().postFrameCallback {
            android.os.Handler(android.os.Looper.getMainLooper()).post { frame.countDown() }
        }
    }
    check(frame.await(5, java.util.concurrent.TimeUnit.SECONDS)) { "No rendered frame after shadow preparation" }
    val context = InstrumentationRegistry.getInstrumentation().targetContext
    val directory = nativeEvidenceDirectory("settled-shadow-witness", context.getExternalFilesDir(null))
    File(directory, "$label-api${android.os.Build.VERSION.SDK_INT}.json").writeText(JSONObject()
        .put("label", label).put("preparedShadows", JSONArray(result))
        .put("nativeFrameAfterCompletion", true).toString(2))
    return result
}

