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
        # Regression: odd-sized source frames must become even-sized H.264
        # YUV420P frames. Use a localized filename and no audio to exercise
        # the optional audio mapping without including any game assets.
        odd_avi = root / "owned-input" / "intro_fr.avi"
        run("ffmpeg", "-hide_banner", "-loglevel", "error", "-y",
            "-f", "lavfi", "-i", "color=c=blue:s=65x63:r=15",
            "-frames:v", "3", "-c:v", "mpeg4", str(odd_avi))
        odd_avi.rename(odd_avi.with_suffix(".BIK"))
        run(*call)
        odd_mp4 = output / "intro_fr.mp4"
        assert odd_mp4.is_file(), "localized odd-sized movie was not converted"
        dimensions = run("ffprobe", "-v", "error", "-select_streams", "v:0",
                         "-show_entries", "stream=width,height,pix_fmt",
                         "-of", "csv=p=0", str(odd_mp4)).stdout.strip().split(",")
        assert len(dimensions) == 3, dimensions
        width, height = map(int, dimensions[:2])
        assert width > 0 and height > 0 and width % 2 == height % 2 == 0, dimensions
        assert dimensions[2] == "yuv420p", dimensions
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
