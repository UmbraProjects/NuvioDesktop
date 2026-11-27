<div align="center">

  <img src="https://github.com/tapframe/NuvioTV/blob/main/assets/brand/app_logo_wordmark.png" alt="Nuvio" width="300" />
  <br />
  <br />

  [![License][license-shield]][license-url]

  <p>
    Nuvio HTPC (Home Theatre PC) is a feature rich Windows media client for either desk or couch.
    <br>To see it in action, click the image for a showcase video.<br/>
  </p> 
</div>

[![Nuvio TV Mode Demo](https://img.youtube.com/vi/txiM8_y2aD8/maxresdefault.jpg)](https://youtu.be/txiM8_y2aD8)

This fork is unaffiliated with the Nuvio team. If you're a Linux user check out [Slokker's Linux Fork.](https://github.com/iamSlokker/NuvioDesktop-Linux) It supports appimage, flatpack or debian. It's based on the nightly releases of this project.

# Key Features

* Adaptive and TV display modes. They feature a focused content hero displaying cast, award info, trailers within the hero/detail page, ambient background, quick jump dot navigation and a quality display. 

* New custom discovery panel to filter all of TMDB, suggestions from your watch history, AI suggestions and a content suggestion system with eight modes you can choose from. There's also a customizable random play content or random episode feature.

* Full YouTube support, IPTV via either of the three main methods with many discovery options, detailed music player and there's even a built in game launcher.

* Extensive playback features including SVP, anime shaders, color profiles, HDR mode toggle, automatic failover, stream scoring based on quality, binge mode, seek thumbnails, automatic subtitle sync, external player handoff with tracking, css override and the option to completely replace the MPV config with your own setup.  

* Multiple custom player UIs, detailed right click menu and lots of subtitle choices.

* Built in Usenet support refined from a test build of Nuvio TV, it works great and avoids having to host your own server. I highly suggest you enable failover, as well as ideally stream scoring.

* Use mouse, keyboard, gamepad or turn your phone into a controller with the android companion app. There's also a ring interface to access all 10 panels with one key, no need to remember binds. Click [here](remote-android/README.md) for info on the android application.

* Completely redesigned PC native settings menu with full hotkey remapping for keyboard and gamepad with more options than you need, and hand-picked defaults.

* Automated/batch downloading via integrated local library, HTPC can become a highly configurable front-end for your own local files populating artwork and metadata automatically.

* Home theatre ready with automatic control of smart lights, pre-show feature, screensaver, casting, audio passthrough, voice control and sleep mode.

# Other Features

* New user startup wizard that walks you through the basic options to get you setup quickly.

* A large amount of time has been spent on optimization, it has alot of features yet starts quickly and keeps RAM usage in check. It's also very easy to hide features you don't use, to avoid them taking up visual space.

* Calendar support for Nuvio Sync, SIMKL, Trakt and Mdblist.

* Support for self-hosted Floppy.

* Built in anime ID mapper through Fribb's mapping database.

* Filename only resolution for debrid/cloud libaries, turning file names into identified titles with artwork.

* Custom hero discovery rules and metadata badges without modifying source. 

* Use your own font for the application and player.

* HUD scaling to configure the UI for your setup.

* VRR toggle, refresh rate change on startup of media option, player zone feature for power users to have various functions based on their mouse pointer, diagnostic box on playback, nuvio engine ported from mobile, stream preloading, artwork/metadata preloading and others that didn't make the list.

# Installation

HTPC is distributed as a zip file, there's no installer however it has seemless automatic updating without an installer. 

Choose one of these two:

1. [Main](https://github.com/UmbraProjects/NuvioDesktop/releases/latest) - new releases 1-2x per month, all of the changes on nightly within that period in a polished state. 
2. [Nightly](https://github.com/UmbraProjects/NuvioDesktop/releases/tag/Nightly) - releases every 1-3 days, UI might be lacking polish, new features might not be completely finished. If you want to watch the development in "real time", submit bug reports and get new features ASAP this is for you.

After choosing your version, scroll to the bottom and click nuvio.zip. Once downloaded, extract it wherever you'd like and run nuvio.exe to start HTPC.

HTPC shares a [Discord](https://discord.gg/wEgTPNXUMU) with PostersPlus, my other project. Feel free to join to chat, report bugs or request features.

# Contributors

* Deigue, for several useful pull requests, feedback, advice and a huge amount of testing.

* Slokker, who's GoatTV project is the basis for the IPTV implementation, translations, pull requests and the [Linux port](https://github.com/iamSlokker/NuvioDesktop-Linux).

* Spyder, who contributed the music feature, expanded IPTV, added the "what to watch" and pre-show features among many other PRs. 

* Allesc the developer of Stremio Kai gave his blessing to use his color presets and anime shaders, Stremio Kai's row by row layout was also a major inspiration for the TV mode found in this project.

* Everyone who works on upstream Nuvio of course, without them the project wouldn't exist.

## Custom Hero Discovery Config & Badges

Nuvio HTPC can show hero info badges for awards, festivals, critic signals, release status, language, notable studios/directors, trending/cult/true-story metadata, short films, mini series, binge-ready shows, and new releases.

You can extend the studio/director list and override the bundled badge images without touching the code. Create this folder first:

```text
%LOCALAPPDATA%\NuvioHTPC\Badges
```

For most Windows users this expands to:

```text
C:\Users\<you>\AppData\Local\NuvioHTPC\Badges
```

### Custom `hero_discovery.json`

Place a file named `hero_discovery.json` in `%LOCALAPPDATA%\NuvioHTPC\Badges`.

Example:

```json
{
  "version": 1,
  "mergeWithDefaults": true,
  "studios": {
    "Janus Films": "Janus Films",
    "Toho": "Toho"
  },
  "directors": {
    "Akira Kurosawa": "A. Kurosawa",
    "Kelly Reichardt": "Kelly Reichardt"
  }
}
```

- `mergeWithDefaults: true` keeps the built-in studios/directors and adds yours.
- `mergeWithDefaults: false` replaces the built-in studio/director lists with only your entries.
- The left side must match the studio or director name from the title metadata.
- The right side is the short label shown in the hero badge/tooltip.
- Restart Nuvio after changing this file.

### Custom Badge Images

Put image files directly in `%LOCALAPPDATA%\NuvioHTPC\Badges`. Supported formats:

```text
.png
.jpg
.jpeg
.webp
```

Custom badge filenames are matched case-insensitively and ignore spaces/punctuation. For example, all of these can match the Metacritic badge:

```text
Metacritic.png
Must See.webp
must-see.jpg
```

Useful badge names/categories include:

```text
Best Picture.png
Best Picture Nominee.png
Golden Globe.png
Golden Globe Nominee.png
Emmy Winner.png
Emmy Nominee.png
Palme dOr.png
Golden Lion.png
Golden Bear.png
Peoples Choice.png
Metacritic.png
Must See.png
Cult Classic.png
Trending.png
Short Film.png
Mini Series.png
Binge Ready.png
True Story.png
New Release.png
Director.png
Studio.png
```

For director badges, you can also use the director name itself, such as:

```text
David Fincher.png
Christopher Nolan.webp
```

The custom image wins over the bundled default whenever its filename matches the badge label or category. Restart Nuvio after adding or replacing custom badge files.

## Upstream - Legal & DMCA

Nuvio functions solely as a client-side interface for browsing metadata and playing media provided by user-installed extensions and/or user-provided sources. It is intended for content the user owns or is otherwise authorized to access.

Nuvio is not affiliated with any third-party extensions, catalogs, sources, or content providers. It does not host, store, or distribute any media content.

For comprehensive legal information, including our full disclaimer, third-party extension policy, and DMCA/Copyright information, please visit our [Legal & Disclaimer Page](https://nuvioapp.space/legal).

## Upstream - Built With

- Kotlin Multiplatform
- Compose Multiplatform
- Kotlin
- Compose Desktop packaging
- Native desktop player integrations

<!-- MARKDOWN LINKS & IMAGES -->
[contributors-shield]: https://img.shields.io/github/contributors/NuvioMedia/NuvioDesktop.svg?style=for-the-badge
[contributors-url]: https://github.com/NuvioMedia/NuvioDesktop/graphs/contributors
[forks-shield]: https://img.shields.io/github/forks/NuvioMedia/NuvioDesktop.svg?style=for-the-badge
[forks-url]: https://github.com/NuvioMedia/NuvioDesktop/network/members
[stars-shield]: https://img.shields.io/github/stars/NuvioMedia/NuvioDesktop.svg?style=for-the-badge
[stars-url]: https://github.com/NuvioMedia/NuvioDesktop/stargazers
[issues-shield]: https://img.shields.io/github/issues/NuvioMedia/NuvioDesktop.svg?style=for-the-badge
[issues-url]: https://github.com/NuvioMedia/NuvioDesktop/issues
[license-shield]: https://img.shields.io/github/license/NuvioMedia/NuvioDesktop.svg?style=for-the-badge
[license-url]: https://github.com/NuvioMedia/NuvioDesktop/blob/main/LICENSE