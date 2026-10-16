package io.github.anastaci1a.localica.ui

import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.listSaver
import androidx.compose.runtime.saveable.rememberSaveable

internal enum class ArtworkStage { Editor, Chooser, Crop, Review }

/** The entry point owns the mounted stage; crop geometry remains with ArtworkCrop. */
@Stable
internal class ArtworkWorkflow(
    val target: String,
    stage: ArtworkStage = ArtworkStage.Editor,
    cropUri: String? = null,
    imageUri: String? = null,
) {
    var stage by mutableStateOf(stage)
        private set
    var cropUri by mutableStateOf(cropUri)
        private set
    var imageUri by mutableStateOf(imageUri)
        private set
    val choosing get() = stage == ArtworkStage.Chooser || stage == ArtworkStage.Crop

    fun choose() { cropUri = null; stage = ArtworkStage.Chooser }
    fun crop(uri: String) { cropUri = uri; stage = ArtworkStage.Crop }
    fun cropCancelled() { cropUri = null; stage = ArtworkStage.Chooser }
    fun chosen(uri: String) { cropUri = null; imageUri = uri; stage = ArtworkStage.Review }
    fun cancelChooser() { cropUri = null; stage = if (imageUri == null) ArtworkStage.Editor else ArtworkStage.Review }
    fun clearImage() { cropUri = null; imageUri = null; stage = ArtworkStage.Editor }

    companion object {
        fun saver(target: String) = listSaver<ArtworkWorkflow, String>(
            save = { listOf(it.target, it.stage.name, it.cropUri.orEmpty(), it.imageUri.orEmpty()) },
            // rememberSaveable inputs invalidate live state, but do not validate restored inputs.
            restore = { saved -> if (saved[0] != target) null else ArtworkWorkflow(
                target, ArtworkStage.valueOf(saved[1]), saved[2].ifEmpty { null }, saved[3].ifEmpty { null }) },
        )
    }
}

@Composable
internal fun rememberArtworkWorkflow(target: String, startChoosing: Boolean = false): ArtworkWorkflow =
    rememberSaveable(target, saver = ArtworkWorkflow.saver(target)) {
        ArtworkWorkflow(target, if (startChoosing) ArtworkStage.Chooser else ArtworkStage.Editor)
    }
