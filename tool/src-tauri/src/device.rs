//! What the connected TV is, what state it is in, and the small shell helpers every task uses.

use crate::adb::Adb;
use crate::lists::{self, Lists};
use serde::{Deserialize, Serialize};
use std::collections::{HashMap, HashSet};
use std::path::{Path, PathBuf};
use std::thread;
use std::time::Duration;

pub const GLASS: &str = "app.glasshome.tv";
/// The launcher's earlier name: an install is migrated from it.
pub const LEGACY: &str = "tv.dumbtv.home";
pub const DNSFILTER: &str = "dnsfilter.android";
pub const AMAZON_HOME: &str = "com.amazon.tv.launcher";

#[derive(Serialize, Clone)]
#[serde(rename_all = "camelCase")]
pub struct Device {
    pub serial: String,
    pub ip: String,
    pub model: String,
    pub name: String,
    pub fire_os: String,
    pub sdk: u32,
    pub supported: bool,
    pub reason: Option<String>,
}

#[derive(Serialize)]
#[serde(rename_all = "camelCase")]
pub struct State {
    pub debloated: bool,
    pub disabled: usize,
    pub dns_filter: bool,
    pub glass_ui: bool,
    pub glass_version: Option<String>,
    pub layout: String,
    pub tiles: String,
    pub theme: String,
    pub arrows: bool,
}

#[derive(Serialize, Deserialize, Clone)]
pub struct App {
    pub pkg: String,
    pub label: String,
}

pub fn detect(adb: &Adb, ip: &str) -> Device {
    let maker = adb.sh1("getprop ro.product.manufacturer");
    let model = adb.sh1("getprop ro.product.model");
    let sdk: u32 = adb.sh1("getprop ro.build.version.sdk").parse().unwrap_or(0);
    let fire_os = match sdk {
        s if s > 30 => format!("Fire OS (Android-API {s})"),
        30 => "Fire OS 8".into(),
        28 | 29 => "Fire OS 7".into(),
        25..=27 => "Fire OS 6".into(),
        _ => "Fire OS 5 or older".into(),
    };
    let (supported, reason) = if !maker.starts_with("Amazon") {
        (false, Some(format!("This is not an Amazon Fire TV ({maker}). Glass Home is built for Fire OS.")))
    } else if sdk < 25 {
        (false, Some("Fire OS 5 and older cannot turn packages off. This device is not supported.".into()))
    } else {
        (true, None)
    };
    Device {
        serial: adb.serial(),
        ip: ip.to_string(),
        name: lists::model_name(&model),
        model,
        fire_os,
        sdk,
        supported,
        reason,
    }
}

/// Every installed package, one shell call.
pub fn installed(adb: &Adb) -> HashSet<String> {
    adb.sh("pm list packages").lines().filter_map(|l| l.strip_prefix("package:")).map(str::to_string).collect()
}

pub fn disabled(adb: &Adb) -> HashSet<String> {
    adb.sh("pm list packages -d").lines().filter_map(|l| l.strip_prefix("package:")).map(str::to_string).collect()
}

pub fn system(adb: &Adb) -> HashSet<String> {
    adb.sh("pm list packages -s").lines().filter_map(|l| l.strip_prefix("package:")).map(str::to_string).collect()
}

pub fn version(adb: &Adb, pkg: &str) -> Option<String> {
    adb.sh(&format!("dumpsys package {pkg}"))
        .lines()
        .find_map(|l| l.trim().strip_prefix("versionName=").map(|v| v.trim().to_string()))
}

/// Android 14 prints appId= where older releases print userId=.
pub fn uid_of(adb: &Adb, pkg: &str) -> Option<u32> {
    for line in adb.sh(&format!("dumpsys package {pkg}")).lines() {
        let t = line.trim();
        for key in ["userId=", "appId="] {
            if let Some(rest) = t.strip_prefix(key) {
                if let Ok(n) = rest.split_whitespace().next().unwrap_or("").parse() {
                    return Some(n);
                }
            }
        }
    }
    None
}

pub fn bg(adb: &Adb, sdk: u32, pkg: &str, allow: bool) {
    let mode = if allow { "allow" } else { "ignore" };
    adb.sh(&format!("appops set {pkg} RUN_IN_BACKGROUND {mode}"));
    if sdk >= 28 {
        adb.sh(&format!("appops set {pkg} RUN_ANY_IN_BACKGROUND {mode}"));
    }
}

/// The activity in front, as package/.Class (dumbtv's front_activity, issue #57 included).
pub fn front(adb: &Adb) -> String {
    let dump = adb.sh("dumpsys activity activities");
    let line = dump
        .lines()
        .find(|l| l.trim_start().starts_with("ResumedActivity: ActivityRecord"))
        .or_else(|| dump.lines().find(|l| l.contains("topResumedActivity=ActivityRecord") || l.contains("mResumedActivity: ActivityRecord")))
        .unwrap_or("");
    line.split_whitespace().find(|w| w.contains('/')).unwrap_or("").trim_end_matches('}').to_string()
}

/// The router: the TV's default route that is not the filter's own tunnel.
pub fn gateway(adb: &Adb) -> String {
    for line in adb.sh("ip route show table all").lines() {
        if line.starts_with("default") && line.contains(" via ") && !line.contains(" dev tun") {
            let w: Vec<&str> = line.split_whitespace().collect();
            if let Some(i) = w.iter().position(|x| *x == "via") {
                if let Some(gw) = w.get(i + 1) {
                    return gw.to_string();
                }
            }
        }
    }
    adb.sh1("getprop dhcp.wlan0.gateway")
}

/// A proper restart. Fire OS 7.7 refuses `svc power reboot` to the shell and stays up; then a
/// plain reboot after the ~10 s package state write keeps every change (dumbtv's ask_reboot).
pub fn reboot_and_wait(adb: &Adb) -> Result<(), String> {
    let before = adb.sh1("cat /proc/sys/kernel/random/boot_id");
    adb.sh("svc power reboot");
    thread::sleep(Duration::from_secs(3));
    if adb.alive() && adb.sh1("getprop sys.shutdown.requested").is_empty() {
        thread::sleep(Duration::from_secs(12));
        adb.sh("reboot");
    }
    thread::sleep(Duration::from_secs(30));
    for _ in 0..40 {
        adb.forget();
        if adb.sh1("getprop sys.boot_completed") == "1" && adb.sh1("cat /proc/sys/kernel/random/boot_id") != before {
            thread::sleep(Duration::from_secs(8));
            return Ok(());
        }
        thread::sleep(Duration::from_secs(4));
    }
    Err("The Fire TV did not come back after the restart. Please check the TV.".into())
}

/// A copy of the tool's notes on the TV itself (shell-only, kept through reboots and app removals),
/// so Restore still works when the phone that ran Debloat died, was reset, or the TV's address changed.
pub const TV_NOTES: &str = "/data/local/tmp/glasshome";

pub fn keep_on_tv(adb: &Adb, local: &Path) {
    let Some(name) = local.file_name().and_then(|n| n.to_str()) else { return };
    adb.sh(&format!("mkdir -p {TV_NOTES} && chmod 700 {TV_NOTES}"));
    let remote = format!("{TV_NOTES}/{name}");
    // The settings listing is this TV's own business: readable by the shell user only, not by apps.
    if adb.push(local, &remote).is_ok() {
        adb.sh(&format!("chmod 600 {remote}"));
    }
}

/// The note from this device, or else the TV's copy (written to this device for next time).
pub fn local_or_tv(adb: &Adb, dir: &Path, name: &str) -> String {
    let local = dir.join(name);
    if let Ok(t) = std::fs::read_to_string(&local) {
        if !t.trim().is_empty() {
            return t;
        }
    }
    if adb.sh1(&format!("[ -s {TV_NOTES}/{name} ] && echo yes")) == "yes" && adb.pull(&format!("{TV_NOTES}/{name}"), &local).is_ok() {
        return std::fs::read_to_string(&local).unwrap_or_default();
    }
    String::new()
}

/// Writes through a temporary file, so a phone dying mid-write leaves the old file or none.
pub fn write_atomic(path: &Path, text: &str) -> std::io::Result<()> {
    let part = path.with_extension("part");
    std::fs::write(&part, text)?;
    std::fs::rename(&part, path)
}

/// Where the tool keeps what it needs to undo its work on this TV (settings snapshot, log).
pub fn device_dir(base: &Path, serial: &str) -> PathBuf {
    let safe: String = serial.chars().map(|c| if c.is_ascii_alphanumeric() || c == '.' { c } else { '_' }).collect();
    let d = base.join("devices").join(safe);
    let _ = std::fs::create_dir_all(&d);
    d
}

pub fn config_path(sdk: u32, pkg: &str) -> String {
    if sdk >= 30 {
        format!("/sdcard/Android/media/{pkg}/home.conf")
    } else {
        format!("/sdcard/Android/data/{pkg}/files/home.conf")
    }
}

/// key=value lines of the launcher's home.conf on the TV.
pub fn read_config(adb: &Adb, sdk: u32, pkg: &str) -> Vec<(String, String)> {
    read_config_checked(adb, sdk, pkg).unwrap_or_default()
}

/// The launcher's settings, or an Err when the TV did not answer: a reinstall must never take a
/// dropped connection for "no settings" and start over with the defaults.
pub fn read_config_checked(adb: &Adb, sdk: u32, pkg: &str) -> Result<Vec<(String, String)>, String> {
    let mut text = adb.try_sh(&format!("cat {}", config_path(sdk, pkg)))?;
    if sdk >= 30 && text.contains("No such file") {
        text = adb.try_sh(&format!("cat /sdcard/Android/data/{pkg}/files/home.conf"))?;
    }
    Ok(parse_config(&text))
}

fn parse_config(text: &str) -> Vec<(String, String)> {
    text.lines()
        .filter(|l| !l.starts_with('#') && l.contains('='))
        .filter_map(|l| l.split_once('=').map(|(k, v)| (k.trim().to_string(), v.trim().to_string())))
        .collect()
}

pub fn state(adb: &Adb, sdk: u32) -> State {
    let lists = Lists::for_sdk(sdk);
    let off = disabled(adb);
    let count = lists.packages.iter().filter(|p| off.contains(*p)).count();
    let have = installed(adb);
    let dns = have.contains(DNSFILTER) && !off.contains(DNSFILTER) && adb.sh1("settings get secure always_on_vpn_app") == DNSFILTER;
    let pkg = if have.contains(GLASS) { Some(GLASS) } else if have.contains(LEGACY) { Some(LEGACY) } else { None };
    let conf: HashMap<String, String> = pkg.map(|p| read_config(adb, sdk, p)).unwrap_or_default().into_iter().collect();
    State {
        debloated: count > 0,
        disabled: count,
        dns_filter: dns,
        glass_ui: pkg.is_some(),
        glass_version: pkg.and_then(|p| version(adb, p)).map(|v| if pkg == Some(LEGACY) { format!("{v} (alt)") } else { v }),
        layout: conf.get("glass.layout").cloned().unwrap_or_else(|| "dock".into()),
        tiles: if conf.get("glass.icons").map(String::as_str) == Some("glass") { "glass".into() } else { "original".into() },
        theme: conf.get("glass.theme").filter(|t| crate::ui::THEMES.contains(&t.as_str())).cloned().unwrap_or_else(|| "neutral".into()),
        arrows: conf.get("glass.arrows").map(String::as_str) == Some("on"),
    }
}

/// The apps the owner installed, each with the name the TV shows. The name comes from the
/// app itself (read from its pulled APK), never from the package id: a guessed name once
/// cost an owner their IPTV player. Names are cached by APK path, which changes on update.
pub fn user_apps(adb: &Adb, cache_dir: &Path) -> Vec<App> {
    let skip = [GLASS, LEGACY, DNSFILTER];
    let cache_file = cache_dir.join("labels.json");
    let mut cache: HashMap<String, String> =
        std::fs::read_to_string(&cache_file).ok().and_then(|s| serde_json::from_str(&s).ok()).unwrap_or_default();
    let mut out = Vec::new();
    for line in adb.sh("pm list packages -3 -f").lines() {
        // package:/data/app/<dir>/base.apk=<pkg>
        let Some(rest) = line.strip_prefix("package:") else { continue };
        let Some((path, pkg)) = rest.rsplit_once('=') else { continue };
        // Amazon's own packages are never offered here, even when an update installed them
        // as user apps (com.amazon.avod is Prime Video's player).
        if skip.contains(&pkg) || pkg.contains(".overlay") || pkg.starts_with("com.amazon.") {
            continue;
        }
        let label = match cache.get(path) {
            Some(l) => l.clone(),
            None => {
                let l = read_label(adb, cache_dir, path).unwrap_or_else(|| pkg.to_string());
                cache.insert(path.to_string(), l.clone());
                l
            }
        };
        out.push(App { pkg: pkg.to_string(), label });
    }
    let _ = std::fs::write(&cache_file, serde_json::to_string(&cache).unwrap_or_default());
    out.sort_by_key(|a| a.label.to_lowercase());
    out
}

/// The app's name from its manifest and resources, as the TV shows it.
fn read_label(adb: &Adb, dir: &Path, remote: &str) -> Option<String> {
    let local = dir.join("probe.apk");
    adb.pull(remote, &local).ok()?;
    let label = apk_info::Apk::new(&local).ok().and_then(|a| a.get_application_label());
    let _ = std::fs::remove_file(&local);
    label.map(|l| l.trim().to_string()).filter(|l| !l.is_empty())
}
