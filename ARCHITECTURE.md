# DeskPad — Architecture

This document explains how DeskPad is built, for contributors and the curious. For install/usage,
see [README.md](README.md).

## The core idea

DeskPad runs as a normal foreground app on the phone while driving a **different display** — the
external monitor's Desktop-mode session. It needs a way to move a cursor, tap, drag, scroll, and
edit text on that other display without adb, root, or Shizuku.

The answer is an **`AccessibilityService`**. Once the user enables it in Settings, it can:

- **dispatch touch gestures to a specific display** — `GestureDescription.Builder.setDisplayId()`
  (API 30+), the mechanism behind tap / drag / scroll;
- **draw an overlay** on that display — a `TYPE_ACCESSIBILITY_OVERLAY` window renders the cursor;
- **read and edit on-screen nodes** — `getWindowsOnAllDisplays()` plus `AccessibilityNodeInfo`
  actions handle the keyboard, finding the field you clicked and setting its text.

This permission persists across reboots and needs no per-boot setup — the property that ruled out
the earlier Shizuku prototype.

## Layout: a pure core with a thin Android shell

The code splits along a hard boundary so the interesting logic is testable without a device.

```
com.example.deskpad
├── core/                     ← pure Kotlin. NO android.* imports. 33 JUnit tests.
│   ├── Gestures.kt           PadFrame, PadPhase, GestureAction, GestureConfig, GestureRecognizer
│   ├── GestureRecognizer     touch-frame stream → semantic GestureActions
│   ├── InputPipeline.kt      GestureAction → InputSink calls, gated by the lock
│   ├── InputSink.kt          the interface the pipeline drives (implemented by the Android layer)
│   ├── CursorController.kt   cursor position/sensitivity per display, clamped to bounds
│   ├── LockController.kt     the freeze-all-input safety gate
│   ├── DisplayTargetResolver resolves which display is the external one
│   ├── KeyMapper.kt          character/modifier mapping helpers
│   └── Geometry.kt           Point, Bounds
│
└── (Android layer)           ← the only place android.* lives
    ├── DeskPadA11yService.kt  the hub: owns the pipeline, tracks the display, does the keyboard
    ├── AccessibilityInputSink actually dispatches gestures (tap/drag/scroll) to the display
    ├── CursorOverlay.kt       the overlay window on the external display
    ├── CursorView.kt          draws the cursor
    ├── TouchpadView.kt        captures phone touches → PadFrames
    ├── MainActivity.kt        UI: full-bleed trackpad, on-screen keyboard, lock button
    └── DeskPadIme.kt          a bundled IME (not on the main path; see "Keyboard")
```

Everything in `core/` is framework-free, so `./gradlew testDebugUnitTest` exercises the gesture
recognition, cursor math, lock gating, and pipeline routing with plain JUnit — no emulator, no
Robolectric.

## Data flow

One finger stroke on the phone becomes an action on the desktop through a short pipeline:

```
TouchpadView          GestureRecognizer        InputPipeline           AccessibilityInputSink
(MotionEvent)   ─▶    (PadFrame stream)   ─▶   (GestureAction)   ─▶    (dispatchGesture to displayId)
                       recognizes tap/                gated by                cursor overlay +
                       drag/scroll/click              LockController          touch gestures
```

1. **`TouchpadView`** turns each `MotionEvent` into a framework-free `PadFrame`
   (phase, pointer count, x, y, time).
2. **`GestureRecognizer`** consumes the frame stream and emits semantic `GestureAction`s:
   `Move`, `LeftClick`, `RightClick`, `Scroll`, `DragStart/DragMove/DragEnd`.
3. **`InputPipeline`** translates each action into `InputSink` calls, advancing the
   `CursorController` and **gating everything through `LockController`** (locked ⇒ no-op).
4. **`AccessibilityInputSink`** (the sink) performs the real work: it repositions the
   `CursorOverlay` and dispatches touch gestures to the external `displayId`.

The keyboard is a parallel path: `MainActivity`'s on-screen keys call methods on
`DeskPadA11yService`, which edits the clicked desktop field directly (see below).

## Threading

Everything runs on the **main thread**. Touch events and `DisplayManager` callbacks are delivered
there, and `AccessibilityService.dispatchGesture` expects to be called there, so `CursorController`
and the sink's gesture state are only ever touched from one thread — no locks. Gesture completion
callbacks (`GestureResultCallback`) also fire on the main thread, which is what lets the scroll and
drag state machines below stay lock-free.

## The load-bearing techniques

These are the non-obvious parts that make DeskPad work on a second display. Each was validated
against a logging test surface on a real secondary display.

### Keyboard: coordinate-based editing, no focus

While DeskPad is foreground on the phone, **nothing on the desktop is input-focused** —
`findFocus(FOCUS_INPUT)` returns null. So typing can't rely on focus. Instead:

- The field is located by the **coordinates of your last click**: `getWindowsOnAllDisplays()`
  (not `getWindows()`, which only sees the default display) → the deepest node whose
  `getBoundsInScreen()` contains the point and which `isEditable`.
- Text is written with `ACTION_SET_TEXT` + `ACTION_SET_SELECTION`. Because `SET_TEXT` is
  asynchronous, re-reading the node every keystroke drops characters, so the service keeps a local
  `StringBuilder` mirror of the field's text, synced when you click the field.
- On-screen keys are `Button`s (they never take input focus), so clicking them doesn't steal the
  desktop field's selection. The keyboard layout mirrors Gboard (letters / `?123` / `=\<` pages).

### Enter that actually submits

`ACTION_IME_ENTER` returns `true` even on an unfocused field while doing nothing — a false positive.
So Enter uses two paths (validated with an editor-action test field):

1. If a **Send/Search/Go button sits on the field's row** (chat, search), click it — reliable and
   needs no focus.
2. Otherwise (e.g. a browser address bar) **request `ACTION_FOCUS`, wait for the IME to bind, then
   fire the editor action.** Desktop mode's per-display focus lets the field focus and deliver its
   real Go/Search/Send action.

### Live drag via continued gestures

Dispatching a drag as a single stroke on release makes the dragged item lurch at the end. Instead
the sink uses a **continued gesture**: an initial `StrokeDescription(..., willContinue = true)`
presses down and stays down; each `continueStroke()` (which must start exactly where the previous
ended) moves toward the current finger position; exactly one segment is in flight at a time (the
next is dispatched from the previous one's completion callback); a held-still finger gets a
zero-drift hold segment to keep the touch alive; release dispatches a final `willContinue = false`
segment. The result is a touch that tracks the finger in real time.

### Scroll without overlap

Firing a swipe per touch frame makes gestures overlap and race (inconsistent, unresponsive). The
sink instead **accumulates scroll deltas and keeps one swipe in flight**, draining whatever
accumulated when the swipe completes — smooth momentum, no overlap.

### The cursor

There is no OS mouse pointer on the desktop, so DeskPad draws its own. `CursorOverlay` attaches a
`TYPE_ACCESSIBILITY_OVERLAY` window to the external display (via `createDisplayContext`) and
`CursorView` paints the pointer. This needs no "draw over other apps" permission. `CursorController`
keeps the position and must **preserve it across display changes** (don't re-center on every
`onDisplayChanged`).

## Safety: the lock

`LockController` is a single boolean gate that `InputPipeline` checks before dispatching anything;
the keyboard methods check it too. When locked, no gesture or keystroke reaches the desktop, and
`MainActivity` shows a "locked" overlay. Unlock is a deliberate **press-and-hold** so an accidental
tap can't re-enable input.

## About `DeskPadIme`

The repo includes a small `InputMethodService`. It is **not** on the main typing path — typing goes
through the accessibility service as described above, so users don't enable a keyboard. The IME is
kept as a fallback/experiment for a future path (an IME bound to the desktop field could send real
key events); it is inert unless explicitly selected as the system keyboard.

## Permissions summary

- **AccessibilityService** — the only meaningful permission; scoped to `canPerformGestures`,
  `canRetrieveWindowContent`, and `flagRetrieveInteractiveWindows`, observing **no** accessibility
  events.
- **No** internet, foreground service, notifications, `SYSTEM_ALERT_WINDOW`, root, or Shizuku.
- The service and IME exports are gated by their system-only `BIND_*` signature permissions.
