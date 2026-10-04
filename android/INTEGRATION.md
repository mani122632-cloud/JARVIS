# Stage 43.1 — Clean MainActivity (not compiled here)

Extract into the project root, overwriting MainActivity.

## 1. Files changed
- `app/src/main/java/com/jarvis/assistant/MainActivity.kt` — replaced (the only file)

The real MainActivity was not in the upload, so this is a complete replacement written without seeing the old one.
- It assumes package `com.jarvis.assistant`. If your MainActivity sits elsewhere, put this file at that path instead.
- It extends the plain `android.app.Activity`, so it works with any manifest theme.
- If the old MainActivity did anything else (for example runtime permission requests), merge that back in.

## 2. Removed from the old Activity
- The "آماده‌ام" text, the old top "JARVIS" legacy title, and the hidden `JarvisCoreView` (`R.id.jarvisCore`).
- The `AndroidTtsSpeechController` / `JarvisActivationController` fields, their bind/unbind and shutdown, and any `activation.activate()`.
- The layout is built in code, so `activity_main.xml` is no longer used. You may delete it, or leave it.

## 3. Overlay permission
- The screen shows "JARVIS", a thin accent line, "دستیار شخصی شما", and either one button or a status line.
- Permission missing: the button "فعال‌سازی نمایش روی برنامه‌ها" calls `JarvisOverlayService.openOverlayPermissionSettings(this)`. It runs only on a tap.
- Permission granted: the button is hidden and the status "نمایش روی برنامه‌ها فعال است" shows, with a small dot (no emoji).
- `onResume()` re-checks `JarvisOverlayService.hasOverlayPermission(this)`, so the screen updates when you come back from Settings.
- If the settings screen can't be opened, a short toast says so.

## 4. No automatic activation
MainActivity no longer activates JARVIS. It never calls `activation.activate()`, has no listening state, owns no TTS, and does not need to stay open for the overlay.
The Stage 43 temporary test trigger, `JarvisOverlayService.show(ctx, delayMs)`, is unchanged. See the Stage 43 INTEGRATION.md. It is not wired to anything here.

## 5. Arc Reactor untouched
`core/` (including `JarvisCoreRenderer.kt` and `JarvisCoreView`) was not modified or read. The overlay service and window are also unchanged and still host the reactor.
