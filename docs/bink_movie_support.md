# Bink movie support — HCE-AND

**Status: conversion utility implemented; playback integration not implemented.**

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
The converter recursively finds `.bik` (case-insensitive), retains relative
filenames and directories, produces H.264/AAC MP4s with yuv420p, and skips
existing output. Pass `--overwrite` to replace them, or `--crf 18` for
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

Only the conversion tool is completed in this branch; these criteria are
**not yet met**.
