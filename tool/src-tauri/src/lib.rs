//! Glass Home tool: the commands the window calls. Long tasks run off the UI thread and report
//! their steps as events; only one TV is connected at a time.

mod adb;
mod bundled;
mod debloat;
mod device;
mod discover;
mod lists;
mod restore;
mod steps;
mod ui;

use adb::Adb;
use std::path::{Path, PathBuf};
use std::sync::{Arc, Mutex};
use steps::Steps;
use tauri::{AppHandle, Manager, State};

struct Ctx {
    adb: Arc<Adb>,
    device: Mutex<Option<device::Device>>,
    resources: PathBuf,
    data: PathBuf,
}

type Res<T> = Result<T, String>;

fn current(ctx: &Ctx) -> Res<device::Device> {
    ctx.device.lock().unwrap().clone().ok_or_else(|| "No Fire TV connected.".to_string())
}

async fn blocking<T: Send + 'static>(f: impl FnOnce() -> T + Send + 'static) -> Res<T> {
    tauri::async_runtime::spawn_blocking(f).await.map_err(|e| e.to_string())
}

fn debloat_steps(app: Option<&AppHandle>) -> Steps {
    Steps::new(app, &[
        ("backup", "Back up settings"),
        ("packages", "Turn off Amazon packages"),
        ("apps", "Remove selected apps"),
        ("starve", "Throttle background services"),
        ("settings", "Privacy settings"),
        ("filter", "Set up DNS filter"),
        ("reboot", "Restart"),
        ("verify", "Check"),
    ])
}

fn ui_steps(app: Option<&AppHandle>) -> Steps {
    Steps::new(app, &[
        ("migrate", "Keep existing settings"),
        ("install", "Install Glass Home"),
        ("config", "Apply layout"),
        ("home", "Take over the home screen"),
        ("verify", "Check"),
    ])
}

fn restore_steps(app: Option<&AppHandle>) -> Steps {
    Steps::new(app, &[
        ("home", "Bring back Amazon's home screen"),
        ("packages", "Turn packages back on"),
        ("limits", "Lift throttling"),
        ("settings", "Reset settings"),
        ("filter", "Remove DNS filter"),
        ("reboot", "Restart"),
    ])
}

/// Fire TVs and other TVs on the network, with what each one says about itself.
#[tauri::command]
async fn discover() -> Res<Vec<discover::Found>> {
    blocking(discover::discover).await?
}

#[tauri::command]
async fn connect(ip: String, ctx: State<'_, Ctx>) -> Res<device::Device> {
    let adb = ctx.adb.clone();
    let dev = blocking(move || -> Res<device::Device> {
        adb.connect(&ip)?;
        Ok(device::detect(&adb, &ip))
    })
    .await??;
    *ctx.device.lock().unwrap() = if dev.supported { Some(dev.clone()) } else { None };
    Ok(dev)
}

#[tauri::command]
async fn disconnect(ctx: State<'_, Ctx>) -> Res<()> {
    ctx.adb.disconnect();
    *ctx.device.lock().unwrap() = None;
    Ok(())
}

#[tauri::command]
async fn device_state(ctx: State<'_, Ctx>) -> Res<device::State> {
    let d = current(&ctx)?;
    let adb = ctx.adb.clone();
    blocking(move || device::state(&adb, d.sdk)).await
}

#[tauri::command]
async fn user_apps(ctx: State<'_, Ctx>) -> Res<Vec<device::App>> {
    let d = current(&ctx)?;
    let adb = ctx.adb.clone();
    let aapt2 = aapt2_path(&ctx.resources);
    let cache = device::device_dir(&ctx.data, &d.serial);
    blocking(move || device::user_apps(&adb, &aapt2, &cache)).await
}

#[tauri::command]
async fn run_debloat(app: AppHandle, options: debloat::Options, ctx: State<'_, Ctx>) -> Res<bool> {
    let d = current(&ctx)?;
    let adb = ctx.adb.clone();
    let dir = device::device_dir(&ctx.data, &d.serial);
    blocking(move || {
        let mut s = debloat_steps(Some(&app));
        debloat::run(&adb, d.sdk, &dir, &options, &mut s)
    })
    .await
}

#[tauri::command]
async fn run_install_ui(app: AppHandle, options: ui::Options, ctx: State<'_, Ctx>) -> Res<bool> {
    let d = current(&ctx)?;
    let adb = ctx.adb.clone();
    let apk = ctx.resources.join("glass-home.apk");
    blocking(move || {
        let mut s = ui_steps(Some(&app));
        ui::run(&adb, d.sdk, &apk, &options, &mut s)
    })
    .await
}

#[tauri::command]
async fn run_restore(app: AppHandle, ctx: State<'_, Ctx>) -> Res<bool> {
    let d = current(&ctx)?;
    let adb = ctx.adb.clone();
    let dir = device::device_dir(&ctx.data, &d.serial);
    blocking(move || {
        let mut s = restore_steps(Some(&app));
        restore::run(&adb, d.sdk, &dir, &mut s)
    })
    .await
}

/// Opens the project's or one of the authors' GitHub pages in the default browser. Only these
/// addresses.
#[tauri::command]
fn open_github(user: String) {
    let url = match user.as_str() {
        "glass-home" => "https://github.com/madara904/glass-home",
        "madara904" => "https://github.com/madara904",
        "denniskribl" => "https://github.com/denniskribl",
        _ => return,
    };
    #[cfg(windows)]
    {
        use std::os::windows::process::CommandExt;
        let _ = std::process::Command::new("rundll32").args(["url.dll,FileProtocolHandler", url]).creation_flags(0x0800_0000).spawn();
    }
    #[cfg(target_os = "macos")]
    let _ = std::process::Command::new("open").arg(url).spawn();
}

/// The right-click menu's "Developer tools": the web inspector, in release builds too.
#[tauri::command]
fn open_devtools(window: tauri::WebviewWindow) {
    window.open_devtools();
}

/// `--discover`: the network search as the window would show it, as JSON. Connects to nothing.
pub fn discover_cli() {
    match discover::discover() {
        Ok(l) => println!("{}", serde_json::to_string_pretty(&l).unwrap_or_default()),
        Err(e) => eprintln!("{e}"),
    }
}

/// `--probe <ip>`: connects, prints what the window would show (device, state, apps) and quits.
/// Reads only; for checking a TV without clicking through the window.
pub fn probe(ip: &str) {
    let base = match bundled::unpack(&std::env::temp_dir().join("glasshome-cli")) {
        Ok(b) => b,
        Err(e) => {
            eprintln!("could not unpack adb: {e}");
            return Default::default();
        }
    };
    let adb = Adb::new(adb_path(&base));
    if let Err(e) = adb.connect(ip) {
        eprintln!("{e}");
        return;
    }
    let d = device::detect(&adb, ip);
    println!("{}", serde_json::to_string_pretty(&d).unwrap_or_default());
    if !d.supported {
        return;
    }
    println!("{}", serde_json::to_string_pretty(&device::state(&adb, d.sdk)).unwrap_or_default());
    let cache = std::env::temp_dir().join("glasshome-probe");
    let _ = std::fs::create_dir_all(&cache);
    println!("{}", serde_json::to_string_pretty(&device::user_apps(&adb, &aapt2_path(&base), &cache)).unwrap_or_default());
}

/// `--run ui|debloat|restore <ip> [data dir]`: the same task as the window's button, steps printed.
/// Debloat runs with the defaults the window starts with (keep Prime, Alexa, updates; DNS
/// filter on; remove nothing); Glass UI keeps the TV's current layout.
pub fn run_cli(task: &str, ip: &str, data: Option<&str>) -> bool {
    let base = match bundled::unpack(&std::env::temp_dir().join("glasshome-cli")) {
        Ok(b) => b,
        Err(e) => {
            eprintln!("could not unpack adb: {e}");
            return Default::default();
        }
    };
    let adb = Adb::new(adb_path(&base));
    if let Err(e) = adb.connect(ip) {
        eprintln!("{e}");
        return false;
    }
    let d = device::detect(&adb, ip);
    if !d.supported {
        eprintln!("{}", d.reason.unwrap_or_default());
        return false;
    }
    let data = data.map(PathBuf::from).unwrap_or_else(|| std::env::temp_dir().join("glasshome-cli"));
    let dir = device::device_dir(&data, &d.serial);
    println!("{} · {} · {}", d.name, d.fire_os, d.serial);
    match task {
        "debloat" => {
            let o = debloat::Options { keep_prime: true, keep_alexa: true, keep_updates: true, dns_filter: true, remove: vec![] };
            debloat::run(&adb, d.sdk, &dir, &o, &mut debloat_steps(None))
        }
        "ui" => {
            let st = device::state(&adb, d.sdk);
            let o = ui::Options { layout: st.layout, tiles: st.tiles };
            ui::run(&adb, d.sdk, &base.join("glass-home.apk"), &o, &mut ui_steps(None))
        }
        "restore" => restore::run(&adb, d.sdk, &dir, &mut restore_steps(None)),
        _ => {
            eprintln!("unknown task {task}");
            false
        }
    }
}

pub fn run() {
    tauri::Builder::default()
        .setup(|app| {
            // adb, aapt2 and the APK are inside the program; they are unpacked next to its data.
            let data = app.path().app_data_dir()?;
            std::fs::create_dir_all(&data)?;
            let resources = bundled::unpack(&data)?;
            let adb = Arc::new(Adb::new(adb_path(&resources)));
            app.manage(Ctx { adb, device: Mutex::new(None), resources, data });
            Ok(())
        })
        .invoke_handler(tauri::generate_handler![
            discover, connect, disconnect, device_state, user_apps, run_debloat, run_install_ui, run_restore, open_github, open_devtools
        ])
        .run(tauri::generate_context!())
        .expect("Glass Home could not start");
}

/// The bundled adb: adb.exe on Windows, adb on macOS.
fn adb_path(resources: &Path) -> PathBuf {
    resources.join("platform-tools").join(format!("adb{}", std::env::consts::EXE_SUFFIX))
}

fn aapt2_path(resources: &Path) -> PathBuf {
    resources.join(format!("aapt2{}", std::env::consts::EXE_SUFFIX))
}

