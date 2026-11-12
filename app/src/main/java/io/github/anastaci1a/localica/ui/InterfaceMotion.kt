package io.github.anastaci1a.localica.ui

import android.database.ContentObserver
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import androidx.activity.ComponentActivity
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.MotionDurationScale
import androidx.compose.ui.platform.compositionContext
import androidx.compose.ui.platform.createLifecycleAwareWindowRecomposer
import androidx.compose.ui.unit.dp
import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.LifecycleOwner
import kotlin.coroutines.CoroutineContext
import io.github.anastaci1a.localica.navigation.FasterAnimationSpeedTenths
import io.github.anastaci1a.localica.navigation.InterfaceSettings
import io.github.anastaci1a.localica.navigation.MaximumAnimationSpeedTenths
import io.github.anastaci1a.localica.navigation.MinimumAnimationSpeedTenths
import kotlin.math.ln
import kotlin.math.pow

internal object Corners {
    val artwork = RoundedCornerShape(8.dp)
    val card = RoundedCornerShape(16.dp)
    val menu = RoundedCornerShape(20.dp)
    val largeArtwork = RoundedCornerShape(24.dp)
    val panel = RoundedCornerShape(28.dp)
}
/** #860 §7.3: normal · 3/4 / s, floored as before; exact for the old Standard (1.0×) and Faster (1.5×) states. */
internal fun InterfaceSettings.motionDuration(normal: Int) =
    if (reducedMotion) 0 else normal * 30 / (4 * animationSpeedTenths.coerceIn(MinimumAnimationSpeedTenths, MaximumAnimationSpeedTenths))

/** The legacy non-engine speed input, 4/3 · s: 4/3 at 1.0× and the old Faster 2 at 1.5×. */
internal val InterfaceSettings.legacyMotionSpeed: Float
    get() = 4f * animationSpeedTenths.coerceIn(MinimumAnimationSpeedTenths, MaximumAnimationSpeedTenths) / 30f

/** The artwork swipe spring stiffness, 380 · (650/380)^(ln s / ln 1.5): exactly 380 at 1.0× and 650 at the old Faster 1.5×. */
internal fun InterfaceSettings.artworkSwipeStiffness(): Float = when (animationSpeedTenths.coerceIn(MinimumAnimationSpeedTenths, MaximumAnimationSpeedTenths)) {
    10 -> 380f
    FasterAnimationSpeedTenths -> 650f
    else -> (380.0 * (650.0 / 380.0).pow(ln(animationSpeed.toDouble()) / ln(1.5))).toFloat()
}

internal val LocalReducedMotion = androidx.compose.runtime.staticCompositionLocalOf { false }
internal val LocalMotionSpeed = androidx.compose.runtime.staticCompositionLocalOf { 4f / 3f }

/** #862: the window's one motion duration scale. Compose animations, every coroutine reader ([motionDurationScale]) and, through the
 * root, the engine (EngineContext.durationScale) all see this effective value: the platform animator duration scale while it is
 * respected, and 1 when the app ignores it (its 0 is then not Reduce motion either). The platform value is observed here, not by
 * Compose: a window recomposer whose context already holds a MotionDurationScale installs no observer of its own. */
@Stable internal class WindowMotionScale(platform: Float, respect: Boolean) : MotionDurationScale {
    var platform by mutableFloatStateOf(platform)
    var respect by mutableStateOf(respect)
    override val scaleFactor: Float get() = effectiveDurationScale(platform, respect)
}

internal fun effectiveDurationScale(platform: Float, respect: Boolean) = if (respect) platform else 1f

/** The effective scale of the window's coroutines; the one reader of the coroutine element outside the root. */
internal val CoroutineContext.motionDurationScale: Float get() = this[MotionDurationScale]?.scaleFactor ?: 1f

/** The window's platform scale, as the root reads it: the installed [WindowMotionScale]'s, or Compose's own observer's in an isolated
 * composition (which then always respects it). */
internal val CoroutineContext.platformDurationScale: Float
    get() = (this[MotionDurationScale] as? WindowMotionScale)?.platform ?: motionDurationScale

/** The root binds the app's respect preference; isolated compositions have nothing to bind. */
internal fun CoroutineContext.bindRespectSystemAnimationScale(respect: Boolean) {
    (this[MotionDurationScale] as? WindowMotionScale)?.respect = respect
}

/** #862: MainActivity installs its window's one recomposer with the app's [WindowMotionScale], before setContent. Every composition in
 * the window, its Compose dialogs included, inherits that context. [respect] is the stored preference, so the first frame is right. */
internal fun ComponentActivity.installLocalicaWindowMotion(respect: Boolean): WindowMotionScale {
    val resolver = contentResolver
    fun read() = Settings.Global.getFloat(resolver, Settings.Global.ANIMATOR_DURATION_SCALE, 1f)
    val scale = WindowMotionScale(read(), respect)
    val observer = object : ContentObserver(Handler(Looper.getMainLooper())) { override fun onChange(selfChange: Boolean) { scale.platform = read() } }
    resolver.registerContentObserver(Settings.Global.getUriFor(Settings.Global.ANIMATOR_DURATION_SCALE), false, observer)
    lifecycle.addObserver(object : DefaultLifecycleObserver { override fun onDestroy(owner: LifecycleOwner) { resolver.unregisterContentObserver(observer) } })
    val root = window.decorView
    root.compositionContext = root.createLifecycleAwareWindowRecomposer(scale, lifecycle)
    return scale
}

/** The app setting, or a window scale of 0. That scale is already effective: with the respect toggle off it is 1, never 0. */
@Composable
internal fun rememberEffectiveReducedMotion(appReducedMotion: Boolean): Boolean =
    effectiveReducedMotion(appReducedMotion, rememberCoroutineScope().coroutineContext.motionDurationScale)

internal fun effectiveReducedMotion(appReducedMotion: Boolean, systemDurationScale: Float, respectSystemScale: Boolean = true) =
    appReducedMotion || respectSystemScale && systemDurationScale == 0f
