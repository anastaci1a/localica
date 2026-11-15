package io.github.anastaci1a.localica.benchmark

import android.graphics.Point
import android.os.ParcelFileDescriptor
import androidx.benchmark.macro.MacrobenchmarkScope
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.uiautomator.By
import androidx.test.uiautomator.Condition
import androidx.test.uiautomator.UiDevice
import androidx.test.uiautomator.UiObject2
import androidx.test.uiautomator.Until
import org.json.JSONArray
import org.json.JSONObject

internal const val PACKAGE = "io.github.anastaci1a.localica.debug"

/** #971 protocol consumer. Input stays in the original calibrated shell drivers. */
internal class Journeys {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val context = instrumentation.context
    private val device = UiDevice.getInstance(instrumentation)
    private val protocol = JSONObject(asset("scenarios.json"))
    val scenarios = protocol.getJSONArray("scenarios").let { array ->
        List(array.length()) { array.getJSONObject(it) }
    }
    private var target: Point? = null
    private val classpath = context.applicationInfo.sourceDir
    private fun asset(name: String) = context.assets.open(name).bufferedReader().use { it.readText() }
    private fun shell(command: String): String {
        val pipes = instrumentation.uiAutomation.executeShellCommandRw("sh")
        ParcelFileDescriptor.AutoCloseOutputStream(pipes[1]).use {
            it.write("exec 2>&1\nset -e\n$command\n".toByteArray())
        }
        return ParcelFileDescriptor.AutoCloseInputStream(pipes[0]).bufferedReader().use { it.readText() }
    }
    private fun node(label: String): UiObject2 = checkNotNull(
        device.wait(Condition<UiDevice, UiObject2?> { d ->
            d.findObject(By.desc(label)) ?: d.findObject(By.text(label))
                ?: if (label == "Expand player") d.findObject(By.text("Scale song 13390")) else null
        }, 10_000)
    ) { "Missing release accessibility label: $label" }
    private fun tap(label: String) { node(label).click(); Thread.sleep(400) }
    private fun scaleX(x: Int) = x * device.displayWidth / 1080
    private fun scaleY(y: Int) = y * device.displayHeight / 2340

    fun prepare(scope: MacrobenchmarkScope, scenario: JSONObject) {
        scope.killProcess()
        scope.startActivityAndWait()
        check(device.wait(Until.hasObject(By.textStartsWith("Scale song")), 120_000)) {
            "13,400-track fixture did not restore"
        }
        tap("Songs")
        tap("View options")
        tap(if (scenario.getString("view") == "gallery") "Gallery" else "List")
        if (scenario.getString("view") == "gallery") node("Items per row: 2")
        else {
            val size = node("Size").visibleBounds
            val value = checkNotNull(device.findObjects(By.textMatches(".+")).firstOrNull {
                val bounds = it.visibleBounds
                bounds.left == size.left && bounds.centerY() > size.centerY() && bounds.centerY() < size.centerY() + scaleY(120)
            }) { "Missing list size value" }
            if (value.text != "Normal") { value.click(); tap("Normal"); tap("Select") }
        }
        tap("Save")
        val preparation = scenario.getString("prepare")
        if (preparation in listOf("player", "mini")) {
            node("Expand player").click()
            node("Show lyrics")
            if (preparation == "mini") { device.pressBack(); Thread.sleep(900) }
        }
        if (preparation == "search") {
            tap("Search library")
            shell("input keycombination 113 29; input keyevent 67")
            Thread.sleep(300)
        }
        target = when {
            scenario.has("target") -> node(scenario.getString("target")).visibleCenter
            preparation == "mini" -> node("Expand player").visibleCenter
            else -> null
        }
        if (preparation == "cold") scope.killProcess()
        Thread.sleep(800)
    }

    fun run(scope: MacrobenchmarkScope, scenario: JSONObject) {
        actions(scope, scenario.getJSONArray("actions"))
        Thread.sleep(protocol.getLong("settleMs"))
    }

    private fun actions(scope: MacrobenchmarkScope, steps: JSONArray) {
        repeat(steps.length()) { index ->
            val step = steps.getJSONObject(index)
            when {
                step.has("repeat") -> repeat(step.getInt("repeat")) { actions(scope, step.getJSONArray("steps")) }
                step.has("waitMs") -> Thread.sleep(step.getLong("waitMs"))
                step.has("back") -> shell("input keyevent KEYCODE_BACK")
                step.has("launch") -> scope.startActivityAndWait()
                step.has("tap") -> checkNotNull(target).let { point ->
                    timeline("A35InputTimeline", "0,DOWN,${point.x},${point.y}\n40,UP,${point.x},${point.y}\n")
                }
                step.has("gesture") -> {
                    val g = step.getJSONObject("gesture")
                    val a = g.getJSONArray("from"); val b = g.getJSONArray("to")
                    val duration = g.getInt("durationMs")
                    fun row(at: Int, action: String, fraction: Float) =
                        "$at,$action,${scaleX((a.getInt(0) + (b.getInt(0) - a.getInt(0)) * fraction).toInt())}," +
                            "${scaleY((a.getInt(1) + (b.getInt(1) - a.getInt(1)) * fraction).toInt())}\n"
                    val csv = buildString {
                        append(row(0, "DOWN", 0f))
                        for (t in 16 until duration step 16) append(row(t, "MOVE", t.toFloat() / duration))
                        append(row(duration, "MOVE", 1f))
                        append(row(duration + g.getInt("holdMs") + 16, "UP", 1f))
                    }
                    timeline("A35InputTimeline", csv)
                }
                step.has("type") -> step.getString("type").forEach { char ->
                    shell("input text ${if (char == ' ') "%s" else char}")
                    Thread.sleep(step.getLong("intervalMs"))
                }
                step.has("pinch") -> {
                    val csv = asset(step.getString("pinch")).lineSequence().filter { it.isNotBlank() }.joinToString("\n") {
                        val p = it.split(',').toMutableList()
                        p[3] = (p[3].toInt() * device.displayWidth / 1440).toString()
                        p[4] = scaleY(1200).toString()
                        p.joinToString(",")
                    } + "\n"
                    timeline("S20MultiTouch", csv)
                }
                else -> error("Unknown protocol step: $step")
            }
        }
    }

    private fun timeline(driver: String, csv: String) {
        val file = "/data/local/tmp/localica-986-input.csv"
        shell("printf '$csv' > $file")
        val receipt = shell("CLASSPATH=$classpath app_process /system/bin $driver $file")
        check(!receipt.contains("false") && !receipt.contains("Exception")) { receipt }
        check(receipt.contains("EVENT,") || receipt.contains("late_us=")) { "Input driver did not acknowledge events: $receipt" }
    }

    fun verify(scenario: JSONObject) {
        when (scenario.getString("id")) {
            "pinch-view-change" -> { tap("View options"); node("Large"); tap("Cancel") }
            "search-open" -> check(device.hasObject(By.clazz("android.widget.EditText")))
            else -> if (scenario.has("witness")) node(scenario.getString("witness"))
        }
    }
}
