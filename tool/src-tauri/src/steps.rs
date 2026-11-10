//! Progress: each step is sent to the window as a `step` event when it changes, or printed when
//! the tool runs from the command line (`--run`).

use serde::Serialize;
use tauri::{AppHandle, Emitter};

#[derive(Serialize, Clone)]
struct Step<'a> {
    id: &'a str,
    label: &'a str,
    state: &'a str,
    detail: Option<String>,
}

pub struct Steps {
    app: Option<AppHandle>,
    labels: Vec<(&'static str, String)>,
    pub failed: bool,
}

impl Steps {
    /// Announces every step up front, so the window shows the whole run from the start.
    pub fn new(app: Option<&AppHandle>, labels: &[(&'static str, &str)]) -> Steps {
        let s = Steps { app: app.cloned(), labels: labels.iter().map(|(i, l)| (*i, l.to_string())).collect(), failed: false };
        if s.app.is_some() {
            for (id, label) in &s.labels {
                s.emit(id, label, "pending", None);
            }
        }
        s
    }

    fn emit(&self, id: &str, label: &str, state: &str, detail: Option<String>) {
        match &self.app {
            Some(app) => {
                let _ = app.emit("step", Step { id, label, state, detail });
            }
            None => println!("{:<8} {label}{}", state, detail.map(|d| format!(" · {d}")).unwrap_or_default()),
        }
    }

    fn label(&self, id: &str) -> String {
        self.labels.iter().find(|(i, _)| *i == id).map(|(_, l)| l.clone()).unwrap_or_default()
    }

    pub fn run(&self, id: &str) {
        self.emit(id, &self.label(id), "running", None);
    }

    pub fn done(&self, id: &str, detail: impl Into<Option<String>>) {
        self.emit(id, &self.label(id), "done", detail.into());
    }

    pub fn skip(&self, id: &str, detail: &str) {
        self.emit(id, &self.label(id), "skipped", Some(detail.to_string()));
    }

    pub fn fail(&mut self, id: &str, detail: &str) {
        self.failed = true;
        self.emit(id, &self.label(id), "failed", Some(detail.to_string()));
    }
}
