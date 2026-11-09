package io.github.anastaci1a.localica.ui

/** NEVER MERGE: immutable-per-activity diagnostic arms; no timing/readback/extra oracle work. */
internal object NoneSamplingDiagnostic {
    var mode = "grid8"
        private set
    var witness = false
        private set
    var artwork = false
        private set
    val sampleSide: Int? get() = when (mode) { "baseline" -> null; "grid16" -> 16; else -> 8 }
    val restingControls: Boolean get() = mode != "baseline"
    fun configure(intent: android.content.Intent) {
        mode = intent.getStringExtra("none-ablation") ?: "grid8"
        require(mode in setOf("baseline", "grid8", "grid16", "gather8"))
        artwork = intent.getBooleanExtra("none-artwork", false)
        witness = intent.getBooleanExtra("none-footprints", false)
        if (witness) NoneSamplingReceipts.reset()
        android.util.Log.i("NoneAblation", "mode=$mode artwork=$artwork")
    }
}
