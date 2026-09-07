---
tags:
  - distribution
  - fdroid
  - publishing
created: 2026-06-02
status: Draft
---

# F-Droid Publishing & Compliance Guide

This document defines the packaging guidelines, licensing criteria, and metadata configurations required to submit **Slim Launcher** to [F-Droid](https://f-droid.org/), the premier repository for Free and Open Source Software (FOSS) on Android.

Back to **[[index|Main Hub]]**

---

## 🏛️ F-Droid Verification Principles

F-Droid builds all applications directly from source using their build servers. For Slim Launcher to be accepted, it must adhere to strict guidelines:

1. **No Proprietary Dependencies**: All libraries (e.g., UI widgets, image loading, databases) must have free licenses. Google Play Services dependencies must be completely excluded or modularized.
2. **Offline Analytics Policy**: Proprietary tracking kits (like Google Firebase, AppCenter, or Mixpanel) are strictly prohibited. Slim Launcher is offline-first: the `android.permission.INTERNET` permission exists solely for the *opt-in* real-weather feature, which calls the free and open [Open-Meteo](https://open-meteo.com) API (no key, no account, no tracking). No network call is ever made unless the user explicitly enables it in Settings.
3. **Reproducible Builds**: The build system must be structured so that compiling the code locally yields the exact same byte-for-byte binary as the build server.

---

## ⚙️ F-Droid Build Configuration (`io.github.playfoundryhq.slim.yml`)

This YAML metadata file is submitted to the F-Droid metadata repository (`fdroiddata`); its name must match the app's `applicationId`. Slim's id is `io.github.playfoundryhq.slim` — the reverse-DNS of the project's GitHub Pages site (`playfoundryhq.github.io`), which is F-Droid's recommended id form for a project without its own domain. (It was `com.opscalehub.slim` before 1.5.0.)

It instructs F-Droid's build bot on how to compile our APK.

```yaml
Categories:
  - System
License: Apache-2.0
WebSite: https://playfoundryhq.github.io/Slim
SourceCode: https://github.com/PlayFoundryHQ/Slim
IssueTracker: https://github.com/PlayFoundryHQ/Slim/issues

Summary: Minimalist, gesture-driven launcher focused on efficiency.
Description: |-
  Slim Launcher is a lightweight, gesture-centric home screen alternative.
  It lists your favorite apps alongside a dynamic, alphabetical side-scrolling wave index.
  
  Features:
  - Wave Alphabet Scroll: Easy single-handed navigation.
  - Smart Search: Relevance-ranked results with recent-search quick chips.
  - Inline Notifications: Previews directly under your favorite apps.
  - Configurable: Toggle clock, date, weather, gestures and more in Slim Settings.
  - Offline-first: Network is used only for opt-in weather (Open-Meteo).

RepoType: git
Repo: https://github.com/PlayFoundryHQ/Slim.git

Builds:
  - versionName: 1.0.0
    versionCode: 1
    commit: v1.0.0
    subdir: app
    gradle:
      - yes
```

---

## 🛠️ Code Adaptations for F-Droid Compliance

### 1. Removing Proprietary SDKs via Gradle Flavors
If we ever decide to include optional services that rely on closed components (like proprietary location search for weather widgets), we must split the app into `foss` and `play` build flavors in `app/build.gradle`:

```gradle
android {
    flavorDimensions "distribution"
    productFlavors {
        foss {
            dimension "distribution"
            // Exclude services requiring proprietary Play Services
        }
        play {
            dimension "distribution"
            // Include Firebase / Google APIs if needed
        }
    }
}
```

### 2. Internet Permission Policy
The manifest declares `android.permission.INTERNET` for exactly one purpose: the **opt-in** real-weather feature backed by Open-Meteo (a free, open, key-less API). This is F-Droid compliant because:

- No proprietary network service or SDK is involved.
- The default configuration ("Ambient" weather) makes **zero** network calls.
- The user must explicitly enable real weather and type a city in Settings before any request is made.

Clock and date metadata are always fetched locally via system providers. If a future feature ever requires a non-free network service, it must be isolated in a `play` build flavor and excluded from the `foss` flavor.
