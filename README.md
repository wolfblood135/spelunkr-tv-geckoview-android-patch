# Spelunkr Gecko

spelunkr.tv wrapped in **GeckoView** (Firefox's engine, bundled inside the APK) instead of the
system WebView. On TV boxes with an old, non-updatable WebView the official app stays stuck on
the gradient screen; this one brings its own modern engine.

Installs next to the official app (package `tv.spelunkr.gecko`, name "Spelunkr Gecko").

> **Unofficial.** Not made by or affiliated with Spelunkr. Spelunkr, its name and logo belong to
> the spelunkr.tv team — this is only a wrapper that opens their site. For the official app, see
> https://spelunkr.tv/app.

**Author:** Iso Seckin ([@wolfblood135](https://github.com/wolfblood135)) · wolfblood1357@gmail.com

## Building (no Android Studio needed)

1. Create a new GitHub repository and upload this folder's contents (including `.github`).
2. The **Actions** tab starts the "Build APK" job automatically (~5–10 min).
3. When it finishes, download the `spelunkr-gecko-apks` artifact from the job page.

With Android Studio instead: open the folder, let it sync, then *Build → Build APK(s)*.

## Which APK?

Four files come out:

| File | For |
|---|---|
| `app-modern-armeabi-v7a-release.apk` | Android 8+, 32-bit system |
| `app-modern-arm64-v8a-release.apk` | Android 8+, 64-bit system |
| `app-legacy-armeabi-v7a-release.apk` | Android 5–7, 32-bit system |
| `app-legacy-arm64-v8a-release.apk` | Android 5–7, 64-bit system |

Find the box's values with `adb shell getprop ro.build.version.release` and
`adb shell getprop ro.product.cpu.abi`. Many boxes run a 32-bit system on a 64-bit chip; go by
what `cpu.abi` says. When unsure, `armeabi-v7a` runs on both.

`modern` uses GeckoView 146 (needs Android 8). `legacy` is pinned to GeckoView 144, the
last release that supports Android 5–7.

## What it does

- Opens https://spelunkr.tv/ fullscreen, screen stays on.
- Remote: D-pad, OK, play/pause and channel keys go to the page; BACK leaves video fullscreen,
  then goes back, then exits.
- Channels autoplay with sound.
- Shows a "NO SIGNAL" screen when offline and retries every 5 seconds.
- Restarts the page if the engine's content process crashes.
- Pauses when you leave the app.

## Notes

- Release APKs are signed with the CI's debug key, which changes per build. To install a newer
  build, uninstall the old one first.
- GeckoView 159 and newer need compileSdk 37 and Android Gradle plugin 9.1+; moving to them
  means upgrading those in `build.gradle` / `app/build.gradle` first.
