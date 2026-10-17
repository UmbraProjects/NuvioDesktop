# awt-jni

`java.awt.*` (event, image, peer, dnd, geom), `sun.awt.*` (windows, image, im), `sun.java2d.*`
(windows, loops, pipe, d3d, opengl), all JNI-accessible.

Why: AWT's Windows native code builds Java objects through JNI. With the JDK 21 trace alone the
mouse wheel was dead (AWT-Windows could not build `MouseWheelEvent` / `java.awt.Event`), and
`sun.awt.windows.WCanvasPeer.setBackground` was missing for the player surface. The spike
registered the whole packages rather than chasing classes one at a time (~1,080 classes,
exe 191 → 201 MB). Test for the wheel: PostMessage `WM_MOUSEWHEEL` to the HWND and check the log.

Also `SwingInteropViewGroup` and `NativePlayerHost` with their declared methods (reflection, not
JNI): AWT checks reflectively whether a component subclass overrides `coalesceEvents`. Neither
does, so the answer was right without the entry, but the QA build reported the lookup (2026-10-17).

Could shrink after a GraalVM 25 trace, which plan step 3 no longer does; leave it unless something
proves an entry unnecessary.
