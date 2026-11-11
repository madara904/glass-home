//! Finding TVs on the network without connecting to them. Four sources run side by side and are
//! merged per address: hosts that answer on adb's port, mDNS announcements (Fire TVs announce
//! `_amzn-wplay._tcp` with their own name; Google and Android TVs announce `_googlecast._tcp`),
//! the Chromecast info page on port 8008, and, as fallbacks, the router's host name and the
//! MAC prefix. Everything stays on the local network: no lookups on the internet.

use serde::Serialize;
use std::collections::{BTreeMap, HashSet};
use std::net::{IpAddr, Ipv4Addr, SocketAddr, TcpStream, UdpSocket};
use std::process::{Command, Stdio};
use std::sync::mpsc;
use std::thread;
use std::time::{Duration, Instant};

/// How long mDNS answers are collected. The port scan and the lookups per host run meanwhile.
const LISTEN: Duration = Duration::from_secs(3);
/// Per lookup (Chromecast page, host name).
const LOOKUP: Duration = Duration::from_millis(1500);

const FIRE_TV: &str = "_amzn-wplay._tcp.local.";
const CHROMECAST: &str = "_googlecast._tcp.local.";

#[derive(Serialize, Clone, Copy, PartialEq, Eq, PartialOrd, Ord, Debug, Default)]
#[serde(rename_all = "lowercase")]
pub enum Kind {
    /// Announced as a Fire TV, or an Amazon device with adb open.
    Fire,
    /// Nothing says what it is.
    #[default]
    Unknown,
    /// A Google TV or Android TV (it has Chromecast built in): Glass Home does not support it.
    Google,
}

#[derive(Serialize, Clone, Debug, Default)]
#[serde(rename_all = "camelCase")]
pub struct Found {
    pub ip: String,
    /// The name the device gives itself ("Living Room Fire TV"), else the router's host name.
    pub name: Option<String>,
    /// The model, where the device says it before connecting (Chromecast `md=`).
    pub model: Option<String>,
    /// "Amazon" when the MAC prefix is registered to Amazon.
    pub vendor: Option<String>,
    pub kind: Kind,
    /// Answers on adb's port 5555.
    pub adb: bool,
}

/// What one mDNS answer says about an address.
#[derive(Default)]
struct Heard {
    fire: bool,
    google: bool,
    name: Option<String>,
    model: Option<String>,
    host: Option<String>,
}

pub fn discover() -> Result<Vec<Found>, String> {
    let me = local_v4()?;
    let heard = thread::spawn(|| listen(LISTEN));

    // A phone with wireless debugging on answers on the adb port itself; it is not a TV.
    let open: Vec<Ipv4Addr> = scan(me).into_iter().filter(|&ip| ip != me).collect();
    let looked: Vec<_> = open
        .iter()
        .map(|&ip| thread::spawn(move || (ip, chromecast_name(ip), host_name(ip), mac_vendor(ip))))
        .collect();
    let looked: Vec<_> = looked.into_iter().filter_map(|h| h.join().ok()).collect();
    let heard = heard.join().unwrap_or_default();

    let mut all: BTreeMap<Ipv4Addr, Found> = BTreeMap::new();
    for (ip, cast, host, vendor) in looked {
        let f = all.entry(ip).or_insert_with(|| Found {
            ip: ip.to_string(),
            ..Default::default()
        });
        f.adb = true;
        if let Some(n) = cast {
            f.kind = Kind::Google;
            f.name = Some(n);
        }
        f.vendor = vendor;
        if f.name.is_none() {
            f.name = host;
        }
    }
    for (ip, h) in heard {
        if ip == me {
            continue;
        }
        let f = all.entry(ip).or_insert_with(|| Found {
            ip: ip.to_string(),
            ..Default::default()
        });
        if h.google {
            f.kind = Kind::Google;
        } else if h.fire && f.kind != Kind::Google {
            f.kind = Kind::Fire;
        }
        // The device's own name beats the router's host name.
        if let Some(n) = h.name.or(h.host) {
            if f.kind == Kind::Google || h.fire || f.name.is_none() {
                f.name = Some(n);
            }
        }
        if h.model.is_some() {
            f.model = h.model;
        }
    }
    for f in all.values_mut() {
        if f.kind == Kind::Unknown && f.adb && f.vendor.as_deref() == Some("Amazon") {
            f.kind = Kind::Fire;
        }
    }

    // Only what matters here: anything with adb open, and Fire TVs that still need adb turned on.
    let mut list: Vec<Found> = all
        .into_values()
        .filter(|f| f.adb || f.kind == Kind::Fire)
        .collect();
    // Fire TVs ready to connect first, then Fire TVs without adb, then the rest; Google TVs last.
    list.sort_by_key(|f| (f.kind, !f.adb));
    Ok(list)
}

/// This computer's IPv4 address on the network that leads to the internet (no packet is sent).
fn local_v4() -> Result<Ipv4Addr, String> {
    let addr = UdpSocket::bind("0.0.0.0:0")
        .and_then(|s| s.connect("8.8.8.8:80").map(|_| s))
        .and_then(|s| s.local_addr())
        .map_err(|_| "No network found.".to_string())?;
    match addr {
        SocketAddr::V4(v4) => Ok(*v4.ip()),
        SocketAddr::V6(_) => Err("Only IPv4 networks can be searched.".to_string()),
    }
}

/// Every host on this computer's /24 that answers on adb's port 5555.
fn scan(me: Ipv4Addr) -> Vec<Ipv4Addr> {
    let o = me.octets();
    let handles: Vec<_> = (1..=254u8)
        .filter(|h| *h != o[3])
        .map(|h| {
            thread::spawn(move || {
                let ip = Ipv4Addr::new(o[0], o[1], o[2], h);
                TcpStream::connect_timeout(
                    &SocketAddr::from((ip, 5555)),
                    Duration::from_millis(400),
                )
                .ok()
                .map(|_| ip)
            })
        })
        .collect();
    handles
        .into_iter()
        .filter_map(|h| h.join().ok().flatten())
        .collect()
}

/// mDNS answers for Fire TVs and Chromecasts, collected for `wait`, per address.
fn listen(wait: Duration) -> BTreeMap<Ipv4Addr, Heard> {
    let mut heard: BTreeMap<Ipv4Addr, Heard> = BTreeMap::new();
    let Ok(daemon) = mdns_sd::ServiceDaemon::new() else {
        return heard;
    };
    let (Ok(fire), Ok(cast)) = (daemon.browse(FIRE_TV), daemon.browse(CHROMECAST)) else {
        let _ = daemon.shutdown();
        return heard;
    };
    let end = Instant::now() + wait;
    while let Some(left) = end.checked_duration_since(Instant::now()) {
        // Both channels in turn, a short wait on each, until the time is up.
        for rx in [&fire, &cast] {
            let Ok(mdns_sd::ServiceEvent::ServiceResolved(s)) =
                rx.recv_timeout(left.min(Duration::from_millis(50)))
            else {
                continue;
            };
            let is_fire = s.ty_domain == FIRE_TV;
            let text = |k: &str| {
                s.get_property_val_str(k)
                    .map(str::trim)
                    .filter(|v| !v.is_empty())
                    .map(str::to_string)
            };
            let host = s
                .get_hostname()
                .trim_end_matches('.')
                .trim_end_matches(".local");
            for ip in s.get_addresses_v4() {
                let h = heard.entry(ip).or_default();
                if is_fire {
                    h.fire = true;
                    h.name = text("n").or(h.name.take());
                } else {
                    h.google = true;
                    h.name = text("fn").or(h.name.take());
                    h.model = text("md").or(h.model.take());
                }
                if !host.is_empty() {
                    h.host.get_or_insert_with(|| host.to_string());
                }
            }
        }
    }
    let _ = daemon.shutdown();
    heard
}

/// The name from a Chromecast's info page; only Google and Android TVs (and Chromecasts) have one.
fn chromecast_name(ip: Ipv4Addr) -> Option<String> {
    let agent: ureq::Agent = ureq::Agent::config_builder().timeout_global(Some(LOOKUP)).build().into();
    let text = agent
        .get(&format!("http://{ip}:8008/setup/eureka_info"))
        .call()
        .ok()?
        .into_body()
        .read_to_string()
        .ok()?;
    let body: serde_json::Value = serde_json::from_str(&text).ok()?;
    body.get("name")
        .and_then(|n| n.as_str())
        .map(str::trim)
        .filter(|n| !n.is_empty())
        .map(str::to_string)
}

/// The host name the router gives this address, first label only ("fire-tv-stick").
fn host_name(ip: Ipv4Addr) -> Option<String> {
    let (tx, rx) = mpsc::channel();
    // getnameinfo has no timeout of its own; a slow router must not hold up the search.
    thread::spawn(move || {
        let _ = tx.send(dns_lookup::lookup_addr(&IpAddr::V4(ip)).ok());
    });
    let name = rx.recv_timeout(LOOKUP).ok().flatten()?;
    let first = name.trim_end_matches('.').split('.').next()?.to_string();
    (!first.is_empty() && first != ip.to_string()).then_some(first)
}

/// "Amazon" when the address's MAC (from the ARP table, filled by the port scan) has one of
/// Amazon's registered prefixes.
fn mac_vendor(ip: Ipv4Addr) -> Option<String> {
    let mac = arp_mac(ip)?;
    amazon_prefixes()
        .contains(&mac[..6])
        .then(|| "Amazon".to_string())
}

/// The MAC for an address as 12 upper-case hex digits, from the system's `arp`.
fn arp_mac(ip: Ipv4Addr) -> Option<String> {
    let mut c = Command::new("arp");
    #[cfg(windows)]
    {
        use std::os::windows::process::CommandExt;
        c.creation_flags(0x0800_0000).args(["-a", &ip.to_string()]);
    }
    #[cfg(not(windows))]
    c.args(["-n", &ip.to_string()]);
    let out = c.stdin(Stdio::null()).stderr(Stdio::null()).output().ok()?;
    String::from_utf8_lossy(&out.stdout)
        .split_whitespace()
        .find_map(parse_mac)
}

/// `8c:4e:bb:f7:18:ec`, `8c-4e-bb-f7-18-ec` or macOS's short form `64:ff:a:a0:96:4d`.
fn parse_mac(word: &str) -> Option<String> {
    let parts: Vec<&str> = word.split([':', '-']).collect();
    if parts.len() != 6
        || !parts
            .iter()
            .all(|p| (1..=2).contains(&p.len()) && p.chars().all(|c| c.is_ascii_hexdigit()))
    {
        return None;
    }
    let mac: String = parts
        .iter()
        .map(|p| format!("{:0>2}", p.to_ascii_uppercase()))
        .collect();
    (mac != "000000000000" && mac != "FFFFFFFFFFFF").then_some(mac)
}

fn amazon_prefixes() -> HashSet<&'static str> {
    include_str!("../data/amazon-oui.txt")
        .lines()
        .map(str::trim)
        .filter(|l| !l.is_empty() && !l.starts_with('#'))
        .collect()
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn macs_in_every_form() {
        assert_eq!(
            parse_mac("8c:4e:bb:12:34:56").as_deref(),
            Some("8C4EBB123456")
        );
        assert_eq!(
            parse_mac("8c-4e-bb-12-34-56").as_deref(),
            Some("8C4EBB123456")
        );
        assert_eq!(
            parse_mac("64:ff:a:12:34:56").as_deref(),
            Some("64FF0A123456")
        );
        assert_eq!(parse_mac("(192.168.1.20)"), None);
        assert_eq!(parse_mac("ff-ff-ff-ff-ff-ff"), None);
    }

    #[test]
    fn amazon_prefix_list() {
        let p = amazon_prefixes();
        assert!(p.contains("8C4EBB"), "a Fire TV Stick's prefix");
        assert!(!p.contains("64FF0A"), "WNC, in a Sony TV");
        assert!(p.iter().all(|x| x.len() == 6));
    }

    #[test]
    fn order_fire_first_google_last() {
        let f = |kind, adb| Found {
            kind,
            adb,
            ..Default::default()
        };
        let mut l = [
            f(Kind::Google, true),
            f(Kind::Unknown, true),
            f(Kind::Fire, false),
            f(Kind::Fire, true),
        ];
        l.sort_by_key(|f| (f.kind, !f.adb));
        let got: Vec<_> = l.iter().map(|f| (f.kind, f.adb)).collect();
        assert_eq!(
            got,
            vec![
                (Kind::Fire, true),
                (Kind::Fire, false),
                (Kind::Unknown, true),
                (Kind::Google, true)
            ]
        );
    }
}
