# Stage 43 — System overlay (not compiled here)

Extract into the project root. Not compiled here; build via GitHub Actions.

## 1. Files
New:
- `app/src/main/java/com/jarvis/assistant/overlay/JarvisOverlayService.kt` — foreground service + public API
- `app/src/main/java/com/jarvis/assistant/overlay/JarvisOverlayWindow.kt` — single WindowManager window hosting `JarvisCoreView`
Changed (drop-in, backward compatible):
- `app/src/main/java/com/jarvis/assistant/activation/JarvisActivationController.kt`

Unchanged: JarvisCoreRenderer, JarvisCoreView, speech/*, Gradle, layouts.
The upload held only the Stage 42 files, so the manifest and MainActivity are not in this ZIP. Apply sections 2 and 6 by hand.

## 2. Manifest (add inside `<manifest>` / `<application>`)
```xml
<uses-permission android:name="android.permission.SYSTEM_ALERT_WINDOW" />
<uses-permission android:name="android.permission.FOREGROUND_SERVICE" />
<uses-permission android:name="android.permission.FOREGROUND_SERVICE_SPECIAL_USE" />

<service
    android:name="com.jarvis.assistant.overlay.JarvisOverlayService"
    android:exported="false"
    android:foregroundServiceType="specialUse">
    <property
        android:name="android.app.PROPERTY_SPECIAL_USE_FGS_SUBTYPE"
        android:value="assistant_overlay" />
</service>
```
No RECORD_AUDIO and no microphone type yet. With the wake-word engine, change the type to `microphone` and add RECORD_AUDIO and FOREGROUND_SERVICE_MICROPHONE.

## 3. Overlay permission
`JarvisOverlayService.hasOverlayPermission(ctx)` wraps `Settings.canDrawOverlays`.
`JarvisOverlayService.openOverlayPermissionSettings(ctx)` opens the "Display over other apps" screen. Call it only from a user action; nothing opens it automatically.
Without permission, `show()` / `activate()` log a warning and return false.

## 4. Temporary test trigger
Start the service while MainActivity is visible. Android 12+ blocks foreground-service starts from the background. The delay lets you switch apps first:
```kotlin
// TEMPORARY, e.g. in a debug-only spot in MainActivity
if (JarvisOverlayService.hasOverlayPermission(this)) JarvisOverlayService.show(this, delayMs = 6000)
else JarvisOverlayService.openOverlayPermissionSettings(this)   // first run only, grants permission
```
Press Home and open Instagram within 6 s. `JarvisOverlayService.hide(ctx)` ends the interaction early.

## 5. ActivationController <-> overlay
The service owns `AndroidTtsSpeechController` and `JarvisActivationController(speech, presenter = service)`.
Future triggers (wake word, headset, ...) call `JarvisOverlayService.activate(ctx, Source.WAKE_WORD)`.
If the wake-word engine runs inside this service, call `activation.activate(source)` directly.
Controller flow:
- `activate()` calls `presenter.showOverlay()`, then `showCinematic()`, then LISTENING, then SPEAKING with "بله ارباب.", then LISTENING, then `Listener.onReadyForCommand`.
- `deactivate()` sets READY, calls `hideCinematic()`, then `presenter.hideOverlay()`.
- If the core's cinematic doubles the window fade, construct the controller with `useCinematic = false`.
- Placeholder: the overlay auto-dismisses 8 s after ready (`AUTO_DISMISS_MS`) until the command listener calls `endInteraction()`.
- Legacy Activity mode still works: `JarvisActivationController(speech)` + `bind(core)`.

## 6. Remove the Stage 42 test trigger
In MainActivity delete the temporary `activation.activate()` line. The Stage 42 `speech`/`activation` fields can be removed too; the overlay no longer needs them in the Activity. Nothing activates on launch.

## 7. Expected behavior
- Over any app, a 180–240dp Arc Reactor appears at bottom-centre (about 12% above the bottom edge).
- Entry: fade and scale 0.88 to 1.0 over 420 ms, no bounce or overshoot.
- The overlay is not focusable and not touchable, so the app underneath stays fully usable.
- MainActivity never opens. JARVIS says "بله ارباب." (SPEAKING), then LISTENING.
- Exit: READY, 300 ms fade, window removed, service stops.
- Repeated show calls never create a second window. Repeated hide calls are safe. Rotation re-lays out the window. `onDestroy` removes the view.

Assumption: `JarvisCoreView(context)` has a single-Context constructor.
