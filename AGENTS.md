# AGENTS.md

## Project Overview

MobileClicker is an Android automation app (Kotlin, AGP 9.2.1, Kotlin 2.2.10) that plays a mobile game AFK by combining **screen capture → OCR → state machine → tap dispatch**. It targets Honor devices running in freeform (small-window) mode with non-standard SurfaceFlinger output.

## Architecture

- **`ClickerService`** — Core foreground service. Captures screen via MediaProjection, runs ML Kit Chinese OCR each scan cycle, detects game state (`MAP → STAGE_SELECT → GAMEPLAY → SETTLEMENT`), and dispatches taps.
- **`ClickerAccessibilityService`** — Preferred tap/swipe dispatcher via `dispatchGesture`. Falls back to Shizuku `input tap` shell commands.
- **`ModuleSelectActivity`** — Launcher; routes to module-specific activities by target package name.
- **`BubbleHelper`** — Shared overlay bubble (pause/stop) used by both services.

## Key Patterns

- **Shizuku shell exec**: Never call `process.waitFor()` — Honor's Binder IPC throws. Use fire-and-forget (`shellExec`) or thread+join timeout (`readProcessOutput`). See `NOTES.md` §Shizuku.
- **Window detection**: Physical coordinates come from `dumpsys SurfaceFlinger` parsing (`toDisplayTransform` on Honor, `displayFrame` on AOSP). Cached for 30s; fullscreen mode skips re-query entirely.
- **OCR robustness**: Stage ids use decorative fonts; a dedicated binarized-column OCR pass (`preprocessStageColumn` + `runStageColumnOcr`) runs alongside the general OCR for better dash-detection in "1-9".
- **Coordinate system**: All taps use physical screen coordinates. `GameWindow.absX/absY` converts relative positions; jitter (±15px default) is added to every tap.
- **State detection priority order**: `领取` → stage-select markers → map marker → loose settlement fallback → timer/ratio gameplay. Order matters to avoid false settlements.

## Build & Run

```bash
./gradlew assembleDebug                    # Build
adb install -r app/build/outputs/apk/debug/app-debug.apk
```

Requires `local.properties` with `ntfy.topic=<uuid>` for push notifications (optional, blank disables).

## Debugging (Critical — logcat is unavailable)

Honor blocks all third-party logcat output. **Do NOT rely on `android.util.Log` for debugging.**

- **File log**: `adb pull /sdcard/Android/data/com.shawtung.mobileclicker/files/mobileclicker.log`
- **Frame dump**: `adb shell "touch /sdcard/Android/data/com.shawtung.mobileclicker/files/dump.flag"` then pull `frame_raw.png`
- **ntfy.sh push**: Critical events (energy depleted, game exit) push to configured topic.
- **UI callback**: `ClickerService.statusCallback` shows state in the activity.

## Conventions

- Single-module Gradle project (`app/`); no version catalogs — deps declared directly in `app/build.gradle.kts`.
- Min SDK 26, target SDK 34, Java 17.
- No Compose — uses XML layouts (`res/layout/`) + programmatic Views.
- All game-specific constants (coordinates, thresholds, target stage) are `companion object` constants in `ClickerService`.
- Chinese comments and UI strings are normal; the target game is Chinese-language.

