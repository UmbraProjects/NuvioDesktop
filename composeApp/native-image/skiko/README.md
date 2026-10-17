# skiko

`org.jetbrains.skiko.FrameWatcher`, public methods and fields.

Why: `IdleHeapCollector.start()` (desktopMain `core/ui/IdleHeapCollector.kt`) sets
`FrameWatcher.gcDelayMillis` by reflection on the native build. skiko calls `System.gc()` every 30 s
once frames render; on the JVM build that is concurrent, but on the native image (Serial GC) it is
a full ~200 ms stop-the-world pause, the regular hitch in normal use (2026-10-16). `FrameWatcher` is
Kotlin-`internal` to skiko, hence reflection. The log line "skiko's periodic GC stopped" confirms it
worked; "could not stop skiko's periodic GC" means this entry is missing.
