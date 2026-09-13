# Project Context & Architecture Guide

This document serves as the primary context reference for AI assistants, Copilots, and developers continuing work on this project.

---

## 🎯 Project Overview
A native Android application built in Kotlin that converts an Android smartphone into a zero-latency USB webcam and studio microphone for Windows PCs using ADB over USB.

- **Offline-First:** 100% offline — no Wi-Fi, cloud, or user accounts.
- **Video:** H.264 Baseline Profile hardware-encoded stream on TCP port 5000.
- **Audio:** AAC-LC 44.1kHz Stereo hardware-encoded stream on TCP port 5001.
- **Windows Driver:** Standalone System Tray app (`windows_driver/WebcamBridge.exe`) providing background port forwarding and auto-reconnect.

---

## 🏗️ Architecture & Component Map

### 1. Android Application (`app/src/main/java/com/example/camerax/`)

| File / Component | Role & Technical Implementation |
|---|---|
| [`MainActivity.kt`](file:///c:/Users/digambar2002/AndroidStudioProjects/CameraX/app/src/main/java/com/example/camerax/MainActivity.kt) | Single activity entry point. Uses `by viewModels()` delegate for eager initialization before `onResume()` to prevent lifecycle uninitialized errors. |
| [`camera/CameraManager.kt`](file:///c:/Users/digambar2002/AndroidStudioProjects/CameraX/app/src/main/java/com/example/camerax/camera/CameraManager.kt) | Direct Camera2 API implementation. Operates in zero-copy Surface mode targeting the `MediaCodecEncoder` surface directly while simultaneously outputting to the local phone `SurfaceView` viewfinder. Bypasses YUV memory copies. |
| [`encoder/MediaCodecEncoder.kt`](file:///c:/Users/digambar2002/AndroidStudioProjects/CameraX/app/src/main/java/com/example/camerax/encoder/MediaCodecEncoder.kt) | Hardware H.264 encoder in Surface input mode (`COLOR_FormatSurface`). Configured with H.264 Baseline Profile (disables B-frames for zero latency), 0.2s I-frame interval, and `KEY_LATENCY = 0`. Prepends cached SPS/PPS to IDR frames. |
| [`encoder/AudioEncoder.kt`](file:///c:/Users/digambar2002/AndroidStudioProjects/CameraX/app/src/main/java/com/example/camerax/encoder/AudioEncoder.kt) | Low-latency AAC hardware encoder. Captures microphone PCM via `AudioRecord` on a dedicated high-priority record thread (`audio-record-loop`) and encodes to AAC with ADTS headers on an output loop thread (`audio-output-loop`). |
| [`streaming/StreamingServer.kt`](file:///c:/Users/digambar2002/AndroidStudioProjects/CameraX/app/src/main/java/com/example/camerax/streaming/StreamingServer.kt) | TCP socket server listening on port **5000**. Streams Annex-B NAL units with `tcpNoDelay = true` (disables Nagle algorithm). |
| [`streaming/AudioServer.kt`](file:///c:/Users/digambar2002/AndroidStudioProjects/CameraX/app/src/main/java/com/example/camerax/streaming/AudioServer.kt) | TCP socket server listening on port **5001**. Streams ADTS AAC audio packets with `tcpNoDelay = true`. |
| [`viewmodel/CameraViewModel.kt`](file:///c:/Users/digambar2002/AndroidStudioProjects/CameraX/app/src/main/java/com/example/camerax/viewmodel/CameraViewModel.kt) | MVVM orchestrator connecting camera, encoders, and servers. Triggers `encoder.requestKeyFrame()` instantly when a client connects. |
| [`ui/screens/MainScreen.kt`](file:///c:/Users/digambar2002/AndroidStudioProjects/CameraX/app/src/main/java/com/example/camerax/ui/screens/MainScreen.kt) | Jetpack Compose Material 3 UI with dark theme, live `SurfaceView` camera viewfinder, controls, status indicators, and resolution/FPS selectors. |

---

### 2. Windows Companion App (`windows_driver/`)

| File | Purpose |
|---|---|
| [`main.py`](file:///c:/Users/digambar2002/AndroidStudioProjects/CameraX/windows_driver/main.py) | Python System Tray application using `pystray` and `Pillow`. Runs background auto-forwarding loop for ADB ports 5000 and 5001. |
| `WebcamBridge.exe` | Compiled standalone Windows executable generated via PyInstaller. Runs silently in System Tray without opening terminal windows. |

---

## 🔑 Key Engineering Rules & Gotchas

1. **Zero-Copy Camera Pipeline:**
   - **Do NOT use CameraX dual `Preview` use-cases.** CameraX rejects multiple `Preview` targets on single session.
   - **Do NOT copy YUV byte arrays manually in Kotlin.** 1080p YUV copying introduces ~300ms–500ms latency. Always use Camera2 Surface mode (`COLOR_FormatSurface`) directly to encoder Surface.

2. **Ultra-Low Latency Settings:**
   - H.264 Profile: Must remain `AVCProfileBaseline` to eliminate B-frame reordering delay on decoders.
   - Socket Settings: `tcpNoDelay = true` must be set on all client sockets to prevent TCP packet aggregation delays.

3. **Audio Architecture:**
   - `AudioRecord.read()` is blocking I/O and must **NEVER** run inside `MediaCodec` callbacks. It runs on its dedicated `recordThread`.

---

## 🛠️ Port & PC Client Quick Reference

| Stream | Port | OBS Input Format | ffplay Command |
|---|---|---|---|
| Video (H.264) | `5000` | `h264` | `ffplay -fflags nobuffer -flags low_delay -i tcp://127.0.0.1:5000` |
| Audio (AAC) | `5001` | `aac` | `ffplay -fflags nobuffer -flags low_delay -i tcp://127.0.0.1:5001` |
