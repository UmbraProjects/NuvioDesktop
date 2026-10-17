# resources

Globs for resources the app reads from its own jars at run time: `composeResources/**`,
`native/**`, `player-shaders/**`, `player-ui/**`, `player-scripts/**`, `icons/**`,
`hero_discovery.json`, `META-INF/services/**`, `jni/windows_x64/**`,
`ai/onnxruntime/native/win-x64/**`.

Why: a native image contains only the resources it is told about. Notable:
- `jni/windows_x64/**`: quickjs-kt ships its own metadata but extracts `libquickjs.dll` from its
  jar. Without the glob every plugin returned 0 streams instantly, with nothing logged.
- `player-ui/**`: the player HUD is HTML/CSS/JS loaded from resources.
- `META-INF/services/**`: ServiceLoader lookups (Ktor engines, coroutines, etc.).

To do (plan step 4): derive these from `externalDesktopResources` and the compose resource
directories in `build.gradle.kts` instead of listing them by hand.
