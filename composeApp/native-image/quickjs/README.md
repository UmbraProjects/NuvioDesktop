# quickjs

`java.util.LinkedHashMap`, JNI-accessible with its public (incl. inherited) methods.

Why: plugins run in QuickJS (quickjs-kt, `PluginRuntime`). When a plugin finishes, quickjs-kt's
native code builds the JavaScript result as a `LinkedHashMap` through JNI (`GetMethodID` for `put`,
which is declared on `HashMap`). Without this entry every plugin failed at that last step with
`NoSuchMethodError: java.util.LinkedHashMap.put`, so every scraper returned 0 streams and nothing
appeared in the stream list. Found by the step-3 QA build on 2026-10-17 (258 failures in one
session). quickjs-kt's own bundled metadata does not cover it.

The `libquickjs.dll` extraction needs the `jni/windows_x64/**` glob in `resources/`.
