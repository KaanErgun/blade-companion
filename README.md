# Blade Companion

An unofficial Android companion for a personal Vuzix Blade OS project, by **Kaan Ergün**. Open source under the [MIT license](LICENSE). This project is not affiliated with or endorsed by Vuzix.

The current version is `1.0.0-rc-beta-dev`, a local development package. It is not a published release.

## Current scope

The native Android app has Device, Connection, Market and About screens, with Turkish UI strings in Android resources. It discovers real USB host devices, requests Android permission only after a user action, and can issue a fixed read-only kernel information request to the project's CDC-ACM diagnostic gadget.

Discovery and permission are displayed separately from a verified kernel response. The diagnostic transport accepts only VID:PID `0525:a4a7` with the expected single ACM control/data pair. Each request uses a fresh response token, a bounded response and a timeout. It sends `uname -srm` with fixed framing; there is no arbitrary command entry, reboot or firmware write action. The USB response is shown in memory and is not logged or uploaded.

The market has an explicit unpublished empty state. Camera streaming, remote assistance, device updates and an application catalog are not implemented in this slice. No HTTP device API is assumed. Physical phone-to-Blade USB transport testing remains pending; phone UI inspection is recorded below.

## Device verification

On 2026-11-15, the debug APK from the source recorded in initial commit `ef6b0f5` was installed on Nothing A063 (Android 15). The installed `base.apk` and local build both have SHA256 `0df37ada683fc7e267c1ddb0070581aaeed6580a7a66982fb54599da5c51b541`. Package Manager reports version `1.0.0-rc-beta-dev`, version code `1`, minimum API `23` and target API `35`.

After the owner unlocked the phone normally, all four tabs were opened and visually inspected on the real device. Device rendered in portrait and landscape; Connection showed the actual empty USB list and its refresh control responded; Market showed its unpublished catalog state; About displayed the developer name, development version and MIT license, with lower text reachable by scrolling in landscape. The activity remained `RESUMED` and its process had no crash-buffer entries after the checks. No application fix or phone setting change was needed.

Local evidence is kept outside git in `artifacts/evidence/2026-11-15/companion/` of the OS workspace: `01-device.png`, `01-device-landscape.png`, `02-connection.png`, `03-market.png`, `04-about.png`, `05-about-scrolled.png`, and `verification.txt`. The initial black capture came from the sleeping secure lock screen, not the app. UIAutomator returned a null root on this phone even while the app was visible, so these checks used observed screenshots and actual touch input; no automated accessibility-tree coverage is claimed.

## Local build

Prerequisites: JDK 17, Android SDK Platform 35, Build Tools 34.0.0, Gradle 8.9 and Android Gradle Plugin 8.7.3. The wrapper verifies the Gradle distribution checksum. There are no runtime dependencies beyond Android platform APIs. Minimum Android API: 23; target: 35.

Set `JAVA_HOME` to a JDK 17 installation. Put the Android SDK path in ignored `local.properties`:

```properties
sdk.dir=/absolute/path/to/Android/sdk
```

Then run the canonical offline gate:

```sh
./scripts/check.sh
```

The Gradle distribution and build plugin must already be cached for offline use. For an initial connected setup, run `./gradlew --no-daemon :check` once. The gate compiles the Android code, runs Android lint, executes Android-free tests for USB profile selection and response parsing, then builds `app/build/outputs/apk/debug/app-debug.apk`. It also rejects hosted CI configuration and Android imports in the core package. Hardware transport and visual UI checks are separate from this gate.

Build outputs, local SDK configuration, device evidence and local artifacts are ignored. Nothing in the gate installs an APK or changes a connected device.

## USB behavior

Connect a data-capable USB cable with the phone acting as host and the Blade running the project's USB diagnostic image. Open **Bağlantı**, refresh the actual device list and grant permission to the matching diagnostic device. **Kernel bilgisini oku** runs the bounded probe. A matching descriptor alone is not a device identity or a successful connection.

This uses Android's [USB host APIs](https://developer.android.com/develop/connectivity/usb/host). USB permission is temporary and expires on detach. The application does not request Internet, camera, microphone or storage access.
