use std::time::{Duration, Instant};

use tao::event::{Event, WindowEvent};
use tao::event_loop::{ControlFlow, EventLoopBuilder};
use tao::platform::unix::WindowExtUnix;
use tao::window::WindowBuilder;
use wry::WebViewBuilderExtUnix;

const PAGE: &str = r#"<!doctype html>
<html><body style="font-family:sans-serif;background:#1e293b;color:#f8fafc">
<h1>wry + WebKitGTK on Termux:X11</h1>
<p id="ua"></p>
<script>
  document.getElementById('ua').textContent = navigator.userAgent;
  window.ipc.postMessage('loaded ' + navigator.userAgent);
</script>
</body></html>"#;

enum UserEvent {
    Ipc(String),
}

fn main() -> wry::Result<()> {
    let event_loop = EventLoopBuilder::<UserEvent>::with_user_event().build();
    let proxy = event_loop.create_proxy();
    let window = WindowBuilder::new()
        .with_title("tao + wry on Termux:X11")
        .with_inner_size(tao::dpi::LogicalSize::new(800.0, 600.0))
        .build(&event_loop)
        .expect("create GTK window");

    let _webview = wry::WebViewBuilder::new()
        .with_html(PAGE)
        .with_ipc_handler(move |request| {
            let _ = proxy.send_event(UserEvent::Ipc(request.body().clone()));
        })
        .build_gtk(window.default_vbox().expect("tao GTK vbox"))?;

    let deadline = std::env::args()
        .nth(1)
        .map(|secs| Instant::now() + Duration::from_secs(secs.parse().expect("seconds")));
    println!("window created: {:?}", window.inner_size());

    event_loop.run(move |event, _, control_flow| {
        *control_flow = deadline.map_or(ControlFlow::Wait, ControlFlow::WaitUntil);
        match event {
            Event::UserEvent(UserEvent::Ipc(body)) => println!("ipc: {body}"),
            Event::WindowEvent { event: WindowEvent::CloseRequested, .. } => *control_flow = ControlFlow::Exit,
            Event::NewEvents(_) if deadline.is_some_and(|d| Instant::now() >= d) => {
                println!("exit after timeout");
                *control_flow = ControlFlow::Exit;
            }
            _ => {}
        }
    });
}
