# quickjs-kt native library (patched)

The native half of `io.github.dokar3:quickjs-kt` **1.0.15** (tag `v1.0.15`, commit
`a081755bcdb18ee6afa7ffee25b8fa976f06d31f`, QuickJS submodule `04be246`), vendored so the app can
ship its own `libquickjs.dll` instead of the one inside the jar. Only the files the Windows JNI
build compiles are here: `quickjs/` (QuickJS, MIT), `common/`, `jni/`, `c-vector/cvector.h` (MIT),
`quickjs.def`. quickjs-kt itself is Apache-2.0 (`LICENSE.txt`).

Built by `:composeApp:buildWindowsQuickJs` with MSYS2 UCRT64 GCC into
`build/native/windows/libquickjs.dll`, staged beside `Nuvio.exe`, and loaded through quickjs-kt's
own `com.dokar.quickjs.library.path` property (`PluginRuntime.kt`). The Kotlin half still comes
from the Maven artifact, so the version here must match `quickjsKt` in `gradle/libs.versions.toml`.

## The one change: `binding_bridge-local-frames.patch`

Every JS -> Kotlin binding call (`jni_invoke_getter/setter/function/async_function`) runs inside
its own `PushLocalFrame`/`PopLocalFrame`. Upstream leaks the argument array, the boxed promise
handles and the host's return value of each call; they live until the outermost Java -> native call
returns, which for a scraper is one whole pending job (a page parsed through the cheerio bindings:
thousands of calls). On GraalVM native image a deleted local slot is not reused until its frame
pops either, so each job grew the thread's JNI handle table without bound. A startup heap dump
(2026-10-19) had 34.6 MB of handle tables holding ~49 MB of Strings. See
`Docs/plans/ui-smoothness-plan.md`, step 18.

To upgrade quickjs-kt: copy the same files from the new tag, re-apply the patch, bump the version
above and in `libs.versions.toml` together.
