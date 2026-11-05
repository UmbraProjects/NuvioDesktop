# java-sound

Java Sound's Windows mixers (`com.sun.media.sound.DirectAudioDevice*`, `PortMixer*`, `Platform`),
JNI-accessible with their constructors and methods, plus `java.util.Vector` and
`javax.sound.sampled.AudioFormat` / `Control`, which `jsound.dll` fills through JNI.

Why: `jsound.dll` builds the mixer list from native code: `nNewDirectAudioDeviceInfo` /
`nNewPortMixerInfo` call the `DirectAudioDeviceInfo` / `PortMixerInfo` constructors,
`nGetFormats` calls the static `DirectAudioDevice.addFormat` into a `Vector`, and
`PortMixer.nGetControls` builds `BoolCtrl` / `FloatCtrl` / `CompCtrl`. None were in the JDK 21
trace (voice control did not exist then), so in the native exe every mixer info came back null
and voice control failed with a `NullPointerException` in `VoiceAudio.openMicrophone`
("No microphone could be opened", 2026-11-04). Code: `features/voice/VoiceAudio.kt`.
