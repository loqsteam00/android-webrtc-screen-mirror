# Android WebRTC Screen Mirror

A high-performance, open-source Android screen mirroring solution designed specifically for Android TV. It uses WebRTC and hardware H.264 encoding to deliver ultra-low latency, 60 FPS screen mirroring over your local Wi-Fi network, without relying on Miracast or internet servers.

## Features
- **Ultra-Low Latency:** Uses WebRTC and forces H.264 hardware encoding on both devices.
- **Zero Configuration:** Uses Network Service Discovery (NSD) to automatically find your Android TV on the local network. No need to type IP addresses.
- **Adjustable Quality:** Built-in settings in the Streamer app let you adjust Resolution, Framerate (30/60 FPS), and Video Bitrate to match your Wi-Fi capabilities.
- **Native Android TV Support:** The Receiver app includes a Leanback Launcher intent and banner, appearing natively on your Android TV home screen.

## Project Structure
This repository contains two Android Studio projects:
1. **`Streamer/`**: The app you install on your Android Phone or Tablet to capture the screen.
2. **`Receiver/`**: The app you install on your Android TV to receive and display the stream.

## Installation
You can either build the apps from source or download the pre-compiled APKs from the [Releases](../../releases) page.

1. **Receiver (Android TV)**:
   - Download `Receiver-app-debug.apk` from the latest release.
   - Install it on your Android TV using ADB (`adb install Receiver-app-debug.apk`) or a USB drive.
   - Open the "Receiver" app on your TV.

2. **Streamer (Android Phone)**:
   - Download `Streamer-app-debug.apk` from the latest release.
   - Install it on your phone.
   - Ensure both your phone and TV are connected to the same Wi-Fi network.
   - Open the app, adjust your desired quality settings, and tap "Start Streaming".

## Building from Source
1. Clone this repository.
2. Open the `Streamer` folder in Android Studio and click Run.
3. Open the `Receiver` folder in a separate Android Studio window and click Run.

## License
This project is licensed under the **Creative Commons Attribution-NonCommercial 4.0 International (CC BY-NC 4.0)** License. 

You are free to share and adapt the code for personal, educational, or public use. However, you may **not** use this material for commercial purposes. See the `LICENSE` file for full details.
