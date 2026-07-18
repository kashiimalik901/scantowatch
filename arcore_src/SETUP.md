# ARCore demo — setup

Native ARCore image tracking with video locked to the marker, wrapped in Flutter.

## What you need

- **A physical Android phone that supports ARCore.** Check the list:
  https://developers.google.com/ar/devices
  The emulator technically supports AR but is painful and does not represent
  real tracking quality — don't judge the result on it.
- USB cable + **USB debugging** enabled (Settings → Developer options)
- ~15 GB free disk

## 1. Install the toolchain

Nothing is installed on this machine yet — no Flutter, no Java, no Android SDK.

1. **Flutter SDK** — https://docs.flutter.dev/get-started/install/windows
   Extract to `C:\flutter`, then add `C:\flutter\bin` to PATH.
2. **Android Studio** — https://developer.android.com/studio
   In the setup wizard, include: **Android SDK**, **SDK Command-line Tools**,
   **Platform-Tools**.
3. Accept licences and verify:

```powershell
flutter doctor --android-licenses
flutter doctor
```

`flutter doctor` must show no Android errors before continuing.

## 2. Create the scaffold and install the AR sources

From `c:\xampp\htdocs\flutter-app`:

```powershell
flutter create --org com.scantowatch --project-name scantowatch .
.\arcore_src\install.ps1
flutter pub get
```

`install.ps1` overlays the AR code onto the scaffold and patches the manifest and
Gradle files. It's idempotent — re-run it any time you change something in
`arcore_src/`.

## 3. Run

```powershell
flutter devices          # confirm your phone is listed
flutter run --release
```

Release mode matters — debug builds render AR noticeably worse.

On first launch the phone may install **Google Play Services for AR** from the
Play Store. That's expected and only happens once.

---

## Swapping in your real marker and video

**Video** — replace `arcore_src/android/app/src/main/assets/demo_video.mp4`
(H.264/AAC MP4). Author it at the **same aspect ratio as the marker**, because
the quad is scaled to the marker's exact extent and anything else will stretch.

**Marker** — replace `arcore_src/android/app/src/main/assets/marker.png`,
then re-run `install.ps1`.

**Then set the physical size.** In `ArVideoView.kt`:

```kotlin
private const val MARKER_WIDTH_METERS = 0.20f
```

Measure your actual printed marker's width in metres and set it. This scales the
overlay and helps ARCore lock on faster — a wrong value makes the video the
wrong size.

### Check the marker will actually track

ARCore rejects images it can't track, and the app will show you that error. To
check *before* building, use Google's scoring tool:

```
arcoreimg eval-img --input_image_path=marker.png
```

`arcoreimg` ships in the ARCore SDK. **Score must be 75+.** More detail, more
contrast, more asymmetry raises it. Logos, line art, flat colour and heavy
symmetry score badly.

This is worth doing before your artwork is signed off — it's an art-direction
constraint, not something code can fix.

---

## How it works

```
MainActivity            registers the platform view + method channel
  └── ArViewFactory     creates the view, forwards lifecycle
        └── ArVideoView ARCore session, image database, ExoPlayer
              └── ArRenderer          per-frame draw + tracking state
                    ├── BackgroundRenderer   camera feed (external OES texture)
                    └── VideoQuadRenderer    video quad at the image's pose
```

The video is decoded by ExoPlayer straight into a `SurfaceTexture` sampled as an
external OES texture, so frames never touch the CPU — cheap even at 1080p.

Playback is driven by tracking state: the quad is only drawn while ARCore reports
`FULL_TRACKING`, meaning it can actually *see* the marker. Without that check,
ARCore falls back to `LAST_KNOWN_POSE` and the video hangs in mid-air after the
marker leaves frame.

## Known constraints

- **Android only.** iOS needs ARKit, a Mac, and an Apple Developer account.
- **ARCore devices only.** `required="true"` in the manifest hides the app from
  incompatible devices on the Play Store. A meaningful share of budget and older
  Android phones cannot run this at all.
- Portrait locked, to keep display-geometry handling simple for the demo.
- Single marker. `ArRenderer` breaks after the first match; supporting several
  means adding more images to the database and one player per marker.
