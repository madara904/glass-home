//! Restore, after dumbtv's restore.sh (MIT): every package the tool can switch off comes back
//! on, background limits and data blocks go, the settings return to what this TV had before
//! (or to stock values), the DNS filter and Glass Home leave, and HOME goes back to Amazon.

use crate::adb::Adb;
use crate::device::{self, DNSFILTER};
use crate::lists::Lists;
use crate::steps::Steps;
use crate::ui;
use std::collections::HashSet;
use std::path::Path;

pub fn run(adb: &Adb, sdk: u32, dir: &Path, s: &mut Steps) -> bool {
    let lists = Lists::for_sdk(sdk);

    // 1. HOME back to Amazon, the launcher removed.
    s.run("home");
    ui::undo(adb, sdk);
    s.done("home", None::<String>);

    // 2. Packages back on.
    s.run("packages");
    let mut all: Vec<String> = lists.packages.clone();
    all.extend(lists.alexa.iter().cloned());
    all.extend(lists.user_apps.iter().cloned());
    all.push("com.amazon.firetv.youtube".into());
    let have = device::installed(adb);
    let off = device::disabled(adb);
    let mut seen = HashSet::new();
    let mut on = 0;
    for p in all.iter().chain(lists.never.iter()).filter(|p| seen.insert((*p).clone())) {
        if !have.contains(p) {
            continue;
        }
        if off.contains(p) {
            adb.sh(&format!("pm enable --user 0 {p}"));
            on += 1;
        }
        device::bg(adb, sdk, p, true);
        if let Some(uid) = device::uid_of(adb, p) {
            adb.sh(&format!("cmd netpolicy remove restrict-background-blacklist {uid}"));
        }
    }
    s.done("packages", Some(format!("{on} back on")));

    // 3. Services that were only limited.
    s.run("limits");
    let log = std::fs::read_to_string(dir.join("debloat.txt")).unwrap_or_default();
    let mut limited: Vec<String> = log.lines().filter_map(|l| l.split('\t').next()).map(str::to_string).collect();
    limited.extend(lists.protected.iter().cloned());
    let mut seen = HashSet::new();
    for p in limited.iter().filter(|p| seen.insert((*p).clone()) && have.contains(*p)) {
        device::bg(adb, sdk, p, true);
        if let Some(uid) = device::uid_of(adb, p) {
            adb.sh(&format!("cmd netpolicy remove restrict-background-blacklist {uid}"));
        }
    }
    adb.sh("cmd netpolicy set restrict-background false");
    s.done("limits", None::<String>);

    // 4. Settings: this TV's own earlier values where the tool saved them, else stock ones.
    s.run("settings");
    let snapshot = |ns: &str| std::fs::read_to_string(dir.join(format!("settings-before-{ns}.txt"))).unwrap_or_default();
    let (secure, global) = (snapshot("secure"), snapshot("global"));
    let before = |ns: &str, key: &str| -> Option<String> {
        let text = if ns == "secure" { &secure } else { &global };
        text.lines().find_map(|l| l.strip_prefix(&format!("{key}="))).map(|v| v.trim().to_string()).filter(|v| !v.is_empty())
    };
    for (ns, key, value) in &lists.settings_restore {
        let want = before(ns, key).unwrap_or_else(|| value.clone());
        adb.sh(&format!("settings put {ns} {key} {want}"));
    }
    if adb.sh1("settings get secure screensaver_components") == "com.android.dreams.basic/.Colors" {
        if let Some(want) = before("secure", "screensaver_components") {
            adb.sh(&format!("settings put secure screensaver_components {want}"));
        }
    }
    s.done("settings", Some(if secure.is_empty() { "Amazon defaults".into() } else { "as before".into() }));

    // 5. The DNS filter. Removing it stops the VPN, which can cut the network adb session.
    s.run("filter");
    adb.sh("settings delete secure always_on_vpn_app");
    if have.contains(DNSFILTER) {
        adb.sh(&format!("pm uninstall {DNSFILTER}"));
        adb.reconnect();
        s.done("filter", Some("removed".into()));
    } else {
        s.skip("filter", "was not installed");
    }

    // 6. Restart.
    s.run("reboot");
    if let Err(e) = device::reboot_and_wait(adb) {
        s.fail("reboot", &e);
        return false;
    }
    let _ = std::fs::remove_file(dir.join("debloat.txt"));
    s.done("reboot", None::<String>);
    !s.failed
}
