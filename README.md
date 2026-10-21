# Android Auto Maker

Put an ordinary Android app on the car screen while the car is parked.

Android Auto Maker takes an APK or an XAPK and prepares a single APK that Android Auto can offer as a parked game. The app's own screen stays available while the car is parked. You can save that APK, or install it on the phone from the website or from the Android Auto Maker app.

An XAPK, including split packages saved as `.apks` content inside an `.xapk` file, comes back as one normal APK. The result is installed the same way as an APK you uploaded yourself, so Android Auto can list it.

Parked games need a phone on Android 15 or newer. The prepared package is signed again, so uninstall the original app before you install the result. Android treats the new copy as a different signature of the same package name.

## What you can do

- Start from an `.apk` or an `.xapk`.
- Prepare it as a parked game, available while the car is parked.
- Save the finished APK, or install it on the phone and wait until the package is actually there.
- Run more than one preparation at a time. Each one keeps its own files until you download the result, or until an abandoned session expires after an hour.

## Use the website

The website runs on a computer and can be opened from that computer or from another device on the same network.

1. Choose an `.apk` or `.xapk` file, or drop it on the page.
2. Leave **Parked game** selected.
3. Press **Create Android Auto package** and wait until the steps finish.
4. Press **Download APK** to save the file, or **Install on phone** to open the installer from this computer.

Install on phone needs the phone plugged into the computer, USB debugging turned on, and this computer accepted on the phone. Stay on the page. It shows **Installing** until the package is on the phone, then **Installed**. The download stays available after that.

If the phone never receives the package, the page says the app was not installed. You can still download the APK.

## Install the website on this computer

The site is a local Node program. These steps are for Windows.

You need:

- Node.js 18 or newer
- JDK 17 (JDK 11 or newer also starts the tools; JDK 17 is the version this project is built with)
- The files already in `tools`: `apktool.jar`, `apksigner.jar`, and `zipalign.exe`
- Android platform-tools, only if you want **Install on phone**

From the project folder:

```powershell
npm install
npm start
```

The server listens on port 4173 and prints every address it is sharing, including `http://127.0.0.1:4173` and the address of this PC on the local network. Open one of those in a browser.

On the first start, the site creates a local signing key at `tools/debug.keystore` if that file is missing, and it creates `tools/framework` and `work` for sessions. Leave the process running while people use the page. Stop it with Ctrl+C.

To use another port:

```powershell
$env:PORT = "4173"
npm start
```

**Install on phone** looks for `adb.exe` in `%ANDROID_HOME%\platform-tools`, `%ANDROID_SDK_ROOT%\platform-tools`, or `%LOCALAPPDATA%\Android\Sdk\platform-tools`. Install [Android SDK platform-tools](https://developer.android.com/tools/releases/platform-tools) if that file is not there. On the phone, turn on USB debugging and accept the computer when Android asks.

A missing `apktool.jar`, `apksigner.jar`, or `zipalign.exe` is reported in the server window. Put those files back in `tools` before preparing a package.

## Use the Android app

Install Android Auto Maker on the phone. The maker app itself runs on Android 8.0 or newer. The parked game you prepare still needs Android 15 or newer on the phone that will show it in the car.

1. Press **Choose an APK or XAPK** and select the file. The chooser lists every file so an `.xapk` can be selected.
2. Leave **Parked game** selected.
3. Press **Create Android Auto package** and wait until it finishes.
4. Press **Save** to keep the APK, or **Install** to install it on this phone.

If Android asks for permission to install unknown apps, allow it for Android Auto Maker and press Install again. Complete the system installer. The card stays on **Installing** until the package is on the phone, or until it reports that the app was not installed. You can still save the APK if you cancel the installer.

Uninstall the original app first. The note on the screen says the same thing.

## Build the Android app

Build this on Windows with JDK 17 and the Android SDK. The project uses Gradle 8.11.1 through the wrapper in `android`.

1. Install JDK 17. `android/gradle.properties` points Gradle at `C:\Program Files\Java\jdk-17`. If your JDK lives somewhere else, change `org.gradle.java.home` to that folder.
2. Install the Android SDK with platform 36 and build-tools. Android Studio can do this, or use an existing SDK.
3. Create `android/local.properties` if it is not there. Point it at the SDK. Escape backslashes:

```properties
sdk.dir=C:\\Users\\YourName\\AppData\\Local\\Android\\Sdk
```

4. The release build is signed with `android/release.keystore`. That file is not part of the git checkout. Create it once from the `android` folder with the JDK `keytool`:

```powershell
& "C:\Program Files\Java\jdk-17\bin\keytool.exe" -genkeypair -keystore release.keystore -alias automaker -keyalg RSA -keysize 2048 -validity 10000 -storepass automaker -keypass automaker -dname "CN=Android Auto Maker, OU=Local, O=Local, C=US"
```

5. Build the release APK:

```powershell
cd android
.\gradlew.bat :app:assembleRelease
```

The APK is written to:

`android/app/build/outputs/apk/release/app-release.apk`

Copy that file to the phone and install it. If a previous Android Auto Maker build was signed with a different key, uninstall that copy first. A debug build and this release build do not replace each other.

Run the unit tests before a release when you have changed the project:

```powershell
cd android
.\gradlew.bat :app:testDebugUnitTest
.\gradlew.bat :app:assembleRelease
```

Website checks, from the project folder:

```powershell
npm test
```
