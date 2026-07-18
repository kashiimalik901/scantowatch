#!/usr/bin/env bash
# Overlays the ARCore demo sources onto a freshly created Flutter scaffold.
# Linux/macOS equivalent of install.ps1 — used by the GitHub Actions build.
#
#   flutter create --org com.scantowatch --project-name scantowatch .
#   bash arcore_src/install.sh
#
# Safe to re-run: every step is idempotent.

set -euo pipefail

SRC="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
ROOT="$(dirname "$SRC")"
PKG="com.scantowatch.demo"
PKG_PATH="com/scantowatch/demo"

echo "ScanToWatch ARCore installer"
echo "root: $ROOT"

if [ ! -d "$ROOT/android/app/src/main" ]; then
  echo "ERROR: no Flutter scaffold found. Run first:"
  echo "  flutter create --org com.scantowatch --project-name scantowatch ."
  exit 1
fi

# ------------------------------------------------------------- kotlin sources
echo "Kotlin sources"
KOTLIN_DEST="$ROOT/android/app/src/main/kotlin/$PKG_PATH"
mkdir -p "$KOTLIN_DEST"

# Remove the scaffold's own MainActivity (generated under whatever package
# flutter create chose) so it cannot clash with ours.
find "$ROOT/android/app/src/main" \( -name 'MainActivity.kt' -o -name 'MainActivity.java' \) \
  -not -path "*/$PKG_PATH/*" -delete 2>/dev/null || true

cp "$SRC/android/app/src/main/kotlin/$PKG_PATH/"*.kt "$KOTLIN_DEST/"
echo "  + $(ls -1 "$KOTLIN_DEST"/*.kt | wc -l) Kotlin files"

# --------------------------------------------------------------- native assets
echo "Assets"
ASSET_DEST="$ROOT/android/app/src/main/assets"
mkdir -p "$ASSET_DEST"
cp "$SRC/android/app/src/main/assets/"* "$ASSET_DEST/"
echo "  + marker.png + demo_video.mp4"

# ----------------------------------------------------------------- dart/pubspec
echo "Dart"
mkdir -p "$ROOT/lib"
cp "$SRC/lib/main.dart" "$ROOT/lib/main.dart"
cp "$SRC/pubspec.yaml" "$ROOT/pubspec.yaml"
echo "  + lib/main.dart + pubspec.yaml"

# flutter create generates test/widget_test.dart referencing MyApp, which does
# not exist once our main.dart replaces the template. It would fail analysis.
if [ -f "$ROOT/test/widget_test.dart" ]; then
  rm -f "$ROOT/test/widget_test.dart"
  echo "  - removed stale scaffold test"
fi

# --------------------------------------------------------------------- manifest
echo "AndroidManifest.xml"
MANIFEST="$ROOT/android/app/src/main/AndroidManifest.xml"

if ! grep -q 'android.permission.CAMERA' "$MANIFEST"; then
  perl -0pi -e 's{(<manifest[^>]*>)}{$1\n    <uses-permission android:name="android.permission.CAMERA" />\n\n    <!-- required=true keeps the app off non-ARCore devices in the Play Store -->\n    <uses-feature android:name="android.hardware.camera.ar" android:required="true" />\n}' "$MANIFEST"
  echo "  + camera permission + AR feature"
else
  echo "  . camera permission already present"
fi

if ! grep -q 'com.google.ar.core' "$MANIFEST"; then
  perl -0pi -e 's{(\s*</application>)}{\n        <!-- "required" makes Play auto-install Google Play Services for AR -->\n        <meta-data android:name="com.google.ar.core" android:value="required" />$1}' "$MANIFEST"
  echo "  + ARCore meta-data"
else
  echo "  . ARCore meta-data already present"
fi

if ! grep -q 'android:screenOrientation' "$MANIFEST"; then
  perl -0pi -e 's{(<activity\s)}{$1android:screenOrientation="portrait" }' "$MANIFEST"
  echo "  + locked to portrait"
else
  echo "  . screenOrientation already set"
fi

# ----------------------------------------------------------------------- gradle
echo "Gradle"
GRADLE=""
IS_KTS=0
if   [ -f "$ROOT/android/app/build.gradle.kts" ]; then GRADLE="$ROOT/android/app/build.gradle.kts"; IS_KTS=1
elif [ -f "$ROOT/android/app/build.gradle" ];     then GRADLE="$ROOT/android/app/build.gradle"
else echo "  ! no app build.gradle found"; fi

if [ -n "$GRADLE" ]; then
  # ARCore needs API 24+
  perl -pi -e 's/minSdk\s*=\s*flutter\.minSdkVersion/minSdk = 24/' "$GRADLE"
  perl -pi -e 's/minSdkVersion\s+flutter\.minSdkVersion/minSdkVersion 24/' "$GRADLE"
  echo "  + minSdk = 24"

  # Force the package to match the Kotlin sources regardless of how
  # flutter create was invoked.
  perl -pi -e "s/namespace\s*=\s*\"[^\"]*\"/namespace = \"$PKG\"/" "$GRADLE"
  perl -pi -e "s/namespace\s+\"[^\"]*\"/namespace \"$PKG\"/" "$GRADLE"
  perl -pi -e "s/applicationId\s*=\s*\"[^\"]*\"/applicationId = \"$PKG\"/" "$GRADLE"
  perl -pi -e "s/applicationId\s+\"[^\"]*\"/applicationId \"$PKG\"/" "$GRADLE"
  echo "  + package = $PKG"

  if ! grep -q 'com.google.ar:core' "$GRADLE"; then
    if [ "$IS_KTS" = "1" ]; then
      cat >> "$GRADLE" <<'EOF'

// --- ScanToWatch AR dependencies ---
dependencies {
    implementation("com.google.ar:core:1.54.0")
    implementation("androidx.media3:media3-exoplayer:1.10.1")
    implementation("androidx.media3:media3-common:1.10.1")
}
EOF
    else
      cat >> "$GRADLE" <<'EOF'

// --- ScanToWatch AR dependencies ---
dependencies {
    implementation 'com.google.ar:core:1.54.0'
    implementation 'androidx.media3:media3-exoplayer:1.10.1'
    implementation 'androidx.media3:media3-common:1.10.1'
}
EOF
    fi
    echo "  + ARCore + media3 dependencies"
  else
    echo "  . dependencies already present"
  fi
fi

echo "Done."
