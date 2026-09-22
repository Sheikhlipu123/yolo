# AI Aimbot Android

An Android FPS game AI aiming assistant based on real-time YOLOv8n object detection, with Qualcomm Hexagon QNN HTP acceleration support.

---

## Device requirements

| Item | Requirement |
|------|-------------|
| Chipset | **Snapdragon 8 Gen 1 or newer** (SM8450+) |
| Android | Android 12 (API 31) or newer |
| Architecture | arm64-v8a |

> Devices below Snapdragon 8 Gen 1 may not run correctly or may provide insufficient inference performance.

---

## Download and install

1. Go to the [Releases](https://github.com/xiangsu1145/Auto-aim_android-yolo/releases) page.
2. Download the latest APK.
3. Install the APK on your phone. You may need to allow installation from unknown sources.

---

## Prerequisites

### 1. Install Shizuku

This application uses Shizuku for touch injection. Shizuku must be configured before starting the app:

1. Install Shizuku from an app store or the [Shizuku website](https://shizuku.rikka.app/).
2. Open Shizuku and choose **Start via wireless debugging**.
3. Follow Shizuku's pairing instructions. Wireless debugging must be enabled in Developer options.
4. Confirm that Shizuku shows a **Running** status.

### 2. Authorize the application

1. Open Shizuku → **Authorized applications**.
2. Find this application and enable its authorization.

---

## Usage

### Step 1: Start the application

Open the application. The main screen shows the current status and available models.

### Step 2: Grant permissions

The first launch requires these permissions:

- **Screen recording**: captures the game screen for detection.
- **Display over other apps**: displays the detection overlay and control panel.

The application opens the permission requests automatically. Follow the prompts and grant access.

### Step 3: Select a model

Select a detection model on the main screen:

| Model | Description |
|-------|-------------|
| INT8 192 | Recommended; fastest option with sufficient accuracy |
| INT8 256 | Higher accuracy with slightly lower performance |
| Float32 192 | For testing; not recommended for everyday use |

### Step 4: Start the service

1. Tap **Start**.
2. The floating AI icon appears on the screen.
3. Switch to the game.

### Step 5: Use the in-game controls

**Floating icon:**

- Drag it to any position.
- Tap it to open or close the control panel.
- Long-press it to stop the service.

**Control panel** (opened by tapping the floating icon):

| Tab | Function |
|-----|----------|
| Aim | Enable automatic aiming and adjust PID parameters such as responsiveness and smoothness |
| Trigger | Enable automatic trigger behavior when the crosshair is over a target |
| Model | Switch the detection model |
| System | Configure the confidence threshold and system settings |

### Features

**Automatic aiming:**

- Moves the crosshair toward detected targets.
- Uses a PID controller for smooth tracking instead of instantaneous movement.
- Supports response tuning: a higher Kp value increases response speed, while a higher Kd value increases smoothing.

**Automatic trigger:**

- Fires when the crosshair overlaps a detected target.
- Supports configurable trigger-area size.

**Hold-to-fire:**

- Requires a physical finger to remain inside the trigger area before automatic aiming is activated.
- Stops when the finger is released to help prevent unintended actions.

**Detection overlay:**

- Displays bounding boxes around detected targets.
- Green box = high confidence; yellow box = low confidence.
- Can be disabled in settings.

---

## Frequently asked questions

### Q: No detection boxes appear after starting

- Confirm that screen recording permission was granted.
- Confirm that Shizuku is running and has authorized this application.
- Try lowering the confidence threshold in Control panel → System.

### Q: Automatic aiming does not work

- Confirm that Shizuku is running.
- Confirm that automatic aiming is enabled in the control panel.
- If **Hold-to-fire** is enabled, keep a physical finger inside the trigger area.

### Q: Inference is slow or stutters

- Confirm that the device uses Snapdragon 8 Gen 1 or newer.
- Switch to the INT8 192 model, which is the fastest option.
- Close other applications that are using significant system resources.

### Q: Shizuku connection fails

- Restart Shizuku using wireless debugging.
- Confirm that the wireless-debugging pairing code was entered correctly.
- Restart the phone and try again.

### Q: Touch input does not work in the game

- Check for conflicts with other overlay applications.
- Restart the application's service.

---

## Important notes

- This tool is provided for learning and research purposes only.
- Using it in online games may violate the games' terms of service. Use it at your own risk.
- On first launch, model files are extracted from `assets` to internal storage. This may take several seconds.
- Start the service before beginning a game and stop it after the game to reduce battery usage.

---

## License

GNU GPL v3 — See [LICENSE](LICENSE).
