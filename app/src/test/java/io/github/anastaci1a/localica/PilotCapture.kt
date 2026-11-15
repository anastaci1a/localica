package io.github.anastaci1a.localica

import android.graphics.BitmapFactory
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.test.SemanticsNodeInteraction
import androidx.test.core.app.ApplicationProvider
import com.github.takahirom.roborazzi.RoborazziOptions
import com.github.takahirom.roborazzi.ExperimentalRoborazziApi
import com.github.takahirom.roborazzi.RoborazziTaskType
import com.github.takahirom.roborazzi.captureRoboImage
import java.io.File

// Compose 1.10's captureToImage waits for a native frame callback absent on Robolectric.
@OptIn(ExperimentalRoborazziApi::class)
internal fun SemanticsNodeInteraction.pilotCaptureToImage() =
    File(ApplicationProvider.getApplicationContext<android.app.Application>().cacheDir, "pilot-capture.png").let { file ->
        captureRoboImage(file, RoborazziOptions(taskType = RoborazziTaskType.Record))
        try { checkNotNull(BitmapFactory.decodeFile(file.path)).asImageBitmap() }
        finally { file.delete() }
    }
