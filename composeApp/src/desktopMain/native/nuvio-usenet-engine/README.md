# Nuvio Usenet Engine

Cross-platform Usenet streaming engine for Nuvio (under active development).

The canonical source and Android release repository is
[NuvioMedia/nuvio-usenet-engine](https://github.com/NuvioMedia/nuvio-usenet-engine).

The engine turns an NZB and NNTP provider configuration into seekable HTTP media
streams. It includes pipelined NNTP connections, SIMD yEnc decoding, lazy archive
handling, file and episode selection, subtitles, and bounded in-memory article
caching. Android binaries are distributed as versioned GitHub Release assets.

This is a standalone sidecar process. The NuvioTV application supplies its
settings, indexer searches, UI and player integration separately. See
[NUVIO-INTEGRATION.md](NUVIO-INTEGRATION.md) for playback behavior and Android
integration details, and [NOTICE.md](NOTICE.md) for provenance and dependencies.

## Build and test

Run commands from this repository's root. Host builds require Go 1.27+ and a
working CGO C/C++ compiler. The vendored RapidYenc wrapper includes upstream
archives for supported Linux, macOS and Windows hosts.

```sh
go build -trimpath -o build/nuvio-usenet ./cmd/nuvio-usenet
python3 -m unittest discover -s tests -p 'usenet_prebuilts_test.py'
go test -race ./... github.com/javi11/nntppool/v4 -timeout 10m
go test github.com/javi11/sevenzip/... -timeout 5m
go test -race github.com/javi11/sevenzip/... \
  -run 'TestMetadataAllocationsAreBounded|TestReaderCancellation|TestKeyDerivation|FuzzReaderMetadata' \
  -timeout 2m
```

The executable expects a JSON bootstrap record on stdin; it is not an
interactive command-line downloader. The bootstrap supplies a control token,
optional CA certificates, a memory target and an optional NZB cache directory.
The process reports `{"protocol":1,"port":...}` on stdout and binds to
`127.0.0.1`. Keep stdin open: EOF shuts the process down.

Control requests use bearer authentication. `POST /sessions` opens an NZB,
`DELETE /sessions/<id>` releases it, and `GET /sessions/<id>/diagnostics` reports
diagnostics. Players read the returned capability URLs over ordinary HTTP,
including byte-range requests. See [main.go](cmd/nuvio-usenet/main.go) and
[server.go](internal/engine/server.go) for the bootstrap and session schemas.

## Android release assets

```sh
python3 scripts/fetch_prebuilts.py
python3 scripts/check_prebuilts.py
```

`prebuilt/manifest.json` pins an exact release, source digest, binary checksums
and build toolchain. Downloads are verified before installation and cached in
`prebuilt/`. Generated Android binaries are ignored by Git.

To rebuild all four ABIs, install the pinned Go toolchain, Android NDK
`29.0.14206865` and CMake `3.22.1`, then run:

```sh
python3 scripts/build_android.py \
  --ndk "$ANDROID_HOME/ndk/29.0.14206865" \
  --cmake "$ANDROID_HOME/cmake/3.22.1/bin/cmake" \
  --release-repository NuvioMedia/nuvio-usenet-engine
python3 scripts/check_prebuilts.py
```

The script builds from a temporary copy of the Go toolchain whose 32-bit
Android runtime always uses the 32-bit time syscalls: Android 8.0-10 kills
apps that call `futex_time64` even on kernels 5.1 and newer
([golang/go#83366](https://github.com/golang/go/issues/83366)). The installed
toolchain is not modified and the 64-bit binaries are unchanged.

Commit and push the source and regenerated manifest. The **Publish Usenet native
assets** workflow on `main` rebuilds with the pinned toolchain, requires matching
checksums, and publishes immutable source-hash release assets. It can also be
started manually. Tests that fetch a new release may need rerunning after its
first publication.

Alternatively, publish the verified local outputs from a clean, pushed checkout
with GitHub CLI release access:

```sh
python3 scripts/publish_prebuilts.py --target "$(git rev-parse HEAD)"
```

Each release contains `libnuvio_usenet-<abi>.so` for `arm64-v8a`, `armeabi-v7a`,
`x86` and `x86_64`, plus its manifest. These are Android PIE executables targeting
API 24 with 16 KiB page alignment. Their `.so` names allow Android to install them
in the executable native library directory; they are launched, not loaded via
JNI.

## Using the engine in NuvioTV

NuvioTV's `usenet` branch keeps its Android adapter and pinned dependency metadata
in `native/usenet`. Normal app builds download the exact release from this
repository and verify all four ABI checksums. To update the app dependency:

1. Build, commit and publish the engine source and manifest in this repository.
2. Verify the published assets with `scripts/fetch_prebuilts.py` and
   `scripts/check_prebuilts.py` from a checkout without cached binaries.
3. Copy `prebuilt/manifest.json` into NuvioTV's `native/usenet/prebuilt/` and set
   `native/usenet/engine-source.json` to this repository and the published commit.
4. Refresh `native/usenet/licenses/` when notices change, then run the app's
   prebuilt verification before committing its dependency update.

For local engine development, clone this repository separately and build NuvioTV
with `-PbuildUsenetFromSource=true` and
`-PusenetEngineSourceDir=/absolute/path/to/nuvio-usenet-engine`. The included
`android.gradle.kts` also supports integrations that place the full engine source
at `native/usenet`. Package `licenses/` as app assets and retain the executable
filename `libnuvio_usenet.so`. The launcher and engine use protocol version 1.

GitHub Release assets belong to the repository that publishes them; copying the
source alone does not copy its binaries. Always publish and verify an engine
release before moving an app's dependency pin to it.

## History and license

Extracted from the cleaned `full-nntp` branch of
[haaihond/NuvioTV](https://github.com/haaihond/NuvioTV) at
`f222d044f69967764804236a65c6ec7d877b377d`, with engine development history and
original authors preserved. Historical commits predate the standalone module
and CI layout; the extraction changes commit hashes and removes signatures.

The project license is [GNU GPLv3](LICENSE). Third-party code retains its
respective licenses; preserve [NOTICE.md](NOTICE.md), [licenses/](licenses/) and
the notices in [third_party/](third_party/).
