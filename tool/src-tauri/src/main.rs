// Desktop entry point; the app lives in lib.rs.
#![cfg_attr(not(debug_assertions), windows_subsystem = "windows")]

fn main() {
    let args: Vec<String> = std::env::args().collect();
    if args.len() == 2 && args[1] == "--discover" {
        glass_home_lib::discover_cli();
        return;
    }
    if args.len() == 3 && args[1] == "--probe" {
        glass_home_lib::probe(&args[2]);
        return;
    }
    if args.len() >= 4 && args[1] == "--run" {
        let ok = glass_home_lib::run_cli(&args[2], &args[3], args.get(4).map(String::as_str));
        std::process::exit(if ok { 0 } else { 1 });
    }
    glass_home_lib::run()
}
