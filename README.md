# Blade Companion

An unofficial Android companion for a personal Vuzix Blade OS project, by **Kaan Ergün**. Open source under the [MIT license](LICENSE). This project is not affiliated with or endorsed by Vuzix.

The current version is `1.0.0-rc-beta-dev`, a local development package. It is not a published release.

## Current scope

The native Android app has Device, Connection, Market and About screens, with Turkish UI strings in Android resources. It discovers real USB host devices, requests Android permission only after a user action, and can issue a fixed read-only kernel information request to the project's CDC-ACM diagnostic gadget.

Discovery and permission are displayed separately from a verified kernel response. Discovery accepts three narrow USB 2 layouts: VID:PID `0525:a4a7` with one ACM control/data pair, and `1d6b:0104` with device class/subclass/protocol `ef/02/01`, either ECM or NCM interfaces 0/1, and ACM interfaces 2/3. Both network layouts require an empty data alternate 0 and bulk alternate 1; the five descriptor records represent four interface IDs. ECM uses control `2/6/0` and data `10/0/0`; NCM uses control `2/13/0` and data `10/0/1` on both alternates. After Android permission, raw configuration lengths/values, required interface association descriptors (IADs), CDC union ownership/pairing and endpoint layout are checked before either ACM interface is claimed. NCM additionally requires the Linux NCM 1.00 functional descriptor with capabilities `0x11`. The raw network type must match the selected discovery profile. Network interfaces remain unclaimed. These checks identify a supported layout, not an authenticated device. Each request uses a fresh response token, a bounded response and a timeout. It sends `uname -srm` with fixed framing; there is no arbitrary command entry, reboot or firmware write action. The USB response is shown in memory and is not logged or uploaded.

The market has an explicit unpublished empty state. Camera streaming, remote assistance, device updates and an application catalog are not implemented in this slice. No HTTP device API is assumed. Physical phone-to-Blade USB transport testing remains pending; phone UI inspection is recorded below.

## Current validation

On 2026-11-15, the uncommitted NCM changes on base commit `4c66e3d` passed `./scripts/check.sh`: Java compilation, Android lint, **109 core checks**, and debug APK assembly, exit 0 in 6 seconds. Tests cover all three accepted layouts, alternate settings, endpoint directions/uniqueness, foreign and ambiguous profiles, required interface associations, CDC union ownership, exact configuration values, malformed lengths and truncated descriptors. NCM cases also reject mixed ECM/NCM tuples, a changed profile after discovery, wrong functional-descriptor ownership, missing/duplicate/short descriptors and unsupported version/capability values. The new local APK SHA256 is `31a6aff1dd8863414c27404cb9915ddf1f083d3efde1776702705da463985449`. Source hashes, the gate log and this APK are under ignored `artifacts/ncm-acm-2026-11-15/`. This APK has not been installed or tested with a phone-to-Blade USB connection.

The earlier ECM+ACM APK from commit `5651cb6` passed 77 core checks and was installed successfully on Nothing A063 / Android 15; its installed `base.apk` SHA256 matched `a188d076ce02b46e8dde561930259b30478a775391eb6e765795205ed03da7d2`. Its process remained alive and the process-filtered crash log was empty. A fresh visual regression was not completed because the phone was sleeping behind its secure lock screen; the earlier four-tab inspection below belongs to the initial APK. No lock bypass or phone setting change was attempted. Physical phone-to-Blade testing of all USB profiles is still pending. Earlier gate, source hashes and installation evidence are under ignored `artifacts/ecm-acm-2026-11-15/`; `00-screen-off.png` records the black sleeping-screen capture and is not an application UI success screenshot.

## Earlier device UI verification

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
