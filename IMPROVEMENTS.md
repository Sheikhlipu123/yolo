# Codebase improvement notes

Reviewed against the current `master` branch. The project is an Android application combining Kotlin/Java UI and services with native C++ inference and touch-injection components.

## Highest priority

### 1. Remove release signing credentials from source control

`app/build.gradle.kts` contains a hard-coded keystore path and passwords in the `release` signing configuration. This is a credential exposure and also makes reproducible/secure release builds difficult.

**Improve by:**

- Revoke and replace the exposed keystore/passwords immediately.
- Store signing values in `keystore.properties`, environment variables, or CI secret storage.
- Add the local keystore/properties files to `.gitignore`.
- Fail the release build with a clear message when signing configuration is absent instead of using fallback secrets.

Reference: `app/build.gradle.kts`

### 2. Add automated build and test validation

No `.github/workflows` directory was found, and the repository currently exposes only the standard Android test dependencies. Native code is substantial, but there is no visible host-side test or device/instrumentation test strategy for the inference and input paths.

**Improve by:**

- Add CI for `./gradlew lint testDebugUnitTest assembleDebug`.
- Add an NDK/CMake build check for all ABIs/configurations that are supported.
- Add instrumentation tests for permission/service lifecycle and configuration import/export.
- Add host-side C++ tests for box decoding/NMS, coordinate transforms, zone detection, and touch event state transitions.
- Test at least one Qualcomm/QNN device and one CPU-fallback device in a documented validation matrix.

References: `app/build.gradle.kts`, `app/src/main/cpp/CMakeLists.txt`

### 3. Fix native global-state and concurrency risks

`app/src/main/cpp/src/inference/aimbot.cpp` stores the active inference engine and settings in process-wide globals (`g_engine`, `g_force_cpu`, `g_cpu_threads`, and `g_input_size`). `touch_core.cpp` similarly uses extensive global mutable state shared by JNI calls and reader threads. Some state is mutex-protected, while other fields such as `g_running`, zone flags, and initialization state are accessed without a consistent synchronization policy.

**Improve by:**

- Encapsulate engine state in an owner object rather than global singletons.
- Define a single-threaded ownership model for init/detect/release, or protect all engine operations with a mutex.
- Replace `volatile` flags with `std::atomic` where cross-thread signaling is required.
- Protect zone configuration and reads consistently; use snapshots for reader threads.
- Make shutdown cancellation-safe and ensure all reader threads are joined before device state is destroyed.
- Add stress tests that repeatedly initialize, detect, rotate, and release the service.

References: `app/src/main/cpp/src/inference/aimbot.cpp`, `app/src/main/cpp/src/injection/touch_core.cpp`

## Important maintainability improvements

### 4. Reduce duplicated and legacy native paths

The native build links multiple overlapping paths: NCNN, LiteRT/TFLite, QNN, MediaTek, `inputmgr_inject`, `uinput_inject`, and a root daemon. The project documentation also identifies unused or experimental components, while `CMakeLists.txt` still builds them. This increases APK size, build time, and the surface area for device-specific failures.

**Improve by:**

- Decide which backends and injection paths are production-supported.
- Move experimental/legacy implementations behind explicit build options or separate modules.
- Remove unused runtime dependencies such as ONNX Runtime if they are not used.
- Document backend capability detection and fallback behavior in one source of truth.
- Add build-time checks that imported prebuilt libraries and headers match the selected ABI.

References: `app/build.gradle.kts`, `app/src/main/cpp/CMakeLists.txt`, `CLAUDE.md`

### 5. Make JNI boundaries safer and easier to evolve

`aimbot.cpp` exposes JNI functions using long name-mangled method names and returns `null` for both “no detections” and several error cases. The detect path also allocates a new Java float array for every result frame.

**Improve by:**

- Register native methods with `JNI_OnLoad` instead of relying only on symbol naming.
- Define an explicit error/status API so initialization, invalid buffers, and empty detections are distinguishable.
- Return an empty result object/array for “no detections” where practical.
- Reuse buffers or expose a native-owned result buffer to reduce per-frame allocations and GC pressure.
- Validate all JNI arguments, dimensions, strides, and thread attachment assumptions.
- Add native crash/error telemetry that reports the selected backend and fallback reason without collecting sensitive screen data.

Reference: `app/src/main/cpp/src/inference/aimbot.cpp`

### 6. Improve touch-device handling and cleanup

`touch_core.cpp` parses human-readable `/system/bin/getevent -p` output to find a touch device, clones device identity/capabilities, grabs the physical device, and writes directly to `/dev/uinput`. This is highly device- and OS-dependent. Several ioctl/write return values are not checked, and the fallback can continue after physical-device grabbing fails, potentially allowing real and injected events to be delivered together.

**Improve by:**

- Treat failed `EVIOCGRAB`, uinput setup, and event writes as explicit states surfaced to the Kotlin layer.
- Avoid relying on localized or version-dependent `getevent` text where a stable API is available.
- Validate slot bounds in all public `touch_down`, `touch_move`, and `touch_up` calls.
- Use a dedicated RNG instead of reseeding global `rand()` in `genRandomString`.
- Add recovery for device disconnects, orientation changes, and stale file descriptors.
- Make the unsafe “injection without physical suppression” fallback opt-in and clearly visible to users.

Reference: `app/src/main/cpp/src/injection/touch_core.cpp`

### 7. Separate application responsibilities

The documented runtime combines screen capture, inference scheduling, overlays, aim/trigger policy, configuration persistence, recording, and injector lifecycle in a foreground-service-centered design. This makes lifecycle bugs and permission failures difficult to isolate.

**Improve by:**

- Introduce explicit interfaces for capture, inference, target selection, overlay rendering, and injection.
- Use a state machine for service lifecycle (`Idle`, `PermissionRequired`, `Starting`, `Running`, `Stopping`, `Error`).
- Keep controllers pure and testable by passing detections and an injector interface rather than accessing global service state.
- Move long-running work to structured coroutines with cancellation and backpressure so recording or dataset saving cannot stall inference.
- Centralize permission and capability checks, including MediaProjection, overlay, Shizuku/root, and backend availability.

References: `CLAUDE.md`, `app/src/main/AndroidManifest.xml`

## Security, privacy, and release quality

### 8. Minimize permissions and document sensitive behavior

The manifest requests overlay, MediaProjection-related service capability, audio recording, internet, input injection, and storage-related permissions. Some may be unnecessary for all features. The app also captures screen contents and can record video, which requires clear consent and retention controls.

**Improve by:**

- Remove permissions that are not required by the current build.
- Explain each sensitive permission at the point of request and provide a visible recording/data-capture indicator.
- Add controls for dataset/video storage location, retention, deletion, and export.
- Never include captured frames, labels, or device identifiers in logs or crash reports.
- Add a threat model covering Shizuku/root commands, the AIDL service, local config import, and model files.

Reference: `app/src/main/AndroidManifest.xml`, `CLAUDE.md`

### 9. Make dependency and repository configuration reproducible

`settings.gradle.kts` mixes Aliyun mirrors with Google, Maven Central, Gradle Plugin Portal, and JitPack. This can create availability, integrity, and reproducibility differences between developer environments.

**Improve by:**

- Prefer official repositories and use mirrors only through documented, opt-in settings.
- Pin or verify prebuilt native artifacts and record their source/version/checksum.
- Add dependency locking or a dependency update process.
- Document the exact JDK, Android SDK, NDK, CMake, and Vulkan requirements.

Reference: `settings.gradle.kts`, `app/src/main/cpp/CMakeLists.txt`

## Suggested order of work

1. Rotate signing credentials and remove secrets from Git history.
2. Establish CI with debug build, lint, unit tests, and native compilation.
3. Add lifecycle/concurrency tests and fix native shutdown/error handling.
4. Consolidate supported inference/injection backends and remove unused dependencies.
5. Harden permissions, capture-data handling, and release documentation.
6. Refactor JNI and service boundaries once behavior is covered by tests.
