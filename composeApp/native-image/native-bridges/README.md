# native-bridges

JDK value classes registered for JNI by the spike: boxed numbers and `Boolean`, `ByteBuffer`,
`ByteOrder`, `ArrayList`, `HashMap`. The app's own C++ no longer looks any of them up. They stay
until a QA build shows nothing else reaches them through JNI, and then they go.

The app's real bridge entries are **generated** by `generateNativeMetadata` (plan step 4) from the
`FindClass` / `GetMethodID` / `GetFieldID` / `ThrowNew` literals in `player_bridge.cpp` and the
nuvio-engine `jni_bridge.cpp`, into `build/native-image/generated/`. That covers
`NativePlayerEventSink`, the `com.nuvio.engine.internal.Native*Payload` classes,
`IllegalStateException` and `String`. The generator fails the build on any lookup it cannot
resolve (`GetObjectClass`, an inline expression). That is why the player looks up `onPlayerEvent`
on the `NativePlayerEventSink` interface and not on the sink lambda's runtime class.

Why that matters: playback once went into a failover loop whose only message was
"java/lang/IllegalStateException". `GetMethodID(onPlayerEvent)` failed on the unregistered sink
lambda, then `FindClass` for the exception failed too, so only the class name survived.
