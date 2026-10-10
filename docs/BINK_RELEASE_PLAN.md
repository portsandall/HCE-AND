# HCE-AND: Bink movies to a working Android release

**Objective:** Halo CE Android plays its original intro, attract, UI-triggered, localized and credits movies, with synchronized sound, correct endings and a usable skip function. Users should not have to modify their maps. Use the shortest reliable implementation path; do not start a second decoder stack unless the MP4 bridge cannot meet the tests.

## Current evidence (2026-10-09)

- Android **build #9 passed** for commit `927b753`: native ARM64/ILP32 game, Java debug/release, and uploaded APK archives.
- That build does **not** prove that a video plays correctly on a device.
- Implemented: offline Bink→H.264/AAC converter; original movie request interception in `source/bink/bink_playback.c`; `host_movie_play` import/JNI; movie resolver and Android player.
- The first player used `startActivityForResult` from the game's `singleInstance` Activity. This has a task/result-routing incompatibility. The feature branch now draws video in an overlay in the existing SDL Activity instead, pending a new build and device test.
- Synthetic video/audio converter smoke test now runs in CI. **An AVI renamed to .BIK does not test Bink decoding.** A real, user-supplied Bink file is required to confirm FFmpeg's decoder handles the game's content.

## Strict milestones / gates

### M1: Verified APK build (automated)

- The latest branch must pass debug **and** release APK compilation.
- Inspect actual first failure in GitHub Actions, fix smallest root cause, run again.
- Keep builds repeatable and retain downloadable APK artifacts.
- No release tag before this gate.

### M2: Source and conversion correctness (automated + private asset test)

- Verify `source/interface/attract_mode.c` and `source/interface/ui_widget.c` movie requests map to converted filenames, including localized variants.
- Ensure `tools/convert_bink_movies.py` converts a real Halo Bink sample into H.264/AAC and gives meaningful failure when decode is impossible.
- Ensure empty or absent `YAHCEP/movies` does not break menu/gameplay.
- Confirm converter handles mixed-case extensions, audio-present and audio-absent input, and reruns without unnecessary recompression.
- Never commit game assets; local/private test material only.

### M3: On-device playback (hard release gate)

Test a signed/debug APK and user-converted movie(s) on ARM64 Android. For each:

| Scenario | Pass criterion |
| --- | --- |
| Intro | Correct movie shown full screen; transitions to Halo correctly |
| Attract mode | Timed playback starts, finishes and returns to menu |
| Credits/outro | Credits finish or skip and return to expected UI |
| Menu/other Bink call | Correct source video loaded (not just a hard-coded intro) |
| Skip | Android Back and tap dismiss immediately without a stuck game |
| Audio | Correct movie audio, no competing stuck background music |
| Orientation | Landscape and resumed focus, no permanent black screen |
| Missing/corrupt MP4 | Game still works, no crash or indefinite wait |
| Exit/background | No lingering movie decoder or blocked game thread |

Resolve any failures in the existing feature branch and rerun CI. Preserve Android's 64-bit host / 32-bit ILP32 guest boundary. Do not claim this gate passed based on CI alone.

### M4: Installation UX and coverage

- Make it clear how to extract owned Xbox `.bik` movies and convert them on Android (Termux) or a computer. Avoid re-downloading game assets.
- If actual device playback works but asset preparation is painful, integrate optional Bink extraction/asset import into the existing XISO import flow. Do not silently increase required storage for people who do not need movies.
- Optimize MP4 size/quality and validate smooth playback on a mid-range Android device.
- Consider in-app/direct Bink decoding only if the conversion requirement blocks functional coverage; FFmpeg Bink demuxing/decoding remains the fallback implementation path.

### M5: Release

- GitHub Actions debug/release artifacts pass.
- Real-device M3 test evidence is recorded.
- README lists the movie installation path and tested formats.
- Merge PR #2 after the gates, tag a release, and provide APK/download/install/rollback instructions.

## Iteration rule

Each work cycle: **read current head and CI → identify one verifiable blocker → fix in code/test/production docs → let Actions build → inspect result → repeat**. No unrelated features, promotional changes, or arbitrary dependency upgrades.

**Scheduled workflow outside GitHub:** hourly watch checks PR #2 and actionable CI failures; daily 19:00 Australia/Brisbane report summarizes verified changes and blockers. GitHub Actions still performs actual build validation whenever the branch changes. Scheduled checks are periodic, not continuous execution.

## Success definition

The feature is finished only when the full movie set available on the user's owned Halo disc plays through the Android port with correctly sequenced audio and input, without crashes, black screens or forced restarts. A compiling APK is necessary but not sufficient.
