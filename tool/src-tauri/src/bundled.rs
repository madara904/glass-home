//! The Glass Home APK is built into the program itself, so the tool is one file that runs
//! without an installer. On start they are written to the app's data folder, into a
//! folder named after this build, and only when they are not there yet.

use std::fs;
use std::io;
use std::path::{Path, PathBuf};

const FILES: &[(&str, &[u8])] = &[("glass-home.apk", include_bytes!("../resources/glass-home.apk"))];

/// Writes the built-in files below `data` and returns their folder. The folder is named after
/// the files' contents, so new files always land in a new folder (a rebuilt APK can have the old
/// one's exact size).
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
    }
    Ok(root)
}
