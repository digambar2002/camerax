# Quick Start & Development Instructions for Copilot / Agents

## 🚀 Common Commands

### 1. Build & Install Android App
```powershell
.\gradlew assembleDebug
adb install -r "app\build\outputs\apk\debug\app-debug.apk"
adb shell am start -n com.example.camerax/.MainActivity
```

### 2. ADB Port Forwarding
```powershell
adb forward tcp:5000 tcp:5000
adb forward tcp:5001 tcp:5001
```

### 3. Rebuild Windows Driver (.exe)
```powershell
cd windows_driver
python -m PyInstaller --noconsole --onefile --name WebcamBridge main.py
Move-Item -Force "dist\WebcamBridge.exe" ".\WebcamBridge.exe"
Remove-Item -Recurse -Force build, dist, WebcamBridge.spec
```

---

## 📌 Context File Locations
- **Architecture Context:** [`docs/PROJECT_CONTEXT.md`](file:///c:/Users/digambar2002/AndroidStudioProjects/CameraX/docs/PROJECT_CONTEXT.md)
- **User Documentation:** [`README.md`](file:///c:/Users/digambar2002/AndroidStudioProjects/CameraX/README.md)
- **Windows Tray Source:** [`windows_driver/main.py`](file:///c:/Users/digambar2002/AndroidStudioProjects/CameraX/windows_driver/main.py)
