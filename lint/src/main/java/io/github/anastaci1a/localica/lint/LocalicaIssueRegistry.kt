package io.github.anastaci1a.localica.lint

import com.android.tools.lint.client.api.IssueRegistry
import com.android.tools.lint.client.api.Vendor
import com.android.tools.lint.detector.api.*

class LocalicaIssueRegistry : IssueRegistry() {
    override val vendor = Vendor("Localica", "io.github.anastaci1a.localica", "https://github.com/anastaci1a/localica/issues")
    override val api = CURRENT_API
    // Compatibility is tested only against the pinned lint API.
    override val minApi = CURRENT_API
    override val issues = UiRuleId.entries.map { it.issue } + MenuRuleId.entries.map { it.issue }
}

internal enum class UiRuleId(val policy: String) {
    RawUiMotion("Use Engine trajectories"),
    IndependentUiClock("Use the shared UiClock"),
    RawPresentationTransform("Bind presentation through Modifier.surface"),
    RawShadowOrZOrder("Use SurfaceTarget and ShadowRole"),
    ReplacePersistentShape("Retarget a stable PersistentShape"),
    UnownedSurfaceLifetime("Retain the SurfaceNode host until dismissal completes"),
    RawMotionMap("Use Curve maps and owner-only mutation"),
    MotionReadInComposition("Sample presentation only in layout or draw"),
    LiteralUiSpacing("Use validated Spec tokens"),
    NonPresetScreenLayout("Use stock screen layout slots"),
    UnvalidatedUiSpec("Construct validated specs at uiSpec ingress"),
    UiLayerDependency("Respect the UI package dependency matrix"),
    UnreviewedUiEscape("Require an exact live lead-reviewed adapter escape"),
    GlassPopupWithoutAnchor("Open glass popups from their anchor");

    val issue: Issue by lazy {
        Issue.create(name, policy, "$policy. Enforces the Localica UI architecture.",
            Category.CORRECTNESS, 8, Severity.ERROR,
            Implementation(UiPolicyDetector::class.java, Scope.JAVA_FILE_SCOPE)).also {
            if (this == UnreviewedUiEscape) it.setOptions(listOf(EscapeOptions.known, EscapeOptions.completed, EscapeOptions.release))
        }
    }
}

internal data class SourceAnchor(
    val path: String, val symbol: String, val line: Int, val codeSha256: String,
)
internal data class EscapeEntry(
    val rule: UiRuleId, val symbol: String, val reason: String,
    val issue: String, val owner: String, val reviewer: String, val expires: String,
)

internal object EscapeOptions {
    val known = StringOption("knownSteps", "Frozen micro-step inventory", defaultValue = "")
    val completed = StringOption("completedSteps", "Completed frozen micro-steps", defaultValue = "missing")
    val release = StringOption("release", "Candidate release version", defaultValue = "")
}
