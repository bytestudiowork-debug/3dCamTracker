# TrackStudio — Phase 1: Footage Import Module

An Android-native 3D-camera-tracking / matchmove app, built modular so each
computation-heavy piece (import, proxy generation, later: tracking/solving)
stays independently swappable and independently efficient. Kotlin + Jetpack
Compose for UI, C++/NDK for anything CPU-bound. Landscape-only, dark
desktop-tool-style UI (not a typical mobile app skin).

This phase ships **Module 1: New Project / Footage Import**, described below.
There is no Android SDK on the dev machine this was built on — every build
happens on GitHub Actions (`.github/workflows/android-build.yml`).

## What Module 1 does

- Home screen with **New Project** / **Recent Projects**.
- New Project opens a fullscreen, tabbed "desktop dialog":
  - **Footage** — import an image sequence via the system file picker
    (image sequences only; other file types are filtered out by the
    picker itself). Frames are validated, naturally sorted, and checked
    for a consistent resolution and a detected numbering pattern
    (e.g. `shot_####.png (1-240)`).
  - **Proxy** — enable/disable proxy generation, pick a proxy resolution
    (Full/½/¼/⅛) and JPEG quality. Proxies are auto-suggested for
    anything wider than ~2K, since this is meant to run on low-end
    devices too.
  - **Project** — project name, frame rate, sensor width (mm, needed
    later for focal-length math), color space tag.
- **Create Project** generates the proxy frames (native C++ decode →
  downscale → JPEG-encode) in the background and writes project
  metadata to app-private storage as JSON.

## Why it's built this way

- **UI/logic separation**: `ui/` only reads `ViewModel` state and sends
  events; all file I/O, sequence validation, and JNI calls live in
  `data/`. You can restyle every screen without touching a single line
  of import/proxy logic.
- **Native compute, not Kotlin loops**: decoding and downscaling frames
  happens in `cpp/proxy_engine.cpp` (using vendored, header-only
  `stb_image` / `stb_image_resize2` / `stb_image_write` — no OpenCV/
  libjpeg dependency needed for this). Kotlin never touches raw pixel
  data.
- **Low-end-device conscious**: no full-resolution frame is ever loaded
  further than a header probe unless a proxy is actually being written;
  bytes are read once per phase (probe, then generate) rather than held
  in memory across the whole sequence; ABI filtering to
  arm64-v8a/armeabi-v7a keeps native binary size down.
- **SAF only, no broad storage permission**: footage is picked through
  `ACTION_OPEN_DOCUMENT`, so there's no `READ_EXTERNAL_STORAGE` /
  `MANAGE_EXTERNAL_STORAGE` prompt to manage across API levels.

## Building

Locally (if you ever do have the SDK/NDK installed):

```
./gradlew assembleDebug   # or `gradle assembleDebug` if no wrapper jar present
```

On GitHub Actions: push to `main`/`master`, open a PR, or trigger
**Actions → Android CI Build → Run workflow**. The debug APK is uploaded
as the `trackstudio-debug-apk` artifact. On native-build failure, a
`native-build-logs` artifact is uploaded too.

## Structure

```
app/src/main/java/com/trackstudio/app/
  MainActivity.kt              # navigation: Home <-> New Project dialog
  ui/theme/                    # dark desktop-tool color/type/theme
  ui/common/                   # Panel, DesktopButton, DesktopTextField, LabeledValueRow
  ui/home/HomeScreen.kt
  ui/newproject/NewProjectScreen.kt   # tabbed dialog shell
  ui/newproject/components/    # FootagePanel, ProxyPanel, ProjectSettingsPanel
  viewmodel/                   # HomeViewModel, NewProjectViewModel + UI state/events
  data/model/                  # ProjectSettings, FootageInfo, ProxySettings, ColorSpace
  data/io/                     # ImageSequenceImporter, ImageSequenceValidator
  data/repository/             # ProjectRepository (JSON persistence)
  data/native/                 # ProxyEngine (JNI declarations)
  util/                        # FileUtils, Logger

app/src/main/cpp/
  CMakeLists.txt
  proxy_engine.cpp             # JNI: nativeProbeImage, nativeGenerateProxy
  stb_image.h / stb_image_resize2.h / stb_image_write.h   # vendored, header-only
```

## Not in this phase (by design)

- Opening a recent project (metadata round-trips; frame-list/SAF
  permission persistence is a follow-up).
- Any tracking/solving math — this module's whole job is to hand a
  clean, validated `FootageInfo` + generated proxies to whatever
  module comes next.
- Home-screen visual polish beyond the two buttons + list, as noted
  in the original ask ("we will refine it later").
