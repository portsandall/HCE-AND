# Bink movie support — HCE-AND

**Status: experimental Android Bink-to-MP4 playback bridge implemented; build and on-device validation pending.**

The existing Halo Android engine skips its original Xbox Bink (.bik) videos.
Simply copying MP4 files to the phone does **not** change that behaviour yet.
This branch provides a reproducible, offline first step, without bundling game
assets or introducing proprietary RAD SDK dependencies.

## Convert your own Xbox game files

Extract the `bink/` directory from a game disc that you own. In Termux:

```sh
pkg install python ffmpeg
python tools/convert_bink_movies.py /storage/emulated/0/Download/halo-bink --output /storage/emulated/0/YAHCEP/movies
```

Or run on a PC or in a CI workspace against user-supplied local assets.
The converter recursively finds `.bik` (case-insensitive), flattens the
movie basenames into `YAHCEP/movies/` using lowercase names (matching the
Android runtime resolver), produces H.264/AAC MP4s with yuv420p, and skips
existing output. Duplicate basenames in different source folders are rejected
rather than silently overwriting a movie. Pass `--overwrite` to replace them, or `--crf 18` for
higher quality. Conversion is not yet connected to the game's UI or runtime.

Do not check converted videos, game files, extracted disc images, or private
assets into this repository.

## Planned Android playback integration

1. Locate the Xbox Bink API bridge and the existing skip path in the shared
   engine and Android host. Retain current skip behavior as a fallback.
2. Resolve the requested movie's original `.bik` filename against
   `/storage/emulated/0/YAHCEP/movies/<name>.mp4`. Canonicalize and validate
   paths and reject traversal.
3. Implement Android host playback with MediaCodec/MediaExtractor or
   Media3/ExoPlayer, presenting the decoded video full-screen and aspect-correct.
   Keep it separate from the 32-bit ILP32 guest: cross the existing host bridge.
4. Transfer playback start, completion, errors and user-skip back into the
   game's movie state machine without blocking SDL/game render threads.
   Restore input focus and GL state; synchronize audio and allow interruption.
5. Verify intro, attract, credits, missing-file fallback, rotation, resume,
   sound, original game sequencing, and multiple Android devices.

**Alternative:** Native Bink decoding via FFmpeg libraries and SDL textures
would avoid preprocessing but increases binary size, maintenance and licensing
work, and needs the same engine movie-state integration. The converted MP4 +
Android decoder approach is simpler for this port.

## Acceptance criteria

- Videos play in the actual game instead of being skipped.
- Original game data without converted videos behaves as it did before.
- Playback honors skip/exit, correct aspect ratio, synchronized audio, and
  returns to the correct game state without input/GPU disruption.
- No copyrighted game video is distributed in APKs or GitHub artifacts.
- Android build and device regression tests pass.

The converter and a private Android MovieActivity are implemented. The
MovieActivity resolves converted files safely, uses Android's video playback,
handles completion/errors/Back, and is registered in the manifest. The native
guest/host bridge does **not yet launch MovieActivity**, so videos will still
be skipped in gameplay; the acceptance criteria are **not yet met**.


## Experimental integrated branch (October 9, 2026)

The Android build now imports `host_movie_play`, intercepts
`bink_playback_start`, resolves movie basenames under `YAHCEP/movies`,
and plays MP4 on Android through a fullscreen activity, then returns control
to the guest. The original native Bink stub remains the missing-video
fallback. Existing game assets are never copied into the APK.

**This path is synchronous:** the guest game thread waits while the Android
activity plays. Device testing is required for SDL lifecycle transitions,
sound restoration, game UI state, credits behavior and video navigation.
On-screen video playback and release quality are not yet verified.
