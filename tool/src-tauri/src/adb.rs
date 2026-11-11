//! adb spoken directly over the network (adb_client): no adb program, no adb server. One TV at a
//! time; its address is kept here. Connections are pooled, so a long pull does not hold up a
//! status check running next to it.

use adb_client::{tcp::ADBTcpDevice, ADBDeviceExt, RustADBError};
use std::fs::File;
use std::net::{SocketAddr, TcpStream, ToSocketAddrs};
use std::path::{Path, PathBuf};
use std::sync::Mutex;
use std::thread;
use std::time::{Duration, Instant};

pub struct Adb {
    key: PathBuf,
    serial: Mutex<String>,
    pool: Mutex<Vec<ADBTcpDevice>>,
}

/// Why a connection could not be opened.
enum Open {
    /// Nothing answers on the adb port: ADB debugging off, wrong address, other network.
    Unreachable,
    /// The port answers, but the handshake failed; usually the TV still waits for "Allow".
    Refused(String),
}

const PORT: u16 = 5555;
const TMP_APK: &str = "/data/local/tmp/glasshome-install.apk";

impl Adb {
    /// `key` is the RSA key this computer signs in with; it is created there when missing.
    pub fn new(key: PathBuf) -> Self {
        Adb { key, serial: Mutex::new(String::new()), pool: Mutex::new(Vec::new()) }
    }

    pub fn serial(&self) -> String {
        self.serial.lock().unwrap().clone()
    }

    pub fn set_serial(&self, s: &str) {
        *self.serial.lock().unwrap() = s.to_string();
        self.forget();
    }

    /// Drops every open connection; the next command opens a fresh one.
    pub fn forget(&self) {
        self.pool.lock().unwrap().clear();
    }

    fn address(&self) -> Option<SocketAddr> {
        self.serial().to_socket_addrs().ok()?.next()
    }

    fn open(&self) -> Result<ADBTcpDevice, Open> {
        let addr = self.address().ok_or(Open::Unreachable)?;
        // adb_client connects without a time limit; a TV that is off would stall for ~20 s.
        TcpStream::connect_timeout(&addr, Duration::from_secs(3)).map_err(|_| Open::Unreachable)?;
        ensure_key(&self.key).map_err(Open::Refused)?;
        ADBTcpDevice::new_with_custom_private_key(addr, &self.key).map_err(|e| Open::Refused(e.to_string()))
    }

    /// Runs `f` on a pooled connection. A pooled connection can have gone stale (the TV
    /// restarted, the DNS filter's VPN cut it): then once more on a fresh one.
    fn with<T>(&self, mut f: impl FnMut(&mut ADBTcpDevice) -> Result<T, RustADBError>) -> Result<T, String> {
        let pooled = self.pool.lock().unwrap().pop();
        let was_pooled = pooled.is_some();
        let mut d = match pooled {
            Some(d) => d,
            None => self.open().map_err(describe)?,
        };
        match f(&mut d) {
            Ok(v) => {
                self.pool.lock().unwrap().push(d);
                Ok(v)
            }
            Err(e) if !was_pooled => Err(e.to_string()),
            Err(_) => {
                let mut d = self.open().map_err(describe)?;
                let v = f(&mut d).map_err(|e| e.to_string())?;
                self.pool.lock().unwrap().push(d);
                Ok(v)
            }
        }
    }

    /// A shell command on the TV: stdout, then stderr, CRs removed. Empty when the TV did not answer.
    pub fn sh(&self, script: &str) -> String {
        self.try_sh(script).unwrap_or_default()
    }

    /// The same, but an Err when the TV did not answer, so a dropped connection is never read as
    /// empty output ("no packages", "no settings").
    pub fn try_sh(&self, script: &str) -> Result<String, String> {
        self.with(|d| {
            let (mut out, mut err) = (Vec::new(), Vec::new());
            d.shell_command(&script, Some(&mut out), Some(&mut err))?;
            out.extend_from_slice(&err);
            Ok(out)
        })
        .map(|b| String::from_utf8_lossy(&b).replace('\r', "").trim_end().to_string())
    }

    /// First line of a shell command, trimmed.
    pub fn sh1(&self, script: &str) -> String {
        self.sh(script).lines().next().unwrap_or("").trim().to_string()
    }

    /// Whether the TV still answers. A dropped network session must not read as "package absent".
    pub fn alive(&self) -> bool {
        self.sh1("echo ok") == "ok"
    }

    pub fn push(&self, local: &Path, remote: &str) -> Result<(), String> {
        self.with(|d| {
            let mut f = File::open(local)?;
            d.push(&mut f, &remote)
        })
        .map_err(|e| format!("Copying to the Fire TV failed: {e}"))
    }

    pub fn pull(&self, remote: &str, local: &Path) -> Result<(), String> {
        self.with(|d| {
            let mut f = File::create(local)?;
            d.pull(&remote, &mut f)
        })
        .map_err(|e| format!("Reading from the Fire TV failed: {e}"))
    }

    /// Installs or updates an app: copied to the TV, then `pm install -r`, which every Fire OS
    /// since 5 understands (the streamed `cmd package install` needs Android 7).
    pub fn install(&self, apk: &Path) -> Result<(), String> {
        self.push(apk, TMP_APK)?;
        let out = self.sh(&format!("pm install -r {TMP_APK}"));
        self.sh(&format!("rm -f {TMP_APK}"));
        if out.contains("Success") { Ok(()) } else { Err(format!("Install failed: {}", last_line(&out))) }
    }

    /// Opens the connection to the TV and waits until it is allowed. The first time, the TV asks
    /// "Allow USB debugging?"; adb_client waits 10 s per attempt, so attempts repeat for a minute.
    pub fn connect(&self, ip: &str) -> Result<(), String> {
        self.set_serial(&format!("{ip}:{PORT}"));
        let start = Instant::now();
        let mut reached = false;
        loop {
            match self.open() {
                Ok(d) => {
                    self.pool.lock().unwrap().push(d);
                    return Ok(());
                }
                Err(Open::Unreachable) if !reached => {
                    return Err(format!(
                        "Could not reach {ip}. Is ADB debugging on, and are the Fire TV and this computer on the same Wi-Fi?"
                    ));
                }
                Err(_) => reached = true,
            }
            if start.elapsed() > Duration::from_secs(60) {
                return Err("The Fire TV did not allow the connection. On the TV, confirm “Allow USB debugging” with OK.".into());
            }
            thread::sleep(Duration::from_secs(1));
        }
    }

    /// Brings a dropped network session back (the DNS filter's VPN restarting can cut it).
    pub fn reconnect(&self) -> bool {
        for _ in 0..15 {
            if self.alive() {
                return true;
            }
            self.forget();
            thread::sleep(Duration::from_secs(3));
        }
        false
    }

    pub fn disconnect(&self) {
        self.forget();
        *self.serial.lock().unwrap() = String::new();
    }
}

fn describe(e: Open) -> String {
    match e {
        Open::Unreachable => "The Fire TV does not answer.".into(),
        Open::Refused(m) => format!("The Fire TV refused the connection: {m}"),
    }
}

fn last_line(s: &str) -> String {
    s.lines().rfind(|l| !l.trim().is_empty()).unwrap_or("").trim().to_string()
}

/// The key a TV remembers this computer by. adb's own (~/.android/adbkey) when there is one, so
/// TVs that already allowed this computer keep doing so; otherwise one next to the app's data.
pub fn key_path(data: &Path) -> PathBuf {
    if let Some(home) = std::env::var_os("ANDROID_USER_HOME").map(PathBuf::from).or_else(home_android) {
        let k = home.join("adbkey");
        if k.is_file() {
            return k;
        }
    }
    data.join("adbkey")
}

fn home_android() -> Option<PathBuf> {
    let home = std::env::var_os(if cfg!(windows) { "USERPROFILE" } else { "HOME" })?;
    Some(PathBuf::from(home).join(".android"))
}

/// Creates the key when it does not exist yet. adb_client would make a new one for every
/// connection without saving it, and the TV would ask "Allow USB debugging?" every time.
fn ensure_key(path: &Path) -> Result<(), String> {
    if path.is_file() {
        return Ok(());
    }
    use rsa::pkcs8::{EncodePrivateKey, LineEnding};
    let key = rsa::RsaPrivateKey::new(&mut rsa::rand_core::OsRng, 2048).map_err(|e| e.to_string())?;
    let pem = key.to_pkcs8_pem(LineEnding::LF).map_err(|e| e.to_string())?;
    if let Some(dir) = path.parent() {
        std::fs::create_dir_all(dir).map_err(|e| e.to_string())?;
    }
    std::fs::write(path, pem.as_bytes()).map_err(|e| e.to_string())
}
