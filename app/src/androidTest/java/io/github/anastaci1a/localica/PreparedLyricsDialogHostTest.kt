package io.github.anastaci1a.localica

import android.view.View
import android.view.WindowManager.LayoutParams
import android.widget.Button
import androidx.activity.compose.BackHandler
import androidx.activity.compose.LocalOnBackPressedDispatcherOwner
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.compose.ui.window.DialogWindowProvider
import androidx.lifecycle.findViewTreeLifecycleOwner
import androidx.lifecycle.findViewTreeViewModelStoreOwner
import androidx.savedstate.findViewTreeSavedStateRegistryOwner
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.uiautomator.UiDevice
import io.github.anastaci1a.localica.ui.LocalReducedMotion
import io.github.anastaci1a.localica.ui.PreparedLyricsDialogHost
import io.github.anastaci1a.localica.ui.PreparedLyricsReadiness
import io.github.anastaci1a.localica.ui.rememberPreparedLyricsDialogHost
import org.junit.After
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test

/** Actual WindowManager input, not Compose semantics or direct Window.Callback dispatch. */
class PreparedLyricsDialogHostTest {
    @get:Rule val compose = createComposeRule()
    private val device get() = UiDevice.getInstance(InstrumentationRegistry.getInstrumentation())
    private var mounted by mutableStateOf(true)
    private var secondMounted by mutableStateOf(false)
    private lateinit var host: PreparedLyricsDialogHost
    private lateinit var secondHost: PreparedLyricsDialogHost
    private lateinit var caller: View
    private lateinit var sourceButton: Button
    private lateinit var modalButton: Button
    private var ready: PreparedLyricsReadiness? = null
    private var secondReady: PreparedLyricsReadiness? = null
    private var retiredReadinessFailure: Throwable? = null
    private var retiredOwnershipFailure: Throwable? = null
    private var sourceClicks = 0
    private var modalClicks = 0
    private var sourceBacks = 0
    private var modalBacks = 0
    private var childBacks = 0
    private var readyPublications = 0
    private var contentDisposals = 0
    private var inheritedLocal: Any? = null
    private var childView: View? = null
    private val localValue = Any()
    private val ownership = mutableListOf<Boolean>()

    private fun fixture(reduced: Boolean = false, childBackHandler: Boolean = false) {
        compose.setContent {
            val sourceDispatcher = checkNotNull(LocalOnBackPressedDispatcherOwner.current)
            CompositionLocalProvider(TestLocal provides localValue, LocalReducedMotion provides reduced,
                LocalOnBackPressedDispatcherOwner provides sourceDispatcher) {
                caller = LocalView.current
                BackHandler { sourceBacks++ }
                Box(Modifier.fillMaxSize().padding(32.dp)) {
                    AndroidView(factory = { context -> Button(context).also {
                        sourceButton = it
                        it.text = "Source action"
                        it.isFocusableInTouchMode = true
                        it.setOnClickListener { sourceClicks++ }
                    } }, modifier = Modifier.size(180.dp, 64.dp))
                }
                if (mounted) host = rememberPreparedLyricsDialogHost(
                    onReadinessChanged = {
                        ready = it
                        if (it != null) readyPublications++ else retiredReadinessFailure?.let { failure -> throw failure }
                    },
                    onOwnershipChanged = {
                        ownership += it
                        if (!it) retiredOwnershipFailure?.let { failure -> throw failure }
                    },
                    onDismissRequest = { modalBacks++; host.retire() },
                ) {
                    BackHandler(childBackHandler) { childBacks++ }
                    val view = LocalView.current
                    val inherited = TestLocal.current
                    SideEffect { childView = view; inheritedLocal = inherited }
                    DisposableEffect(Unit) { onDispose { contentDisposals++ } }
                    Box(Modifier.fillMaxSize().padding(48.dp)) {
                        AndroidView(factory = { context -> Button(context).also {
                            modalButton = it
                            it.text = "Modal action"
                            it.setOnClickListener { modalClicks++ }
                        } }, modifier = Modifier.size(180.dp, 64.dp))
                    }
                }
                if (secondMounted) secondHost = rememberPreparedLyricsDialogHost(
                    onReadinessChanged = { secondReady = it }, onOwnershipChanged = {}, onDismissRequest = {},
                ) { Box(Modifier.fillMaxSize()) }
            }
        }
        compose.waitForIdle()
        compose.runOnIdle { assertTrue(sourceButton.requestFocus()) }
        await { caller.hasWindowFocus() && sourceButton.hasFocus() }
    }

    private fun await(predicate: () -> Boolean) {
        compose.waitUntil(10000) {
            var satisfied = false
            compose.runOnIdle { satisfied = predicate() }
            satisfied
        }
    }

    private fun prepare(): PreparedLyricsReadiness {
        compose.runOnIdle { assertTrue(host.prepare()) }
        await { ready != null }
        return requireNotNull(ready)
    }

    private fun nativeClick(button: Button) {
        val location = IntArray(2)
        compose.runOnIdle {
            assertTrue(button.isAttachedToWindow)
            button.getLocationOnScreen(location)
            location[0] += button.width / 2
            location[1] += button.height / 2
        }
        assertTrue(device.click(location[0], location[1]))
        compose.waitForIdle()
    }

    private fun assertInert() {
        val flags = host.window.attributes.flags
        assertEquals(InertFlags, flags and InertFlags)
        assertEquals(0, flags and LayoutParams.FLAG_DIM_BEHIND)
        assertEquals((caller.rootView.layoutParams as LayoutParams).flags and LayoutParams.FLAG_SECURE,
            flags and LayoutParams.FLAG_SECURE)
        assertEquals(0f, host.window.attributes.alpha, 0f)
        assertEquals(0f, host.window.attributes.dimAmount, 0f)
        assertEquals(View.IMPORTANT_FOR_ACCESSIBILITY_NO_HIDE_DESCENDANTS, host.composeView.importantForAccessibility)
        assertEquals(View.IMPORTANT_FOR_ACCESSIBILITY_NO_HIDE_DESCENDANTS, host.window.decorView.importantForAccessibility)
        assertFalse(host.ownsWindow)
    }

    @After fun disposeOwnedHost() {
        if (::host.isInitialized) compose.runOnUiThread { host.retire() }
        if (::secondHost.isInitialized) compose.runOnUiThread { secondHost.retire() }
    }

    @Test fun firstAttachmentIsInertAndKeepsCallerOwnersAndFocus() {
        fixture()
        var attachments = 0
        compose.runOnIdle {
            assertInert()
            host.composeView.addOnAttachStateChangeListener(object : View.OnAttachStateChangeListener {
                override fun onViewAttachedToWindow(view: View) {
                    attachments++
                    assertInert()
                    assertTrue("The source still owns native focus at first attachment", caller.hasWindowFocus())
                    assertTrue(sourceButton.hasFocus())
                }
                override fun onViewDetachedFromWindow(view: View) { }
            })
        }
        prepare()
        compose.runOnIdle {
            assertEquals(1, attachments)
            assertInert()
            assertTrue(caller.hasWindowFocus())
            assertFalse(host.composeView.hasWindowFocus())
            assertSame(localValue, inheritedLocal)
            assertNotEquals(View.NO_ID, host.composeView.id)
            val child = requireNotNull(childView)
            assertSame(host.composeView, child.parent)
            assertSame(host.window, (child.parent as DialogWindowProvider).window)
            assertSame(caller.findViewTreeLifecycleOwner(), child.findViewTreeLifecycleOwner())
            assertSame(caller.findViewTreeSavedStateRegistryOwner(), child.findViewTreeSavedStateRegistryOwner())
            assertSame(caller.findViewTreeViewModelStoreOwner(), child.findViewTreeViewModelStoreOwner())
            assertTrue(ownership.isEmpty())
        }
    }

    @Test fun preparedWindowPassesRealSourceTouchAndBackWithoutClaiming() {
        fixture(childBackHandler = true)
        prepare()
        nativeClick(sourceButton)
        await { sourceClicks == 1 }
        // pressBack() additionally waits for a content-change event. This source handler
        // deliberately changes only its counter; assert the real system action's admission.
        pressSystemBack()
        await { sourceBacks == 1 }
        compose.runOnIdle {
            assertEquals(0, modalClicks)
            assertEquals(0, modalBacks)
            assertEquals("A hidden child BackHandler cannot register on the caller dispatcher", 0, childBacks)
            assertInert()
            assertTrue(ownership.isEmpty())
        }
    }

    @Test fun explicitClaimOwnsNativeTouchAndBackThenRetirementCannotReclaim() {
        fixture()
        val token = prepare()
        compose.runOnIdle { assertTrue(host.claim(token)) }
        await { host.composeView.hasWindowFocus() }
        nativeClick(modalButton)
        await { modalClicks == 1 }
        compose.runOnIdle {
            assertEquals(0, host.window.attributes.flags and InertFlags)
            assertEquals((caller.rootView.layoutParams as LayoutParams).flags and LayoutParams.FLAG_SECURE,
                host.window.attributes.flags and LayoutParams.FLAG_SECURE)
            assertEquals(1f, host.window.attributes.alpha, 0f)
            assertTrue(host.ownsWindow)
            assertEquals(listOf(true), ownership)
        }
        assertTrue(device.pressBack())
        await { modalBacks == 1 && !host.composeView.isAttachedToWindow && caller.hasWindowFocus() }
        compose.runOnIdle {
            assertEquals(0, sourceBacks)
            assertFalse(host.claim(token))
            assertFalse(host.prepare())
            assertFalse(host.composeView.hasComposition)
            assertEquals(1, contentDisposals)
            assertEquals(listOf(true, false), ownership)
        }
        nativeClick(sourceButton)
        await { sourceClicks == 1 }
    }

    @Test fun preClaimNativeResizeInvalidatesTokenAndNeedsFreshTraversal() {
        fixture()
        val old = prepare()
        var oldWidth = 0
        var oldHeight = 0
        compose.runOnIdle {
            oldWidth = host.composeView.width
            oldHeight = host.composeView.height
            host.window.setLayout((oldWidth * 3 / 4).coerceAtLeast(1), (oldHeight * 3 / 4).coerceAtLeast(1))
        }
        await { host.composeView.width != oldWidth && host.composeView.height != oldHeight && ready != null && ready !== old }
        compose.runOnIdle {
            assertFalse(host.claim(old))
            assertInert()
            assertTrue(ownership.isEmpty())
            assertTrue(host.claim(requireNotNull(ready)))
            assertEquals(listOf(true), ownership)
        }
    }

    @Test fun claimedNativeResizeRetainsModalOwnershipWhileReplacingGeometryToken() {
        fixture()
        val old = prepare()
        compose.runOnIdle { assertTrue(host.claim(old)) }
        await { host.composeView.hasWindowFocus() }
        var oldWidth = 0
        var oldHeight = 0
        compose.runOnIdle {
            oldWidth = host.composeView.width
            oldHeight = host.composeView.height
            host.window.setLayout((oldWidth * 3 / 4).coerceAtLeast(1), (oldHeight * 3 / 4).coerceAtLeast(1))
        }
        await { host.composeView.width != oldWidth && host.composeView.height != oldHeight && ready != null && ready !== old }
        compose.runOnIdle {
            assertFalse(host.claim(old))
            assertTrue(host.ownsWindow)
            assertEquals(0, host.window.attributes.flags and InertFlags)
            assertEquals(1f, host.window.attributes.alpha, 0f)
            assertEquals(listOf(true), ownership)
        }
        nativeClick(modalButton)
        await { modalClicks == 1 }
        assertTrue(device.pressBack())
        await { modalBacks == 1 }
        compose.runOnIdle { assertEquals(0, sourceBacks); assertEquals(0, sourceClicks) }
    }

    @Test fun cancellationBeforeTraversalAndCompositionDisposalAreTerminal() {
        fixture()
        compose.runOnIdle {
            assertTrue(host.prepare())
            host.retire()
            assertFalse(host.prepare())
        }
        compose.waitForIdle()
        compose.runOnIdle {
            assertNull(ready)
            assertEquals("No deferred readiness callback after cancellation", 0, readyPublications)
            assertFalse(host.composeView.isAttachedToWindow)
            assertFalse(host.composeView.hasComposition)
            assertInert()
        }
    }

    @Test fun compositionDisposalRetiresClaimAndRejectsTokenInAnotherIncarnation() {
        fixture()
        val oldHost = host
        val oldToken = prepare()
        compose.runOnIdle { assertTrue(host.claim(oldToken)); mounted = false }
        compose.waitForIdle()
        compose.runOnIdle {
            assertFalse(oldHost.ownsWindow)
            assertFalse(oldHost.composeView.isAttachedToWindow)
            assertFalse(oldHost.composeView.hasComposition)
            assertFalse(oldHost.claim(oldToken))
            assertNull(ready)
            mounted = true
        }
        compose.waitForIdle()
        val replacementToken = prepare()
        compose.runOnIdle {
            assertNotSame(oldHost, host)
            assertFalse(host.claim(oldToken))
            assertTrue(host.claim(replacementToken))
        }
    }

    @Test fun reducedMotionUsesTheSameExplicitNativeHandshake() {
        fixture(reduced = true)
        val token = prepare()
        compose.runOnIdle { assertInert(); assertTrue(host.claim(token)); host.retire(); assertFalse(host.claim(token)) }
    }

    @Test fun coexistingHostsHaveSeparateSaveableProvidersAndRetireIndependently() {
        fixture()
        prepare()
        compose.runOnIdle { secondMounted = true }
        compose.waitForIdle()
        compose.runOnIdle { assertTrue(secondHost.prepare()) }
        await { secondReady != null }
        compose.runOnIdle {
            assertNotEquals(host.composeView.id, secondHost.composeView.id)
            val registry = requireNotNull(caller.findViewTreeSavedStateRegistryOwner()).savedStateRegistry
            // Compose UI 1.8.2 keys its public Android SavedStateRegistry provider by host view ID.
            val firstKey = "SaveableStateRegistry:${host.composeView.id}"
            val secondKey = "SaveableStateRegistry:${secondHost.composeView.id}"
            assertNotNull(registry.getSavedStateProvider(firstKey))
            val secondProvider = requireNotNull(registry.getSavedStateProvider(secondKey))
            host.retire()
            assertNull(registry.getSavedStateProvider(firstKey))
            assertSame(secondProvider, registry.getSavedStateProvider(secondKey))
            assertTrue(secondHost.composeView.hasComposition)
            assertTrue(secondHost.claim(requireNotNull(secondReady)))
            secondHost.retire()
            assertNull(registry.getSavedStateProvider(secondKey))
        }
    }

    @Test fun retirementAttemptsBothNotificationsAndPreservesPrimaryFailure() {
        fixture()
        val token = prepare()
        val primary = IllegalStateException("readiness retirement callback")
        val secondary = IllegalArgumentException("ownership retirement callback")
        compose.runOnIdle {
            assertTrue(host.claim(token))
            retiredReadinessFailure = primary
            retiredOwnershipFailure = secondary
            val failure = runCatching { host.retire() }.exceptionOrNull()
            assertSame(primary, failure)
            assertTrue(primary.suppressed.any { it === secondary })
            assertFalse(host.ownsWindow)
            assertFalse(host.composeView.isAttachedToWindow)
            assertFalse(host.composeView.hasComposition)
            assertFalse(host.claim(token))
            assertEquals(listOf(true, false), ownership)
        }
    }

    private companion object {
        val TestLocal = staticCompositionLocalOf<Any?> { null }
        const val InertFlags = LayoutParams.FLAG_NOT_FOCUSABLE or LayoutParams.FLAG_NOT_TOUCHABLE
    }
}
