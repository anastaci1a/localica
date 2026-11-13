# UI system

Every screen is built from one shared UI system. `ui.engine` owns motion, clocks and drawing. `ui.spec` holds the numbers. `ui.components` are the controls and popups. `ui.layout` places them. Each role has exactly one implementation. Screens supply content and intent only. They never choose motion, popup homes or draw paths. A lint gate enforces this.

- To add behaviour, extend the one shared component with a parameter, slot, data field or sealed case. Do not add a second implementation or a subclass.
- A release must pass the gate with no new override. The count of old overrides is recorded per rule and may only go down.
- One input rule serves every control, the Queue drawer and the player.
- Status: the four packages and the gate are Implemented. Adoption is Incomplete: most screen code still lives in the root `ui` package.

## The four packages

| Package | Owns | Examples |
| --- | --- | --- |
| `ui.spec` | Immutable numbers and tokens: spacing, radii, control sizes, clearances, motion profiles, spring laws, material tokens. | `UiSpec`, `PopupViewportSpec`, `ScrollbarSpec` |
| `ui.engine` | The frame clock, motion values, expansion and phase, the compositor, shadow planes and material reduction. | `UiClock`, `ExpandCollapse`, `Compositor`, `PopupWindowMotion` |
| `ui.components` | What screens call: popup, sheet, drawer, presence, retained layers, reveal and scrollbar input. | `Popup`, `TriggerRole`, `Sheet`, `Drawer`, `Presence` |
| `ui.layout` | Slots that place content and consume insets. | `PopupViewportSlot`, `SettingsSlots`, `ReturnControlSlot` |

A screen passes a role and content. `TriggerRole` is content identity only: the shared components alone map a role to motion and rendering policy. `LocalicaEngineRoot` installs the engine once at the root.

## Rules for screens

[Contributor rules](../../AGENTS.md) require composition through one owner per role. Screens supply content, callbacks and intent. Ownership and policy stay inside the shared system; reviewers approve any required inheritance.

## Roles and owners

These owners predate the four packages and still live in the root `ui` package. Each is the single implementation of its role.

| Role | Owner |
| --- | --- |
| Song and collection menus across rows, artwork holds and Home | `CollectionActions`; `ActionMenuItem` requires an explicit icon. |
| Material of a dragged song stack | `HoverDragCard` |
| Material of a dragged action during customization | `GlassDragBubble` |
| Grid insets and row layout for browsing, pinch and lifted cards | `BrowseCellGeometry`, `BrowseItemMetrics` |
| Settings rows | The shared row in `ui/PreferenceFrames.kt` |
| Gesture capture, reversal and commit of a pinch | `BrowsePinch` |

## Input ownership

Before a 250 ms hold, travel toward an ancestor that can still move belongs to that ancestor. Otherwise the control owns the contact. Ownership lasts through reversal/edges until release; cancellation never commits. One gesture moves one surface level.

Opening a modal cancels lower contacts/flings and uncommitted pinch/reorder previews. Dismissal permits fresh gestures only. The top modal alone takes input. ButtonGestureTest and modal cancellation/takeover tests specify the event sequences.

## The lint gate

Eighteen ERROR rules arrive through lintChecks. Five popup rules prohibit private homes, motion, triggers, drawing and duplicate roles. They have no baseline/escape; popup-ratchet.txt records existing counts. Thirteen general timing/transform/spacing rules retain existing findings in app/lint-baseline.xml. Both permit zero new incidents, including equal-count replacements. Counts only decrease; strengthened rules record their initial count on adoption.

Exact-path popup-owners.txt, popup-roles.txt and popup-hosts.txt pin owners, declarations and host counts. Neighbouring files gain no ownership. app/ui-escapes.yaml is empty; reviewed adapter exceptions need an issue, owner and expiry. Comparison uses immutable main. Never regenerate baselines or disable rules to hide findings.

[Testing](../development/testing.md#ui-policy-checks) owns commands. The lint module and [ReleaseLintAdoptionTest](../../app/src/androidTest/java/io/github/anastaci1a/localica/ReleaseLintAdoptionTest.kt) assert enforcement; passing lint does not establish motion/performance.

## Design before patch

A failing test is a question about the model, not a request for a guard. A change to shared UI code starts as a short design note, reviewed before code. The full flow is in the [contributor entry point](../../AGENTS.md#design-note-first).

## Tests

[ButtonGestureTest](../../app/src/androidTest/java/io/github/anastaci1a/localica/ButtonGestureTest.kt).

[ModalGestureCancellationTest](../../app/src/androidTest/java/io/github/anastaci1a/localica/ModalGestureCancellationTest.kt).

[ModalGestureTakeoverTest](../../app/src/androidTest/java/io/github/anastaci1a/localica/ModalGestureTakeoverTest.kt).

[QueueScrollOwnershipTest](../../app/src/androidTest/java/io/github/anastaci1a/localica/QueueScrollOwnershipTest.kt).
