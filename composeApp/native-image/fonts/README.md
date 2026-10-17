# fonts

All of `sun.font.*`, JNI-accessible.

Why: AWT fonts (the Swing tray menu, the subtitle font list) need JNI into `sun.font`, and JDK 25
calls methods the JDK 21 trace never saw (for example `Font2D.charToGlyphRaw`). The build also has
to ship `runtime\lib\fontconfig.bfc` beside the exe; that is a file, not metadata.

Expected to be covered by the GraalVM 25 re-trace (plan step 3).
