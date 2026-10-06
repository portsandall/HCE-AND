# HCE-AND — Halo: Combat Evolved for Android

[![Android build](https://github.com/portsandall/HCE-AND/actions/workflows/build.yml/badge.svg)](https://github.com/portsandall/HCE-AND/actions/workflows/build.yml)

**HCE-AND** is an Android-focused continuation of the open Halo: Combat Evolved porting work carried through
`halo-ce-universal` and `halo-ce-android`.

The goal of this fork is simple: make the port behave like a real Android game rather than a desktop port that
happens to run on a phone.

That means touch-first controls, Android-friendly storage, controller support, widescreen rendering, mobile
input options, repeatable CI builds, and as little dependence on ADB or a desktop computer as possible.

This repository contains **no Halo game data**. You must supply game data from a copy of Halo: Combat Evolved
that you own.

---

## Why this fork exists

The existing Android work already proved that Halo CE could run extremely well on modern ARM64 Android
hardware. What remained awkward for a phone-only user was everything around the game.

The biggest example was storage. The port traditionally kept maps, saves and configuration under:

```text
/storage/emulated/0/Android/data/com.halo.decomp/files/
```

Modern Android deliberately makes that directory difficult to reach from ordinary file managers. Moving a
`maps/` directory onto a phone could therefore require ADB, another computer, root access or special file
manager workarounds.

HCE-AND moves the user-facing game directory to:

```text
/storage/emulated/0/YAHCEP/
```

The result is a port that can be installed, populated, configured and backed up directly from the Android
device.

The fork also exists to keep the Android-specific work together instead of repeatedly losing it during
upstream merges. Upstream gameplay, networking and renderer improvements are still valuable and are preserved
where compatible, while Android-specific behavior is treated as a first-class target.

---

## Current status

The current Android build is playable.

Recent device testing confirmed:

- game data loaded successfully from `/storage/emulated/0/YAHCEP/maps/`
- no dependency on the app-private `Android/data` folder for normal game data
- launcher, menus and gameplay working
- touch controls working
- shared configuration/save path working
- stable **60 FPS** during the reported test

Performance depends on the device, GPU driver, resolution and game scene. The 60 FPS result is a real device
test, not a universal minimum.

The app targets **64-bit ARM Android** and uses **OpenGL ES 3** with SDL3.

Minimum Android version: **Android 9 / API 28**.

---

## What HCE-AND adds

### User-accessible game storage

Persistent Android data now lives under:

```text
/storage/emulated/0/YAHCEP/
├── maps/
│   └── ui.map
├── save/
├── config.toml
├── debug.txt
├── init.txt
├── hardware_id.txt
└── join_link.txt
```

The important path is:

```text
/storage/emulated/0/YAHCEP/maps/ui.map
```

If that file exists and the rest of the required maps are present, the launcher can start the game.

On Android 11 and later the launcher requests **All files access** so the game can use this normal shared
directory. Android 9 and 10 use the older external-storage permission model.

You can now manage the game using an ordinary Android file manager.

ADB remains optional.

### Android touch controls

The Android port includes a multitouch control layer designed around playing Halo on a touchscreen rather than
simulating a single mouse pointer.

Features include:

- simultaneous movement, aiming and actions
- direct swipe aiming
- dual Fire controls
- movement stick
- jump, melee, reload/action and weapon switching
- grenade and grenade-type controls
- flashlight
- crouch and zoom
- D-pad
- pause/back
- camera-mode control
- direct touch interaction with menus
- configurable positions and sizes
- hide/show individual controls
- duplicate controls
- import/export of touch layouts

Physical controllers remain supported and can be used alongside the Android UI.

### Porting options

The game exposes Android-specific options from the game UI.

Current options include:

- touch-overlay layout editing
- control visibility and duplication
- control size
- swipe sensitivity
- gyroscope aiming
- gyro sensitivity
- phone rumble
- FPS counter
- field of view
- complete overlay disable
- startup cheats
- live cheat controls
- camera-mode controls

Touch-layout files are portable and can be exported through Android's document picker.

### Mobile input

The Android layer supports:

- Bluetooth controllers
- USB controllers
- Android touch input
- phone vibration
- gyroscope aiming
- Android Back integration
- multiple physical controllers for multiplayer

### Widescreen and high-resolution UI work

The inherited renderer and UI work supports modern widescreen Android displays instead of assuming the Xbox
640×480 presentation everywhere.

That includes:

- wider 3D view
- centered 4:3 menu presentation where appropriate
- full-width fades and menu backgrounds
- widescreen HUD handling
- high-resolution HUD/text improvements inherited from upstream

### Multiplayer

The inherited networking work includes substantial changes beyond the original Xbox behavior:

- LAN/system-link play
- direct invitation links
- public server-browser work
- player-name handling
- multiplayer scoreboard improvements
- player-slot cleanup
- large-game fixes
- relay fallback work for difficult NAT/mobile-network situations
- split-screen multiplayer work
- split-screen co-op work

Networking is still active development. Both players should use compatible builds.

---

## Installing game data

### Method 1 — Android file manager

1. Install and launch HCE-AND.
2. Grant storage access when Android asks.
3. Open your preferred file manager.
4. Open:

   ```text
   /storage/emulated/0/YAHCEP/
   ```

5. Copy your complete `maps/` directory into it.
6. Confirm this exists:

   ```text
   /storage/emulated/0/YAHCEP/maps/ui.map
   ```

7. Start HCE-AND.

No desktop computer is required.

### Method 2 — import a disc image

The launcher can also import the required maps from an Xbox Halo: Combat Evolved disc image that you own.

Select the image with Android's system file picker and let the launcher extract the maps.

### Method 3 — ADB

ADB is still supported for developers:

```sh
adb push <folder>/. /storage/emulated/0/YAHCEP/
```

---

## Controls

| Control | Action |
| --- | --- |
| Left stick | Move |
| Swipe display | Look |
| Fire | Fire weapon |
| Grenade | Throw grenade |
| A / Jump | Jump / accept |
| B / Melee | Melee / back |
| X / Reload | Reload / action |
| Y / Weapon | Switch weapon |
| Crouch | Crouch |
| Zoom | Zoom |
| Light | Flashlight |
| Gren. type | Switch grenade type |
| D-pad | Menu/game navigation |
| Pause | Pause / Start |
| Back | Controller Back |
| Camera mode | Switch supported camera modes |
| Hide / Touch | Hide or restore touch controls |

The touch layer and the first physical controller are combined for player 1.

---

## Building

### Requirements

You need:

- Python
- Ninja
- CMake
- JDK 17 or newer
- Android SDK, including API 35
- Android NDK
- a Clang build with the `arm64_32` target

The first build downloads required third-party build components such as musl and SDL3.

### Local build

From the repository root:

```sh
python configure.py --release --pgo=off
ninja android_apk
```

The APK is produced under:

```text
port/android/app/build/outputs/apk/debug/app-debug.apk
```

For the CI-style release build:

```sh
python tools/ci_build.py android release
```

Build output is collected under:

```text
dist/halo-android-release/
```

---

## GitHub Actions

The repository includes an Android build workflow.

It runs Android validation and builds debug and release packages. Pull requests can also be validated before
merge.

Artifacts are uploaded by GitHub Actions when the build completes.

The workflow uses a persistent signing key when these repository secrets are configured:

- `ANDROID_KEYSTORE_BASE64`
- `ANDROID_KEYSTORE_PASSWORD`

Do not commit signing keys, original game data, ROM/disc images or private credentials to the repository.

---

## Tests

The project contains regression tests for important Android and shared-engine behavior.

Useful checks include:

```sh
python tools/test_touch_layout.py
python tools/test_porting_actions.py
python tools/test_menu_touch.py
python tools/test_porting_ui.py
python tools/test_startup_cheats_engine.py
python tools/test_multiplayer.py
```

The complete Android build remains the final compile-level check, and real Android hardware remains necessary
for GPU, sensor, touch, controller and performance validation.

---

## How the Android port works

Halo's original data structures contain 32-bit pointers and layouts that cannot simply be rebuilt as ordinary
LP64 Android structures.

The Android port therefore uses an **ILP32 AArch64 guest** inside a normal 64-bit Android host process.

At a high level:

1. Halo game code is compiled for an ILP32 AArch64 target.
2. The guest image is packaged with the Android application.
3. A native ARM64 host reserves the expected low-address memory layout.
4. The host loads the guest image.
5. Host-side bridges provide POSIX, SDL and OpenGL ES functionality.
6. The guest runs with the data layouts expected by the original game.

This lets the project retain the original 32-bit game memory assumptions while running on modern Android
devices that may no longer support 32-bit ARM applications.

See `port/android/README.md` for the detailed implementation notes.

---

## Contribution history and credit

HCE-AND is a continuation of a long chain of work. This fork would not exist without those projects and
contributors.

### cybersecurity / halo-ce-universal

[cybersecurity/halo-ce-universal](https://github.com/cybersecurity/halo-ce-universal) is the primary upstream
codebase from which much of the current engine, platform and gameplay work is inherited.

The history carried into this repository credits **@cybersecurity** with a large amount of active development,
including work such as:

- split-screen co-op
- public internet server-browser functionality
- high-resolution HUD rendering
- PC-style menus
- multiplayer scoreboard and player-management work
- gameplay and multiplayer fixes
- renderer/platform maintenance
- continued integration of the Halo source reconstruction

These are upstream contributions and are not presented as original HCE-AND work.

### theLlamaNet / halo-ce-android

[theLlamaNet/halo-ce-android](https://github.com/theLlamaNet/halo-ce-android) developed and maintained the
Android-focused branch from which HCE-AND directly descends.

The inherited commit history credits **@theLlamaNet** with Android-specific work including:

- improved multitouch controls
- native Android porting menus
- direct menu touch support
- phone rumble
- gyroscope aiming and gyro fixes
- Android input/general settings
- Android camera controls
- HUD-overlay integration
- Android build maintenance
- preservation of relay/join-status changes while merging upstream work
- automated upstream synchronization infrastructure

This work is a major part of why the current port is usable on a phone.

### bfenty

The history credits **@bfenty** with widescreen UI work including widening full-screen UI widget backgrounds
to the full display width, tested on a Retroid Pocket Flip2.

That change prevents full-screen menu/dim backgrounds from stopping at the old centered 640-pixel game area on
widescreen displays.

### thelinkin3000

The history includes a contribution from **@thelinkin3000** fixing unarmed melee behavior in campaign maps
where no multiplayer weapon is available.

That fix was merged upstream through PR #73 and is retained here through the inherited history.

### Earlier Halo reconstruction lineage

The project also descends through:

- [bnunu/halo-1](https://github.com/bnunu/halo-1)
- [punpckhdq/halo](https://github.com/punpckhdq/halo)

Those projects and their contributors provided the earlier reverse-engineering/decompilation foundation on
which later ports were built.

### portsandall / HCE-AND

This fork currently focuses on making the Android port easier to use and maintain as a standalone Android
project.

HCE-AND-specific work includes:

- moving persistent game data to `/storage/emulated/0/YAHCEP/`
- eliminating the normal requirement to manipulate `Android/data`
- allowing map installation and backups with ordinary Android file managers
- Android 9/10 and Android 11+ storage-permission handling
- keeping launcher, native host, startup cheats and updater configuration on one shared storage root
- GitHub Actions validation on the new fork
- maintaining a clean Android-focused integration point for continued upstream work

Where a change came from upstream, its original Git history is intentionally preserved whenever practical.

---

## Contributing

Contributions are welcome.

Useful areas include:

- Android GPU compatibility
- touch/UI polish
- controller mappings
- device-specific testing
- multiplayer testing
- performance profiling
- Android storage compatibility
- Android lifecycle handling
- renderer fixes
- regression tests
- documentation

When reporting a device issue, include:

- device model
- Android version
- SoC/GPU if known
- build/commit
- whether the problem occurs with touch or a controller
- relevant `debug.txt` or logcat output

Please keep copyrighted game assets out of issues, pull requests and build artifacts.

---

## Known limitations

- Bink video playback is not currently available.
- The native engine requires a compatible low-address guest-memory layout.
- 16 KB kernel-page configurations are currently unsupported.
- Some multiplayer paths still need broader real-world testing.
- Performance varies by Android device and GPU driver.
- A successful CI build does not replace testing on real Android hardware.

---

## Legal

This repository does **not** distribute Halo: Combat Evolved game data, maps, disc images or other copyrighted
Microsoft/Bungie assets.

Users are responsible for supplying game data from media they are legally entitled to use.

Halo, Halo: Combat Evolved, Xbox, Microsoft and Bungie are trademarks or properties of their respective
owners. This is an independent community porting project and is not affiliated with or endorsed by Microsoft,
Xbox or Bungie.

See [LICENSE.md](LICENSE.md) and the third-party notices in `port/third_party/` for source-license details.

---

## Project lineage

```text
punpckhdq/halo
      ↓
bnunu/halo-1
      ↓
cybersecurity/halo-ce-universal
      ↓
theLlamaNet/halo-ce-android
      ↓
portsandall/HCE-AND
```

HCE-AND exists to keep that work moving forward on Android while making the port practical to install, use,
modify and back up directly from the device.
