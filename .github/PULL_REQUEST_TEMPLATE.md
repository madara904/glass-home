## What and why

<!-- What does this change, and what problem does it solve? Link an issue if there is one. -->

## Tested on

<!-- Fire TV model and Fire OS version, and Windows or macOS. "No TV needed" is fine for docs and UI-only changes. -->

## Checklist

- [ ] `cargo test` and `cargo clippy -- -D warnings` pass (in `tool/src-tauri`)
- [ ] `pnpm build` passes (in `tool`)
- [ ] New visible text is in `tool/src/i18n.ts`, English and German
- [ ] `CHANGELOG.md` has a line under `[Unreleased]`
- [ ] Restore still undoes the change on the TV (if it touches the TV)
