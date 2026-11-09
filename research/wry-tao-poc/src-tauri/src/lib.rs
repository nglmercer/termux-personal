//! Minimal Wry/Tao (through Tauri 2) app for issue #15: one window, local HTML, and a log line for every
//! lifecycle event Tao reports, so creation, pause, resume and destruction can be read from logcat.
use std::time::Instant;

#[cfg_attr(mobile, tauri::mobile_entry_point)]
pub fn run() {
    #[cfg(target_os = "android")]
    android_logger::init_once(android_logger::Config::default().with_max_level(log::LevelFilter::Info).with_tag("wrytaopoc"));
    let started = Instant::now();
    log::info!("wrytaopoc run() entered");
    tauri::Builder::default()
        .build(tauri::generate_context!())
        .expect("failed to build the app")
        .run(move |_app, event| match event {
            tauri::RunEvent::Ready => log::info!("wrytaopoc lifecycle ready after {} ms", started.elapsed().as_millis()),
            tauri::RunEvent::Resumed => log::info!("wrytaopoc lifecycle resumed"),
            tauri::RunEvent::WindowEvent { event: tauri::WindowEvent::Focused(f), .. } => log::info!("wrytaopoc lifecycle focused={f}"),
            tauri::RunEvent::WindowEvent { event: tauri::WindowEvent::Destroyed, .. } => log::info!("wrytaopoc lifecycle window-destroyed"),
            tauri::RunEvent::ExitRequested { .. } => log::info!("wrytaopoc lifecycle exit-requested"),
            tauri::RunEvent::Exit => log::info!("wrytaopoc lifecycle exit"),
            _ => {}
        });
}
