# PC Remote

Control a Windows PC from an Android phone over Wi-Fi, the phone's hotspot or Bluetooth.
Mouse, keyboard, air mouse, media keys, live screen with touch control, full file access, shared
clipboard, power controls, custom buttons and a notification bar with quick buttons.
File transfers and screen streaming stay on the local link (no mobile data).

## Install
1. **PC:** download `PCRemote.exe` from [the latest release](https://github.com/edward25m/pc-remote/releases/latest) and run it once.
   It installs itself, starts with Windows and sits in the system tray. Click **Yes** on the Windows prompt (it opens the firewall for the app).
   If Windows says "Windows protected your PC", click **More info → Run anyway**.
2. **Phone:** download `PCRemote.apk` from the same page and open it. Allow "install unknown apps" when asked.
3. Put both on the same Wi-Fi (or connect the PC to the phone's hotspot) and open the app. Click **Allow** on the PC the first time.
4. **Bluetooth:** pair the phone with the PC once in Windows Settings → Bluetooth & devices, then pick the PC under Bluetooth in **Switch PC**.

## Updates
The phone app: Settings → Update this app (shows the size first) or the blue banner on the home screen.
The PC program: tray icon → Check for updates, or from the phone: Settings → Update the PC program.

Every push to `main` builds both apps on GitHub Actions and publishes a new release.
