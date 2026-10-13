package io.github.anastaci1a.localica

import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Canvas
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.*
import org.junit.Test

class AppIdentityTest {
    @Test fun musicDiscoveryFindsTheServiceAndLauncherUsesTheRenderedIcon() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val manager = context.packageManager
        val application = manager.getApplicationInfo(context.packageName, 0)
        assertEquals("Localica", manager.getApplicationLabel(application).toString())
        assertEquals(android.content.pm.ApplicationInfo.CATEGORY_AUDIO, application.category)
        val services = manager.queryIntentServices(Intent("android.media.browse.MediaBrowserService").setPackage(context.packageName), 0)
        assertEquals(1, services.size)
        assertTrue(services.single().serviceInfo.exported)
        val drawable = manager.getApplicationIcon(application)
        val bitmap = Bitmap.createBitmap(432, 432, Bitmap.Config.ARGB_8888)
        drawable.setBounds(0, 0, 432, 432)
        drawable.draw(Canvas(bitmap))
        val pixels = IntArray(432 * 432).also { bitmap.getPixels(it, 0, 432, 0, 0, 432, 432) }
        assertTrue("The inset note retains the red accent", pixels.count { android.graphics.Color.red(it) > 180 && android.graphics.Color.green(it) < 130 } > 1000)
        java.io.File(context.cacheDir, "launcher-review.png").outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
        bitmap.recycle()
    }
}
