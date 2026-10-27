package io.github.anastaci1a.localica

import androidx.test.platform.app.InstrumentationRegistry
import java.io.File

/** Export existing synthetic captures through AGP; manual runs retain their original location. */
internal fun nativeEvidenceDirectory(name: String, fallback: File?): File {
    val output = InstrumentationRegistry.getArguments().getString("additionalTestOutputDir")
        ?.takeIf { it.isNotBlank() } ?: return requireNotNull(fallback)
    return File(output, name).apply { mkdirs() }
}
