# UI system

Every screen is built from one shared UI system. `ui.engine` owns motion, clocks and drawing. `ui.spec` holds the numbers. `ui.components` are the controls and popups. `ui.layout` places them. Each role has exactly one implementation. Screens supply content and intent only. They never choose motion, popup homes or draw paths. A lint gate enforces this.

- To add behaviour, extend the one shared component with a parameter, slot, data field or sealed case. Do not add a second implementation or a subclass.
- A release must pass the gate with no new override. The count of old overrides is recorded per rule and may only go down.
- One input rule serves every control, the Queue drawer and the player.
- Status: the four packages and the gate are Implemented. Adoption is Incomplete: most screen code still lives in the root `ui` package.

## Why one system

A menu must look right the first time it is built. If two screens each draw a popup, they drift apart, and every visual fix must be made twice. With one implementation per role, a visual inconsistency is a defect in the system. It is fixed once, for every screen.

## The four packages

| Package | Owns | Examples |
| --- | --- | --- |
| `ui.spec` | Immutable numbers and tokens: spacing, radii, control sizes, clearances, motion profiles, spring laws, material tokens. | `UiSpec`, `PopupViewportSpec`, `ScrollbarSpec` |
| `ui.engine` | The frame clock, motion values, expansion and phase, the compositor, shadow planes and material reduction. | `UiClock`, `ExpandCollapse`, `Compositor`, `PopupWindowMotion` |
| `ui.components` | What screens call: popup, sheet, drawer, presence, retained layers, reveal and scrollbar input. | `Popup`, `TriggerRole`, `Sheet`, `Drawer`, `Presence` |
| `ui.layout` | Slots that place content and consume insets. | `PopupViewportSlot`, `SettingsSlots`, `ReturnControlSlot` |

A screen passes a role and content. `TriggerRole` is content identity only: the shared components alone map a role to motion and rendering policy. `LocalicaEngineRoot` installs the engine once at the root.

## Rules for screens

| A screen may | A screen may not |
| --- | --- |
| Supply content, labels, icons and callbacks. | Provide its own popup home or popup window. |
| Name a role or a spec token. | Pass a motion kind, law, duration or easing. |
| Ask a shared state object to open, dismiss or select. | Build its own trigger or its own draw path for a shared role. |
| Request a new parameter on the shared component. | Copy a component to change it. |

"Extend" means composition, not inheritance. A new subclass or abstract layer needs a reviewer to agree the case truly requires one. When a duplicate is found, consolidate it.

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

A control shows contact at once but defers ownership of movement. One rule decides who owns a contact.

| Contact | Owner |
| --- | --- |
| Travel before a 250 ms hold, toward a direction an ancestor can still move | The ancestor: a scrollable short of that edge, the open Queue drawer or the player. The control lets go with its normal release. |
| Travel no ancestor can use: a menu that cannot scroll, a reached edge, a horizontal slide in a vertical list | The control, immediately. |
| Contact held still for 250 ms | The control, for any later direction. |

- The owner keeps the contact through reversals and edges until release. Switching needs a fresh gesture.
- Cancellation never commits.
- One gesture changes one surface level. Lyrics, reordering, carousels and overflow bars never swipe their parent once acquired.
- Artwork has diagonal priority over clearly vertical parent gestures.

Opening a modal cancels background contacts and flings, rolls back uncommitted pinch and reorder previews, and discards their later releases. Dismissal permits fresh gestures and never resumes old fingers. Only the top modal accepts input.

Tests: `ButtonGestureTest`, `ModalGestureCancellationTest`, `ModalGestureTakeoverTest`, `QueueScrollOwnershipTest`.

## The lint gate

The `lint` module supplies eighteen ERROR rules to the app through `lintChecks`. They come in two groups with different debt rules.

| Group | Debt rule |
| --- | --- |
| Five popup conformity rules: screen popup homes, motion overrides, private triggers, private draw paths, duplicate popup roles | No baseline and no escape. Old incidents are counted in `popup-ratchet.txt`. |
| Thirteen general UI rules: raw timing, transforms and spacing outside the shared system | Old incidents are listed in the generated `app/lint-baseline.xml`. |

Both groups follow the ratchet:

- A release passes with zero new incidents.
- A recorded count may only go down. An equal-count replacement fails.
- A strengthened rule records its count on the day it lands, then only goes down.
- The baseline is never regenerated to accept new debt. A rule is never disabled to hide a finding.

Popup ownership uses exact paths. `popup-owners.txt` pins the shared owner files, `popup-roles.txt` pins the home and trigger declarations, and `popup-hosts.txt` pins how many homes each shell host may create. A new file beside an owner gains no ownership.

`app/ui-escapes.yaml` is empty. An adapter exception needs a tracking issue, an owner, a concrete expiry and a recorded review. The comparison runs against an immutable `main` commit. Commands are in [Testing](../development/testing.md#ui-policy-checks).

Passing lint does not prove motion continuity or performance. Those have their own tests and [budgets](../development/performance.md).

Tests: the `:lint` module tests, `ReleaseLintAdoptionTest`.

## Design before patch

A failing test is a question about the model, not a request for a guard. A change to shared UI code starts as a short design note, reviewed before code. The full flow is in the [contributor entry point](../../AGENTS.md#design-note-first).

## Related pages

| Topic | Page |
| --- | --- |
| Popup lifecycle and layers | [Popups](popups.md) |
| Springs, families and Reduced motion | [Motion](motion.md) |
| The material | [Glass](glass.md) |
| Artwork, recordings and shadows | [Rendering](rendering.md) |
