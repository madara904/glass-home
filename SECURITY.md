# Security policy

## Reporting a vulnerability

Please report security problems privately through
[GitHub Security Advisories](https://github.com/madara904/glass-home/security/advisories/new),
not in a public issue. We will answer within a few days and agree on a timeline for a fix.

## What Glass Home touches

Knowing this helps judge whether something is a vulnerability:

- **adb over the network.** The tool connects to the Fire TV on port 5555 with the bundled adb.
  ADB debugging gives full shell access to the TV to anyone on the same network whose computer
  the TV has allowed. Glass Home does not change who is allowed; turn ADB debugging off on the
  TV when you don't need it.
- **The network search** sends TCP connection attempts to port 5555 on every address of your
  /24 subnet, listens to mDNS and asks the router for host names. It connects to nothing on its
  own.
- **Downloads** (only with the DNS filter): personalDNSfilter from F-Droid, checked against a
  SHA-256 checksum pinned in the source; its configuration from GitHub, pinned to one commit and
  checksum the same way; a blocklist from jsDelivr, whose entries are checked to be domain
  names, so a tampered list can only change which Amazon domains are blocked.
- **The launcher** holds the HOME button through an accessibility service (Fire OS 7 and 8) or a
  keeper service (Fire OS 6). The accessibility service only hears which window comes to the
  front (`typeWindowStateChanged`) and cannot read what is on screen
  (`canRetrieveWindowContent="false"`, `launcher/res/xml/guard.xml`).
- **Signing.** The launcher APK is signed with a key that is not in this repository. An APK
  claiming to be Glass Home with a different signature is not ours.

## Supported versions

Only the latest release gets fixes.
