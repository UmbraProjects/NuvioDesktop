# innertubex

`com.nuvio.app.features.music.InnerTubeXFallback`, with its `INSTANCE` field.

Why: `MusicStreamFallbacks.load` finds the InnerTubeX resolver by name (`Class.forName(...).getField("INSTANCE")`),
because the class is compiled in only with `-Pnuvio.innertubex=true` and the rest of the app must build without
it. Native-image cannot see a by-name lookup, and with `--exact-reachability-metadata` an unlisted one fails, so
without this entry the native exe logged "no fallback resolver in this build" even when built with the flag. In a
build without the flag the class does not exist and the entry is unused.

The resolver opens age-gated YouTube tab videos and is the second-chance lookup for music tracks.
