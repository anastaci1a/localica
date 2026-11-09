package io.github.anastaci1a.localica;

import android.accessibilityservice.AccessibilityService;
import android.view.accessibility.AccessibilityEvent;

/** Test-only real service. Android framework classes keep standalone service loading independent. */
public final class ToastFocusFixtureService extends AccessibilityService {
    @Override public void onAccessibilityEvent(AccessibilityEvent event) { }
    @Override public void onInterrupt() { }
}
