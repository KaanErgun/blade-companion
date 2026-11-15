# Blade Companion

An unofficial Android companion for a personal Vuzix Blade OS project, by **Kaan Ergün**. Open source under the [MIT license](LICENSE). This project is not affiliated with or endorsed by Vuzix.

The current version is `1.0.0-rc-beta-dev`, a local development package. It is not a published release.

## Current scope

The native Android app has Device, Connection, Market and About screens, with Turkish UI strings in Android resources. It discovers real USB host devices, requests Android permission only after a user action, and can issue a fixed read-only kernel information request to the project's CDC-ACM diagnostic gadget.

Discovery and permission are displayed separately from a verified kernel response. The diagnostic transport accepts only VID:PID `0525:a4a7` with the expected single ACM control/data pair. Each request uses a fresh response token, a bounded response and a timeout. It sends `uname -srm` with fixed framing; there is no arbitrary command entry, reboot or firmware write action. The USB response is shown in memory and is not logged or uploaded.

The market has an explicit unpublished empty state. Camera streaming, remote assistance, device updates and an application catalog are not implemented in this slice. No HTTP device API is assumed. Physical phone-to-Blade USB testing and phone UI inspection remain pending.

## Device verification

On 2026-11-15, the debug APK from the companion working tree, before its independent initial commit, was installed on Nothing A063 (Android 15). The installed `base.apk` and local build both have SHA256 `0df37ada683fc7e267c1ddb0070581aaeed6580a7a66982fb54599da5c51b541`. Package Manager reports version `1.0.0-rc-beta-dev`, version code `1`, minimum API `23` and target API `35`.

The initial black screenshot was captured while the phone was asleep behind its secure lock screen. The companion process was alive, its activity was stopped behind the lock, and its crash buffer had no application crash. Visual verification of the four tabs requires the owner to unlock the phone; this check does not bypass the lock or change screen security settings. Local evidence is kept outside git in `artifacts/evidence/2026-11-15/companion/` of the OS workspace.

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
