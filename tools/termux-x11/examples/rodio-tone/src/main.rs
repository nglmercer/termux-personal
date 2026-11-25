use std::time::Duration;

use cpal::traits::{DeviceTrait, HostTrait};
use rodio::source::{SineWave, Source};

fn main() {
    let host = cpal::default_host();
    println!("cpal host: {:?}", host.id());
    let device = host.default_output_device().expect("default output device");
    println!("output device: {:?}", device.description().map(|d| d.name().to_string()));

    let seconds = std::env::args().nth(1).map_or(2, |s| s.parse().expect("seconds"));
    let sink = rodio::DeviceSinkBuilder::open_default_sink().expect("open default audio stream");
    let player = rodio::Player::connect_new(sink.mixer());
    player.append(SineWave::new(440.0).take_duration(Duration::from_secs(seconds)).amplify(0.2));
    player.sleep_until_end();
    println!("played {seconds}s 440 Hz tone");
}
