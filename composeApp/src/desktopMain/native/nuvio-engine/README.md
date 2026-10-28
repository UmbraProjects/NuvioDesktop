# Nuvio Engine on desktop

`nuvio_engine.dll` is built from a sibling checkout of [NuvioMedia/nuvio-engine](https://github.com/NuvioMedia/nuvio-engine)
at `../nuvio-engine` (override with `-Pnuvio.engine.sourceDir=`), by `:composeApp:buildWindowsNuvioEngine`.

Toolchain (dev box): MSYS2 UCRT64 GCC + static OpenSSL (`pacman -S mingw-w64-ucrt-x86_64-openssl`),
CMake from Visual Studio Build Tools, jni.h from the Gradle JDK. The task skips with a warning when any
of those is missing; `createDistributable` refuses to package without the DLL.

## Local engine changes

The checkout must carry `patches/*.patch` on top of the pinned upstream version (v0.1.6):

```
cd ../nuvio-engine
git checkout -b nuvio-desktop-0.1.6 v0.1.6
git am ../NuvioDesktop/composeApp/src/desktopMain/native/nuvio-engine/patches/*.patch
```

`git config core.autocrlf false` in that checkout first — the engine's own libtorrent patches are applied
with `git apply` at configure time and fail on CRLF sources. A build directory configured against an
older engine already has a patched libtorrent source; the configure step applies only the patches whose
sentinel it does not find, so an engine bump needs no clean build.

- `0001-desktop-peer-discovery.patch` — Stremio-parity swarm behaviour measured against Stremio on the
  same infohashes: DHT bootstrap routers (libtorrent ships one node), a 30 s re-announce to all
  trackers + DHT while under 40 peers, 10 s peer connect timeout (was 3), 8-strike failcount (was 3).
  Without it sparse swarms die inside 15 s and are never re-queried. (TCP-only outgoing was part of it
  until upstream made it the default in 0.1.2.)

- `0002-desktop-download-window.patch` — the download window ahead of the reader follows the playback
  buffer preset, which `nuvioEngineWindowBytes` passes as the memory cache capacity (Metered 64 / Low Data
  128 / Balanced 256 / Resilient 512 MiB), bounded by disk cache / 4 (unbounded with no disk cache) and at
  least 64 MiB. Upstream 0.1.6 sizes it from the disk cache alone and caps it at 256 MiB. (On 0.1.1 this
  patch raised the 15 MiB rolling-selection ceiling, under which a 16 MiB-piece release had less than one
  piece in flight: 1 MB/s with 13 seeds before, 5.6 MB/s with 3 seeds after.)

- `0003-desktop-request-timeout.patch` — block request timeout 4 s → 15 s (4 s snubbed fast seeds under deep
  queues: 2 Mbps/seed vs 14; 60 s let a dead peer pin a block for a minute; upstream's urgent hedge now
  re-requests the blocking piece's stuck blocks long before 15 s).

- `0004-desktop-windows-long-paths.patch` — payload files live at `<cache>/payload/<info hash>/<the torrent's
  own folders>/<file>`, which release names routinely push past MAX_PATH (299 characters measured for a UHD
  remux). Nuvio.exe is not long-path aware, so the engine's own Win32 opens failed on those files: the
  unverified-block reader fell back to whole verified pieces, range release reported the file missing (evicted
  pieces stayed on disk: 2.4 GB allocated against 2.0 GB retained), and the disk cache scan threw `inspect
  torrent payload entry`, so finished torrents were never measured or evicted. The reader, range release and
  disk cache now use extended-length (`\\?\`) paths, and the cache walks and deletes trees through Win32 (MinGW's
  std::filesystem cannot take prefixed paths). Also fixes the cache's directory touch, which MinGW's
  `last_write_time` cannot do on a directory (`touch disk cache entry: Permission denied` on every add).

- `0005-desktop-ram-only-mode.patch` — RAM-only mode (`nuvio_engine_config.ram_only_mode`, carved out of the
  reserved bytes, so the ABI size is unchanged). libtorrent stores pieces through a RAM `disk_interface`
  (`src/torrent/ram_disk_io.*`, from a community patch) and the backend writes no resume data, DHT state or
  disk cache. The stream bridge serves unverified blocks from the RAM store, shares its buffers with the
  verified-piece cache, and bounds it with 0.1.6's own retention: the budget is exactly the window
  (playback + readahead = the buffer preset) plus what is kept behind the reader and the file's head and tail.
  Evicted pieces leave libtorrent at once and the store after `range_release_grace`, the path file ranges
  take: dropping them sooner races queued upload reads, and libtorrent pauses a torrent on a failed read.
  Pieces of a stopped stream are held until its warm timeout removes the torrent (60 s). Known overhead: the
  store allocates a whole piece on its first block, and `nuvio_evict_pieces` only drops complete pieces, so
  pieces left half-done by a seek stay at full size until then (measured: Resilient settled at 700–820 MB
  against a ~600 MB budget after three seeks on 8 MiB pieces). Block-granular partial storage would cut it.

Retired on the 0.1.6 bump, because upstream fixed the same problems inside libtorrent: `0004-desktop-blocking-
piece-hotswap` (force-cancel the blocking piece's blocks on slow peers; upstream's `streaming-stall` patch
removes the busy-mode trap it worked around and `urgent-hedge` re-requests stuck blocks on the fastest peer
that has them) and 0003's `prioritize_partial_pieces` (upstream's `top-priority-first` picks the blocking
piece before any partial). The `NUVIO_ENGINE_PIECE_DEADLINES` / `NUVIO_ENGINE_DEBUG_BLOCKING` switches went
with 0004.

Probes: `desktopTest --tests *NuvioEngineNetworkProbeTest -Pnuvio.p2pProbeHash=<hash> [-Pnuvio.p2pProbePreloadMb=64]`
(linear read from a fresh engine) and `*NuvioEngineSeekProbeTest -Pnuvio.p2pSeekProbeHash=<hash>
-Pnuvio.p2pSeekProbeOffsetsMb=0,tail,5000` (replays mpv-style seeks against the app's real engine state; close
Nuvio first). `-Pnuvio.engineEnv=NAME=value;NAME2=value` passes env to the engine. Always `--rerun`: the DLL is not a
test input.

`jni_bridge.cpp` here is the engine's Android bridge with one line changed (empty TLS bundle path
→ nullptr so Windows uses the system certificate store). Re-copy and re-apply on an engine bump.
