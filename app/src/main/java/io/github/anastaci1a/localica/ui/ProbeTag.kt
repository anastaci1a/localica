package io.github.anastaci1a.localica.ui

import androidx.compose.runtime.Composable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import io.github.anastaci1a.localica.BuildConfig

/** Whether test-only semantics tags are emitted. Debug builds (where instrumentation runs) keep them;
 * release builds drop the nodes so accessibility services traverse a smaller semantics tree. */
internal val LocalProbeTags = staticCompositionLocalOf { BuildConfig.DEBUG }

/** A [testTag] that exists only for tests: no accessibility service or user reads it. Each tag is
 * an unmerged semantics node that every accessibility pass walks, so release builds leave it out. */
@Composable
internal fun Modifier.probeTag(tag: String): Modifier = if (LocalProbeTags.current) testTag(tag) else this
