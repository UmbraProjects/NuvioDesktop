# Native-image build inputs

Everything the GraalVM native-image build of Nuvio needs besides the app's own jars. Plan and
background: `Docs/plans/native-image-shipping.md`; spike history:
`Docs/handoff-2026-10-16-perf-native-memory.md`.

| Path | What |
|---|---|
| `agent/` | Output of the native-image tracing agent (legacy multi-file format). Recorded on JDK 21 during the 2026-10-16 spike; plan step 3 replaces it with a fresh trace on GraalVM 25. Do not hand-edit: re-trace instead. |
| `<topic>/reachability-metadata.json` | Hand-written entries the trace missed, one directory per reason. Each has a `README.md` saying why it exists, because JSON has no comments. |
| `nuvio-launcher.manifest` | The jpackage launcher's PerMonitorV2 DPI manifest. Embedded into the native exe with `mt.exe`; without it Windows DPI-virtualises the window (1920x1080 stretched on a 4K screen, broken borderless fullscreen). |

The build passes every directory here to `-H:ConfigurationFileDirectories` (comma-separated),
plus `build/native-image/generated/`.

**Generated, not here** (plan step 4): `generateNativeMetadata` runs before `nativeCompile` and
writes `build/native-image/generated/reachability-metadata.json`:
- every `X$$serializer` in the app jars, as `X`, `X$Companion` and `X$$serializer`, plus
  `kotlinx.serialization.json`;
- the JNI lookups literally named in `player_bridge.cpp` and the engine's `jni_bridge.cpp`.

The generator fails the build on a C++ lookup it cannot read, so look classes up with
`FindClass("literal")` into a variable. `NativeImageReflectionCheckTest` fails when Kotlin code
looks up a class or member by name that has no entry here or in the generated file; its
`allowed` list holds the exceptions, each with a reason.

Rules:
- **No BOM.** Windows PowerShell 5.1 `Set-Content -Encoding utf8` writes one, and native-image then
  fails with "Error parsing JNI configuration". `NativeImageMetadataTest` checks every file.
- **Reflection added on purpose names its directory** in a code comment, and the directory's README
  names the code (example: skiko `FrameWatcher` in `IdleHeapCollector.kt` ↔ `skiko/`).
- A hand entry stays only while something fails without it. When a fresh trace covers a topic,
  delete the directory rather than keeping both.

Topics (2026-10-17 split of the spike's single `nuvio-config-extra` file; 3,462 types, 16 resource
entries, nothing changed in the split):

| Topic | Types | Resources |
|---|---|---|
| `awt-jni/` | 1,158 | |
| `fonts/` | 124 | |
| `swing/` | 108 | 4 |
| `onnxruntime/` | 71 | |
| `file-dialogs/` | 52 | 1 |
| `clipboard/` | 39 | 1 |
| `native-bridges/` | 9 | |
| `skiko/` | 1 | |
| `resources/` | | 10 |

Added from the step-3 QA build (2026-10-17): `quickjs/` (LinkedHashMap JNI: every plugin was
failing), `management/` (13 MXBeans), and entries in `swing/`, `file-dialogs/`, `awt-jni/`.

Step 4 (2026-10-17): `serialization/` (1,893) was deleted, because generation produces the identical
set. `native-bridges/` lost its app classes to generation. `quickjs/` did not fix plugins (they
were fixed in `PluginRuntime.kt` by never handing JS objects to Java) and can go after the next QA
round.
