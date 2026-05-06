# WeWatch

<p align="center">
  <img src="src/assets/icon.png" alt="WeWatch logo" width="140" />
</p>

WeWatch helps friends watch local media together. The Windows Electron app can host a watch session, and both Windows and Android clients can connect, share playback state, and keep VLC in sync.

The app does not stream video files. Everyone should have the same media available locally, then WeWatch syncs play, pause, seek, and timeline position.

## Platforms

- **Windows desktop:** Electron app with a built-in WebSocket session server and VLC desktop control.
- **Android:** Native Android companion app that joins the Windows host and controls VLC for Android through VLC Remote Access.

## Features

- Host or join a watch session over WebSockets.
- Sync play, pause, seek, file changes, and timeline position.
- Show connected people, playback state, latency, and drift.
- Auto-follow the host timeline when a member falls behind or jumps ahead.
- Control VLC from the Windows app.
- Connect Android phones to the Windows host.
- Build a shareable Windows installer and Android APK.
- Show app versions and check GitHub Releases for updates.

## Requirements

### Windows

- Windows
- Node.js and npm
- VLC Media Player

VLC desktop should be installed in one of the default locations:

- `C:\Program Files\VideoLAN\VLC\vlc.exe`
- `C:\Program Files (x86)\VideoLAN\VLC\vlc.exe`

### Android

- Android Studio
- Android SDK Platform 35
- JDK 17, or the JBR bundled with Android Studio
- VLC for Android
- Android phone/emulator on the same network as the Windows host

The Android app package is `com.wewatch.android`, with minimum SDK 23 and target SDK 35.

## Windows Setup

Install dependencies from the repository root:

```bash
npm install
```

Run the Windows Electron app in development:

```bash
npm start
```

Build the Windows installer:

```bash
npm run build
```

The installer is generated at:

```text
dist/WeWatch-Setup-1.0.0.exe
```

Send that `.exe` file to friends. It creates normal desktop and Start Menu shortcuts named `WeWatch`.

When publishing an update, also keep the generated `.blockmap` and `latest.yml` files from `dist/`. The Windows updater needs those release assets.

## Android Setup

Open the Android project in Android Studio:

```text
android/
```

If Android Studio asks for SDK components, install Android SDK Platform 35 and the usual Android build tools/platform tools.

Build a debug APK from PowerShell:

```powershell
cd android
$env:JAVA_HOME="C:\Program Files\Android\Android Studio\jbr"
.\gradlew.bat :app:assembleDebug
```

The debug APK is generated at:

```text
android/app/build/outputs/apk/debug/app-debug.apk
```

You can also run the app directly from Android Studio using the `app` configuration.

Build a release APK for GitHub Releases:

```powershell
cd android
$env:JAVA_HOME="C:\Program Files\Android\Android Studio\jbr"
.\gradlew.bat :app:assembleRelease
```

The release APK is generated at:

```text
android/app/build/outputs/apk/release/app-release.apk
```

If you have not added a release keystore yet, Gradle will create `app-release-unsigned.apk` instead. Do not upload the unsigned APK for real users; create the keystore below first. Rename the signed APK before upload, for example `WeWatch-Android-1.0.1.apk`.

Before sharing release APKs widely, create one Android release keystore and reuse it forever. From the `android/` folder:

```powershell
keytool -genkeypair -v -keystore wewatch-release.jks -alias wewatch -keyalg RSA -keysize 2048 -validity 10000
```

Create `android/keystore.properties`:

```properties
storeFile=wewatch-release.jks
storePassword=your-store-password
keyAlias=wewatch
keyPassword=your-key-password
```

`keystore.properties` and `.jks` files are ignored by git.

## Updates And Versioning

Both apps use your GitHub repo releases:

```text
https://github.com/RaoNatu/WeWatch/releases
```

Use one version for both apps, like `1.0.1`, and create the GitHub release tag as `v1.0.1`.

For every new release:

1. Change the shared version:

```bash
npm run version:set -- 1.0.1
```

2. Test the apps.
3. Commit and push the code.
4. Build Windows:

```bash
npm run build
```

5. Build Android release:

```powershell
cd android
.\gradlew.bat :app:assembleRelease
```

6. Create a GitHub Release named/tagged `v1.0.1`.
7. Upload these files to that release:

```text
dist/WeWatch-Setup-1.0.1.exe
dist/WeWatch-Setup-1.0.1.exe.blockmap
dist/latest.yml
android/app/build/outputs/apk/release/app-release.apk
```

Rename the signed APK to `WeWatch-Android-1.0.1.apk` before upload if you want the asset name to be clean. If the file says `unsigned`, stop and set up the Android keystore first.

In GitHub, the APK is not uploaded into the code file browser. Upload it as a release asset:

```text
RaoNatu/WeWatch -> Releases -> Draft a new release -> Attach binaries by dropping them here
```

That same release asset area is where the Windows installer, `.blockmap`, and `latest.yml` go.

Important notes:

- The version must go up every release, or users will not be offered an update.
- The Android `versionCode` is generated from the version by `scripts/set-version.js`.
- Android updates only install over apps signed with the same signing key. Keep your release keystore safe forever once you start sharing release APKs.
- Windows auto-update works in the installed app, not in `npm start`.
- A public GitHub repo does not need a token for users to receive updates.

## How To Use

### Host From Windows

1. Open WeWatch on Windows.
2. Enter your name.
3. Click `Launch VLC`, or open VLC manually with the HTTP interface enabled.
4. Keep the session port as `3000`, unless you need a different port.
5. Click `Start hosting`.
6. Allow Windows Firewall access on your private network if prompted.
7. Share your Windows PC IPv4 address and port with friends.

### Join From Windows

1. Open WeWatch on another Windows computer.
2. Enter the host IP address and port.
3. Click `Join host`.
4. Open the same media file in VLC.
5. Use play, pause, seek, or `Sync` to stay aligned.

### Join From Android

1. Install VLC for Android.
2. Start hosting from the Windows app.
3. Make sure the phone and Windows host are on the same Wi-Fi/network.
4. In the Android app, enter the Windows host IP address and port.
5. Enable Remote Access in VLC for Android and note the Remote Access URL plus OTP/password.
6. Enter the VLC Remote Access details in the Android app.
7. Open the same media in VLC for Android, or use `Send file to VLC`.
8. Tap `Join Windows`.

Default connection values:

- WeWatch session server: `ws://<Windows IP>:3000`
- Windows VLC: `127.0.0.1:8080`
- Windows VLC default password: `1234`

## Project Structure

```text
src/
  assets/       Windows app logo used by Electron
  main/         Electron main process, VLC bridge, and session server
  renderer/     Windows app UI, socket client, and sync behavior

android/
  app/          Native Android companion app
  gradle/       Gradle wrapper files

build/          Windows installer icon resources
dist/           Generated Windows installer output
```

## Packaging Notes

The Windows installer uses:

- `build/icon.ico` for the app and installer icon
- `src/assets/icon.png` for the running Electron window and UI logo

The Android app uses launcher assets from:

```text
android/app/src/main/res/mipmap-*/
```

Generated outputs are ignored by git, including `dist/`, Android build folders, `.exe`, `.apk`, and `.aab` files.

## Scripts

Windows:

```bash
npm start
npm run build
npm run dist
```

Android:

```powershell
cd android
.\gradlew.bat :app:assembleDebug
.\gradlew.bat :app:assembleRelease
```

## Troubleshooting

- If Android cannot connect, check that both devices are on the same network and Windows Firewall allows TCP port `3000`.
- If VLC is not reachable on Windows, confirm VLC is installed and the HTTP interface is running on port `8080`.
- If Android VLC control fails, re-check VLC Remote Access URL and OTP/password.
- If sync works but video differs, make sure every device opened the same media file.

## Graphify

This repository uses [Graphify](https://github.com/anomalyco/graphify) to generate a code knowledge graph for faster codebase navigation.

The generated graph lives in `graphify-out/`. If it's stale, update it:

```bash
graphify update .
```

You can then explore the graph structure through `graphify-out/graph.html` in a browser.

## License

ISC
