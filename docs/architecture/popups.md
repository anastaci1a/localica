# Popups

A popup is one glass surface that grows out of its trigger and returns into it. Proposed: each popup has one phase, written in one place: closed, admitting, in its window, restoring, or handed off. One function turns that phase into who draws, what the glass samples, and who takes touch. Trigger differences are data, not branches.

- Implemented: every popup is drawn by shared owners, opens from a trigger and returns to it as one visible surface, on the shared [motion](motion.md) law.
- Proposed (#920): the single phase and the single presentation function. Today the same facts are spread over many flags.
- Proposed (#927): one top popup layer, with lift-and-drop of the source.
- Released in v0.34.2 (#926): closing uses the main law on every edge. The fix that keeps a plain close above bottom chrome is Incomplete.
- Screens never own a popup home, window or trigger. The [lint gate](ui-system.md#the-lint-gate) enforces it.

## Terms

| Term | Meaning |
| --- | --- |
| Trigger | The control a popup opens from. |
| Window | The native dialog window that draws an open popup. |
| Home | The screen layer that hosts a trigger. It can draw the popup's recorded pixels itself. |
| Handed off | The home draws the popup, so it moves, fades and clips with its screen. |
| Sampling basis | What the glass refracts: a recorded backdrop and the mapping onto the popup. |

## Lifecycle

![Popup lifecycle: closed, admitting, in window, restoring, and handed off](../assets/diagrams/popup-lifecycle.svg)

The diagram shows the proposed phases. A popup leaves Closed for Admitting, enters its window, then restores its trigger and closes. Handed off is a side state entered while the screen behind draws the popup.

| Phase | What is true |
| --- | --- |
| Closed | The trigger draws itself and takes input. |
| Admitting | The window is prepared before the trigger hides, so no frame shows neither or both. |
| In window | The window draws the popup while it opens, rests and closes. Only the top popup takes input. |
| Restoring | Input is released at dismissal. The drawing stays until the trigger acknowledges that it is visible again. |
| Handed off | The opening screen is leaving or covered. The home draws the popup and supplies movement, fade and clip. |

Rules that hold today, whatever the storage:

- A hidden trigger accepts no input.
- A visibly restored trigger can reverse the return at once, with one owner and no settling lock or cooldown.
- A dismissed popup releases input immediately, while its exit drawing finishes.
- Reopening a retained, prepared popup reclaims its surface and shadow at once.
- A popup belongs to its opening screen. A destination screen never inherits a floating popup.
- After the screen is disposed, the same owner resumes the return and retires through native receipts.

### Why one phase

Today "the popup has taken over from its trigger" is stored in several flags that are set by hand. Most popup defects had one shape: two readers derived different answers from the same flags. With one phase and one writer, those disagreements cannot be written.

In the proposal, every wait belongs to a phase. Leaving the phase cancels its waits, and every wait has a bounded exit. A trigger type is one row of policy data: where it returns, and which backdrop it samples.

## Layers and lift-and-drop

![Popup layers: screen content, bottom chrome, the popup layer holding popup and lifted source, and page transitions on top](../assets/diagrams/popup-layers.svg)

The diagram stacks four layers from bottom to top: screen content, floating chrome, the popup layer, page transitions. Status: Proposed (#927).

1. An open popup lives in one top layer: above every screen and menu element, below page transitions.
2. Lift: on press or open, the source (trigger, pill or held item) lifts into that layer with the popup. Growth stays continuous even when the source sat behind a foreground control.
3. Drop: on close, the popup returns into the lifted source. The source drops back to its own layer only after the popup has fully become it.
4. The glass samples everything beneath the popup layer, never itself or its source. This rules out sampling cycles.
5. Placement (anchored or centred) is data per popup type. Motion is the one shared law, closing included.

## Windows and the modal stack

The theme keeps modal state through exit. Caller locals are restored before each window is created, so view and focus ownership survive. A nested window samples its completed parent panel.

One logical modal stack does three things when a popup opens: it cancels and quarantines the native pointer streams of lower windows, stops registered scroll motion, and rejects stale queued flings. This holds through window recreation and exit drawing.

App-drawn scrims replace native dim layers. A native dim layer's invisible surface can still block touches during the return.

## Material and ink

- Trigger and popup are one continuous material. Its scattering and tint change along the way; no endpoint surface is swapped in.
- Trigger and popup share deformation, corner policy and optical parameters. Their source recordings blend before handoff, without discarding an unsettled rebound.
- Foreground and optical tone inherit the trigger's explicit ink, then the launching appearance, otherwise the ambient ink.
- A popup that owns a grouped trigger's shadow matches the group's shadow at home and separates only as it opens.

How the home footprint is sampled is in [Glass](glass.md#what-glass-samples).

## Size, keyboard and chrome

- Glass is measured from foreground content. Its padded backdrop is fitted separately.
- Fixed menu layout scales uniformly into the moving viewport. Menu width fits measured labels, icons and padding within the screen.
- A retained dialog keeps a fixed height. A content-sized dialog eases its measured height.
- Sizing and inset presets live in `ui.spec`. `ui.layout.PopupViewportSlot` owns the container, inset consumption and the keyboard measurement that fixes content height.
- The popup's own window reports the keyboard target. Size and placement follow it together, and a retarget keeps current position and velocity.
- `GlassMorphContent` moves between faces inside one surface. Only the destination's children are placed; outgoing faces draw without input.

A scrollable body fades only at an edge that hides more content. A body that fits has no edge fade. The shell and the fixed title and footer stay outside that mask. Rim highlights follow curved corners.

Search is a popup with one difference: its field stays unscaled and top-aligned. Its behaviour is in [Search and sort](../interface/search-and-sort.md).

## Tests

| Topic | Classes |
| --- | --- |
| Ownership across screens | `PopupHostNavigationTest`, `PopupParentPendingHandoffTest` |
| Morph and faces | `GlassMorphTest`, `MorphFacesTest` |
| Windows, slot and keyboard | `PopupWindowHostContractTest`, `PopupViewportSlotTest`, `PopupImeGeometryTest`, `PopupEditorFocusOwnershipTest` |
| Edges, rim and chrome | `PopupScrollEdgesTest`, `PopupRimTest`, `DialogChromeLayoutTest` |
| Shape and shadow | `PopupHomeShapeTest`, `PopupShadowContinuityTest` |
