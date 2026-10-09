#!/usr/bin/env python3
"""Small offline converter smoke test; uses generated media, no game assets."""
import shutil
import subprocess
import sys
import tempfile
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
CONVERTER = ROOT / "tools" / "convert_bink_movies.py"


def run(*args):
    return subprocess.run(args, stdout=subprocess.PIPE, stderr=subprocess.PIPE,
                          text=True, check=True)


def main():
    if not shutil.which("ffmpeg") or not shutil.which("ffprobe"):
        raise SystemExit("ffmpeg and ffprobe required for Bink conversion smoke test")
    with tempfile.TemporaryDirectory(prefix="hce-bink-") as directory:
        root = Path(directory)
        source = root / "owned-input" / "language" / "credits.BIK"
        source.parent.mkdir(parents=True)
        # FFmpeg has no Bink encoder. An AVI with a .BIK extension exercises
        # input probing, transcoding, naming, audio, and output handling,
        # NOT whether a specific proprietary Bink version is decodable.
        source_avi = source.with_suffix(".avi")
        run("ffmpeg", "-hide_banner", "-loglevel", "error", "-y",
            "-f", "lavfi", "-i", "color=c=blue:s=64x64:r=15",
            "-f", "lavfi", "-i", "sine=frequency=440:sample_rate=22050",
            "-shortest", "-t", "0.5", "-c:v", "mpeg4", "-c:a", "pcm_s16le",
            str(source_avi))
        source_avi.rename(source)
        output = root / "android movies"
        call = [sys.executable, str(CONVERTER), str(root / "owned-input"),
                "--output", str(output)]
        result = run(*call)
        movie = output / "credits.mp4"
        assert not (output / "language" / "credits.mp4").exists(), "nested output cannot be resolved by HaloActivity"
        assert movie.is_file(), result.stdout
        codecs = run("ffprobe", "-v", "error", "-show_entries",
                     "stream=codec_name", "-of", "csv=p=0", str(movie)).stdout.splitlines()
        assert "h264" in codecs, codecs
        assert "aac" in codecs, codecs
        old = movie.stat().st_mtime_ns
        skipped = run(*call)
        assert "SKIP" in skipped.stdout, skipped.stdout
        assert movie.stat().st_mtime_ns == old, "existing output was overwritten"
        run(*call, "--overwrite")
        assert movie.is_file(), "overwrite deleted converted file"
        # The guest requests a basename, so two extracted directories with
        # the same movie name must not overwrite each other.
        duplicate = root / "owned-input" / "other" / "CREDITS.bik"
        duplicate.parent.mkdir(parents=True)
        shutil.copyfile(source, duplicate)
        collision = subprocess.run(call, capture_output=True, text=True)
        assert collision.returncode != 0, "duplicate basename was accepted"
        assert "duplicate movie name" in collision.stderr, collision.stderr
    print("Android Bink conversion smoke test passed (synthetic AVI input)")


if __name__ == "__main__":
    main()
