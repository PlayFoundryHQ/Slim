# 📱 Slim Launcher

[![Build Status](https://github.com/PlayFoundryHQ/Slim/actions/workflows/android-build.yml/badge.svg)](https://github.com/PlayFoundryHQ/Slim/actions)
[![Release](https://img.shields.io/github/v/release/PlayFoundryHQ/Slim?color=6366f1&label=Download)](https://github.com/PlayFoundryHQ/Slim/releases/latest/download/slim-launcher.apk)
[![License](https://img.shields.io/github/license/PlayFoundryHQ/Slim?color=blue)](LICENSE)

**Slim Launcher** is an ultra-minimalist, gesture-based Android launcher designed for speed, focus, and single-handed efficiency. Inspired by Niagara Launcher, it replaces your cluttered app drawer with an elegant, responsive vertical list and a dynamic side-scrolling alphabetical wave gesture.

👉 **View the live landing page at [playfoundryhq.github.io/Slim](https://playfoundryhq.github.io/Slim/)**

📦 **[Download the latest signed APK](https://github.com/PlayFoundryHQ/Slim/releases/latest/download/slim-launcher.apk)** — every successful build on `main` is automatically signed and published to [GitHub Releases](https://github.com/PlayFoundryHQ/Slim/releases).

> **Note on URLs:** GitHub Pages paths are case-sensitive — the address is `/Slim/` (capital S), matching the repository name. The repository itself is reachable at [github.com/PlayFoundryHQ/Slim](https://github.com/PlayFoundryHQ/Slim).

---

## ✨ Features

*   **Wave Alphabet Scroll**: Drag along the screen edge to dynamically expand letters in an interactive wave, scrolling instantly to any application with micro-haptic feedback. Only letters that actually have apps are shown, keeping the index compact.
*   **Floating Search Panel**: Swipe up from the home screen to open a floating search panel with live, relevance-ranked results (prefix matches first, then word matches, then any part of the name) and quick-launch chips for your recently searched apps.
*   **Inline Notifications**: Read notification previews directly under your favorite apps on the home screen.
*   **Home-Screen Widget**: Host one standard Android app widget (Duolingo streak, calendar, clock — anything on your device) in a slot above the app list. The slot sizes itself to each widget's natural shape and rounds it to a consistent card, so widgets of any aspect look right. Add, change, or remove it from Slim Settings.
*   **Configurable Home Screen**: Toggle the clock, date, and weather independently; choose 12/24-hour time — all from Slim Settings.
*   **Weather, your way**: Off, *Ambient* (an offline seasonal estimate — no network), or *Real weather* (opt-in, powered by the key-less [Open-Meteo](https://open-meteo.com) API for the city you choose).
*   **Adaptive Readability**: Text colors automatically adapt to light or dark wallpapers, with Material You dynamic accent colors on Android 12+.
*   **Work Profile Support**: Apps from your work profile appear in the list with a WORK badge and badged icon, and launch into the correct profile.
*   **Hide & Rename Apps**: Long-press any app to hide it from the list/search or give it a custom name. Hidden apps are managed from Settings.
*   **Text-Only Mode**: Turn off app icons entirely for the most minimal launcher possible.
*   **Gesture Shortcuts**: Swipe up for search, swipe down for the notification shade — both toggleable.
*   **Backup & Restore**: Export your settings, favorites, hidden apps, and renames to a JSON file; import them on a new device.
*   **Privacy-First**: No analytics, no tracking, no accounts. The launcher works fully offline — the *only* network call it can ever make is the optional Open-Meteo weather fetch, and only if you turn it on.

### Slim Settings

Slim has no persistent settings button cluttering the home screen. To open settings, simply **search for or scroll to the "Slim" app entry and tap it** — the launcher's own list entry opens its settings. From there you can configure:

| Section | Options |
|---|---|
| Home Screen | Show/hide clock, show/hide date, 12/24-hour format |
| Appearance | Show/hide app icons (text-only mode), background mode, immersive full-screen, manage hidden apps |
| Widget | Add / change / remove one home-screen widget |
| Weather | Off / Ambient (offline) / Real (Open-Meteo), city, °C/°F |
| Search | Remember recent searches, clear history |
| Gestures | Swipe-up search, swipe-down notification shade |
| Backup & Restore | Export/import settings, favorites, hidden apps, and renames |
| System | Set default launcher, notification access |
| About | Version, who we are, GitHub, website, report a bug, contact |

---

## 🛠️ Project Structure

This repository is structured for Obsidian-based documentation and native Android development:

*   **`app/`**: Android application sources (Kotlin).
    *   `MainActivity.kt`: Home screen, search panel, gestures, adaptive theming.
    *   `SettingsActivity.kt`: Slim Settings screen (toggles, weather, about/contact).
    *   `SlimPreferences.kt`: All user-configurable options (SharedPreferences).
    *   `WeatherService.kt`: Optional Open-Meteo client (geocoding + current weather).
    *   `WaveGestureView.kt`: The wave alphabet index custom view.
    *   `WidgetHostManager.kt`: Hosts and renders the optional home-screen app widget (`AppWidgetHost`).
    *   `AppRepository.kt` / `AppDatabase.kt`: Room-backed app cache and favorites.
*   **`docs/`**: Obsidian knowledge vault detailing the project structure.
    *   [docs/index.md](docs/index.md): Central dashboard for requirements, architecture, and agent guides.
    *   [docs/architecture/system_requirements.md](docs/architecture/system_requirements.md): System permissions and components.
    *   [docs/architecture/ui_ux_layout.md](docs/architecture/ui_ux_layout.md): Mathematical models for the wave scrolling.
    *   [docs/distribution/fdroid_publishing.md](docs/distribution/fdroid_publishing.md): F-Droid FOSS packaging and metadata guidelines.
*   **`website/`**: Sources for the GitHub Pages landing page.
*   **`.github/workflows/`**: Continuous Integration pipelines.
    *   `android-build.yml`: Verifies Kotlin compiling, lints, and builds debug APKs.
    *   `deploy-pages.yml`: Automatically deploys the landing page to GitHub Pages on merges to main.

---

## ⚙️ Building From Source

Prerequisites:
- Android SDK (API 34+)
- JDK 17

Run the following commands in your shell to build and run the debug APK:

```bash
# Clone the repository
git clone https://github.com/PlayFoundryHQ/Slim.git
cd Slim

# Compile the application
./gradlew assembleDebug

# Install on a running emulator/device
adb install app/build/outputs/apk/debug/app-debug.apk
```

---

## 👥 Who We Are

Slim is built in the open by **PlayFoundryHQ** and community contributors. No company backing, no ads, no telemetry — just people who want a calmer phone.

*   🐛 Found a bug? [Open an issue](https://github.com/PlayFoundryHQ/Slim/issues)
*   💡 Have a feature idea? [Start a discussion](https://github.com/PlayFoundryHQ/Slim/issues/new)
*   ✉️ Contact: **opscale.solution@gmail.com**

---

## 📄 License

This project is licensed under the Apache License 2.0. See the `LICENSE` file for details.
