//! adb, aapt2 and the Glass Home APK are built into the program itself, so the tool is one file
//! that runs without an installer. On start they are written to the app's data folder, into a
//! folder named after this build, and only when they are not there yet.

use std::fs;
use std::io;
use std::path::{Path, PathBuf};

#[cfg(windows)]
const FILES: &[(&str, &[u8])] = &[
    ("platform-tools/adb.exe", include_bytes!("../resources/platform-tools/adb.exe")),
    ("platform-tools/AdbWinApi.dll", include_bytes!("../resources/platform-tools/AdbWinApi.dll")),
    ("platform-tools/AdbWinUsbApi.dll", include_bytes!("../resources/platform-tools/AdbWinUsbApi.dll")),
    ("aapt2.exe", include_bytes!("../resources/aapt2.exe")),
    ("glass-home.apk", include_bytes!("../resources/glass-home.apk")),
];

#[cfg(target_os = "macos")]
const FILES: &[(&str, &[u8])] = &[
    ("platform-tools/adb", include_bytes!("../resources/macos/platform-tools/adb")),
    ("aapt2", include_bytes!("../resources/macos/aapt2")),
    ("glass-home.apk", include_bytes!("../resources/glass-home.apk")),
];

#[cfg(not(any(windows, target_os = "macos")))]
const FILES: &[(&str, &[u8])] = &[];

/// Writes the built-in files below `data` and returns their folder. The folder is named after
/// the files' contents, so new files always land in a new folder (a rebuilt APK can have the old
/// one's exact size) and an adb server still running from the old folder is never in the way.
pub fn unpack(data: &Path) -> io::Result<PathBuf> {
    use sha2::{Digest, Sha256};
    let mut h = Sha256::new();
    for (name, bytes) in FILES {
        h.update(name.as_bytes());
        h.update(bytes);
    }
    let sum: String = h.finalize().iter().take(6).map(|b| format!("{b:02x}")).collect();
    let root = data.join("bin").join(sum);
    for (name, bytes) in FILES {
        let path = root.join(name);
        if fs::metadata(&path).map(|m| m.len() == bytes.len() as u64).unwrap_or(false) {
            continue;
        }
        if let Some(dir) = path.parent() {
            fs::create_dir_all(dir)?;
        }
        let part = path.with_extension("part");
        fs::write(&part, bytes)?;
        fs::rename(&part, &path)?;
        #[cfg(unix)]
        if !name.ends_with(".apk") {
            use std::os::unix::fs::PermissionsExt;
            fs::set_permissions(&path, fs::Permissions::from_mode(0o755))?;
        }
    }
    Ok(root)
}
