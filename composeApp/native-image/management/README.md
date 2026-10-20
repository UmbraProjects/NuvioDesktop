# management

The native image's own management beans (`com.oracle.svm.core...MXBean`: GC, memory pools, code
cache, runtime, threads, class loading, compilation).

Why: `java.lang.management.ManagementFactory` registers the platform beans reflectively. The app
reads the GC beans for `(FullGc)` / `(FrameStall)` logging (`IdleHeapCollector.kt`,
`SteadyFramePacing.kt`) and the OS bean for `(MemoryTrim)`. Without these entries the reads still
worked, but the QA build (2026-10-17) reported each bean as a missing registration at startup.

`org.graalvm.nativeimage.VMRuntime.dumpHeap`: the perf-logging-only heap dump
(`HeapDumpOnHighLiveSet` in `IdleHeapCollector.kt`). The native image has no
`HotSpotDiagnosticMXBean`, so it is reached by reflection (needs `--enable-monitoring=heapdump`).
