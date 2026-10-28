# Continuous lyrics-source phase — isolated validation plan

Base: delivered dev73 cbf72276f60d373c9494e56351c2219a3b51233b.

## Intended change, not an established result

Replace the source-button preparation/full-settle/menu-expansion sequence only for callers providing GlassOriginPreparation. One retained scalar owner carries velocity through ordinary visibility and speed reversals. Preparation and popup expansion overlap. Ownership safety gates can hold motion while the native window or restored trigger is not ready; a canceled never-admitted opening has a documented zero-velocity boundary impulse rather than admitting unwanted UI.

This changes preparation timing. Smooth scalar mapping is not proof of spatial/optical continuity, unchanged original ease, or perceived smoothness. Native geometry/visual review is required before any claim. Irregular-selection optics remains unresolved and is not part of this candidate.

## Required safety/geometry behavior

- Premeasure one native window without rendering, semantic exposure, focus or touch interception; retain usable source/hide controls until actual admission.
- The source reports its actual placed/drawn phase. Native surface placement is tied to exact window identity. Do not substitute guessed frame delays for either.
- During overlapping preparation, use live source geometry/footprint; no frozen capture while the header still moves.
- Popup expansion and preparation overlap in both directions; no fully settled intermediary spring restart.
- At return seam, keep the returned modal surface until actual restored-trigger drawing. Only then rejoin the neck. Reversal follows actual phase crossings, not requested target alone.
- Generation plus crossing identity prevents canceled readiness/draw callbacks from releasing another flight.
- Dispose resets ownership and abandons old continuation. Generic and Search modal paths remain unchanged.

## Existing test intent changes

GlassOriginPreparationTest's six existing methods remain. Tests previously demanded sequential full preparation and zero native mounts before admission; these requirements conflict with the approved continuous choreography. Replacements demand observed overlap both ways and non-touchable/non-focusable premeasurement, while preserving endpoint, restored-draw, canceled opening, retained-window, reopening-during-rejoin and reduced-motion checks. Immediate ownership after reopen is not asserted when retained negative velocity still needs to cross/reverse safely; same native-window reuse and coherent ownership are required instead.

LyricsHeaderActionsTest retains both real-header/cancellation cases and captures; its pre-admission sample moves from 100 ms to 64 ms because the new opening seam is approximately 101 ms before native readiness holds. This is an intentional timing-spec change, not evidence the UI is correct.

Controller unit tests are authored but uncompiled/unrun. Isolated workflow will compile app/instrumentation, run full JVM/lint and complete GlassOriginPreparationTest, LyricsHeaderActionsTest, GlassMorphTest, GlassDialogReopenTest, GlassDialogLateReopenTest plus mandatory SearchImeMotionTest, ListSearchTest, FrostedSurfaceTest on API26/36. Existing strict inventory/source hashes/original XML/qualified skips/zero-failure monitor rules remain intact. No gate weakening or release certification.
