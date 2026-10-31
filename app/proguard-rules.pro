# Room and Media3 ship their consumer rules. Keep app-specific rules here when needed.
# Jaudiotagger is configured for Android; its desktop image implementations are unused.
-dontwarn java.awt.**
-dontwarn javax.imageio.**
# Jaudiotagger instantiates tag FrameBody and format classes by name (Class.forName).
-keep class org.jaudiotagger.** { *; }
-dontwarn org.jaudiotagger.**
