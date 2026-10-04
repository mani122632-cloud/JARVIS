# JARVIS Stage 41 — Cinematic activation / exit

NOT compiled or run here — build via GitHub Actions and verify on a device.
Renderer (Arc Reactor design) is untouched.

## 1. Files (replace, package com.jarvis.assistant.core)
app/src/main/java/com/jarvis/assistant/core/CoreAnimationController.kt
app/src/main/java/com/jarvis/assistant/core/JarvisCoreView.kt

## 2. MainActivity
Core is now HIDDEN by default. Add only the calls below at the right moments
(no other changes). If something already calls setCoreVisible(false, false) at
startup, it can stay; it is now redundant.

## 3. Layout
No change. Keep the existing JarvisCoreView. The reactor enters/exits through the
view's own bottom edge, so for a screen-bottom entrance make the view reach the
bottom of the screen (e.g. match_parent height).

## 4-7. Calls
core.showCinematic()                 // rise from bottom, ~550 ms, FastOutSlowIn
core.setState(JarvisState.LISTENING) // restrained cyan activity
core.setState(JarvisState.THINKING)  // scanner + LED pattern
core.setState(JarvisState.SPEAKING)  // follows amplitude
core.setVoiceAmplitude(0f..1f)       // call while SPEAKING; smoothed internally
core.setState(JarvisState.READY)     // optional, before hide
core.hideCinematic()                 // sink fully below, ~620 ms, loop stops
core.setCoreVisible(visible, animated) // convenience

## Behavior
- Reversal (show<->hide mid-flight) continues from the current position; no jump.
- Repeated show()/hide() are no-ops.
- State changes while hidden are applied instantly; position is never reset by state.
- Frame loop sleeps when fully hidden or detached; idle visible runs ~30 fps.
