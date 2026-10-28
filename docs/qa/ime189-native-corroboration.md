# IME189 zero-height exposure corroboration — isolated candidate

Base dev74 9186bf5d9a16732535a5cb11dda3e673bc299d56. Not integrated or approved for delivery.

Observed failure: a scoped editor inherited Compose visible=true with current/source/target zero while its native root reported invisible. That falsely armed floating exposure, then Compose's false edge ended editing before the keyboard arrived. Original evidence exists in runs40653718937 and40683771521. A later green retry does not prove a fix.

Only zero-height arming on API30+ now requires a fresh attached/focused same-host native IME-visible observation. No preceding native true sample is latched. Positive-height settled arming and all existing hide/Back predicates remain; legacy API26 mapping retains its previous default.

Native-only visibility can change without Compose state/layout/draw. A retained Choreographer callback runs only for a focused, window-focused, Compose-visible all-zero editor. Its initial activation and subsequent visibility changes emit a revision, but the decision rereads the native root. No insets listener, animation callback, input interceptor or InputConnection is replaced. Host focus-loss/detach synchronously invalidates prior exposure and increments a generation, so coalesced loss/refocus cannot carry an armed state through a lifecycle gap. Exact callback/listener cleanup is required.

Tradeoff: an active floating keyboard keeps a lightweight native-frame query alive. Hidden/hardware-keyboard, docked and unfocused states do not poll. This is not a claim of measured battery or physical-device performance improvement.

Validation plan: preserve all original TextEditingImeSession JVM tests and append six targeted transition contracts. A new native bridge contract uses atomic injected visibility (not Compose state), pauses the Compose clock, checks native-only wakeup/actual session arming/exactly-once hide, input-generation stability, scope/disposal cleanup and real covering-window focus invalidation. This is not a real floating IME. Run complete unchanged SearchImeMotionTest, ListSearchTest and FrostedSurfaceTest plus new class on APIs26/36, full JVM/lint, exact source inventory and original evidence.

No promotion until real floating-keyboard compatibility is established separately. The repository currently has no real floating IME fixture. A docked keyboard's transient zero insets or injected native visibility is not such evidence. Native confirmation is corroboration, not a universal proof: if both Compose and native transiently report visible/zero, this narrow change cannot distinguish that from floating exposure.
