# Agent Guidance — Cardboard VR Thermal Viewer

## Purpose
A native Android VR viewer that renders live video as a stereo, lens-distortion-corrected split-screen for a phone mounted in a Google-Cardboard-style headset.

## Architecture
- Single Gradle module `:app`.
- Package root: `com.example.vrviewer`.
- UI shell: Jetpack Compose (Material3) hosted in `MainActivity`.
- VR output: `GLSurfaceView` embedded via `AndroidView` interop.
- Rendering: OpenGL ES 3.0 only. Custom barrel-distortion shader. No Cardboard SDK.
- Camera abstraction: `CameraSource` renders into a `SurfaceTexture`.
  - `PhoneCameraSource` -> camera2, binding to each available focal length of the logical back camera so every physical lens can be cycled.
  - `ThermalCameraSource` -> camera2 `LENS_FACING_EXTERNAL`, with a clear extension point to swap in a UVC library later.
- State management: MVVM with `ViewModel` + `StateFlow`.
- Concurrency: Kotlin Coroutines + Flow.
- No DI framework; manual construction / simple ViewModel factory.

## Package map
```
com.example.vrviewer
├── MainActivity.kt                  Compose host + AndroidView(GLSurfaceView)
├── ui.theme
│   ├── Theme.kt
│   ├── Color.kt
│   └── Type.kt
├── vr
│   ├── VrGlSurfaceView.kt            GLSurfaceView, stereo viewport split
│   ├── DistortionRenderer.kt         GL renderer, barrel-distortion shader, textured quad
│   ├── CardboardProfile.kt           Lens params data class + JSON parse stub
│   └── Eye.kt                        enum LEFT / RIGHT
├── camera
│   ├── CameraSource.kt               interface: start(target), stop
│   ├── Camera2Latency.kt             shared low-latency camera2 capture-request settings
│   ├── PhoneCameraSource.kt          camera2 implementation, one source per physical lens
│   └── ThermalCameraSource.kt        camera2 EXTERNAL implementation (stub)
└── viewmodel
    └── ViewerViewModel.kt            StateFlow<UiState> (selected source, ready flag)
```

## Build / lint / typecheck commands
```bash
./gradlew :app:assembleDebug
./gradlew :app:lint
./gradlew :app:testDebugUnitTest
```

## Deploy
If an Android device is connected via adb, install the debug build after each successful build and launch it so the change is exercised immediately:
```bash
./gradlew :app:installDebug && adb shell am start -n com.example.vrviewer.debug/com.example.vrviewer.MainActivity
```
Always attempt this install-and-launch step after building when a phone is available, so changes are verified on device.

## Conventions
- Keep it minimal. No new dependency, module, file, or feature without justification.
- Jetpack Compose is used only for non-GL UI. The VR output is always the `GLSurfaceView`.
- All new Kotlin stubs must have correct package + imports, and `TODO()` bodies with 1–2 line KDoc.
- Prefer explicit, simple code over frameworks. No analytics, no networking, no DI library.
- Preserve the single-module layout. No multi-module refactor without user approval.
