# Contributing to Glass Home

Thanks for helping. Bug reports with your Fire TV model and Fire OS version are as useful as
code.

## Repository layout

- `launcher/`: the TV home screen. Android, plain Java, no Gradle, no libraries.
- `tool/`: the desktop app. Tauri 2: Rust in `tool/src-tauri/src`, TypeScript in `tool/src`.

## Build

The desktop tool needs Rust (stable), Node 22 and pnpm. The tool speaks adb
itself (the adb_client crate) and bundles the launcher APK from `tool/src-tauri/resources`, so you
don't need the Android SDK for it.

```sh
cd tool
pnpm install
pnpm tauri dev      # run with live reload
pnpm tauri build    # a release build for this computer
```

The launcher needs JDK 17 and an Android SDK with a platform and build-tools; `build.sh`
explains the options at the top.

```sh
cd launcher && ./build.sh
cp build/glass-home.apk ../tool/src-tauri/resources/
```

Opening `tool/` with `pnpm dev` in a plain browser shows the interface with a mock Fire TV, for
design work without a TV.

## Before you open a pull request

CI runs these; run them first:

```sh
cd tool/src-tauri
cargo test
cargo clippy -- -D warnings
cd .. && pnpm build             # type check and frontend build
```

Please don't run `cargo fmt` over the whole project: the code uses longer lines than rustfmt's
default, and a full reformat buries the real change in the diff. Format the lines you touch the
way the code around them looks.

## Testing on a TV

A debug build has command-line modes that print instead of opening the window:

```sh
tool/src-tauri/target/debug/glass-home-tool --discover        # the network search
tool/src-tauri/target/debug/glass-home-tool --probe <tv-ip>   # reads only
tool/src-tauri/target/debug/glass-home-tool --run restore <tv-ip>
```

Say in the pull request which Fire TV and Fire OS version you tested on. Debloat and Glass UI
change the TV; check that Restore brings it back.

## Code style

- Comments say why, in full sentences.
- Every visible word in the tool goes through `src/i18n.ts`, in English and German. Backend
  messages are English and are translated there by text.
- No new dependencies without a reason in the pull request.

## Releases

Maintainers release by starting the "Build tool" workflow or pushing a `v*` tag. Pull requests
never build a release.

## License

By contributing you agree that your contribution is licensed under the MIT License (see
`LICENSE`).
