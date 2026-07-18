# ScanToWatch — AR "point camera at image, video plays on it"

Marketing demo. Point a phone camera at a specific image → a video plays pinned
onto that image, tracked in 3D as the phone moves.

---

## Feasibility summary (read this first)

| What you asked for | Verdict |
|---|---|
| Camera detects a specific image | ✅ Standard image-tracking AR |
| Video plays pinned on that image | ✅ Working in this demo |
| QR scan → app **auto-installs** | ❌ Not possible with Flutter — see below |

**Why the instant-install part doesn't work:**

- **Google Play Instant** (Android) caps at ~15 MB. Flutter's engine alone exceeds
  that. Flutter has never supported instant apps, and Google has been winding the
  platform down.
- **App Clips** (iOS equivalent) cap at ~15 MB and require native Swift. Flutter
  doesn't support them.
- Neither actually auto-installs from a plain QR. Both need app-store-registered
  URL associations, signed manifests, and platform review. A QR pointing at
  `https://scantowatch.com` opens a browser or a store page — the user still taps.

**Practical consequence for a marketing campaign:** a native app costs you an
install step (scan → store → install → open → AR), which is where most of your
audience drops off. The WebAR page in `webar/` has *zero* install steps and is
the same code path — worth considering as the actual campaign deliverable even
though the Flutter app is what we're building.

---

## Part 1 — WebAR demo (working now)

This runs today and validates the marker + video before any native build.
It is also the AR engine that gets embedded into the Flutter app in Part 2.

```
webar/
  index.html                        the whole AR app
  vendor/aframe.min.js              A-Frame 1.6.0        (vendored, offline)
  vendor/mindar-image-aframe.prod.js MindAR 1.2.5        (vendored, offline)
  assets/targets.mind               compiled image target
  assets/marker.png                 the image to point at
  assets/demo.mp4                   placeholder video (16:9)
  assets/demo-qr.svg                QR for the tunnel URL
```

### Run it

```bash
# 1. serve the folder (only this folder — not all of htdocs)
cd c:/xampp/htdocs/flutter-app/webar
php -S 0.0.0.0:8080 -t .

# 2. expose it over HTTPS
ngrok http 8080
```

Open the `https://...ngrok-free.dev` URL on your phone.

> **ngrok free tier** shows a "You are about to visit..." interstitial first.
> Tap **Visit Site**. This does not appear on a real domain.

### ⚠️ The one gotcha: HTTPS is mandatory

Phone browsers expose `navigator.mediaDevices.getUserMedia` **only on a secure
origin**. Consequences:

- `https://...` → works
- `http://localhost:8080` on this PC → works (localhost is treated as secure)
- `http://192.168.1.15:8080` from your phone → **camera silently unavailable**

That LAN case is the usual reason "the demo just shows a black screen". The page
detects it and shows an explanation rather than failing opaquely.

### Point the camera at what?

`webar/assets/marker.png` — the shown card image. Open it on a second screen
(laptop/monitor) or print it. Printed and flat-lit tracks best; a glossy phone
screen reflects and tracks worse.

---

## Swapping in your real marker + video

**Video** — replace `webar/assets/demo.mp4`.
Use H.264 / AAC in an MP4. If the aspect ratio isn't 16:9, update the plane in
`index.html`:

```html
<a-video src="#clip" width="1" height="0.552" ...>
```

`height` = marker height ÷ marker width. Matching this is what makes the video
land *exactly* on the image instead of floating slightly off it.

**Marker image** — you must compile it into a `.mind` file:

1. Go to https://hiukim.github.io/mind-ar-js-doc/tools/compile
2. Upload your image → **Start** → download `targets.mind`
3. Replace `webar/assets/targets.mind`, and `marker.png` with your image

**What makes a good marker** (this matters more than anything else for quality):

- ✅ Busy, high-contrast, asymmetric, lots of fine detail
- ❌ Large flat colour areas, heavy symmetry, repeating patterns, mostly text
- ❌ Faces and gradients — surprisingly poor feature detection

The compiler shows a feature-point preview. Sparse points = unreliable tracking.
**Test your real marker before committing to the artwork** — this is the single
biggest risk to the campaign, and it's an art-direction constraint, not a code one.

---

## Part 2 — Flutter app (next)

### Blocker: no toolchain installed

This machine currently has **no Flutter, no Java, no Android SDK, no Android
Studio**. All of it needs installing before anything compiles (~15 GB).

1. **Flutter SDK** — https://docs.flutter.dev/get-started/install/windows
   Extract to `C:\flutter`, add `C:\flutter\bin` to PATH
2. **Android Studio** — https://developer.android.com/studio
   During setup install: Android SDK, SDK Command-line Tools, Platform-Tools
3. Accept licences and verify:
   ```bash
   flutter doctor --android-licenses
   flutter doctor
   ```

`flutter doctor` must be clean for Android before continuing. iOS is not
possible from Windows at all — it requires a Mac + Apple Developer account.

### Then

```bash
cd c:/xampp/htdocs/flutter-app
flutter create --org com.scantowatch --platforms android .
```

…and I'll wire the AR view in on top of that scaffold.

### Approach — native ARCore

**Decided: native ARCore via a Kotlin platform view.** Sources are in
`arcore_src/`; see [arcore_src/SETUP.md](arcore_src/SETUP.md).

Chosen over WebAR-in-a-WebView because ARCore fuses the camera with the phone's
motion sensors (visual-inertial odometry) and anchors the marker in a persistent
world map. MindAR re-solves the pose from scratch every frame with no memory,
which is what causes the swimming. ARCore holds position through fast movement
and motion blur.

No Flutter plugin was usable: `arcore_flutter_plugin` is unmaintained since ~2021
on deprecated Sceneform, and `augen` — the only current option — had 8 likes and
661 downloads at time of writing, with no documented video-on-image support. So
the AR view is hand-written Kotlin against ARCore + media3/ExoPlayer.

Trade-offs accepted: **Android only** (iOS needs ARKit + a Mac), and only
ARCore-certified devices can run it.

---

## Current status

- [x] WebAR demo working, assets vendored offline
- [x] HTTPS tunnel verified
- [x] ARCore native sources written (`arcore_src/`) — **not yet compiled**
- [ ] Flutter + Android toolchain installed ← **blocking**
- [ ] First build on a physical ARCore device
- [ ] Real marker image scored with `arcoreimg` (needs 75+) ← **highest risk**
- [ ] Real video swapped in, `MARKER_WIDTH_METERS` measured and set
- [ ] Signed APK
