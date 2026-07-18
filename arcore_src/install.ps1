# Overlays the ARCore demo sources onto a freshly created Flutter scaffold.
#
#   flutter create --org com.scantowatch --project-name scantowatch .
#   .\arcore_src\install.ps1
#
# Safe to re-run: every step is idempotent.

$ErrorActionPreference = 'Stop'

$root = Split-Path -Parent $PSScriptRoot   # project root (parent of arcore_src)
$src  = $PSScriptRoot
$pkg  = 'com.scantowatch.demo'
$pkgPath = $pkg -replace '\.', '/'

function Info($m) { Write-Host "  $m" }
function Warn($m) { Write-Host "  ! $m" -ForegroundColor Yellow }
function Ok($m)   { Write-Host "  + $m" -ForegroundColor Green }

Write-Host "`nScanToWatch ARCore installer" -ForegroundColor Cyan
Write-Host "root: $root`n"

# ---------------------------------------------------------------- sanity check
if (-not (Test-Path "$root/android/app/src/main")) {
  Write-Host "ERROR: no Flutter scaffold found." -ForegroundColor Red
  Write-Host "Run this first, from $root :" -ForegroundColor Red
  Write-Host "  flutter create --org com.scantowatch --project-name scantowatch ." -ForegroundColor Red
  exit 1
}

# ------------------------------------------------------------- kotlin sources
Write-Host "Kotlin sources"
$kotlinDest = "$root/android/app/src/main/kotlin/$pkgPath"
New-Item -ItemType Directory -Force -Path $kotlinDest | Out-Null

# The scaffold generates its own MainActivity under whatever package
# flutter create chose. Remove it so it cannot clash with ours.
Get-ChildItem "$root/android/app/src/main" -Recurse -Include 'MainActivity.kt','MainActivity.java' `
  -ErrorAction SilentlyContinue | ForEach-Object {
    if ($_.FullName -notlike "*$($pkgPath -replace '/','\')*") {
      Remove-Item $_.FullName -Force
      Info "removed generated $($_.Name)"
    }
  }

Copy-Item "$src/android/app/src/main/kotlin/$pkgPath/*.kt" $kotlinDest -Force
Ok "copied $((Get-ChildItem "$kotlinDest/*.kt").Count) Kotlin files"

# ---------------------------------------------------------------- native assets
Write-Host "Assets"
$assetDest = "$root/android/app/src/main/assets"
New-Item -ItemType Directory -Force -Path $assetDest | Out-Null
Copy-Item "$src/android/app/src/main/assets/*" $assetDest -Force
Ok "marker.png + demo_video.mp4 -> android/app/src/main/assets/"

# ------------------------------------------------------------------ dart / pubspec
Write-Host "Dart"
New-Item -ItemType Directory -Force -Path "$root/lib" | Out-Null
Copy-Item "$src/lib/main.dart" "$root/lib/main.dart" -Force
Copy-Item "$src/pubspec.yaml" "$root/pubspec.yaml" -Force
Ok "lib/main.dart + pubspec.yaml"

# flutter create generates test/widget_test.dart referencing MyApp, which does
# not exist once our main.dart replaces the template. It would fail analysis.
if (Test-Path "$root/test/widget_test.dart") {
  Remove-Item "$root/test/widget_test.dart" -Force
  Info "removed stale scaffold test"
}

# -------------------------------------------------------------------- manifest
Write-Host "AndroidManifest.xml"
$manifestPath = "$root/android/app/src/main/AndroidManifest.xml"
$m = Get-Content $manifestPath -Raw

if ($m -notmatch 'android\.permission\.CAMERA') {
  $inject = @"
    <uses-permission android:name="android.permission.CAMERA" />

    <!-- required=true keeps the app off non-ARCore devices in the Play Store -->
    <uses-feature android:name="android.hardware.camera.ar" android:required="true" />

"@
  $m = $m -replace '(?s)(<manifest[^>]*>\s*)', "`$1$inject"
  Ok "added camera permission + AR feature"
} else { Info "camera permission already present" }

if ($m -notmatch 'com\.google\.ar\.core') {
  $meta = @"

        <!-- "required" makes Play auto-install Google Play Services for AR -->
        <meta-data android:name="com.google.ar.core" android:value="required" />
"@
  $m = $m -replace '(?s)(\s*</application>)', "$meta`$1"
  Ok "added ARCore meta-data"
} else { Info "ARCore meta-data already present" }

if ($m -notmatch 'android:screenOrientation') {
  $m = $m -replace '(<activity\s)', '$1android:screenOrientation="portrait" '
  Ok "locked activity to portrait"
} else { Info "screenOrientation already set" }

Set-Content $manifestPath $m -Encoding utf8

# ---------------------------------------------------------------------- gradle
Write-Host "Gradle"
$gradleKts = "$root/android/app/build.gradle.kts"
$gradleGroovy = "$root/android/app/build.gradle"

if (Test-Path $gradleKts) { $gradlePath = $gradleKts; $isKts = $true }
elseif (Test-Path $gradleGroovy) { $gradlePath = $gradleGroovy; $isKts = $false }
else { Warn "no app build.gradle found - patch dependencies manually"; $gradlePath = $null }

if ($gradlePath) {
  $g = Get-Content $gradlePath -Raw

  # ARCore needs API 24+
  $g = $g -replace 'minSdk\s*=\s*flutter\.minSdkVersion', 'minSdk = 24'
  $g = $g -replace 'minSdkVersion\s+flutter\.minSdkVersion', 'minSdkVersion 24'
  Ok "minSdk = 24"

  # Force the package so it matches the Kotlin sources regardless of what
  # flutter create was invoked with.
  $g = $g -replace 'namespace\s*=\s*"[^"]*"', "namespace = `"$pkg`""
  $g = $g -replace 'namespace\s+"[^"]*"',    "namespace `"$pkg`""
  $g = $g -replace 'applicationId\s*=\s*"[^"]*"', "applicationId = `"$pkg`""
  $g = $g -replace 'applicationId\s+"[^"]*"',     "applicationId `"$pkg`""
  Ok "package = $pkg"

  if ($g -notmatch 'com\.google\.ar:core') {
    if ($isKts) {
      $deps = @"

// --- ScanToWatch AR dependencies ---
dependencies {
    implementation("com.google.ar:core:1.54.0")
    implementation("androidx.media3:media3-exoplayer:1.10.1")
    implementation("androidx.media3:media3-common:1.10.1")
}
"@
    } else {
      $deps = @"

// --- ScanToWatch AR dependencies ---
dependencies {
    implementation 'com.google.ar:core:1.54.0'
    implementation 'androidx.media3:media3-exoplayer:1.10.1'
    implementation 'androidx.media3:media3-common:1.10.1'
}
"@
    }
    $g = $g + $deps
    Ok "added ARCore + media3 dependencies"
  } else { Info "dependencies already present" }

  Set-Content $gradlePath $g -Encoding utf8
}

Write-Host "`nDone." -ForegroundColor Green
Write-Host "Next:"
Write-Host "  flutter pub get"
Write-Host "  flutter run --release        (physical ARCore device required)`n"
