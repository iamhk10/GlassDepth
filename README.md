# GlassDepth

Free, ad-free Android app (Kotlin, Android 12+) with two things in one:

1. **Glass widgets** (clock, date, battery) that work on their own. No KWGT, no other app.
2. **Depth live wallpaper**: pick a photo, the subject is cut out on your phone, a glass clock sits behind the subject, and everything shifts when you tilt the phone.

Everything runs on-device. No server, no accounts, no ads.

> **Status:** v1.0 source. It was written without an Android SDK available, so it has **not been compiled or run on a phone yet**. Expect to fix a few small build errors on the first build. See "First build" below.

## First build (pick one)

### A) Android Studio (easiest)
1. Install Android Studio (free).
2. `File > Open` and choose this folder. Let Gradle sync finish.
3. Plug in your phone (USB debugging on) and press Run, or use `Build > Build APK(s)`.
4. The APK is in `app/build/outputs/apk/`.

### B) GitHub Actions (free, no PC setup)
1. Create a GitHub repo and push this folder to the `main` branch.
2. Open the repo > Actions > "Build APK" > Run workflow.
3. When it finishes, download the `GlassDepth-apk` artifact and install the APK on your phone.

## Signing (do this before you share the APK with anyone)
Without a keystore the APK is signed with a debug key. Updates only install over an older version if both were signed with the **same key**, so pick your key now and never lose it.

```
keytool -genkeypair -v -keystore glassdepth.jks -alias glassdepth -keyalg RSA -keysize 2048 -validity 10000
```

For GitHub Actions add these repository secrets: `KEYSTORE_B64` (output of `base64 -w0 glassdepth.jks`), `KEYSTORE_PASSWORD`, `KEY_ALIAS`, `KEY_PASSWORD`.
For a local build set the environment variables `KEYSTORE_FILE`, `KEYSTORE_PASSWORD`, `KEY_ALIAS`, `KEY_PASSWORD` and run `./gradlew assembleRelease`.

Keep a backup of the `.jks` file somewhere safe. It is in `.gitignore` on purpose.

## How it works

| Part | File | What it does |
| --- | --- | --- |
| Photo import | `WallpaperStore.kt` | Decodes the picked photo, crops it to your screen shape, stores it privately |
| Subject cutout | `SubjectCutout.kt` | ML Kit subject segmentation, runs on the phone |
| Live wallpaper | `DepthWallpaperService.kt` | 3 layers (photo, glass clock, subject) with gyro tilt, about 30 fps, only draws while visible |
| Glass look | `GlassRenderer.kt` | Blurred crop of the wallpaper + frost + sheen + rim light |
| Widgets | `Widgets.kt` | Clock, date, battery. Time uses `TextClock`, so the widget does not wake the phone |
| Widget setup | `WidgetConfigActivity.kt` | Blur, frost, corner, and X/Y position so the glass lines up with the wallpaper |
| Main screen | `MainActivity.kt` | 3-step flow: photo, wallpaper, widgets |

### Widget and wallpaper matching
Android does not tell a widget where it sits on the home screen. So each widget uses the saved wallpaper photo, takes a crop from the spot you set with the **Position X / Y** sliders, and blurs it. Move the sliders until the preview matches your home screen. This is a close match, not a pixel-perfect one, and it can drift if you change launcher grid size or scroll between pages.

### Known limits (v1.0)
- Depth comes from a subject cutout (2 layers), not a full depth map.
- The ML Kit model is downloaded once by Google Play services, so phones without Play services will get a flat photo (no depth) but everything else still works.
- Some phone makers (Xiaomi, Realme, Samsung battery savers) may delay widget updates or stop the wallpaper. Set the app's battery usage to "Unrestricted" if that happens.
- If the tilt motion feels reversed, change `signX` / `signY` at the top of `DepthEngine` in `DepthWallpaperService.kt` to `-1f`.
- Library versions (AGP 8.7.3, Kotlin 2.0.21, ML Kit subject segmentation 16.0.0-beta1) were chosen without a test build. If Gradle sync complains, let Android Studio suggest the nearest working version.

## Ideas for next versions
Weather widget (Open-Meteo, free), lock-screen support, tap ripple on the glass, downloadable wallpaper packs from GitHub Releases, true depth map for smoother 3D.
