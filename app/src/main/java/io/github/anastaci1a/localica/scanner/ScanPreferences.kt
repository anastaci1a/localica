package io.github.anastaci1a.localica.scanner

import android.content.Context
import io.github.anastaci1a.localica.library.ScanOptions

class ScanPreferences(context: Context) {
    private val store = context.getSharedPreferences("scanner", 0)
    var scanOnLaunch: Boolean
        get() = store.getBoolean("on-launch", false)
        set(value) { store.edit().putBoolean("on-launch", value).apply() }
    var fullScanOnLaunch: Boolean
        get() = store.getBoolean("full-on-launch", false)
        set(value) { store.edit().putBoolean("full-on-launch", value).apply() }
    fun read(root: String) = ScanOptions(store.getBoolean("subfolders", true), store.getInt("minimum-seconds", 0),
        store.getStringSet("excluded:$root", emptySet()).orEmpty().toSet())
    fun write(options: ScanOptions) { store.edit().putBoolean("subfolders", options.includeSubfolders)
        .putInt("minimum-seconds", options.minimumDurationSeconds.coerceIn(0, 3600)).apply() }
    fun excluded(root: String, paths: Set<String>) { store.edit().putStringSet("excluded:$root", paths).apply() }
}

