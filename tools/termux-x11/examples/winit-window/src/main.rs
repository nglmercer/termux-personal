use std::time::{Duration, Instant};

use winit::application::ApplicationHandler;
use winit::event::WindowEvent;
use winit::event_loop::{ActiveEventLoop, ControlFlow, EventLoop};
use winit::platform::x11::EventLoopBuilderExtX11;
use winit::window::{Window, WindowId};

struct App {
    window: Option<Window>,
    deadline: Option<Instant>,
}

impl ApplicationHandler for App {
    fn resumed(&mut self, event_loop: &ActiveEventLoop) {
        if self.window.is_some() {
            return;
        }
        let attributes = Window::default_attributes()
            .with_title("winit on Termux:X11")
            .with_inner_size(winit::dpi::LogicalSize::new(480.0, 320.0));
        let window = event_loop.create_window(attributes).expect("create X11 window");
        println!("window created: id={:?} size={:?}", window.id(), window.inner_size());
        self.window = Some(window);
    }

    fn window_event(&mut self, event_loop: &ActiveEventLoop, _id: WindowId, event: WindowEvent) {
        match event {
            WindowEvent::CloseRequested => event_loop.exit(),
            WindowEvent::Resized(size) => println!("resized: {size:?}"),
            WindowEvent::Focused(focused) => println!("focused: {focused}"),
            WindowEvent::KeyboardInput { event, .. } => println!("key: {:?} {:?}", event.logical_key, event.state),
            WindowEvent::MouseInput { state, button, .. } => println!("mouse: {button:?} {state:?}"),
            _ => {}
        }
    }

    fn about_to_wait(&mut self, event_loop: &ActiveEventLoop) {
        match self.deadline {
            Some(deadline) if Instant::now() >= deadline => {
                println!("exit after timeout");
                event_loop.exit();
            }
            Some(deadline) => event_loop.set_control_flow(ControlFlow::WaitUntil(deadline)),
            None => event_loop.set_control_flow(ControlFlow::Wait),
        }
    }
}

fn main() {
    let event_loop = EventLoop::builder().with_x11().build().expect("connect to X server (is DISPLAY set?)");
    let deadline = std::env::args()
        .nth(1)
        .map(|secs| Instant::now() + Duration::from_secs(secs.parse().expect("seconds")));
    let mut app = App { window: None, deadline };
    event_loop.run_app(&mut app).expect("event loop");
}
