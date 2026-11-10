# Room and Media3 ship their consumer rules. Keep app-specific rules here when needed.
# Compose 1.10's API-34 text range adapter must stay behind its SDK guard.
# R8 8.13.19 otherwise inlines its allocation into AndroidParagraph, making API 26
# verification resolve Layout$TextInclusionStrategy during ordinary text layout.
# Preserve this helper's boundary while still allowing removal and renaming.
-keep,allowshrinking,allowobfuscation class androidx.compose.ui.text.android.AndroidLayoutApi34 { *; }

# Jaudiotagger is configured for Android; its desktop image implementations are unused.
-dontwarn java.awt.**
-dontwarn javax.imageio.**
# Jaudiotagger instantiates tag FrameBody and format classes by name (Class.forName).
-keep class org.jaudiotagger.** { *; }
-dontwarn org.jaudiotagger.**

# Experimental storage diagnostics only, on the disposable queue proof ref.
# The production actor is not cut over yet, so R8 would otherwise remove these test targets.
-keep class io.github.anastaci1a.localica.queue.LegacyQueueOrderStream { *; }
-keep class io.github.anastaci1a.localica.queue.QueueSequence* { *; }
-keep class io.github.anastaci1a.localica.queue.PositionedQueueEntry { *; }
-keep class io.github.anastaci1a.localica.queue.QueueStorageState { *; }
-keep class io.github.anastaci1a.localica.queue.QueuePlaybackWindow* { *; }
-keep class io.github.anastaci1a.localica.queue.QueueTraversal* { *; }
-keep class io.github.anastaci1a.localica.queue.QueueWindowStore { *; }

# The diagnostic fixtures access logical counts before the runtime cutover.
-keep class io.github.anastaci1a.localica.queue.ListeningQueue { *; }
