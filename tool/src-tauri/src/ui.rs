//! Installs Glass Home and gives it the HOME button, after dumbtv's launcher/install.sh (MIT).
//! Fire OS keeps HOME on Amazon's protected launcher, so an accessibility service (Fire OS 7/8)
//! or a UsageStats keeper (Fire OS 6) brings Glass Home up instead; alarms must fire at once
//! for that (min_futurity=0). An install of the launcher's earlier name is migrated: its
//! settings come along, then it is removed.

use crate::adb::Adb;
use crate::device::{self, AMAZON_HOME, GLASS, LEGACY};
use crate::steps::Steps;
use serde::Deserialize;
use std::path::Path;
use std::thread;
use std::time::Duration;

#[derive(Deserialize)]
pub struct Options {
    pub layout: String,
    pub tiles: String,
}

const GUARD: &str = "app.glasshome.tv/app.glasshome.tv.Guard";
const LEGACY_GUARD: &str = "tv.dumbtv.home/tv.dumbtv.home.Guard";
const SAVED_FUTURITY: &str = "glasshome_min_futurity";

pub fn run(adb: &Adb, sdk: u32, apk: &Path, o: &Options, s: &mut Steps) -> bool {
    let have = device::installed(adb);

    // 1. Carry over an earlier install's settings.
    s.run("migrate");
    let mut conf: Vec<(String, String)> = Vec::new();
    if have.contains(GLASS) {
        conf = device::read_config(adb, sdk, GLASS);
        s.done("migrate", Some("settings kept".into()));
    } else if have.contains(LEGACY) {
        conf = device::read_config(adb, sdk, LEGACY);
        set_guard(adb, "");
        adb.sh(&format!("pm uninstall {LEGACY}"));
        s.done("migrate", Some("old version carried over".into()));
    } else {
        s.skip("migrate", "first install");
    }

    // 2. The app.
    s.run("install");
    if let Err(e) = adb.install(apk) {
        s.fail("install", &e);
        return false;
    }
    adb.sh(&format!("dumpsys deviceidle whitelist +{GLASS}"));
    adb.sh(&format!("appops set {GLASS} AUTO_START allow"));
    adb.sh("appops write-settings");
    s.done("install", device::version(adb, GLASS).map(|v| format!("version {v}")));

    // 3. Its settings: one start creates its folder, then the file goes in and the app restarts.
    s.run("config");
    adb.sh(&format!("am start -n {GLASS}/.Home"));
    thread::sleep(Duration::from_secs(4));
    let set = |conf: &mut Vec<(String, String)>, k: &str, v: &str| {
        conf.retain(|(key, _)| key != k);
        conf.push((k.to_string(), v.to_string()));
    };
    set(&mut conf, "glass.layout", match o.layout.as_str() { "list" => "list", "icons" => "icons", _ => "dock" });
    set(&mut conf, "glass.icons", if o.tiles == "glass" { "glass" } else { "banner" });
    let text = conf.iter().map(|(k, v)| format!("{k}={v}")).collect::<Vec<_>>().join("\n") + "\n";
    let local = std::env::temp_dir().join(format!("glasshome-conf-{}.txt", std::process::id()));
    let pushed = std::fs::write(&local, text).map_err(|e| e.to_string()).and_then(|_| adb.push(&local, &device::config_path(sdk, GLASS)));
    let _ = std::fs::remove_file(&local);
    adb.sh(&format!("am force-stop {GLASS}"));
    match pushed {
        Ok(()) => s.done("config", Some(format!("{} settings", conf.len()))),
        Err(e) => s.fail("config", &e),
    }

    // 4. HOME.
    s.run("home");
    if sdk < 28 {
        // Fire OS 6.7 refuses third-party accessibility services: the Keeper watches instead.
        set_guard(adb, "");
        adb.sh(&format!("appops set {GLASS} GET_USAGE_STATS allow"));
    } else {
        adb.sh(&format!("cmd package set-home-activity {GLASS}/.Home"));
        futurity(adb, sdk, true);
        set_guard(adb, GUARD);
        let mut bound = false;
        for _ in 0..15 {
            if adb.sh("dumpsys accessibility").contains("label=Glass Home button") {
                bound = true;
                break;
            }
            thread::sleep(Duration::from_secs(1));
        }
        if !bound {
            futurity(adb, sdk, false);
            set_guard(adb, "");
            s.fail("home", "Fire OS did not allow the home screen takeover. Are parental controls on?");
            return false;
        }
    }
    adb.sh("input keyevent KEYCODE_WAKEUP");
    adb.sh(&format!("am start -n {GLASS}/.Home"));
    s.done("home", None::<String>);

    // 5. Check.
    s.run("verify");
    let mut front = String::new();
    for _ in 0..10 {
        thread::sleep(Duration::from_secs(1));
        front = device::front(adb);
        if front.starts_with(GLASS) {
            break;
        }
    }
    if front.starts_with(GLASS) {
        s.done("verify", Some("Glass Home is in front".into()));
    } else {
        s.fail("verify", &format!("In front: {}", if front.is_empty() { "unknown" } else { &front }));
    }
    !s.failed
}

/// Hands HOME back to Amazon and removes the launcher (and its earlier name).
pub fn undo(adb: &Adb, sdk: u32) {
    for pkg in [GLASS, LEGACY] {
        adb.sh(&format!("am force-stop {pkg}"));
        adb.sh(&format!("appops set {pkg} GET_USAGE_STATS default"));
        adb.sh(&format!("appops set {pkg} AUTO_START default"));
        adb.sh(&format!("dumpsys deviceidle whitelist -{pkg}"));
    }
    futurity(adb, sdk, false);
    set_guard(adb, "");
    adb.sh(&format!("am start -a android.intent.action.MAIN -c android.intent.category.HOME -p {AMAZON_HOME}"));
    adb.sh(&format!("pm uninstall {GLASS}"));
    adb.sh(&format!("pm uninstall {LEGACY}"));
}

/// Fire OS 6's `settings` silently drops writes to enabled_accessibility_services; the content
/// provider still takes them.
fn put_secure(adb: &Adb, key: &str, value: &str) {
    if value.is_empty() {
        adb.sh(&format!("settings put secure {key} \"\""));
    } else {
        adb.sh(&format!("settings put secure {key} {value}"));
    }
    let got = adb.sh1(&format!("settings get secure {key}"));
    if got != value && !(value.is_empty() && got == "null") {
        adb.sh(&format!("content insert --uri content://settings/secure --bind name:s:{key} --bind value:s:{value}"));
    }
}

/// Puts `guard` (or nothing) in place of any launcher's service, keeping every other one.
/// Clearing first makes the accessibility manager bind the service again.
fn set_guard(adb: &Adb, guard: &str) {
    let cur = adb.sh1("settings get secure enabled_accessibility_services");
    let mut keep: Vec<String> = cur
        .split(':')
        .filter(|c| !c.is_empty() && *c != "null" && *c != GUARD && *c != LEGACY_GUARD
            && !c.starts_with("app.glasshome.tv/") && !c.starts_with("tv.dumbtv.home/")
            && *c != "com.spocky.projengmenu/com.spocky.projengmenu.services.ProjectivyAccessibilityService")
        .map(str::to_string)
        .collect();
    if !guard.is_empty() {
        keep.push(guard.to_string());
    }
    put_secure(adb, "enabled_accessibility_services", "");
    thread::sleep(Duration::from_secs(1));
    if keep.is_empty() {
        put_secure(adb, "accessibility_enabled", "0");
    } else {
        put_secure(adb, "enabled_accessibility_services", &keep.join(":"));
        put_secure(adb, "accessibility_enabled", "1");
    }
}

/// Alarms normally fire no sooner than 5 s from now; the HOME switch needs them at once.
/// Android 11 and older read global alarm_manager_constants, 12 and later DeviceConfig. A value
/// someone set before is kept aside and put back on undo.
fn futurity(adb: &Adb, sdk: u32, on: bool) {
    let saved = adb.sh1(&format!("settings get global {SAVED_FUTURITY}"));
    let saved = if saved == "null" { String::new() } else { saved };
    if sdk >= 31 {
        let cur = adb.sh1("device_config get alarm_manager min_futurity");
        if on {
            if !cur.is_empty() && cur != "null" && cur != "0" && saved.is_empty() {
                adb.sh(&format!("settings put global {SAVED_FUTURITY} {cur}"));
            }
            adb.sh("device_config put alarm_manager min_futurity 0");
        } else if saved.is_empty() {
            adb.sh("device_config delete alarm_manager min_futurity");
        } else {
            adb.sh(&format!("device_config put alarm_manager min_futurity {saved}"));
        }
    } else {
        let cur = adb.sh1("settings get global alarm_manager_constants");
        let cur = if cur == "null" { String::new() } else { cur };
        let old = cur.split(',').find_map(|p| p.strip_prefix("min_futurity=")).unwrap_or("").to_string();
        let mut parts: Vec<String> = cur.split(',').filter(|p| !p.is_empty() && !p.starts_with("min_futurity=")).map(str::to_string).collect();
        if on {
            if !old.is_empty() && old != "0" && saved.is_empty() {
                adb.sh(&format!("settings put global {SAVED_FUTURITY} {old}"));
            }
            parts.push("min_futurity=0".into());
        } else if !saved.is_empty() {
            parts.push(format!("min_futurity={saved}"));
        }
        if parts.is_empty() {
            adb.sh("settings delete global alarm_manager_constants");
        } else {
            adb.sh(&format!("settings put global alarm_manager_constants {}", parts.join(",")));
        }
    }
    if !on {
        adb.sh(&format!("settings delete global {SAVED_FUTURITY}"));
        adb.sh("settings delete global dumbtv_min_futurity");
    }
}
