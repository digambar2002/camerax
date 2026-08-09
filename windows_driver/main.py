import os
import sys
import time
import subprocess
import threading
from PIL import Image, ImageDraw
import pystray
from pystray import MenuItem as item

class WebcamBridgeTray:
    def __init__(self):
        self.running = False
        self.worker_thread = None
        self.icon = None

    def create_image(self, color):
        # Generate dynamic tray icon (Camera symbol)
        image = Image.new('RGBA', (64, 64), color=(0, 0, 0, 0))
        dc = ImageDraw.Draw(image)
        # Outer camera box
        dc.rounded_rectangle([8, 16, 56, 52], radius=6, fill=color)
        # Camera Lens
        dc.ellipse([24, 26, 40, 42], fill=(255, 255, 255))
        # Top Flash Dot
        dc.rectangle([20, 10, 32, 16], fill=color)
        return image

    def run_adb_loop(self):
        while self.running:
            try:
                # Silently execute ADB port forwarding
                subprocess.run("adb devices", shell=True, stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL)
                subprocess.run("adb forward tcp:5000 tcp:5000", shell=True, stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL)
                subprocess.run("adb forward tcp:5001 tcp:5001", shell=True, stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL)
            except Exception:
                pass
            time.sleep(3)

    def start_service(self, icon=None, item=None):
        if not self.running:
            self.running = True
            self.worker_thread = threading.Thread(target=self.run_adb_loop, daemon=True)
            self.worker_thread.start()
            if self.icon:
                self.icon.icon = self.create_image((0, 200, 100)) # Green when active
                self.icon.title = "USB Webcam Bridge: ACTIVE (Ports 5000/5001)"

    def stop_service(self, icon=None, item=None):
        if self.running:
            self.running = False
            if self.icon:
                self.icon.icon = self.create_image((150, 150, 150)) # Gray when stopped
                self.icon.title = "USB Webcam Bridge: STOPPED"

    fun_on_exit = lambda self, icon, item: (self.stop_service(), icon.stop())

    def run(self):
        menu = pystray.Menu(
            item('🟢 Start Bridge', self.start_service),
            item('🔴 Stop Bridge', self.stop_service),
            pystray.Menu.SEPARATOR,
            item('❌ Exit', self.fun_on_exit)
        )
        self.icon = pystray.Icon("WebcamBridge", self.create_image((0, 200, 100)), "USB Webcam Bridge", menu)
        self.start_service() # Auto-start on launch
        self.icon.run()

if __name__ == "__main__":
    app = WebcamBridgeTray()
    app.run()
