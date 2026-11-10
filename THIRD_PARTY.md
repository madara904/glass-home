# Third-party code and assets

Glass Home's own code is MIT licensed (see `LICENSE`). This file lists everything else that is
in the repository, built into the released programs, or downloaded by the tool at runtime.

## Built into the desktop tool

| What | Where | License | Notes |
|---|---|---|---|
| adb (Android platform-tools) | `tool/src-tauri/resources/platform-tools/`, `resources/macos/platform-tools/` | Apache License 2.0 | Google's prebuilt binaries; their notice is in `NOTICE.txt` next to them |
| aapt2 (Android build-tools) | `tool/src-tauri/resources/aapt2.exe`, `resources/macos/aapt2` | Apache License 2.0 | Google's prebuilt binaries, from the Android Open Source Project |
| Glass Home launcher APK | `tool/src-tauri/resources/glass-home.apk` | MIT, plus the launcher's assets below | built from `launcher/` |
| dumbtv package and settings lists | `tool/src-tauri/data/` | MIT | [dumbtv](https://github.com/humblemuzzu/dumbtv); `data/LICENSE-dumbtv` |
| Roboto font | `tool/public/roboto.ttf`, `docs/assets/roboto.ttf` (the guide) | SIL Open Font License 1.1 | by Google; license in `tool/public/OFL-roboto.txt` and `docs/assets/OFL-roboto.txt` |
| "Sunlit ridges" photo, behind the layout previews | `tool/public/wall.jpg` | [Unsplash License](https://unsplash.com/license) | by Cristina Gottardi ([photo page](https://unsplash.com/photos/wndpWTiDuT0)); same file as the launcher's `wall-ridge.jpg` |

Google distributes adb and aapt2 as part of the Android SDK, under the Android Software
Development Kit License Agreement; the source code is Apache 2.0 in the Android Open Source
Project. Other open-source tools ship these binaries the same way.

## The launcher (`launcher/`)

Details and the license texts are in `launcher/NOTICE.md`.

| What | License |
|---|---|
| HOME takeover, app catalog, Fire TV settings entry points, build script | MIT, from [dumbtv](https://github.com/humblemuzzu/dumbtv) (`launcher/LICENSE-dumbtv`) |
| The look: glass materials, focus plate, dock geometry, service mark metrics | MIT, from [CleanFrame](https://github.com/nthnclrk/CleanFrame) (`launcher/LICENSE-cleanframe`) |
| Wallpaper photographs (`assets/wall-*`) | [Unsplash License](https://unsplash.com/license); photographers in `assets/wall-CREDITS.md` |
| Fonts: Geist, Newsreader | SIL Open Font License 1.1 (`assets/*-OFL.txt`) |
| Service marks (Netflix, Prime Video, Disney+ and others) | Simple Icons (CC0 1.0) and Custom Brand Icons (MIT). The names and logos remain their owners' trademarks |

## Rust crates (compiled into the tool)

About 260 crates, listed with their versions in `tool/src-tauri/Cargo.lock`; most are MIT or
Apache 2.0, often both. Others:

- **MPL 2.0:** `cssparser`, `cssparser-macros`, `selectors`, `dtoa-short`, `option-ext` (used
  by Tauri and wry). Their source is unchanged and available from crates.io.
- **CDLA-Permissive-2.0:** `webpki-roots` (the root certificate list).
- **Unicode-3.0, BSD-3-Clause, ISC, Zlib, Unlicense, 0BSD, CC0:** various small crates.

The main direct dependencies: [Tauri](https://tauri.app) (MIT or Apache 2.0), `serde`,
`serde_json`, `sha2`, `ureq`, `mdns-sd`, `dns-lookup` (all MIT or Apache 2.0).

## npm packages (in the tool's interface)

`@tauri-apps/api` (MIT or Apache 2.0). Build tools only, not shipped: `@tauri-apps/cli`,
`typescript`, `vite`.

## Data

| What | Where | Source |
|---|---|---|
| Amazon MAC prefixes | `tool/src-tauri/data/amazon-oui.txt` | the IEEE MA-L registry (public), filtered to Amazon Technologies Inc. and Amazon.com |

## Downloaded at runtime, not redistributed

When you choose the DNS filter during Debloat, the tool downloads these onto the TV.

| What | From | License |
|---|---|---|
| [personalDNSfilter](https://github.com/IngoZenz/personaldnsfilter) APK | F-Droid, pinned by checksum | GPL-2.0 |
| personalDNSfilter's default configuration | GitHub, pinned to a commit and checksum | GPL-2.0 |
| [hagezi DNS blocklist](https://github.com/hagezi/dns-blocklists), Amazon domains | jsDelivr | GPL-3.0 |

## Trademarks

Amazon, Fire TV and Fire OS are trademarks of Amazon.com, Inc. or its affiliates. Glass Home is
not affiliated with or endorsed by Amazon.
