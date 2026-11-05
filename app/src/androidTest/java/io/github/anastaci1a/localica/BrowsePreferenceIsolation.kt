package io.github.anastaci1a.localica

import android.content.Context
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.rules.TestRule
import org.junit.runner.Description
import org.junit.runners.model.Statement

/** Default catalog styling for a fixture, with exact restoration after its inner Compose rule disposes. */
internal class BrowsePreferenceIsolation : TestRule {
    override fun apply(base: Statement, description: Description): Statement = object : Statement() {
        override fun evaluate() {
            val context = InstrumentationRegistry.getInstrumentation().targetContext
            val stores = listOf("browse-style", "library-query").map { context.getSharedPreferences(it, Context.MODE_PRIVATE) }
            val saved = stores.map { store -> store.all.mapValues { (_, value) ->
                if (value is Set<*>) value.toSet() else value
            } }
            try {
                stores.forEach { check(it.edit().clear().commit()) { "Could not initialize browse preferences" } }
                base.evaluate()
            } finally {
                stores.zip(saved).forEach { (store, values) ->
                    val edit = store.edit().clear()
                    values.forEach { (key, value) -> when (value) {
                        is String -> edit.putString(key, value)
                        is Boolean -> edit.putBoolean(key, value)
                        is Int -> edit.putInt(key, value)
                        is Long -> edit.putLong(key, value)
                        is Float -> edit.putFloat(key, value)
                        is Set<*> -> edit.putStringSet(key, value.map { it as String }.toSet())
                        else -> error("Unsupported browse preference type for $key")
                    } }
                    check(edit.commit()) { "Could not restore browse preferences" }
                }
            }
        }
    }
}
