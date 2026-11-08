package io.github.anastaci1a.localica.ui.engine

import androidx.compose.runtime.*

/** Borrow the window phase. Only structural threshold events enter composition;
 * continuous geometry is sampled by the origin's actual layout and draw. */
@Stable internal class OriginPhaseProjection {
    private var phase by mutableStateOf<MotionValue?>(null)
    private var curve=Curve.window(0f,.4f)
    val value: Float get()=phase?.let { curve.at(it.value()) }?:0f
    var separated by mutableStateOf(false); private set
    var active by mutableStateOf(false); private set
    fun bind(value: MotionValue,mapping: Curve) { phase=value; curve=mapping }
    fun clear() { phase=null }
    internal fun publishStructure(p: Float) { separated=p>=.5f; active=p>0f }
    fun captured(separated: Boolean): Float=value.let { if((it>=.5f)==separated) it else Float.NaN }
}

@Composable internal fun rememberOriginPhaseProjection(): OriginPhaseProjection {
    val value=remember { OriginPhaseProjection() }
    // This observes the existing owner; it neither requests frames nor drives motion.
    LaunchedEffect(value) { snapshotFlow { value.value }.collect { value.publishStructure(it) } }
    return value
}

// The existing split/contact projection remains exact; no optical model changes.
internal fun originGap(p: Float)=((p-.5f)*2f).coerceIn(0f,1f)
internal fun originExpansion(reveal: Float,p: Float)=maxOf(reveal,(p*2f).coerceIn(0f,1f))
internal fun originDividerAlpha(reveal: Float,p: Float)=((originExpansion(reveal,p)-.4f)/.6f).coerceIn(0f,1f)*(1f-p*2f)
internal fun originRightAlpha(reveal: Float,p: Float)=((originExpansion(reveal,p)-.25f)/.75f).coerceIn(0f,1f)
internal fun glassBodyFlattening(thickness: Float)=((1f-thickness)/.18f).coerceIn(0f,1f)
