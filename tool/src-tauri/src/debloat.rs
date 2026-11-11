//! Debloat, after dumbtv's debloat.sh, lockdown.sh and dnsfilter.sh (MIT): Amazon packages that
//! serve ads, track viewing or report telemetry are disabled (never uninstalled), the privacy
//! settings are set, the packages Amazon protects lose background work and metered data, and
//! an on-device DNS filter can block their servers. Apps go only when the owner picked them.

use crate::adb::Adb;
use crate::device::{self, DNSFILTER};
use crate::lists::{self, Lists, OTA_HOSTS, OTA_PACKAGES};
use crate::steps::{Steps, LOST};
use serde::Deserialize;
use sha2::{Digest, Sha256};
use std::collections::HashSet;
use std::io::Read;
use std::path::Path;
use std::thread;
use std::time::Duration;

#[derive(Deserialize)]
#[serde(rename_all = "camelCase")]
pub struct Options {
    pub keep_prime: bool,
    pub keep_alexa: bool,
    pub keep_updates: bool,
    pub dns_filter: bool,
    pub remove: Vec<String>,
}

const APK_VERSION: &str = "1506103";
const APK_URL: &str = "https://f-droid.org/repo/dnsfilter.android_1506103.apk";
const APK_SHA256: &str = "3ddd0db322d1ca5fe950ea29dc1788af6e6581bb5f34694085fb4aa2e77b68ad";
/// personalDNSfilter's default configuration, pinned to one commit and checked like the APK:
/// keys the tool doesn't rewrite pass through to the TV, so an upstream change must not reach it
/// unseen. To update: pick a newer commit, check the file, put its SHA-256 here.
const CONF_URL: &str = "https://raw.githubusercontent.com/IngoZenz/personaldnsfilter/94182b2b17b2f0f392e5d973d2076fb3b32abfde/app/src/main/assets/dnsfilter.conf";
const CONF_SHA256: &str = "b0f99638581a4aa87148cd4638fd12f1659de2c29909df88c78fae7750447af9";
const FEED_URL: &str = "https://cdn.jsdelivr.net/gh/hagezi/dns-blocklists@latest/wildcard/native.amazon-onlydomains.txt";
const FILTER_DIR: &str = "/sdcard/Android/data/dnsfilter.android/files/PersonalDNSFilter";

pub fn run(adb: &Adb, sdk: u32, dir: &Path, o: &Options, s: &mut Steps) -> bool {
    let lists = Lists::for_sdk(sdk);

    // 1. Snapshot the settings before the first change, for Restore. Later runs keep the original.
    if !s.start(adb, "backup") {
        return false;
    }
    // Taken whole or not at all: a half-written or empty snapshot would make Restore put back
    // Amazon's defaults instead of this TV's own settings. The secure file is written last and
    // marks a complete snapshot. A copy goes onto the TV, for Restore from another device.
    let secure = dir.join("settings-before-secure.txt");
    let global = dir.join("settings-before-global.txt");
    if secure.exists() && global.exists() {
        s.done("backup", Some("already saved".into()));
    } else if !device::local_or_tv(adb, dir, "settings-before-global.txt").is_empty()
        && !device::local_or_tv(adb, dir, "settings-before-secure.txt").is_empty()
    {
        s.done("backup", Some("taken from the TV".into()));
    } else {
        let (g, sc) = match (adb.try_sh("settings list global"), adb.try_sh("settings list secure")) {
            (Ok(g), Ok(sc)) if looks_like_settings(&g) && looks_like_settings(&sc) => (g, sc),
            _ => {
                s.fail("backup", LOST);
                return false;
            }
        };
        if device::write_atomic(&global, &g).and_then(|_| device::write_atomic(&secure, &sc)).is_err() {
            s.fail("backup", "Could not save the settings on this device; nothing was changed.");
            return false;
        }
        s.done("backup", Some("saved".into()));
    }
    device::keep_on_tv(adb, &global);
    device::keep_on_tv(adb, &secure);

    // 2. Amazon packages.
    if !s.start(adb, "packages") {
        return false;
    }
    let keep: HashSet<&str> = if o.keep_updates { OTA_PACKAGES.iter().copied().collect() } else { HashSet::new() };
    let never: HashSet<&String> = lists.never.iter().collect();
    let mut cleanup = lists.packages.clone();
    if !o.keep_alexa {
        cleanup.extend(lists.alexa.iter().cloned());
    }
    let have = device::installed(adb);
    if have.is_empty() {
        s.fail("packages", LOST);
        return false;
    }
    let mut log = String::new();
    let (mut off, mut protected) = (0, 0);
    for p in cleanup.iter().filter(|p| have.contains(*p) && !never.contains(p) && !keep.contains(p.as_str())) {
        let out = adb.sh1(&format!("pm disable-user --user 0 {p}"));
        if out.contains("disabled-user") {
            off += 1;
            log.push_str(&format!("{p}\tdisabled\n"));
        } else if out.contains("protected") {
            protected += 1;
            log.push_str(&format!("{p}\tprotected\n"));
        }
    }
    if !adb.alive() {
        s.fail("packages", "Lost the connection to the Fire TV");
        return false;
    }
    s.done("packages", Some(format!("{off} off{}", if protected > 0 { format!(", {protected} protected") } else { String::new() })));

    // 3. Apps the owner picked: store apps are uninstalled, the TV's own are disabled.
    if !s.start(adb, "apps") {
        return false;
    }
    if o.remove.is_empty() {
        s.skip("apps", "none selected");
    } else {
        let system = device::system(adb);
        let mut gone = 0;
        for p in o.remove.iter().filter(|p| have.contains(*p) && !never.contains(p)) {
            let out = if system.contains(p) {
                adb.sh1(&format!("pm disable-user --user 0 {p}"))
            } else {
                adb.sh1(&format!("pm uninstall --user 0 {p}"))
            };
            if out.contains("Success") || out.contains("disabled") {
                gone += 1;
                log.push_str(&format!("{p}\tremoved\n"));
            }
        }
        s.done("apps", Some(format!("{gone} removed")));
    }

    // 4. Packages that keep running after their disable: background work off, metered data blocked.
    if !s.start(adb, "starve") {
        return false;
    }
    let running: HashSet<String> = adb.sh("ps -A -o NAME").lines().map(|l| l.trim().to_string()).collect();
    let mut starved = 0;
    for p in cleanup.iter().filter(|p| running.contains(*p) && !never.contains(p) && !keep.contains(p.as_str())) {
        device::bg(adb, sdk, p, false);
        adb.sh(&format!("am force-stop {p}"));
        if let Some(uid) = device::uid_of(adb, p).filter(|u| *u >= 10000) {
            adb.sh(&format!("cmd netpolicy add restrict-background-blacklist {uid}"));
        }
        starved += 1;
        log.push_str(&format!("{p}\tstarved\n"));
    }
    adb.sh("cmd netpolicy set restrict-background true");
    for p in lists.protected.iter().filter(|p| have.contains(*p) && !keep.contains(p.as_str())) {
        if let Some(uid) = device::uid_of(adb, p).filter(|u| *u >= 10000) {
            device::bg(adb, sdk, p, false);
            adb.sh(&format!("am force-stop {p}"));
            adb.sh(&format!("cmd netpolicy add restrict-background-blacklist {uid}"));
            starved += 1;
            log.push_str(&format!("{p}\tprotected-starved\n"));
        }
    }
    s.done("starve", Some(format!("{starved} services")));
    append(&dir.join("debloat.txt"), &log);
    device::keep_on_tv(adb, &dir.join("debloat.txt"));

    // 5. Privacy settings.
    if !s.start(adb, "settings") {
        return false;
    }
    let mut n = 0;
    let mut all = lists.settings.clone();
    if !o.keep_alexa {
        all.extend(lists.settings_alexa.iter().cloned());
    }
    for (ns, key, value) in &all {
        adb.sh(&format!("settings put {ns} {key} {value}"));
        n += 1;
    }
    s.done("settings", Some(format!("{n} set")));

    // 6. The DNS filter.
    if o.dns_filter {
        if !s.start(adb, "filter") {
            return false;
        }
        match dns_filter(adb, sdk, o) {
            Ok(Some(entries)) => s.done("filter", Some(format!("{entries} entries"))),
            Ok(None) => s.skip("filter", "not yet on Fire OS 8"),
            Err(e) => s.fail("filter", &e),
        }
    } else {
        s.skip("filter", "not selected");
    }

    // 7. Restart, so Fire OS reloads the package state and the filter starts as always-on VPN.
    if !s.start(adb, "reboot") {
        return false;
    }
    adb.sh("appops write-settings");
    if let Err(e) = device::reboot_and_wait(adb) {
        s.fail("reboot", &e);
        return false;
    }
    s.done("reboot", None::<String>);

    // 8. Check.
    if !s.start(adb, "verify") {
        return false;
    }
    let off_now = device::disabled(adb);
    let count = cleanup.iter().filter(|p| off_now.contains(*p)).count();
    if o.dns_filter && sdk < 30 {
        let host = lists::verify_blocked().into_iter().find(|h| !(o.keep_updates && OTA_HOSTS.contains(&h.as_str())));
        let mut blocked = false;
        for _ in 0..4 {
            let line = adb.sh1(&format!("ping -c1 -W2 {}", host.clone().unwrap_or_default()));
            if line.contains("127.0.0.1") || line.contains("0.0.0.0") {
                blocked = true;
                break;
            }
            thread::sleep(Duration::from_secs(8));
        }
        let open = adb.sh1("ping -c1 -W2 www.youtube.com");
        if !blocked {
            s.fail("verify", "The DNS filter is not blocking yet. Restart the Fire TV once.");
            return false;
        }
        if open.contains("unknown host") || open.is_empty() {
            s.fail("verify", "Names do not resolve: the filter cannot reach the router.");
            return false;
        }
    }
    s.done("verify", Some(format!("{count} packages off")));
    !s.failed
}

/// Starts the filter's VPN now: the app opens its window to start it, then the home screen comes
/// back (Glass Home when it is installed). Starting the VPN can cut adb; the connection returns.
fn start_filter(adb: &Adb) {
    adb.sh(&format!("am start -n {DNSFILTER}/.DNSProxyActivity"));
    thread::sleep(Duration::from_secs(6));
    adb.reconnect();
    if device::installed(adb).contains(device::GLASS) {
        adb.sh(&format!("am start -n {}/.Home", device::GLASS));
    } else {
        adb.sh("input keyevent KEYCODE_HOME");
    }
}

/// Whether the filter's list on the TV is the one just sent: its download count, and the lines.
fn list_on_tv(adb: &Adb, hosts: usize) -> bool {
    let count = adb.sh1(&format!("head -n 1 {FILTER_DIR}/FILTERHOSTS.TXT.DLD_CNT"));
    let lines: usize = adb.sh1(&format!("grep -vc '^#' {FILTER_DIR}/FILTERHOSTS.TXT")).parse().unwrap_or(0);
    count == hosts.to_string() && lines >= hosts
}

/// A settings listing, not an empty or broken answer.
fn looks_like_settings(text: &str) -> bool {
    text.lines().filter(|l| l.contains('=')).count() >= 5
}

fn append(file: &Path, text: &str) {
    use std::io::Write;
    if let Ok(mut f) = std::fs::OpenOptions::new().create(true).append(true).open(file) {
        let _ = f.write_all(text.as_bytes());
    }
}

fn fetch(url: &str) -> Result<Vec<u8>, String> {
    let mut last = String::new();
    let agent: ureq::Agent = ureq::Agent::config_builder().timeout_global(Some(Duration::from_secs(60))).build().into();
    for _ in 0..4 {
        match agent.get(url).call() {
            Ok(r) => {
                let mut buf = Vec::new();
                // into_reader has no size cap; read_to_vec stops at 10 MB, and the APK is larger.
                r.into_body().into_reader().read_to_end(&mut buf).map_err(|e| e.to_string())?;
                return Ok(buf);
            }
            Err(e) => {
                last = e.to_string();
                thread::sleep(Duration::from_secs(3));
            }
        }
    }
    Err(format!("Download failed ({url}): {last}"))
}

fn is_domain(s: &str) -> bool {
    let s = s.strip_prefix("*.").unwrap_or(s);
    s.contains('.') && s.chars().all(|c| c.is_ascii_alphanumeric() || c == '.' || c == '-' || c == '_')
        && !s.starts_with('.') && !s.ends_with('.')
}

/// personalDNSfilter as an always-on VPN that answers only for the listed hosts. Returns the
/// number of entries (an Option so a later TV generation can report "not here").
pub fn dns_filter(adb: &Adb, sdk: u32, o: &Options) -> Result<Option<usize>, String> {
    let gw = device::gateway(adb);
    if gw.is_empty() {
        return Err("Could not read the Fire TV's router address.".into());
    }
    // Where the filter asks for everything it does not block: the network's own DNS servers,
    // then the router. Not the router alone: behind a Pi-hole or a provider's DNS the TV would
    // otherwise resolve nothing at all once the filter runs.
    let mut upstream = device::dns_servers(adb);
    if !upstream.contains(&gw) {
        upstream.push(gw.clone());
    }
    let fallback = upstream.iter().map(|ip| format!("[{ip}]::53::UDP")).collect::<Vec<_>>().join("; ");
    let route = upstream.join("; ");

    // The list: HaGeZi's Amazon trackers plus dumbtv's hosts, minus what the owner keeps.
    let feed = String::from_utf8_lossy(&fetch(FEED_URL)?).to_string();
    let feed_lines: Vec<&str> = feed.lines().map(str::trim).filter(|l| !l.is_empty() && !l.starts_with('#')).collect();
    if feed_lines.len() < 50 || !feed_lines.iter().all(|l| is_domain(l)) {
        return Err("The tracker list looks wrong; try again later.".into());
    }
    let mut hosts: Vec<String> = feed_lines.iter().map(|s| s.to_string()).collect();
    hosts.extend(lists::extra_hosts());
    if !o.keep_alexa {
        hosts.extend(lists::alexa_hosts());
    }
    hosts.retain(|h| {
        let l = h.to_lowercase();
        !(o.keep_updates && OTA_HOSTS.contains(&l.as_str()))
            && !(o.keep_prime && (l.contains("aiv-delivery") || l.contains("aiv-cdn") || l == "device-messaging-na.amazon.com"))
    });
    let mut seen = HashSet::new();
    hosts.retain(|h| seen.insert(h.clone()));
    let list = hosts.join("\n") + "\n";

    let tmp = std::env::temp_dir().join(format!("glasshome-{}", std::process::id()));
    std::fs::create_dir_all(&tmp).map_err(|e| e.to_string())?;

    // The app, pinned by version and checksum.
    let have_version = adb
        .sh(&format!("dumpsys package {DNSFILTER}"))
        .lines()
        .find_map(|l| l.trim().strip_prefix("versionCode=").map(|v| v.split_whitespace().next().unwrap_or("").to_string()));
    if have_version.as_deref() != Some(APK_VERSION) {
        let apk = fetch(APK_URL)?;
        let sum = Sha256::digest(&apk).iter().map(|b| format!("{b:02x}")).collect::<String>();
        if sum != APK_SHA256 {
            return Err("The DNS filter's checksum does not match; not installed.".into());
        }
        let path = tmp.join("dnsfilter.apk");
        std::fs::write(&path, apk).map_err(|e| e.to_string())?;
        let _ = adb.install(&path);
        adb.reconnect();
    }
    adb.sh(&format!("pm enable --user 0 {DNSFILTER}"));
    adb.sh(&format!("appops set {DNSFILTER} ACTIVATE_VPN allow"));
    adb.sh(&format!("appops set {DNSFILTER} AUTO_START allow"));
    adb.sh(&format!("dumpsys deviceidle whitelist +{DNSFILTER}"));

    // Its configuration: no list downloads on the TV, the router as fallback, route the router
    // through the tunnel so apps cannot bypass the filter.
    let raw = fetch(CONF_URL)?;
    if Sha256::digest(&raw).iter().map(|b| format!("{b:02x}")).collect::<String>() != CONF_SHA256 {
        return Err("The DNS filter's configuration does not match its checksum; not set up.".into());
    }
    let conf = String::from_utf8_lossy(&raw).to_string();
    let white = if o.keep_prime && sdk < 28 { "com.amazon.firebat,com.amazon.avod" } else { "<none>" };
    let mut lines = Vec::new();
    for line in conf.lines() {
        let l = if line.starts_with("filterAutoUpdateURL = ") {
            format!("#{line}")
        } else if line.starts_with("previousAutoUpdateURL = ") {
            "previousAutoUpdateURL = ".into()
        } else if line.starts_with("detectDNS = ") {
            "detectDNS = false".into()
        } else if line.starts_with("fallbackDNS = ") {
            format!("fallbackDNS = \\#network; {fallback}")
        } else if line.starts_with("routeIPs =") {
            format!("routeIPs = {route}")
        } else if line.starts_with("routeUnderlyingDNS = ") {
            "routeUnderlyingDNS = true".into()
        } else if line.starts_with("showInitialInfoPopUp = ") {
            "showInitialInfoPopUp = false".into()
        } else if line.starts_with("reloadIntervalDays = ") {
            "reloadIntervalDays = 3650".into()
        } else if line.starts_with("filterHostsFile = ") {
            "filterHostsFile = FILTERHOSTS.TXT".into()
        } else if line.starts_with("androidAppWhiteList = ") {
            format!("androidAppWhiteList = {white}")
        } else {
            line.to_string()
        };
        lines.push(l);
    }
    let patched = lines.join("\n") + "\n";
    for want in ["#filterAutoUpdateURL", "detectDNS = false", "routeUnderlyingDNS = true"] {
        if !patched.contains(want) {
            return Err("The DNS filter's template has changed; not set up.".into());
        }
    }

    // A download-info file marks the pushed list as fresh; without it the first boot replaces
    // it with an empty "update" (format of DNSFilterManager.writeDownloadInfoFile).
    let millis = std::time::SystemTime::now().duration_since(std::time::UNIX_EPOCH).map(|d| d.as_millis()).unwrap_or(0);
    let count = format!("{}\n{}\n", hosts.len(), millis);

    if sdk >= 30 {
        // Android 11 hides the app's folder from adb: an adb restore carries the files in.
        restore_into_app(adb, sdk, &tmp, &patched, &list, &count)?;
        adb.sh(&format!("appops set {DNSFILTER} ACTIVATE_VPN allow"));
        adb.sh(&format!("dumpsys deviceidle whitelist +{DNSFILTER}"));
        adb.sh(&format!("settings put secure always_on_vpn_app {DNSFILTER}"));
        start_filter(adb);
        let _ = std::fs::remove_dir_all(&tmp);
        return Ok(Some(hosts.len()));
    }

    // A running filter must be out of the way first: Android restarts an always-on VPN app right
    // after a force-stop, and the running app then wrote an empty "download" over the new list.
    adb.sh("settings delete secure always_on_vpn_app");
    adb.sh(&format!("am force-stop {DNSFILTER}"));
    // Its first start creates its folder; only needed on a fresh install.
    if adb.sh1(&format!("test -d {FILTER_DIR} && echo yes")) != "yes" {
        adb.sh(&format!("am start -n {DNSFILTER}/.DNSProxyActivity"));
        let mut ready = false;
        for _ in 0..8 {
            if adb.sh1(&format!("test -d {FILTER_DIR} && echo yes")) == "yes" {
                ready = true;
                break;
            }
            thread::sleep(Duration::from_secs(3));
        }
        if !ready {
            return Err("The DNS filter did not create its folder.".into());
        }
        adb.sh(&format!("am force-stop {DNSFILTER}"));
    }
    thread::sleep(Duration::from_secs(2));
    let conf_path = tmp.join("dnsfilter.conf");
    let list_path = tmp.join("FILTERHOSTS.TXT");
    let count_path = tmp.join("FILTERHOSTS.TXT.DLD_CNT");
    std::fs::write(&conf_path, patched).map_err(|e| e.to_string())?;
    std::fs::write(&list_path, &list).map_err(|e| e.to_string())?;
    std::fs::write(&count_path, count).map_err(|e| e.to_string())?;
    // Copied, switched on, and checked twice: right after copying, and once the VPN runs again.
    let mut placed = false;
    for _ in 0..2 {
        adb.sh(&format!("am force-stop {DNSFILTER}"));
        adb.push(&conf_path, &format!("{FILTER_DIR}/dnsfilter.conf"))?;
        adb.sh(&format!("rm -rf {FILTER_DIR}/FILTERHOSTS.TXT.idx; touch {FILTER_DIR}/IDX_OUTDATED"));
        adb.push(&list_path, &format!("{FILTER_DIR}/FILTERHOSTS.TXT"))?;
        adb.push(&count_path, &format!("{FILTER_DIR}/FILTERHOSTS.TXT.DLD_CNT"))?;
        if !list_on_tv(adb, hosts.len()) {
            continue;
        }
        adb.sh(&format!("settings put secure always_on_vpn_app {DNSFILTER}"));
        thread::sleep(Duration::from_secs(8));
        adb.reconnect();
        if list_on_tv(adb, hosts.len()) {
            placed = true;
            break;
        }
        adb.sh("settings delete secure always_on_vpn_app");
    }
    if !placed {
        adb.sh(&format!("settings put secure always_on_vpn_app {DNSFILTER}"));
        return Err("The DNS filter replaced its block list with an empty one. Run Debloat again.".into());
    }
    // Running again at once, not only after the next restart: the filter was stopped above, and
    // the TV must not be left without it. Fire OS 6 also needs this one launch for its boot start.
    start_filter(adb);
    let _ = std::fs::remove_dir_all(&tmp);
    Ok(Some(hosts.len()))
}

// ------------------------------------------------------------------ Fire OS 8 (Android 11)

/// One ustar entry: Android's backup reader knows plain ustar only.
fn tar_entry(out: &mut Vec<u8>, name: &str, data: Option<&[u8]>) {
    let mut h = [0u8; 512];
    let put = |h: &mut [u8; 512], at: usize, s: &[u8]| h[at..at + s.len()].copy_from_slice(s);
    put(&mut h, 0, name.as_bytes());
    let (mode, size, kind) = match data { Some(d) => ("0000600", d.len(), b'0'), None => ("0000700", 0, b'5') };
    put(&mut h, 100, format!("{mode}\0").as_bytes());
    put(&mut h, 108, b"0000000\0");
    put(&mut h, 116, b"0000000\0");
    put(&mut h, 124, format!("{size:011o}\0").as_bytes());
    put(&mut h, 136, format!("{:011o}\0", 1_700_000_000u64).as_bytes());
    put(&mut h, 148, b"        ");
    h[156] = kind;
    put(&mut h, 257, b"ustar\0");
    put(&mut h, 263, b"00");
    let sum: u32 = h.iter().map(|b| *b as u32).sum();
    put(&mut h, 148, format!("{sum:06o}\0 ").as_bytes());
    out.extend_from_slice(&h);
    if let Some(d) = data {
        out.extend_from_slice(d);
        out.resize(out.len() + (512 - d.len() % 512) % 512, 0);
    }
}

/// An uncompressed `adb backup` archive whose files land in the app's files/PersonalDNSFilter,
/// as dumbtv's appdata.sh writes it: manifest first, then the folder and its files.
fn backup_archive(sdk: u32, conf: &str, list: &str, count: &str) -> Vec<u8> {
    let signer: String = include_str!("../data/personaldnsfilter-signer.txt")
        .lines()
        .filter(|l| !l.starts_with('#'))
        .collect::<String>()
        .split_whitespace()
        .collect();
    let base = format!("apps/{DNSFILTER}");
    let manifest = format!("1\n{DNSFILTER}\n{APK_VERSION}\n{sdk}\n\n0\n1\n{signer}\n");
    let dir = format!("{base}/ef/PersonalDNSFilter");
    let mut tar = Vec::new();
    tar_entry(&mut tar, &format!("{base}/_manifest"), Some(manifest.as_bytes()));
    tar_entry(&mut tar, &format!("{dir}/"), None);
    for (name, body) in [
        ("dnsfilter.conf", conf),
        ("FILTERHOSTS.TXT", list),
        ("FILTERHOSTS.TXT.DLD_CNT", count),
        ("IDX_OUTDATED", ""),
        // Without VERSION.TXT the app takes the files for an upgrade and merges its defaults back.
        ("VERSION.TXT", APK_VERSION),
    ] {
        tar_entry(&mut tar, &format!("{dir}/{name}"), Some(body.as_bytes()));
    }
    tar.extend_from_slice(&[0u8; 1024]);
    let mut out = b"ANDROID BACKUP\n5\n0\nnone\n".to_vec();
    out.extend_from_slice(&tar);
    out
}

fn restore_count(adb: &Adb, pattern: &[&str]) -> usize {
    adb.sh("logcat -d -s BackupManagerService").lines().filter(|l| pattern.iter().any(|p| l.contains(p))).count()
}

fn confirmation_shown(adb: &Adb) -> bool {
    adb.sh("dumpsys window").lines().any(|l| l.contains("mCurrentFocus=") && l.contains("BackupRestoreConfirmation"))
}

/// Taps "Restore my data" where a uiautomator dump places it.
fn tap_allow(adb: &Adb) -> bool {
    let ui = adb.sh("uiautomator dump /sdcard/glasshome-ui.xml >/dev/null 2>&1; cat /sdcard/glasshome-ui.xml; rm -f /sdcard/glasshome-ui.xml");
    let Some(at) = ui.find("com.android.backupconfirm:id/button_allow") else { return false };
    let Some(b) = ui[at..].find("bounds=\"[") else { return false };
    let rest = &ui[at + b + 9..];
    let nums: Vec<i32> = rest.split(|c: char| !c.is_ascii_digit()).filter(|s| !s.is_empty()).take(4).filter_map(|s| s.parse().ok()).collect();
    if nums.len() < 4 {
        return false;
    }
    adb.sh(&format!("input tap {} {}", (nums[0] + nums[2]) / 2, (nums[1] + nums[3]) / 2));
    true
}

/// Restores the archive with `bu restore` on the TV (what adbd runs for `adb restore`) and
/// answers the TV's confirmation (dumbtv's restore_app_data). Android
/// logs "processing complete" even for a refused request, so success also needs its "taking
/// data" line and no read error.
fn restore_into_app(adb: &Adb, sdk: u32, tmp: &Path, conf: &str, list: &str, count: &str) -> Result<(), String> {
    if !adb.sh1("dumpsys backup").starts_with("Backup Manager is ") {
        return Err("Android's backup service is not running on this Fire TV; the filter cannot be set up here.".into());
    }
    let ab = tmp.join("appdata.ab");
    std::fs::write(&ab, backup_archive(sdk, conf, list, count)).map_err(|e| e.to_string())?;
    // With the filter's VPN up the restore broke off part-way; with the app stopped it went through.
    adb.sh(&format!("am force-stop {DNSFILTER}"));
    thread::sleep(Duration::from_secs(3));
    adb.reconnect();
    let done0 = restore_count(adb, &["adb restore processing complete"]);
    let took0 = restore_count(adb, &["taking data"]);
    let fail0 = restore_count(adb, &["ErrnoException", "timed out waiting for user confirmation"]);
    adb.sh("am force-stop com.android.backupconfirm");

    const REMOTE_AB: &str = "/data/local/tmp/glasshome-appdata.ab";
    adb.push(&ab, REMOTE_AB)?;
    // Runs until the restore is confirmed or refused; the steps below watch and answer the TV
    // meanwhile, on their own connections.
    let child = thread::scope(|s| {
        let run = s.spawn(|| adb.sh(&format!("bu restore < {REMOTE_AB}")));
        let r = answer_restore(adb, &run, (done0, took0, fail0));
        let _ = run.join();
        r
    });
    adb.sh(&format!("rm -f {REMOTE_AB}"));
    child
}

/// Waits for the confirmation, presses Allow, and waits for the restore to finish.
fn answer_restore(adb: &Adb, child: &thread::ScopedJoinHandle<'_, String>, (done0, took0, fail0): (usize, usize, usize)) -> Result<(), String> {
    for _ in 0..40 {
        if confirmation_shown(adb) || child.is_finished() {
            break;
        }
        thread::sleep(Duration::from_millis(500));
    }
    // The window has focus before its buttons reach the accessibility tree: try a few times.
    for _ in 0..5 {
        thread::sleep(Duration::from_millis(1500));
        if tap_allow(adb) {
            thread::sleep(Duration::from_secs(2));
        }
        if !confirmation_shown(adb) {
            break;
        }
    }
    for _ in 0..90 {
        if child.is_finished() {
            break;
        }
        thread::sleep(Duration::from_secs(1));
    }
    for _ in 0..30 {
        if restore_count(adb, &["adb restore processing complete"]) > done0 {
            break;
        }
        thread::sleep(Duration::from_secs(1));
    }
    let ok = restore_count(adb, &["adb restore processing complete"]) > done0
        && restore_count(adb, &["taking data"]) > took0
        && restore_count(adb, &["ErrnoException", "timed out waiting for user confirmation"]) == fail0;
    if ok {
        Ok(())
    } else {
        Err("The confirmation on the Fire TV did not come through. Run Debloat again and choose “Restore my data” on the TV.".into())
    }
}

#[cfg(test)]
mod tests {
    /// The Fire OS 8 archive's tar part must read back with a real tar: names, sizes, checksums.
    #[test]
    fn backup_archive_is_valid_ustar() {
        let ab = super::backup_archive(30, "detectDNS = false\n", "a.example.com\n", "1\n0\n");
        let header = b"ANDROID BACKUP\n5\n0\nnone\n";
        assert!(ab.starts_with(header));
        let tar = std::env::temp_dir().join("glasshome-test.tar");
        std::fs::write(&tar, &ab[header.len()..]).unwrap();
        let out = std::process::Command::new("tar").arg("-tvf").arg(&tar).output().expect("tar runs");
        let list = String::from_utf8_lossy(&out.stdout);
        assert!(out.status.success(), "{}", String::from_utf8_lossy(&out.stderr));
        assert!(list.lines().next().unwrap().ends_with("apps/dnsfilter.android/_manifest"), "{list}");
        for f in ["dnsfilter.conf", "FILTERHOSTS.TXT", "FILTERHOSTS.TXT.DLD_CNT", "IDX_OUTDATED", "VERSION.TXT"] {
            assert!(list.contains(&format!("ef/PersonalDNSFilter/{f}")), "{f} missing: {list}");
        }
    }
}
