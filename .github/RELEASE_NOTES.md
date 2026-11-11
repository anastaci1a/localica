<!-- Release: v0.34.1 -->

# Release v0.34.1

v0.34.0 was folded into v0.34.1 after its final run failed (#869/#870); this release contains everything listed for v0.34.0 plus the v0.34.1 items.

Android APK for Android 8 or newer. Keeps the existing `.debug` application ID and project development signing key for in-place updates. Separate production signing is not configured.

This optimized, non-debuggable build uses R8 and updates the existing app in place.

## Changes

- Share the motion engine across player, Queue, popup, scrollbar, pinch and settings-search interactions ([#600](https://github.com/anastaci1a/localica/issues/600), completed UI consolidation scope).
- Consolidate control layouts, lyrics-header presentation, transport outlines, preference frames and image chooser geometry.
- Keep popup navigation and native-window preparation continuous through closing, reversal and nested handoffs.
- Keep the scrollbar visible throughout dragging, capture touches consistently, and place its track below the header controls.
- Provide the shared UI context to tag editor, jobs and interrupted-edit recovery dialogs.
- Apply the measured Apple popup spring model to menus, row menus and dialogs, including reversals and native-window handoffs.
- Replace Faster motion with an Animation speed slider from 0.5× to 1.5×. Existing Faster motion settings migrate to 1.5×.
- Add Respect system animation scale, enabled by default. Reduce motion takes precedence and disables both motion controls.
- Preserve the accepted player and Queue drawer law while applying the selected animation speed and system-scale preference.

## Resolved issues

- [#667](https://github.com/anastaci1a/localica/issues/667): Keep utility popup returns behind the mini player and bottom navigation, and retire them after closing.
- [#676](https://github.com/anastaci1a/localica/issues/676): Preserve player collapse when Back interrupts a closing lyrics drag and retain release pointer history.
- [#679](https://github.com/anastaci1a/localica/issues/679): Reduce repeated UI lint graph queries and handle classification work.
- [#693](https://github.com/anastaci1a/localica/issues/693): Preserve source shadows through canceled popup openings and returns.
- [#704](https://github.com/anastaci1a/localica/issues/704): Correct scrollbar expansion, drag visibility, touch capture and placement below header controls.
- [#721](https://github.com/anastaci1a/localica/issues/721): Keep Search direct return independent of native keyboard movement.
- [#786](https://github.com/anastaci1a/localica/issues/786): Isolate native IME policy, retire its cache after restoration, and reject suppressed input-view callbacks.
- [#811](https://github.com/anastaci1a/localica/issues/811): Retain popup parents until descendant handoff completes.
- [#812](https://github.com/anastaci1a/localica/issues/812): Preserve outgoing popup continuity during host navigation and retain departing channels until retirement.
- [#813](https://github.com/anastaci1a/localica/issues/813): Verify complete native shadow-field receipts for existing controls.
- [#814](https://github.com/anastaci1a/localica/issues/814): Verify native preparation, reversal and closing-window retirement; correct R4 fixture ordering.
- [#815](https://github.com/anastaci1a/localica/issues/815): Correct shared-neighbor shadow continuity measurement using an independent live reference and restore exact-once test coverage.
- [#818](https://github.com/anastaci1a/localica/issues/818): Provide the shared engine context to root-level tag editor and recovery dialogs.
- [#835](https://github.com/anastaci1a/localica/issues/835): Respect Android animation duration and Remove animations.
- [#836](https://github.com/anastaci1a/localica/issues/836): Bound popup retirement when a mounted trigger is no longer drawn.
- [#837](https://github.com/anastaci1a/localica/issues/837): Keep a resting player at rest during collection navigation.
- [#838](https://github.com/anastaci1a/localica/issues/838): Preserve ongoing scrollbar motion when reduced motion changes; verify steady scalar reads without repeated allocation.
- [#841](https://github.com/anastaci1a/localica/issues/841): Check merge commits without the workflow-guard false positive.
- [#846](https://github.com/anastaci1a/localica/issues/846): Restore the accepted drawer, lyrics and popup motion laws and their speed behavior.
- [#847](https://github.com/anastaci1a/localica/issues/847): Stop popup refraction after the restored trigger presents, preventing the retract handoff defect.
- [#848](https://github.com/anastaci1a/localica/issues/848): Coalesce non-advancing frame times instead of crashing the shared motion clock.
- [#851](https://github.com/anastaci1a/localica/issues/851): Correct the auto-scroll drawn-cover fixture's real-panel color readback.
- [#852](https://github.com/anastaci1a/localica/issues/852): Compare Queue shadow parity on the same prepared branch in the regression fixture.
- [#853](https://github.com/anastaci1a/localica/issues/853): Publish the engine record and fixture time in the subview fling/pinch test.
- [#858](https://github.com/anastaci1a/localica/issues/858): Sample popup returns at presented frames, preserve and reclaim the returned native window, and keep undrawn retained-trigger retirement within six frames.
- [#860](https://github.com/anastaci1a/localica/issues/860): Add the animation speed slider and preserve existing Faster motion preferences during migration.
- [#862](https://github.com/anastaci1a/localica/issues/862): Add the option to respect or ignore Android's animation scale, with Reduce motion taking precedence.
- [#869](https://github.com/anastaci1a/localica/issues/869): Restore selection motion and transformed home registration; correct Reduced fixtures and native IME test isolation.
- [#870](https://github.com/anastaci1a/localica/issues/870): Include shared-test instrumentation classes in the native inventory, assign every method once, and preserve summary errors safely.

## Partially delivered

- [#854](https://github.com/anastaci1a/localica/issues/854): The measured Apple model ships for popups, menus, row menus and dialogs. App-wide adoption remains open: sheets keep their prior laws, and other non-physics motion is not yet adopted.


## Known issues and follow-ups

- [#850](https://github.com/anastaci1a/localica/issues/850): Add debug visibility for consecutive non-advancing clock frames.
- [#856](https://github.com/anastaci1a/localica/issues/856): Cover cancellation after native layout while admission receipts remain pending.
- [#857](https://github.com/anastaci1a/localica/issues/857): Remove the remaining receipt-wait overlap during popup return.
- [#859](https://github.com/anastaci1a/localica/issues/859): Make settled shadow caching pixel-identical and strengthen the drawer shadow guard.
- [#861](https://github.com/anastaci1a/localica/issues/861): Standardize animation speed scaling across every animation in the app (some animations outside the motion engine do not yet follow the speed slider or the system-scale choice).
- [#863](https://github.com/anastaci1a/localica/issues/863): Preserve continuity when the Android animation scale changes during motion.
- [#864](https://github.com/anastaci1a/localica/issues/864): Verify native-plane ownership after the trigger-return deadline expires; a visible defect remains unproven.
- [#865](https://github.com/anastaci1a/localica/issues/865): Cover live reduced-motion toggles during scrollbar motion through the full screen.
- [#866](https://github.com/anastaci1a/localica/issues/866): Qualify scrollbar drag timing on a non-debuggable A35 at operator library scale. No drag timing claim is made.
- [#867](https://github.com/anastaci1a/localica/issues/867): The Library pill can draw over the More menu for 1 to 2 frames at the start of its close; present in this release, investigation tracked.
- [#868](https://github.com/anastaci1a/localica/issues/868): Share complete-lane deferral and diagnostic assumption policy with the prototype lane.
- The extended reduced-motion audit of popup handoffs, Search return/reopening and live reduction remains incomplete.
- Other tracked work remains open: [#787](https://github.com/anastaci1a/localica/issues/787), [#824](https://github.com/anastaci1a/localica/issues/824), [#738](https://github.com/anastaci1a/localica/issues/738), [#734](https://github.com/anastaci1a/localica/issues/734), [#530](https://github.com/anastaci1a/localica/issues/530), [#780](https://github.com/anastaci1a/localica/issues/780) and [#794](https://github.com/anastaci1a/localica/issues/794).
- The 8.3 ms whole-frame performance objective remains unmet and unchanged. Device visual acceptance of the new motion does not qualify frame timing.

Every included method and both exact-once unions must pass. Intentional diagnostic assumptions remain recorded skips. If the known #786 IME shard flake recurs, the operator permits rerunning only that shard, with method-level review and the actual rerun recorded here before publication.

## Process

This release retains the valid v0.33.9/v0.33.10 class-duration profile for shard balancing. A one-train lead exception permits this because the preceding v0.33.21 run includes failed timing cases rejected by the refresh guard. Every included test and both exact-once unions remain required to pass.

The earlier focused API 36 run reached its 35-minute timeout before writing XML. Publication requires the complete run plus method-level audits of the same-source tail and split proofs.

## Install

1. Download `localica-v0.34.1-pre-release.apk` from **Assets** below (Android 8+).
2. Open the APK and allow installation from your browser or file manager if prompted.
3. First use: open **More → Scan music**, choose **Folders to scan**, then **Scan now**.
