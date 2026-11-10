# Changelog

All notable changes to Glass Home are listed here. The format follows
[Keep a Changelog](https://keepachangelog.com/en/1.1.0/). The desktop tool and the launcher have
their own version numbers (`tool/src-tauri/tauri.conf.json`, `launcher/version.env`).

## [Unreleased]

### Added
- The connect screen searches the network by itself and names each device: Fire TVs by their own
  name (mDNS), Google and Android TVs greyed out as not supported, the router's host name and an
  Amazon MAC prefix as fallbacks. Typing the address stays as the fallback.
- `--discover` prints the network search as JSON.
- A link to the repository in the window's byline.
- License, third-party notices, security policy, contributing guide, issue and pull request
  templates, CI checks for pull requests.
- A step-by-step guide with screenshots in `docs/`, published to GitHub Pages; the README shows
  the main screens.

### Changed
- Launcher 1.0.2: the marks and the first dock cover public streaming apps only; the tool's demo
  data lists only public apps.
- Dependencies: ureq 3 for downloads and the network search; the GitHub Actions run on Node 24.

### Fixed
- macOS: the top bar shares the traffic lights' centre (32 pt) and starts clear of them; it sat
  10 pt lower before.

## Tool 0.1.0, launcher 1.0.1

First version: Debloat, Glass UI and Restore for Fire OS 6, 7 and 8, on Windows and macOS.
