# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

## Build & Run

```bash
# Debug build (APK in app/build/outputs/apk/debug/)
./gradlew assembleDebug

# Install on connected device
./gradlew installDebug

# Release build
./gradlew assembleRelease
```

## Tests

```bash
# All unit tests
./gradlew test

# Single test class
./gradlew test --tests "com.example.hubengine.bluetooth.PrinterBridgeTest"

# Single test method
./gradlew test --tests "com.example.hubengine.bluetooth.PrinterBridgeTest.print decodes valid base64 and sends bytes to printer"

# Instrumented tests (requires device/emulator)
./gradlew connectedAndroidTest
```

## Architecture

HubEngine is a **kiosk WebView wrapper** for a PDV (point-of-sale) web system. The single `MainActivity` loads a URL into a fullscreen WebView with immersive sticky mode. On first launch, `SetupFragment` prompts for the URL and saves it to SharedPreferences (`hub_engine_prefs` / `pdv_url`). FAB long-press clears the saved URL and returns to setup.

The app exposes two JavaScript interfaces to the WebView:

| JS Object | Android Class | Purpose |
|---|---|---|
| `Android` | `PrinterBridge.kt` | Receives pre-built ESC/POS bytes as Base64; uses persistent `BluetoothPrinterManager` connection |
| `AndroidPrint` | `PrintBridge.java` | Receives a JSON ticket payload; builds ESC/POS bytes internally and opens its own BT connection per print job |

`PrintBridge.java` is the full-featured path: it accepts `{ logo, company, tickets: [] }` JSON (or a bare ticket array for backwards compatibility), rasterises a Base64 PNG logo using the `GS v 0` ESC/POS raster command, generates a QR code with `GS ( k`, and cuts with `GS V A`. It detects the paired printer by name keywords (`ELEPH`, `PRINT`, `POS`, `58`, `80MM`, etc.) and falls back to the first bonded device. Paper width is hardcoded at `PRINTER_WIDTH_PX = 384` (58 mm).

`PrinterBridge.kt` is the thin path: the web side builds and encodes the ESC/POS bytes, which are decoded here and forwarded through the `PrinterPort` interface. This design enables unit-testing with `FakePrinterPort` without Bluetooth hardware.

### QR scanning (dual mode)

- **Bluetooth HID scanner** — paired as a keyboard; types directly into the focused WebView input, requires no Android code.
- **Camera FAB** — opens `QrScannerActivity` (CameraX + ML Kit, offline), which returns the scanned value via `ActivityResult`. `MainActivity.injectQrIntoWebView()` then fires `input` and `change` events on the active element so Vue/React frameworks detect the change.

### Navigation flow

```
SharedPrefs has URL?
  No  → SetupFragment (URL input)
  Yes → WebView.loadUrl()
          ↓ network error on main frame → OfflineFragment (retry button)
          ↓ PDF URL detected → PdfDownloadHandler → DownloadManager
          ↓ non-http scheme (whatsapp://, tel:, etc.) → system Intent
```

### Key constraints

- `minSdk = 26` — `java.util.Base64` is available; no need for `android.util.Base64` in Kotlin files.
- Bluetooth permissions split by API level: legacy `BLUETOOTH`/`BLUETOOTH_ADMIN` capped at API 30; `BLUETOOTH_CONNECT`/`BLUETOOTH_SCAN` for API 31+. `PrintBridge.java` suppresses `MissingPermission` lint because the permission request is handled in `MainActivity` before the bridge is called.
- `configChanges` in the manifest absorbs orientation/resize without recreating `MainActivity`, preserving WebView state.
- PDFs are saved to `Downloads/HubEngine/` and opened via `FileProvider` (authority `com.example.hubengine.provider`).
