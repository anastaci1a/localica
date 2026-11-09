package io.github.anastaci1a.localica

import android.content.Context
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.rules.TestRule
import org.junit.runner.Description
import org.junit.runners.model.Statement

/**
 * Default preferences for a fixture, with exact restoration after its inner Compose rule disposes.
 * Device tests share one app process and data directory per shard, so a fixture that persists
 * preferences (for example a one-tab bar) must not leak them into later real-app tests (#722).
 */
internal class BrowsePreferenceIsolation(
    private val names: List<String> = listOf("browse-style", "library-query")
) : TestRule {
    override fun apply(base: Statement, description: Description): Statement = object : Statement() {
        override fun evaluate() {
            val context = InstrumentationRegistry.getInstrumentation().targetContext
            val stores = names.map { context.getSharedPreferences(it, Context.MODE_PRIVATE) }
            val saved = stores.map { store -> store.all.mapValues { (_, value) ->
                if (value is Set<*>) value.toSet() else value
            } }
            try {
                stores.zip(names).forEach { (store, name) -> check(store.edit().clear().commit()) { "Could not initialize $name preferences" } }
                base.evaluate()
            } finally {
                stores.zip(saved).zip(names).forEach { (pair, name) ->
                    val (store, values) = pair
                    val edit = store.edit().clear()
                    values.forEach { (key, value) -> when (value) {
                        is String -> edit.putString(key, value)
                        is Boolean -> edit.putBoolean(key, value)
                        is Int -> edit.putInt(key, value)
                        is Long -> edit.putLong(key, value)
                        is Float -> edit.putFloat(key, value)
                        is Set<*> -> edit.putStringSet(key, value.map { it as String }.toSet())
                        else -> error("Unsupported $name preference type for $key")
                    } }
                    check(edit.commit()) { "Could not restore $name preferences" }
                }
            }
        }
    }
}
