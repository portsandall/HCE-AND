#!/usr/bin/env python3
"""Convert personally supplied Halo Xbox Bink movies into Android-compatible MP4.

This is an asset-preparation utility, NOT an in-game movie player.
Requires ffmpeg with Bink decoding and H.264/AAC encoding support.
"""
import argparse
import shutil
import subprocess
import sys
from pathlib import Path


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("source", type=Path, help="directory containing .bik files")
    parser.add_argument("--output", type=Path, default=Path("movies"),
                        help="output folder (default: ./movies)")
    parser.add_argument("--overwrite", action="store_true")
    parser.add_argument("--crf", type=int, default=21,
                        help="H.264 quality: smaller is better (default: 21)")
    args = parser.parse_args()
    if not 0 <= args.crf <= 51:
        parser.error("--crf must be between 0 and 51")
    if not shutil.which("ffmpeg"):
        parser.error("ffmpeg is required; in Termux use: pkg install ffmpeg")
    if not args.source.is_dir():
        parser.error(f"source directory not found: {args.source}")
    files = sorted(p for p in args.source.rglob("*")
                   if p.is_file() and p.suffix.lower() == ".bik")
    if not files:
        parser.error(f"no .bik files found under {args.source}")
    failed = 0
    for src in files:
        target = (args.output / src.relative_to(args.source)).with_suffix(".mp4")
        if target.exists() and not args.overwrite:
            print(f"SKIP {target} (already exists)")
            continue
        target.parent.mkdir(parents=True, exist_ok=True)
        temp = target.with_name(target.stem + ".partial.mp4")
        temp.unlink(missing_ok=True)
        cmd = [
            "ffmpeg", "-hide_banner", "-loglevel", "error", "-nostdin",
            "-y", "-i", str(src),
            "-map", "0:v:0", "-map", "0:a:0?",
            "-c:v", "libx264", "-preset", "veryfast",
            "-crf", str(args.crf), "-pix_fmt", "yuv420p",
            "-vf", "scale='min(1280,iw)':-2:flags=lanczos",
            "-c:a", "aac", "-b:a", "128k",
            "-movflags", "+faststart", str(temp),
        ]
        print(f"CONVERT {src} -> {target}", flush=True)
        result = subprocess.run(cmd, check=False)
        if result.returncode:
            failed += 1
            temp.unlink(missing_ok=True)
            print(f"FAILED {src} (ffmpeg exit {result.returncode})", file=sys.stderr)
            continue
        temp.replace(target)
    print(f"Finished: {len(files)} source movie(s), {failed} failure(s)")
    return 1 if failed else 0


if __name__ == "__main__":
    sys.exit(main())
