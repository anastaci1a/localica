package io.github.anastaci1a.localica

import android.content.res.AssetManager
import java.io.File

internal fun copyTestAsset(assets: AssetManager, assetName: String, destination: File) {
    assets.open(assetName).use { input -> destination.outputStream().use(input::copyTo) }
}
