package io.github.anastaci1a.localica.navigation

import android.content.ContextWrapper
import android.content.SharedPreferences
import org.junit.Assert.*
import org.junit.Test

/**
 * #860 and #862 S2: the animation-speed and respect-system-scale keys round-trip, "faster-motion" migrates, the downgrade key is written and
 * stored values are repaired. No test under app/src/test had a SharedPreferences, so this one fakes the store (an in-memory map) and hands it to the real
 * [InterfacePreferences] through a ContextWrapper (the unit-test android.jar keeps constructors callable).
 */
class AnimationSpeedPersistenceTest {
    private class FakePreferences(val values: MutableMap<String, Any?> = mutableMapOf()) : SharedPreferences {
        private fun name(key: String?): String = requireNotNull(key)
        override fun getAll(): MutableMap<String, *> = values.toMutableMap()
        override fun getString(key: String?, defValue: String?): String? = if (values.containsKey(name(key))) values[name(key)] as String? else defValue
        override fun getStringSet(key: String?, defValues: MutableSet<String>?): MutableSet<String>? = defValues
        override fun getInt(key: String?, defValue: Int): Int = if (values.containsKey(name(key))) values[name(key)] as Int else defValue
        override fun getLong(key: String?, defValue: Long): Long = if (values.containsKey(name(key))) values[name(key)] as Long else defValue
        override fun getFloat(key: String?, defValue: Float): Float = if (values.containsKey(name(key))) values[name(key)] as Float else defValue
        override fun getBoolean(key: String?, defValue: Boolean): Boolean = if (values.containsKey(name(key))) values[name(key)] as Boolean else defValue
        override fun contains(key: String?): Boolean = values.containsKey(name(key))
        override fun edit(): SharedPreferences.Editor = FakeEditor(values)
        override fun registerOnSharedPreferenceChangeListener(listener: SharedPreferences.OnSharedPreferenceChangeListener?) {}
        override fun unregisterOnSharedPreferenceChangeListener(listener: SharedPreferences.OnSharedPreferenceChangeListener?) {}
    }
    /** Stages edits and applies them on commit or apply, as SharedPreferences does. */
    private class FakeEditor(private val target: MutableMap<String, Any?>) : SharedPreferences.Editor {
        private val staged = linkedMapOf<String, Any?>(); private val removed = mutableSetOf<String>(); private var cleared = false
        private fun stage(key: String?, value: Any?): SharedPreferences.Editor { staged[requireNotNull(key)] = value; removed.remove(key); return this }
        override fun putString(key: String?, value: String?): SharedPreferences.Editor = stage(key, value)
        override fun putStringSet(key: String?, values: MutableSet<String>?): SharedPreferences.Editor = stage(key, values)
        override fun putInt(key: String?, value: Int): SharedPreferences.Editor = stage(key, value)
        override fun putLong(key: String?, value: Long): SharedPreferences.Editor = stage(key, value)
        override fun putFloat(key: String?, value: Float): SharedPreferences.Editor = stage(key, value)
        override fun putBoolean(key: String?, value: Boolean): SharedPreferences.Editor = stage(key, value)
        override fun remove(key: String?): SharedPreferences.Editor { staged.remove(requireNotNull(key)); removed.add(key); return this }
        override fun clear(): SharedPreferences.Editor { cleared = true; return this }
        override fun commit(): Boolean { apply(); return true }
        override fun apply() { if (cleared) target.clear(); for (key in removed) target.remove(key); target.putAll(staged) }
    }
    private fun preferencesOver(store: FakePreferences) = InterfacePreferences(object : ContextWrapper(null) {
        override fun getSharedPreferences(name: String?, mode: Int): SharedPreferences = store
    })

    @Test fun defaultsAreNormalSpeedAndRespectOn() {
        val read = preferencesOver(FakePreferences()).read()
        assertEquals(10, read.animationSpeedTenths); assertTrue(read.respectSystemAnimationScale); assertEquals(1f, read.animationSpeed, 0f)
    }

    @Test fun bothKeysRoundTrip() {
        for (tenths in MinimumAnimationSpeedTenths..MaximumAnimationSpeedTenths) for (respect in listOf(true, false)) {
            val store = FakePreferences(); val preferences = preferencesOver(store)
            preferences.write(InterfaceSettings(animationSpeedTenths = tenths, respectSystemAnimationScale = respect))
            assertEquals(tenths, store.values["animation-speed-tenths"]); assertEquals(respect, store.values["respect-system-animation-scale"])
            val read = preferences.read()
            assertEquals(tenths, read.animationSpeedTenths); assertEquals(respect, read.respectSystemAnimationScale); assertEquals(tenths / 10f, read.animationSpeed, 0f)
            assertEquals("A second round trip changes nothing", read, preferencesOver(store).read())
        }
    }

    @Test fun fasterMotionMigratesToTheNewKeyUntilItIsWritten() {
        assertEquals(FasterAnimationSpeedTenths, preferencesOver(FakePreferences(mutableMapOf("faster-motion" to true))).read().animationSpeedTenths)
        assertEquals(15, FasterAnimationSpeedTenths); assertEquals(10, preferencesOver(FakePreferences(mutableMapOf("faster-motion" to false))).read().animationSpeedTenths)
        // The new key wins over the old one once it exists.
        assertEquals(7, preferencesOver(FakePreferences(mutableMapOf("faster-motion" to true, "animation-speed-tenths" to 7))).read().animationSpeedTenths)
        // The next save writes the migrated value to the new key.
        val store = FakePreferences(mutableMapOf("faster-motion" to true)); val preferences = preferencesOver(store)
        assertFalse(store.contains("animation-speed-tenths"))
        preferences.write(preferences.read())
        assertEquals(15, store.values["animation-speed-tenths"]); assertEquals(true, store.values["faster-motion"])
        assertTrue("The respect key defaults on for an upgrading user", preferencesOver(FakePreferences(mutableMapOf("faster-motion" to true))).read().respectSystemAnimationScale)
    }

    @Test fun downgradeKeyKeepsTheClosestOldState() {
        for (tenths in 5..15) {
            val store = FakePreferences(); preferencesOver(store).write(InterfaceSettings(animationSpeedTenths = tenths))
            assertEquals("tenths $tenths", tenths >= FasterAnimationSpeedTenths, store.values["faster-motion"])
        }
    }

    @Test fun outOfRangeStoredValuesAreRepairedToTheNearestStep() {
        for ((stored, repaired) in listOf(-5 to 5, 0 to 5, 4 to 5, 16 to 15, 99 to 15, Int.MAX_VALUE to 15, Int.MIN_VALUE to 5)) {
            val read = preferencesOver(FakePreferences(mutableMapOf("animation-speed-tenths" to stored))).read()
            assertEquals("stored $stored", repaired, read.animationSpeedTenths); assertEquals(repaired / 10f, read.animationSpeed, 0f)
        }
        // Writing an out-of-range setting stores the repaired value, and the downgrade key follows the repaired value.
        val store = FakePreferences(); preferencesOver(store).write(InterfaceSettings(animationSpeedTenths = 99))
        assertEquals(15, store.values["animation-speed-tenths"]); assertEquals(true, store.values["faster-motion"])
        preferencesOver(store).write(InterfaceSettings(animationSpeedTenths = 0))
        assertEquals(5, store.values["animation-speed-tenths"]); assertEquals(false, store.values["faster-motion"])
    }

    @Test fun otherSettingsAreUntouchedByTheNewKeys() {
        val store = FakePreferences(); val preferences = preferencesOver(store)
        val settings = InterfaceSettings(reducedMotion = true, animationSpeedTenths = 8, respectSystemAnimationScale = false, lyricDelayMs = 120)
        preferences.write(settings)
        val read = preferences.read()
        assertEquals(settings.reducedMotion, read.reducedMotion); assertEquals(settings.lyricDelayMs, read.lyricDelayMs)
        assertEquals(8, read.animationSpeedTenths); assertFalse(read.respectSystemAnimationScale)
    }
}
