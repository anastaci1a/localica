# API36 real-window synthetic floating IME — service-only draft

This is a test InputMethodService, not Gboard and not a forged Insets observer.
All files belong only under `app/src/androidTest`. Merge the service element into
existing test manifest; do not replace its other components. No production
manifest/source, IME setting, emulator or external repository has been changed.

## Identifiers / parent integration

- Class: `io.github.anastaci1a.localica.FloatingTestImeService`
- IME component: `<actualTestApkPackage>/io.github.anastaci1a.localica.FloatingTestImeService`
- Process: `<actualTestApkPackage>:floating_ime_fixture`
- Logcat tag: `LocalicaFloatingIme` (256 event lines max per service instance).
- Card description: `Localica synthetic floating IME`.
- Runtime receiver actions: `<actualTestApkPackage>.floating.HIDE` and `.floating.SNAPSHOT`.

The receiver is registered while the service exists, exported only in this test
APK for shell invocation, and rejects intents without its exact package. It has
no manifest receiver component: use `am broadcast -p <actualTestApkPackage> -a
<actualTestApkPackage>.floating.HIDE`, not `-n`. HIDE calls `requestHideSelf(0)`;
it does not dispatch app Back or touch host focus. SNAPSHOT logs real service facts
only. No command can edit text. Avoid leaving this test APK enabled after testing.
The parent owns enable/select and independent finally-based setting restoration.

## Real native construction

The service creates the system-managed TYPE_INPUT_METHOD window with transparent
background and an opaque, touchable card. Non-extract/fullscreen mode stays false.
The input view keeps a transparent bottom gap (72dp padding, display-height minimum clamped by native parent measurement),
with the card bottom/end-aligned so upper host editors remain unobstructed. Thus
the keyboard is visibly floating rather than a zero-height invisible service.
Only the card's actual window-coordinate region is touchable.

`onComputeInsets` reports content/visible top at actual decor bottom. This is the
public IME geometry contract, not host-insets injection. The platform still owns
IME show state and delivers actual root insets to the host. No fixed height/time
is used as proof of native visibility.

API36 can augment IME content insets with its navigation/caption bar after the
service callback. The fixture requests hiding its own caption bar through public
`WindowInsetsController.hide(captionBar())` and supplies the public API36 custom
switcher callback/button. It does not hide the host's system bars, set a hidden
sysprop, inspect private framework views, or replace Compose inset listeners.
If the platform refuses/augments this, host bottom==0 must fail; do not subtract a
navigation-bar height from the observed result to make it pass.

The normal InputMethodService input connection/session lifecycle is retained.
No editor contents are queried or logged, no text is committed automatically,
and no connection is cached. The optional Insert x button commits only on an
explicit click into the two known Localica production/debug package identities.

## Required evidence — none established locally yet

A pass needs all evidence for the same shown interval:
1. The focused, attached HOST root returns native IME visible=true and bottom=0.
2. Its Compose observation is visible=true and current/source/target all zero.
3. The selected/bound IME is this test component and the real native IME window
   is shown (dumpsys), with the floating card visibly drawn in a screenshot.
4. Service `snapshot` confirms decor/card shown and attached, TYPE_INPUT_METHOD
   (2011), nonzero card size, and a current-show `inputViewDrawn` witness:
   `drawShowGeneration == showGeneration`, with draw uptime after current show.
5. HIDE produces actual native disappearance and exactly one host editing-end;
   empty/nonempty reopen and already-visible focus transfer work, and all changed
   secure settings are independently restored/verified afterward.

The service's `requestedTop` is the geometry it requested, NOT evidence that the
host received zero insets. `windowShown` callback alone is not draw/surface proof.
Snapshots report historical draw counters too; correlate show generation/uptime.
This fixture can establish a genuine synthetic floating-IME lifecycle contract,
not compatibility with Gboard or every OEM/third-party floating keyboard.

## Feasibility / sources

The approach is supported by the public IME geometry contract and AOSP's separate
window/source visibility handling; API36 runtime outcome is still unproven.
The official references used were:
- [Android16 InputMethodService](https://raw.githubusercontent.com/aosp-mirror/platform_frameworks_base/android16-release/core/java/android/inputmethodservice/InputMethodService.java): public window configuration, geometry callback and ordinary input lifecycle.
- [Android16 NavigationBarController](https://raw.githubusercontent.com/aosp-mirror/platform_frameworks_base/android16-release/core/java/android/inputmethodservice/NavigationBarController.java): post-callback navigation inset augmentation and caption visibility.
- [Public API36 InputMethodService reference](https://developer.android.com/reference/android/inputmethodservice/InputMethodService.html): caption-bar hide and custom switcher callback.
- [AOSP CTS caption-bar hide test](https://android.googlesource.com/platform/cts/+/5e4294d27e2781c91ab84c7d28b3984e951d6615%5E%21/): hide request should not hide an otherwise visible IME.

## Validation status / risks

- XML is well-formed; source/resource whitespace checked.
- Initial compile/AAPT and service selection succeeded, but a Kotlin service in
  a standalone test-APK process failed because Kotlin runtime belongs to the target
  app. The replacement is Android/Java-only; its runtime is not yet validated.
- Earlier direct-exec command quoting was corrected; all dynamic arguments are
  validated before mutation, never shell-quoted. Settings restoration readback
  passed in the second comparison, but service crash left host focus unavailable.
- Host zero-inset visibility, service draw witnesses and complete native lifecycle
  remain unproven. No floating-IME compatibility or product fix is claimed.
- Needs compileSdk36 for the public switcher override; inspected local project
  source uses compileSdk36. Intended runtime is API36, not a claimed API26 fixture.
- Test-APK component packaging, platform show/caption timing, device navigation
  configuration and small/large-font layouts require actual run validation.
- The transparent native window uses the real IME protocol. Native focus/input
  routing must remain under platform ownership; do not patch host observations
  or weaken zero-inset/visible-window assertions if this service fails them.

## Isolated comparison scope

Identical service/test resources are applied to dev74 baseline and the unpromoted IME189 candidate, API36 only. The original mandatory SearchImeMotion, ListSearch and FrostedSurface classes remain selected in full. The host test verifies actual focus loss/refocus with a covering Dialog but does not assert that the IME hid during coverage. Explicit service HIDE cycles separately verify IME-driven editing termination. Five secure settings restore independently after input retirement; final aggregate setting readback is required. The exported receiver package filter is not sender authentication: this APK is restricted to a disposable emulator, never production.

Cleanup now also requires restored native host focus, hidden/nonaccepting input and original-or-absent live IME binding. Historical disconnected mCurIntent does not count as a live binding. Runtime cleanup failures preserve native window dumps/screenshots and the primary failure; no unknown crash window is dismissed.
