# WeWatch Android

Native Android companion app for the Electron/Node WeWatch host.

## What It Does

- Connects to the Windows WebSocket server at `ws://<PC IP>:3000`.
- Joins as an Android member using the same `hello`, `status`, `action`, `sync`, `clients`, `control`, `event`, and `ping/pong` messages as the Electron app.
- Opens selected Android media in the official VLC app instead of embedding a separate player.
- Bridges VLC Android Remote Access / VLC HTTP status into the Windows sync server.
- Publishes VLC playback state once per second.
- Applies Windows host play, pause, seek, and sync commands to the VLC app.
- Can send VLC play, pause, seek, and file events back to the Windows VLC user.
- Includes light/dark mode, seek controls, 10-second skip controls, and keyboard/status-bar safe layout handling.

The WebSocket server does not stream the video file. For synced watching, open the same media on Android VLC and on the Windows VLC side.

## Open In Android Studio

1. Open the `android` folder in Android Studio.
2. If Android Studio asks to install an SDK, install:
   - Android SDK Platform 35
   - Android SDK Build-Tools
   - Android SDK Platform-Tools
3. Let Android Studio create `local.properties`, or create it manually:

   ```properties
   sdk.dir=C\:\\Users\\shrey\\AppData\\Local\\Android\\Sdk
   ```

4. Sync Gradle, then run the `app` configuration on an emulator or Android phone.

## Use With The Windows App

1. Start the Windows Electron app.
2. Click `Start hosting`; keep the port as `3000` unless you changed it.
3. Allow Windows Firewall access for the app/server on your private network.
4. On Android, enter the Windows PC IPv4 address and port.
5. In VLC for Android, enable Remote Access and note the URL plus OTP.
6. In the Android app, enter the VLC Remote URL and OTP/password, then tap `Connect VLC`.
7. Open VLC and choose the matching video there, or use `Send file to VLC` from the Android app.
8. Tap `Join Windows`.

If the phone cannot connect, confirm both devices are on the same Wi-Fi, the Windows IP is correct, and TCP port `3000` is allowed through the firewall.

## Build From Terminal

After the Android SDK is installed and `local.properties` exists:

```powershell
$env:JAVA_HOME="C:\Program Files\Android\Android Studio\jbr"
.\gradlew.bat :app:assembleDebug
```

The debug APK is generated at `app\build\outputs\apk\debug\app-debug.apk`.
