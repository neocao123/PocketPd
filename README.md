# PocketPd

**English** | [简体中文](README.zh-CN.md)

A **visual Pure Data editor for Android** — edit *and* run Pure Data patches
fully on your phone or tablet. Built on official libpd
(`io.github.libpd.android:pd-core:1.2.1-rc6`, core **pd 0.51.3**), with a
complete touch-screen patch editor, built-in extension libraries and
peripheral bridges on top.

> **Download**: grab the APK from [Releases](../../releases) (Android 8.0+).

## What PocketPd adds over desktop pd / other Android pd options

**1. Full touch editing — not a read-only player**
- Create and drag objects, draw and delete cords with two fingers, long-press
  for the property OSD — the desktop editing workflow, rebuilt for touch
- **Edit / Run / Lock** modes: silent hot-rebuild while editing, live GUI in
  run mode, performance-locked play
- **Lossless `.pd` round-trip**: patches move both ways with desktop pd
  (objects, cords, GOP, iemgui all preserved)

**2. Touch GUI superset (beyond vanilla)**
- `keyboard` chord keyboard, `pad` drum pads, `button` / `popmenu` — widgets
  designed for playing
- `euclid` Euclidean rhythm generator, `circle5` circle-of-fifths dial —
  music theory as objects
- `graph~` / `spectrograph~` scope and spectrum (graph-on-parent), and
  `display` / `display~` text monitors
- The full vanilla iemgui set (bng/tgl/vsl/vradio/…), redrawn for touch

**3. Built-in libraries, zero configuration**
- Self-developed chip engines: `ym2612~` (Sega Genesis FM), `nes~`, `sid~`,
  `vgm~`, `opl3~` and more, with banks and demos included
- Prebuilt classic libraries: ELSE, cyclone, zexy, iemlib, creb, cxc,
  fftease, mrpeach (osc/midi/binfile), diy2 — no externals setup, no paths
- **653 help patches** plus the official vanilla tutorial patches
  (control / audio series), fully offline

**4. Peripheral bridges**
- **MIDI** (android.media.midi, USB/BLE), **OSC** (UDP, both ways),
  **serial** (USB-serial), **sensors** (accelerometer etc.) — all straight
  into pd

## Known limits (the honest list)

- The engine is pd 0.51.3: **no multichannel signals (mc)**, `[clone]`
  supports `-s` only, some newer ELSE flag syntax (e.g. `initmess verbose`)
  is unavailable — see [PD_VERSIONS.md](PD_VERSIONS.md)
- The published APK is debug-signed; verify versions via
  `aapt dump badging` or system settings

## Documentation

| Doc | Content |
|---|---|
| [README.dev.md](README.dev.md) | Architecture layers, visual design language (development) |
| [PD_VERSIONS.md](PD_VERSIONS.md) | Runtime/library version archaeology (where 0.51 vs 0.55 is actually used) |
| [CHANGELOG.md](CHANGELOG.md) | Per-round version history since 0.6.2 (Chinese) |
| [OBJECT_PORTS.md](OBJECT_PORTS.md) | Object port table |
| [GM_BANK.md](GM_BANK.md) | ym2612~ GM bank notes (Chinese) |

## Building from source

```bash
JAVA_HOME="C:/Android/jdk-17.0.20.1+1" /c/Android/gradle-8.9/bin/gradle assembleDebug
```

- Android SDK required (compileSdk 34); the gradle wrapper is unusable in
  this network environment, so a local gradle 8.9 is invoked directly
- The slibs (bundled libraries) ship prebuilt; `slibs/build_slibs.py`
  reproduces them

## Branches

- `master` — mainline
- `0.51` — the current engine baseline (pd 0.51.3 / libpd 1.2.1-rc6); a
  future engine migration (e.g. pd-for-android master's pd 0.57-dev) would
  open a new branch
