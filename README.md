# 📸 Ultra-Fast USB Webcam & Microphone

A high-performance, zero-latency Android application that converts your Android phone into a Windows PC USB webcam (H.264 Video) and studio microphone (AAC Audio) over ADB.

---

## ⚡ Quick Start Guide (One-Click Setup)

### 1. On your Phone:
1. Open the **CameraX** app.
2. Grant Camera & Microphone permissions.
3. Tap **Start Streaming** (Status indicator turns **● LIVE**).

### 2. On your Windows PC:
- Open the **`windows_driver`** folder and launch **`WebcamBridge.exe`**.
- It runs quietly in your **Windows System Tray** (bottom-right near your clock) with a camera icon:
  - 🟢 **Start Bridge:** Auto-forwards ports 5000 & 5001.
  - 🔴 **Stop Bridge:** Stops forwarding.
  - ❌ **Exit:** Closes the application.

---

## 🎥 Using with OBS Studio

### Video Stream (Camera):
- **Source:** Media Source
- **Input:** `tcp://127.0.0.1:5000`
- **Input Format:** `h264`
- **Network Buffering:** `Disabled (0 MB)`

### Audio Stream (Microphone):
- **Source:** Media Source
- **Input:** `tcp://127.0.0.1:5001`
- **Input Format:** `aac`
- **Network Buffering:** `Disabled (0 MB)`

---

## 📚 Developer & Copilot Documentation

- **Detailed Architecture & Context:** [`docs/PROJECT_CONTEXT.md`](file:///c:/Users/digambar2002/AndroidStudioProjects/CameraX/docs/PROJECT_CONTEXT.md)
- **Developer & Build Guide:** [`docs/DEVELOPER_GUIDE.md`](file:///c:/Users/digambar2002/AndroidStudioProjects/CameraX/docs/DEVELOPER_GUIDE.md)

---

## ⚙️ Technical Specifications

| Feature | Specification |
|---|---|
| Video Codec | H.264 Baseline (Hardware MediaCodec) |
| Video Latency | ~30ms - 50ms (Zero-Copy Camera2 Surface) |
| Audio Codec | AAC-LC 44.1kHz Stereo (ADTS Framed) |
| Connectivity | Pure Offline USB over ADB TCP Forwarding |
| Supported Res | 480p, 720p, 1080p @ 30 / 60 FPS |
