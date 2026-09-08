# Slim Launcher — Claude Code Guide

This file is loaded automatically by Claude Code. Read it before touching any code.

---

## Project overview

Slim is a minimalist Android launcher (home-replacement app). It is the primary system entry point on the device — every app-switch, screen-wake, and Back gesture goes through it. This makes stability and focus-chain correctness far more critical than in a normal app.

Key files:
- `app/src/main/java/io/github/playfoundryhq/slim/MainActivity.kt` — single Activity; owns the full UI, lifecycle, and window management
- `app/src/main/java/io/github/playfoundryhq/slim/WidgetHostManager.kt` — hosts the home-screen widget strip (1–5, swiped via `WidgetPagerView`)
- `app/src/main/java/io/github/playfoundryhq/slim/AppRepository.kt` — app list; refreshes via `LauncherApps`
- `app/src/main/java/io/github/playfoundryhq/slim/SlimPreferences.kt` — typed SharedPreferences wrapper
- `app/src/main/java/io/github/playfoundryhq/slim/WaveGestureView.kt` — custom alphabet-scrubber view
- `docs/architecture/` — human + agent architecture docs; keep in sync with code changes

---

## ANR-safety rules (mandatory)

Slim suffered recurring `"Input dispatching timed out (Application does not have a focused window)"` ANRs — not slow-thread ANRs, but InputFlinger focus-token races triggered by window relayouts during focus acquisition. The following rules were established to prevent them. **Do not regress any of these.**

### 1. Guard `addFlags` / `clearFlags`
`window.addFlags()` and `window.clearFlags()` always trigger a `relayoutWindow()` Binder call even when the flag bit is already in the right state. If that relayout fires while WM is assigning an InputFlinger focus token, the token becomes stale.

```kotlin
// Always do this before mutating a window flag
val hasFlag = (window.attributes.flags and FLAG_SHOW_WALLPAPER) != 0
if (hasFlag == show) return
```

### 2. Never recreate / re-parent `AppWidgetHostView` on every `onResume`
`WidgetHostManager.render()` tracks `renderedWidgetIds` (the ordered list) and exits early when it hasn't changed. Removing and re-adding a host view tears down any embedded surfaces the widget holds, opening an InputFlinger focus gap.

If you change widget rendering logic, preserve the `if (live == renderedWidgetIds) return` guard. The multi-widget strip is a hand-rolled snap-scroller (`WidgetPagerView`) precisely so every `AppWidgetHostView` stays attached to one `LinearLayout` for its whole life — do **not** swap it for `ViewPager2`/`RecyclerView`, which recycle and re-parent pages.

### 3. Dismiss the IME in `onPause`
`MainActivity.onPause()` calls `imm.hideSoftInputFromWindow` + `currentFocus?.clearFocus()`. This ensures the IME never holds a live `InputConnection` across a focus transition (screen-off, app-switch), which caused a separate class of the same ANR when the keyboard had been open.

Do not remove this block. Do not move it to `onStop` — by then the window is already gone.

### 4. No Binder IPC on the main thread in lifecycle callbacks
`PackageManager`, `RoleManager`, and similar system-service calls can stall for hundreds of milliseconds. They must run on `Dispatchers.IO`. See `maybePromptDefaultLauncher()` for the canonical pattern.

### 5. Never launch an Activity/dialog from Slim without holding window focus
The default-Home role chooser (`maybePromptDefaultLauncher()`) is the sharp edge here. Launching *any* activity while Slim's window is mid focus-transition — the first frame after an OEM freezer (OxygenOS/ColorOS "Hans") thaws the process — races the InputFlinger focus-token handoff and produces the "no focused window" ANR **with the main thread idle**. Rules that must not regress:
- The chooser is driven from `onWindowFocusChanged(hasFocus=true)` + a `hasWindowFocus()` recheck on the main thread before `launch()`, **never** `onResume`.
- It fires **once, ever** — guarded by `prefs.defaultLauncherPromptShown` (persisted, survives process death) as well as the in-memory `defaultHomePrompted`.
- `DefaultLauncherHelper.isDefaultHome()` trusts `RoleManager.isRoleHeld(ROLE_HOME)` first; `resolveActivity(HOME)` alone gives false negatives right after a thaw. A single stale signal must never trigger the prompt.

---

## Other architectural invariants

- **`onResume` is called on every home press** — it must be cheap. Avoid allocations, heavy queries, or synchronous I/O there.
- **Back is always consumed at home state** — `OnBackPressedCallback` is registered and enabled permanently. Letting Back finish the Activity causes OEM ROMs (notably ColorOS) to fall back to the stock launcher silently.
- **Widget binding uses the system picker** — Slim does not hold `BIND_APPWIDGET`. The picker binds on the user's behalf. Do not add `BIND_APPWIDGET` to the manifest.
- **`INTERNET` permission is opt-in only** — used exclusively by the real-weather path. It must never be called on a cold start or without explicit user opt-in.
- **Home Activity is never finished** — `finish()` must never be called from `MainActivity`. The process can be killed by the system, but the Activity itself must stay alive.
- **`applicationId` is `io.github.playfoundryhq.slim`** — renamed from `com.opscalehub.slim` in 1.5.0 (the PlayFoundryHQ move). It matches the `namespace`, the Kotlin package, and the F-Droid metadata filename; keep all four in lockstep. Changing it again produces a different app with no in-place upgrade path, so don't.
- **The launch theme starts opaque black** — `Theme.Slim` sets an opaque `windowBackground` (`@color/bg_dark`) and is **not** `windowIsTranslucent`. The default background mode is solid black; `applyBackgroundMode()` opts back into the wallpaper at runtime (via `FLAG_SHOW_WALLPAPER`) for the transparent/dimmed modes. Do not restore `windowIsTranslucent` / `windowShowWallpaper` in the theme — a translucent activity gets no starting window, which reintroduced the cold-start wallpaper flash.
- **`onNewIntent` resets to a clean home** — `MainActivity` is `singleTask`, so a Home press while Slim is already foreground arrives via `onNewIntent`, not `onCreate`. It must dismiss the focus screen, close search, exit alphabet mode, and scroll the list to the top.
- **No lock screen; the focus screen is a gesture, not a wake hook** — Slim never runs over the keyguard and never weakens Android's secure lock. The "focus screen" (Settings → Appearance, opt-in, off by default) is only a black clock veil (`R.id.focusVeil`) drawn inside `MainActivity`'s own window, raised by long-pressing `txtClock` and dismissed by tap / swipe-up. It is deliberately **not** tied to screen-off / unlock — a launcher can't reliably paint at wake time (the OS may have killed it) and can't draw over the secure keyguard. No new Activity, no window flags, no `FLAG_SHOW_WHEN_LOCKED`, no service, no new permission.

---

## Docs

Architecture docs live in `docs/architecture/`. When you change behaviour that is documented there, update the relevant doc in the same commit/session. The two most relevant files:

- `docs/architecture/system_requirements.md` — permissions, component architecture, ANR stability rules
- `docs/architecture/settings_and_features.md` — all user-facing features including widget hosting details
