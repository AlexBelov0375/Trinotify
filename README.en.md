<div align="center">

<img src="logo.png" alt="Trinotify" width="160">

# Trinotify

**A smart notification and call filter for Android — no root required**

[![License: GPL v3](https://img.shields.io/badge/License-GPLv3-blue.svg)](LICENSE)
[![Platform](https://img.shields.io/badge/platform-Android%2010%2B-brightgreen.svg)](#requirements)
[![Kotlin](https://img.shields.io/badge/Kotlin-2.0.21-purple.svg)](https://kotlinlang.org)
[![Compose](https://img.shields.io/badge/UI-Jetpack%20Compose-orange.svg)](https://developer.android.com/jetpack/compose)
[![Offline](https://img.shields.io/badge/network-never%20used-lightgrey.svg)](#privacy)

[Русский](README.md) · English

</div>

---

## What it is

Trinotify intercepts **every** notification and incoming call, then decides what
happens to each one: let it through with sound, show it silently, or block it
entirely.

Some apps get to wake you with sound, others stay quiet, and some never appear
at all — configured per app, per sender, or delegated to an on-device classifier
that learns from labels you assign yourself.

Everything runs **offline**: the app never makes a single network request.

---

## Features

### Notifications

- **Intercepts all notifications** in real time.
- **Per-app rules**: sound · silent · block · let ML decide.
- **Per-sender rules** matching a substring in the title or body, optionally
  bound to one specific app.
- **Blocks non-dismissible notifications** — including the persistent "daemon"
  notifications other apps normally won't let you remove.
- **Sweeps already-posted notifications** the moment a rule changes.
- **Smart repeat suppression**: progress updates and service re-posts don't
  alert twice.
- **Media players are not treated as notifications** — music, voice messages,
  and video never trigger the filter.

### Calls

- **Blocklist or allowlist** of numbers.
- **International formats**: `+375 29 …`, `8 900 …`, `+998 …` — matched on
  significant digits rather than raw strings.
- **Wildcards** like `+7900*` and short substrings like `8800`.
- An "allow" rule **always beats** a "block" rule.
- Blocked calls are rejected silently.

### Archive and learning

- **Local archive** of every notification and call, with search and filters.
- **Manual labelling** of records, which doubles as the training set.
- **On-device naive Bayes**: the source app is the key feature, plus title and
  body words. Training is instant; the confidence threshold is configurable.
- **Explainability**: every record shows which rule decided its fate
  ("app rule", "ML (80%)", "default action").

### Modes and sound

- **Quick modes right in the notification shade**: normal · vibrate · silent.
- **Scheduled night mode** with a manual override that lasts until morning.
- **Custom ringtones** for calls and notifications, with separate volumes.
- **Alert coalescing**: a burst of notifications produces one sound, not a
  cacophony.
- **Correct audio focus**: other apps' music ducks and is properly restored.

### Privacy

- **No network access** — data never leaves the device.
- **Optional archive encryption** (SQLCipher, key held in hardware Keystore).
- **Optional biometric or PIN lock**, with screenshot protection.
- **Cloud backup disabled** at the manifest level.
- **Export and import** of rules and the training set as JSON.

---

## How it works

Android does not let a third-party app cancel the sound of someone else's
notification: the system plays it at post time, **before** a listener can react.
There is no way around this without root.

So Trinotify takes an honest trade-off:

1. It turns on the system **Do Not Disturb** mode with a policy where
   notifications remain visible but silent.
2. Every foreign notification therefore goes quiet.
3. Whatever passes your rules **Trinotify voices itself**, through its own
   player on the alarm stream.

The visible side effect is a permanent DND icon in the status bar. It cannot be
removed: system mode indicators are drawn by SystemUI and no third-party API
exists for them.

A deep architectural walkthrough — including the MIUI workarounds, the
classifier internals, and the threat model — lives in
**[ARCHITECTURE.md](ARCHITECTURE.md)** (written in Russian).

---

## Requirements

- Android 10 (API 29) or newer
- **No root**
- About 75 MB of free space (the APK bundles ringtones and native crypto
  libraries)

---

## Installation

### Prebuilt APK

1. Download the APK from the [Releases](../../releases) page.
2. Allow installation from unknown sources.
3. Install and open the app.

### Granting permissions

Open **Settings → Permissions** inside the app — it shows the live status of
each access along with a button to grant it:

| Permission | Why it is needed |
|---|---|
| Notification access | reading and dismissing notifications — the core feature |
| Do Not Disturb control | system-level silencing of foreign sounds |
| Call screening role | filtering incoming calls |
| Phone state | playing its own ringtone for incoming calls |
| Unrestricted battery | keeping the background service alive |

**Xiaomi/MIUI users:** additionally enable **Autostart** for Trinotify in the
system app settings, or the vendor shell will kill the background service.

### First-run setup

1. Turn on **Silent mode** (Settings → General) — this enables system DND.
2. Pick the **default action** for apps that have no rule yet.
3. On the **Apps** tab, assign rules to the apps that matter.
4. After a few hours of use, open the **Archive**, label a dozen records and
   press **Retrain model** if you want to use the ML classifier.

---

## Building from source

### Prerequisites

- JDK 17
- Android SDK (compileSdk 34, build-tools 34+)
- Gradle 8.7+ (or Android Studio)

### Steps

```bash
git clone <repository-url>
cd Trinotify

# Point the build at your Android SDK
echo "sdk.dir=/path/to/Android/Sdk" > local.properties

# Debug build
gradle assembleDebug

# Unit tests (24 of them)
gradle testDebugUnitTest
```

The resulting APK lands in `app/build/outputs/apk/debug/`.

### Signed release build

```bash
# Create your own keystore
keytool -genkeypair -v -keystore trinotify.keystore -alias trinotify \
        -keyalg RSA -keysize 2048 -validity 10000

# Provide the passwords
cp keystore.properties.example keystore.properties
# then edit keystore.properties

gradle assembleRelease
```

`keystore.properties`, `*.keystore` and `local.properties` are git-ignored and
**must never be committed**. Without a signing config the build still succeeds —
the APK simply comes out unsigned.

---

## Project layout

```
app/src/main/java/com/trinotify/app/
├── data/       Room entities, DAOs, database, preferences, key vault
├── logic/      Rule engine, cache, modes, export/import
├── ml/         Naive Bayes: pure math core + model wrapper
├── service/    Notification listener, call screener, sound, DND control
└── ui/         Jetpack Compose screens
```

Roughly 3,900 lines of Kotlin in the main source set plus 271 lines of tests.
The full module map is in [ARCHITECTURE.md](ARCHITECTURE.md).

---

## Privacy

By its nature this app sees deeply sensitive material: private conversations,
one-time codes, caller numbers. Therefore:

- **no network calls at all** — no analytics, no telemetry;
- the archive is **excluded from cloud backup** (`allowBackup="false"` plus
  explicit data-extraction rules);
- database encryption is **opt-in**: SQLCipher with the key sealed in the
  hardware-backed Android Keystore, non-extractable even with file access;
- the UI can be locked behind **biometrics or the device PIN**, and the window
  is then marked `FLAG_SECURE` (no Recents preview, no screenshots);
- exported JSON is **not encrypted** — it contains notification text, so store
  it deliberately.

Known limitation: with encryption enabled, the database is unreachable after a
reboot until the screen is unlocked for the first time. During that window
filtering falls back to the default action and nothing is archived.

---

## Limitations

An honest list of what the app cannot do:

- **Status bar icons** (the DND moon) cannot be hidden — they belong to the
  system.
- **Calls from contacts** are never handed to the screening service by Android,
  so they always ring.
- **Emergency calls** are not filtered — and must not be.
- A **sender rule without an app binding** can be triggered by any app posting a
  notification with the same title; the UI warns about this.
- Notifications are blocked **after** they appear, not before — Android offers
  no earlier hook.

---

## Contributors

| | |
|---|---|
| **Black Triangle** | concept, requirements, testing, repository owner |
| **Claude** (Anthropic) | implementation, architecture, documentation |

See [CONTRIBUTORS.md](CONTRIBUTORS.md) for details.

---

## License

Trinotify is released under the **GNU General Public License v3.0** — see
[LICENSE](LICENSE).

```
Copyright (C) 2026 Black Triangle

This program is free software: you can redistribute it and/or modify it under
the terms of the GNU General Public License as published by the Free Software
Foundation, either version 3 of the License, or (at your option) any later
version.

This program is distributed in the hope that it will be useful, but WITHOUT ANY
WARRANTY; without even the implied warranty of MERCHANTABILITY or FITNESS FOR A
PARTICULAR PURPOSE. See the GNU General Public License for more details.
```

The bundled audio files remain the property of their respective copyright
holders and are included for personal use only.
