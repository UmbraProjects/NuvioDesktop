<div align="center">

  <img src="https://github.com/tapframe/NuvioTV/blob/main/assets/brand/app_logo_wordmark.png" alt="Nuvio" width="300" />
  <br />
  <br />

  [![License][license-shield]][license-url]

  <p>
    Nuvio Desktop is a desktop media hub built with Kotlin Multiplatform and Compose Multiplatform.
    <br />
    Desktop app in development
  </p>

</div>

## About

Nuvio Desktop brings the Nuvio media experience to desktop. It keeps the playback-focused browsing, collection, watch progress, downloads, and Stremio addon ecosystem integration from Nuvio while adapting the app for desktop input, desktop storage, and native desktop playback.

The desktop app is built from the shared Kotlin Multiplatform codebase in [composeApp](./composeApp), with desktop-specific code in [composeApp/src/desktopMain](./composeApp/src/desktopMain). Desktop packaging is configured through Gradle, with macOS development builds currently active and Windows and Linux support planned later.

## Platform Status

- macOS: actively being developed.
- Windows: planned later.
- Linux: planned later.

## Installation

Public desktop releases are not available yet. Current builds are development builds.

When releases are ready, desktop builds will be published from the Nuvio Desktop repository.

## Development

```bash
git clone --branch Dev --recurse-submodules https://github.com/NuvioMedia/NuvioDesktop.git
cd NuvioDesktop
git submodule update --init --recursive MPVKit
git -C MPVKit switch Nuvio
./gradlew :composeApp:run
```

Useful commands:

```bash
./gradlew :composeApp:compileKotlinDesktop
./gradlew :composeApp:packageDmg
```

The native player bridge currently uses MPVKit macOS libmpv artifacts from the `MPVKit` submodule. For development, the submodule is configured to use [NuvioMedia/MPVKit](https://github.com/NuvioMedia/MPVKit) on the `Nuvio` branch. If you already have a checkout, sync and update it with:

```bash
git submodule sync MPVKit
git submodule update --init --recursive MPVKit
git -C MPVKit switch Nuvio
git -C MPVKit pull --ff-only
```

If Gradle reports missing MPVKit artifacts, build the macOS runtime before running the app:

```bash
cd MPVKit
make build platform=macos
```

You can also point Gradle at a separate MPVKit checkout:

```bash
./gradlew :composeApp:run -Pnuvio.mpvkit.dir=/absolute/path/to/MPVKit
```

## Project Structure

- `composeApp/` contains the Kotlin Multiplatform and Compose Multiplatform app code.
- `composeApp/src/commonMain/` contains shared UI, features, repositories, and platform-agnostic logic.
- `composeApp/src/desktopMain/` contains desktop-specific app code, storage, settings, player integration, and desktop resources.
- `composeApp/src/desktopMain/native/macos/` contains the macOS native player bridge.
- `composeApp/src/desktopMain/resources/player-ui/` contains the desktop native player control UI.
- `composeApp/src/desktopMain/resources/icons/` contains desktop app icons for macOS, Windows, and Linux packaging.

## Desktop Player

Nuvio Desktop uses a native desktop player path with MPVKit/libmpv integration and desktop-owned controls. The desktop player is separate from the mobile Compose player surface so desktop behavior can match desktop input, keyboard, windowing, and playback expectations.

## Legal & DMCA

Nuvio functions solely as a client-side interface for browsing metadata and playing media provided by user-installed extensions and/or user-provided sources. It is intended for content the user owns or is otherwise authorized to access.

Nuvio is not affiliated with any third-party extensions, catalogs, sources, or content providers. It does not host, store, or distribute any media content.

For comprehensive legal information, including the full disclaimer, third-party extension policy, and DMCA/Copyright information, please visit the [Legal & Disclaimer Page](https://nuvioapp.space/legal).

## Built With

- Kotlin Multiplatform
- Compose Multiplatform for Desktop
- Kotlin
- MPVKit and libmpv

<!-- MARKDOWN LINKS & IMAGES -->
[license-shield]: https://img.shields.io/github/license/NuvioMedia/NuvioDesktop.svg?style=for-the-badge
[license-url]: https://github.com/NuvioMedia/NuvioDesktop/blob/Dev/LICENSE
