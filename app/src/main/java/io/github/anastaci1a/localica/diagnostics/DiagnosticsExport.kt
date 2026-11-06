package io.github.anastaci1a.localica.diagnostics

import android.content.ClipData
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import androidx.core.content.FileProvider
import java.io.File
import java.time.Instant
import java.time.ZoneId

internal data class DiagnosticsEnvironment(val appId: String, val versionName: String, val versionCode: Long, val debuggable: Boolean,
    val manufacturer: String, val model: String, val androidRelease: String, val sdk: Int)

/** Builds the export, writes it under the app cache and makes the share intent. Nothing is sent by the app. */
internal object DiagnosticsExport {
    const val DirectoryName = "diagnostics"
    fun authority(context: Context) = "${context.packageName}.diagnostics"

    fun environment(context: Context): DiagnosticsEnvironment {
        val info = context.packageManager.getPackageInfo(context.packageName, 0)
        val code = if (Build.VERSION.SDK_INT >= 28) info.longVersionCode else @Suppress("DEPRECATION") info.versionCode.toLong()
        return DiagnosticsEnvironment(context.packageName, info.versionName ?: "?", code,
            context.applicationInfo.flags and android.content.pm.ApplicationInfo.FLAG_DEBUGGABLE != 0,
            Build.MANUFACTURER, Build.MODEL, Build.VERSION.RELEASE, Build.VERSION.SDK_INT)
    }

    fun report(environment: DiagnosticsEnvironment, ring: DiagnosticsRing, nowMillis: Long, zone: ZoneId = ZoneId.systemDefault()): String =
        buildString {
            appendLine("Localica diagnostics")
            appendLine("App: ${environment.appId} ${environment.versionName} (${environment.versionCode})${if (environment.debuggable) " debuggable" else ""}")
            appendLine("Device: ${environment.manufacturer} ${environment.model}")
            appendLine("Android: ${environment.androidRelease} (API ${environment.sdk})")
            appendLine("Exported: ${Instant.ofEpochMilli(nowMillis).atZone(zone)}")
            val lines = ring.snapshot()
            appendLine("Entries: ${lines.size} kept of ${ring.totalAdded} recorded since this process started")
            appendLine("Contains only pinch/transition state; no music titles, paths or personal data.")
            appendLine()
            lines.forEach { appendLine(DiagnosticsRing.format(it, zone)) }
        }

    /** Replaces any earlier export, so at most one file stays in the cache. */
    fun write(context: Context, ring: DiagnosticsRing = Diagnostics.ring, nowMillis: Long = System.currentTimeMillis()): File {
        val directory = File(context.cacheDir, DirectoryName).apply { mkdirs() }
        directory.listFiles()?.forEach { it.delete() }
        val stamp = java.time.format.DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss").withZone(ZoneId.systemDefault()).format(Instant.ofEpochMilli(nowMillis))
        return File(directory, "localica-diagnostics-$stamp.txt").also {
            it.writeText(report(environment(context), ring, nowMillis) + "\nFrame capture\n" + FrameStats.report() + "\n")
        }
    }

    /** A chooser around ACTION_SEND for [file], granting read access to the chosen target only. */
    fun shareIntent(context: Context, file: File): Intent {
        val uri: Uri = FileProvider.getUriForFile(context, authority(context), file)
        val send = Intent(Intent.ACTION_SEND).setType("text/plain")
            .putExtra(Intent.EXTRA_STREAM, uri).putExtra(Intent.EXTRA_SUBJECT, "Localica diagnostics")
            .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        send.clipData = ClipData.newRawUri("Localica diagnostics", uri)
        return Intent.createChooser(send, "Export diagnostics").addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
    }

    fun export(context: Context) {
        val chooser = shareIntent(context, write(context))
        context.startActivity(if (context is android.app.Activity) chooser else chooser.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
    }
}
