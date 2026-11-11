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
use std::path::PathBuf;
use std::sync::{Arc, Mutex};
use steps::Steps;
use tauri::{AppHandle, Manager, State};

struct Ctx {
    adb: Arc<Adb>,
    /// The task running now; a second one waits its turn (a double tap on a phone).
    task: Mutex<Option<String>>,
    device: Mutex<Option<device::Device>>,
    resources: PathBuf,
    data: PathBuf,
}

type Res<T> = Result<T, String>;

/// A task that did not finish: the app was killed or the phone died while it ran.
#[derive(serde::Serialize, serde::Deserialize, Clone)]
struct Unfinished {
    task: String,
    name: String,
    ip: String,
}

/// Marks a task as running, here and on disk. The note on disk only outlives the task when the
/// process dies with it; the next start then says which task on which TV did not finish.
fn begin(ctx: &Ctx, task: &str, d: &device::Device) -> Res<()> {
    let mut t = ctx.task.lock().unwrap();
    if t.is_some() {
        return Err("Another task is still running. Wait for it to finish.".into());
    }
    *t = Some(task.to_string());
    let note = Unfinished { task: task.into(), name: d.name.clone(), ip: d.ip.clone() };
    let _ = device::write_atomic(&ctx.data.join("running.json"), &serde_json::to_string(&note).unwrap_or_default());
    Ok(())
}

fn end(ctx: &Ctx) {
    *ctx.task.lock().unwrap() = None;
    let _ = std::fs::remove_file(ctx.data.join("running.json"));
}

/// The task the last run of the app did not finish, once; nothing while a task still runs.
#[tauri::command]
fn unfinished(ctx: State<'_, Ctx>) -> Option<Unfinished> {
    if ctx.task.lock().unwrap().is_some() {
        return None;
    }
    let path = ctx.data.join("running.json");
    let note = std::fs::read_to_string(&path).ok().and_then(|s| serde_json::from_str(&s).ok());
    let _ = std::fs::remove_file(&path);
    note
}

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
    let cache = device::device_dir(&ctx.data, &d.serial);
    blocking(move || device::user_apps(&adb, &cache)).await
}

#[tauri::command]
async fn run_debloat(app: AppHandle, options: debloat::Options, ctx: State<'_, Ctx>) -> Res<bool> {
    let d = current(&ctx)?;
    let adb = ctx.adb.clone();
    let dir = device::device_dir(&ctx.data, &d.serial);
    begin(&ctx, "debloat", &d)?;
    let r = blocking(move || {
        let mut s = debloat_steps(Some(&app));
        debloat::run(&adb, d.sdk, &dir, &options, &mut s)
    })
    .await;
    end(&ctx);
    r
}

#[tauri::command]
async fn run_install_ui(app: AppHandle, options: ui::Options, ctx: State<'_, Ctx>) -> Res<bool> {
    let d = current(&ctx)?;
    let adb = ctx.adb.clone();
    let apk = ctx.resources.join("glass-home.apk");
    begin(&ctx, "ui", &d)?;
    let r = blocking(move || {
        let mut s = ui_steps(Some(&app));
        ui::run(&adb, d.sdk, &apk, &options, &mut s)
    })
    .await;
    end(&ctx);
    r
}

/// The owner's photo as the TV's wallpaper, with a colour scheme; see ui::send_wallpaper.
#[tauri::command]
async fn set_wallpaper(image: Vec<u8>, theme: String, ctx: State<'_, Ctx>) -> Res<()> {
    current(&ctx)?;
    let adb = ctx.adb.clone();
    blocking(move || ui::send_wallpaper(&adb, &image, &theme)).await?
}

/// Turns ADB debugging off on the Fire TV, the last thing this window can do with it. The
/// connection ends with the command; afterwards the TV must no longer answer.
#[tauri::command]
async fn adb_off(ctx: State<'_, Ctx>) -> Res<()> {
    current(&ctx)?;
    let adb = ctx.adb.clone();
    let result = blocking(move || -> Res<()> {
        adb.sh("settings put global adb_enabled 0");
        std::thread::sleep(std::time::Duration::from_secs(2));
        let still = adb.alive();
        adb.disconnect();
        if still {
            Err("The Fire TV still answers over ADB. Turn ADB debugging off on the TV: Settings › My Fire TV › Developer options.".into())
        } else {
            Ok(())
        }
    })
    .await?;
    *ctx.device.lock().unwrap() = None;
    result
}

#[tauri::command]
async fn run_restore(app: AppHandle, ctx: State<'_, Ctx>) -> Res<bool> {
    let d = current(&ctx)?;
    let adb = ctx.adb.clone();
    let dir = device::device_dir(&ctx.data, &d.serial);
    begin(&ctx, "restore", &d)?;
    let r = blocking(move || {
        let mut s = restore_steps(Some(&app));
        restore::run(&adb, d.sdk, &dir, &mut s)
    })
    .await;
    end(&ctx);
    r
}

/// Opens the project's or one of the authors' GitHub pages in the default browser. Only these
/// addresses.
#[tauri::command]
fn open_github(app: AppHandle, user: String) {
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
    #[cfg(mobile)]
    {
        use tauri_plugin_opener::OpenerExt;
        let _ = app.opener().open_url(url, None::<&str>);
    }
    #[cfg(not(mobile))]
    let _ = app;
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
    let adb = Adb::new(adb::key_path(&cli_data()));
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
    println!("{}", serde_json::to_string_pretty(&device::user_apps(&adb, &cache)).unwrap_or_default());
}

/// `--wallpaper <ip> <photo.jpg> [scheme]`: the window's own-photo upload, from the command line.
pub fn wallpaper_cli(ip: &str, photo: &str, theme: &str) -> bool {
    let adb = Adb::new(adb::key_path(&cli_data()));
    if let Err(e) = adb.connect(ip) {
        eprintln!("{e}");
        return false;
    }
    let jpeg = match std::fs::read(photo) {
        Ok(b) => b,
        Err(e) => {
            eprintln!("{photo}: {e}");
            return false;
        }
    };
    match ui::send_wallpaper(&adb, &jpeg, theme) {
        Ok(()) => {
            println!("sent {} KB, scheme {theme}", jpeg.len() / 1024);
            true
        }
        Err(e) => {
            eprintln!("{e}");
            false
        }
    }
}

/// `--run ui|debloat|restore <ip> [data dir]`: the same task as the window's button, steps printed.
/// Debloat runs with the defaults the window starts with (keep Prime, Alexa, updates; DNS
/// filter on; remove nothing); Glass UI keeps the TV's current layout.
pub fn run_cli(task: &str, ip: &str, data: Option<&str>) -> bool {
    let base = match bundled::unpack(&std::env::temp_dir().join("glasshome-cli")) {
        Ok(b) => b,
        Err(e) => {
            eprintln!("could not unpack the launcher: {e}");
            return Default::default();
        }
    };
    let adb = Adb::new(adb::key_path(&cli_data()));
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
            let o = ui::Options { layout: st.layout, tiles: st.tiles, theme: st.theme, arrows: st.arrows };
            ui::run(&adb, d.sdk, &base.join("glass-home.apk"), &o, &mut ui_steps(None))
        }
        "restore" => restore::run(&adb, d.sdk, &dir, &mut restore_steps(None)),
        _ => {
            eprintln!("unknown task {task}");
            false
        }
    }
}

#[cfg_attr(mobile, tauri::mobile_entry_point)]
pub fn run() {
    let builder = tauri::Builder::default();
    // On a phone, links open through the system (an intent); the desktop starts the browser itself.
    #[cfg(mobile)]
    let builder = builder.plugin(tauri_plugin_opener::init());
    builder
        .setup(|app| {
            // Windows 11: the frameless window keeps rounded corners, without the shadow the
            // system frame would bring.
            #[cfg(windows)]
            if let Some(w) = app.get_webview_window("main") {
                if let Ok(hwnd) = w.hwnd() {
                    use windows_sys::Win32::Graphics::Dwm::{DwmSetWindowAttribute, DWMWA_WINDOW_CORNER_PREFERENCE, DWMWCP_ROUND};
                    let round = DWMWCP_ROUND;
                    // Older Windows simply ignores the attribute.
                    unsafe {
                        DwmSetWindowAttribute(hwnd.0 as _, DWMWA_WINDOW_CORNER_PREFERENCE as u32, &round as *const _ as _, 4);
                    }
                }
            }
            // The launcher APK is inside the program; it is unpacked next to its data. adb is
            // spoken by the program itself, with a key kept there too.
            let data = app.path().app_data_dir()?;
            std::fs::create_dir_all(&data)?;
            let resources = bundled::unpack(&data)?;
            let adb = Arc::new(Adb::new(adb::key_path(&data)));
            app.manage(Ctx { adb, task: Mutex::new(None), device: Mutex::new(None), resources, data });
            Ok(())
        })
        .invoke_handler(tauri::generate_handler![
            discover, connect, disconnect, unfinished, device_state, user_apps, run_debloat, run_install_ui, run_restore, adb_off, set_wallpaper, open_github, open_devtools
        ])
        .run(tauri::generate_context!())
        .expect("Glass Home could not start");
}

/// Where the command-line modes keep their key: the window's data folder, so a TV that allowed
/// the window also allows them.
fn cli_data() -> PathBuf {
    let base = std::env::var_os(if cfg!(windows) { "APPDATA" } else { "HOME" }).map(PathBuf::from).unwrap_or_else(std::env::temp_dir);
    if cfg!(target_os = "macos") {
        base.join("Library/Application Support/app.glasshome.tool")
    } else {
        base.join("app.glasshome.tool")
    }
}

