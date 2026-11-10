//! The bundled adb, run without console windows. One TV at a time: its serial is kept here.

use std::path::{Path, PathBuf};
use std::process::{Command, Stdio};
use std::sync::Mutex;
use std::thread;
use std::time::Duration;

#[cfg(windows)]
use std::os::windows::process::CommandExt;
#[cfg(windows)]
const CREATE_NO_WINDOW: u32 = 0x0800_0000;

pub struct Adb {
    exe: PathBuf,
    serial: Mutex<String>,
}

impl Adb {
    pub fn new(exe: PathBuf) -> Self {
        Adb { exe, serial: Mutex::new(String::new()) }
    }

    pub fn exe(&self) -> &Path {
        &self.exe
    }

    pub fn serial(&self) -> String {
        self.serial.lock().unwrap().clone()
    }

    pub fn set_serial(&self, s: &str) {
        *self.serial.lock().unwrap() = s.to_string();
    }

    fn command(&self) -> Command {
        let mut c = Command::new(&self.exe);
        #[cfg(windows)]
        c.creation_flags(CREATE_NO_WINDOW);
        c.stdin(Stdio::null());
        c
    }

    /// adb with these arguments; stdout and stderr together, CRs removed.
    pub fn raw(&self, args: &[&str]) -> Result<String, String> {
        let out = self.command().args(args).output().map_err(|e| format!("adb could not start: {e}"))?;
        let mut s = String::from_utf8_lossy(&out.stdout).to_string();
        s.push_str(&String::from_utf8_lossy(&out.stderr));
        Ok(s.replace('\r', ""))
    }

    fn on_device(&self, args: &[&str]) -> Result<String, String> {
        let serial = self.serial();
        let mut all = vec!["-s", serial.as_str()];
        all.extend_from_slice(args);
        self.raw(&all)
    }

    /// A shell command on the TV. Output, or an empty string when the TV did not answer.
    pub fn sh(&self, script: &str) -> String {
        self.on_device(&["shell", script]).unwrap_or_default().trim_end().to_string()
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
        let l = local.to_string_lossy().to_string();
        let out = self.on_device(&["push", &l, remote])?;
        if out.contains("pushed") { Ok(()) } else { Err(format!("Copying to the Fire TV failed: {}", last_line(&out))) }
    }

    pub fn pull(&self, remote: &str, local: &Path) -> Result<(), String> {
        let l = local.to_string_lossy().to_string();
        let out = self.on_device(&["pull", remote, &l])?;
        if out.contains("pulled") { Ok(()) } else { Err(format!("Reading from the Fire TV failed: {}", last_line(&out))) }
    }

    pub fn install(&self, apk: &Path) -> Result<(), String> {
        let a = apk.to_string_lossy().to_string();
        let out = self.on_device(&["install", "-r", &a])?;
        if out.contains("Success") { Ok(()) } else { Err(format!("Install failed: {}", last_line(&out))) }
    }

    /// `adb connect`, then waits for the TV to answer as an authorised device. The first
    /// connection makes the TV ask "Allow USB debugging?"; a person has to accept it.
    pub fn connect(&self, ip: &str) -> Result<(), String> {
        let target = format!("{ip}:5555");
        let _ = self.raw(&["start-server"]);
        let out = self.raw(&["connect", &target])?;
        if out.contains("cannot connect") || out.contains("failed") {
            return Err(format!(
                "Could not reach {ip}. Is ADB debugging on, and are the Fire TV and this computer on the same Wi-Fi?"
            ));
        }
        self.set_serial(&target);
        for _ in 0..60 {
            let state = self.raw(&["-s", &target, "get-state"]).unwrap_or_default();
            if state.trim() == "device" {
                return Ok(());
            }
            thread::sleep(Duration::from_millis(1000));
        }
        Err("The Fire TV did not allow the connection. On the TV, confirm “Allow USB debugging” with OK.".into())
    }

    /// Brings a dropped network session back (the DNS filter's VPN restarting can cut it).
    pub fn reconnect(&self) -> bool {
        let serial = self.serial();
        for _ in 0..15 {
            if self.alive() {
                return true;
            }
            let _ = self.raw(&["disconnect", &serial]);
            let _ = self.raw(&["connect", &serial]);
            thread::sleep(Duration::from_secs(3));
        }
        false
    }

    pub fn disconnect(&self) {
        let serial = self.serial();
        if !serial.is_empty() {
            let _ = self.raw(&["disconnect", &serial]);
        }
        self.set_serial("");
    }
}

fn last_line(s: &str) -> String {
    s.lines().rfind(|l| !l.trim().is_empty()).unwrap_or("").trim().to_string()
}
