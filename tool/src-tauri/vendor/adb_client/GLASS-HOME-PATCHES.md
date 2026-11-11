# adb_client 3.2.3, patched

A copy of [adb_client](https://github.com/cocool97/adb_client) 3.2.3 from crates.io (MIT), used
through `[patch.crates-io]` in `../../Cargo.toml`. Three changes, all in the TCP transport, so
a phone that drops off Wi-Fi mid-run ends in an error the tool can retry instead of a hang:

1. `tcp/tcp_transport.rs`, `connect`: a 5 s connect timeout and TCP keep-alive (10 s idle,
   5 s between probes), so a vanished peer is noticed in about 25 s.
2. `tcp/tcp_transport.rs`, `read_message_with_timeout`: a read of 0 bytes (connection closed)
   returns an error; the loops spun on it forever.
3. `adb_message_transport.rs`: the default read timeout is 600 s instead of `u64::MAX` seconds.

To update: copy the new version from `~/.cargo/registry/src/*/adb_client-<version>` and apply
these changes again, or drop the copy once upstream has them.
