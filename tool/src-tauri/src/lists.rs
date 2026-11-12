//! dumbtv's package and settings lists (MIT), built into the tool. Fire OS 6 has its own
//! package lists; anything it lacks falls back to the Fire OS 7/8 file, as in dumbtv.

macro_rules! data {
    ($p:literal) => {
        include_str!(concat!("../data/", $p))
    };
}

/// Lines without comments and blank lines; the first word of each.
pub fn words(text: &str) -> Vec<String> {
    text.lines()
        .map(|l| l.split('#').next().unwrap_or("").trim())
        .filter(|l| !l.is_empty())
        .filter_map(|l| l.split_whitespace().next().map(str::to_string))
        .collect()
}

/// A list file as groups: `# --- title ---` lines start a group, a comment after a package
/// describes it. Packages before the first title land in a group without one.
pub fn grouped(text: &str) -> Vec<(String, Vec<(String, String)>)> {
    let mut out: Vec<(String, Vec<(String, String)>)> = Vec::new();
    for line in text.lines() {
        let l = line.trim();
        if let Some(t) = l.strip_prefix("# ---") {
            let title = t.trim_end_matches('-').trim();
            // "update nags (the updater itself is protected, …)": the part before the bracket.
            let title = title.split(" (").next().unwrap_or(title).trim().to_string();
            out.push((title, Vec::new()));
            continue;
        }
        if l.is_empty() || l.starts_with('#') {
            continue;
        }
        let (pkg, desc) = match l.split_once('#') {
            Some((p, d)) => (p.trim(), d.trim()),
            None => (l, ""),
        };
        let Some(pkg) = pkg.split_whitespace().next() else { continue };
        if out.is_empty() {
            out.push((String::new(), Vec::new()));
        }
        out.last_mut().unwrap().1.push((pkg.to_string(), desc.to_string()));
    }
    out
}

/// The package files themselves, for the plan the window shows before Debloat.
pub fn package_text(sdk: u32) -> &'static str {
    if sdk > 0 && sdk < 28 { data!("fireos6/packages.txt") } else { data!("fireos/packages.txt") }
}
pub fn protected_text(sdk: u32) -> &'static str {
    if sdk > 0 && sdk < 28 { data!("fireos6/protected.txt") } else { data!("fireos/protected.txt") }
}
pub fn alexa_text() -> &'static str {
    data!("fireos/alexa.txt")
}

/// `namespace key value` lines (dumbtv also writes `key = value`).
pub fn settings(text: &str) -> Vec<(String, String, String)> {
    text.lines()
        .map(|l| l.split('#').next().unwrap_or("").trim().replace(" = ", " "))
        .filter(|l| !l.is_empty())
        .filter_map(|l| {
            let mut it = l.splitn(3, ' ');
            Some((it.next()?.to_string(), it.next()?.to_string(), it.next().unwrap_or("").trim().to_string()))
        })
        .collect()
}

pub struct Lists {
    pub packages: Vec<String>,
    pub protected: Vec<String>,
    pub never: Vec<String>,
    pub alexa: Vec<String>,
    pub user_apps: Vec<String>,
    pub settings: Vec<(String, String, String)>,
    pub settings_alexa: Vec<(String, String, String)>,
    pub settings_restore: Vec<(String, String, String)>,
}

impl Lists {
    pub fn for_sdk(sdk: u32) -> Lists {
        let six = sdk > 0 && sdk < 28;
        Lists {
            packages: words(if six { data!("fireos6/packages.txt") } else { data!("fireos/packages.txt") }),
            protected: words(if six { data!("fireos6/protected.txt") } else { data!("fireos/protected.txt") }),
            never: words(if six { data!("fireos6/never-disable.txt") } else { data!("fireos/never-disable.txt") }),
            alexa: words(data!("fireos/alexa.txt")),
            user_apps: words(data!("fireos/user-apps.txt")),
            settings: settings(data!("fireos/settings.txt")),
            settings_alexa: settings(data!("fireos/settings.alexa.txt")),
            settings_restore: settings(data!("fireos/settings.restore.txt")),
        }
    }
}

/// The OTA updater's packages and hosts: left alone when the owner keeps Fire OS updates.
pub const OTA_PACKAGES: &[&str] = &[
    "com.amazon.device.software.ota",
    "com.amazon.device.software.ota.override",
    "com.amazon.tv.forcedotaupdater.v2",
];
pub const OTA_HOSTS: &[&str] = &[
    "softwareupdates.amazon.com",
    "dubri19p4y5b.cloudfront.net",
    "distro-download.amazon.com",
    "amzdigital-a.akamaihd.net",
    "amzdigitaldownloads.edgesuite.net",
];

pub fn extra_hosts() -> Vec<String> {
    words(data!("blocklists/extra-hosts.txt"))
}
pub fn alexa_hosts() -> Vec<String> {
    words(data!("blocklists/alexa-hosts.txt"))
}
pub fn verify_blocked() -> Vec<String> {
    words(data!("blocklists/verify-blocked.txt"))
}

/// Retail names for the Fire TV models in dumbtv's catalog; others show their model id.
pub fn model_name(model: &str) -> String {
    let name = match model {
        "AFTSS" => "Fire TV Stick Lite",
        "AFTSSS" => "Fire TV Stick",
        "AFTT" => "Fire TV Stick",
        "AFTMM" => "Fire TV Stick 4K",
        "AFTKM" => "Fire TV Stick 4K",
        "AFTKA" => "Fire TV Stick 4K Max",
        "AFTKRT" => "Fire TV Stick 4K Max",
        "AFTMA08C15" => "Fire TV Stick 4K Plus",
        "AFTR" => "Fire TV Cube",
        "AFTGAZL" => "Fire TV Cube",
        "AFTA" => "Fire TV Cube",
        "AFTN" => "Fire TV",
        _ => "",
    };
    if name.is_empty() { format!("Fire TV ({model})") } else { name.to_string() }
}
