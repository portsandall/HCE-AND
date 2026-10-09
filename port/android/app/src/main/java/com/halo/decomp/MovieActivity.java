package com.halo.decomp;

import android.app.Activity;
import android.media.MediaPlayer;
import android.net.Uri;
import android.os.Bundle;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.FrameLayout;
import android.widget.TextView;
import android.widget.VideoView;
import java.io.File;
import java.io.IOException;

/**
 * Android-side playback of MP4 movies converted from an owned Halo disc.
 * A separate Activity keeps decoding out of the 32-bit guest address space.
 * Native game sequencing still needs to call this bridge and consume completion.
 */
public final class MovieActivity extends Activity {
    public static final String EXTRA_MOVIE = "com.halo.decomp.MOVIE";
    private VideoView video;
    private boolean finished;

    /** Reject paths outside the movie directory, including symlinks. */
    public static File resolveMovie(String movie) throws IOException {
        if (movie == null || movie.isEmpty() || movie.indexOf('\0') >= 0)
            throw new IOException("Invalid movie name");
        String name = movie.replace('\\', '/');
        if (name.length() > 240 || name.startsWith("/") || name.indexOf(':') >= 0)
            throw new IOException("Invalid movie path");
        name = name.toLowerCase(java.util.Locale.ROOT);
        if (name.endsWith(".bik")) name = name.substring(0, name.length() - 4) + ".mp4";
        else if (!name.endsWith(".mp4")) name += ".mp4";
        File root = new File(StoragePaths.dataRoot(), "movies").getCanonicalFile();
        File file = new File(root, name).getCanonicalFile();
        if (!file.toPath().startsWith(root.toPath()))
            throw new IOException("Invalid movie path");
        if (file.isFile()) return file;

        // Halo tries other installed languages when the requested movie is
        // absent. The converter flattens each movie to a lowercase basename.
        // Keep exact matches first, then follow the game's language order.
        String stem = name.substring(0, name.length() - 4);
        String base = stem;
        for (String suffix : new String[] {"_de", "_fr", "_es", "_it"}) {
            if (stem.endsWith(suffix)) {
                base = stem.substring(0, stem.length() - suffix.length());
                break;
            }
        }
        if (base.equals("intro") || base.equals("credits")
                || base.equals("attract1") || base.equals("attract2")
                || base.equals("attract3")) {
            for (String suffix : new String[] {"_de", "_fr", "_es", "_it", ""}) {
                File alternative = new File(root, base + suffix + ".mp4").getCanonicalFile();
                if (alternative.toPath().startsWith(root.toPath()) && alternative.isFile())
                    return alternative;
            }
        }
        throw new IOException("Movie file unavailable");
    }

    @Override public void onCreate(Bundle state) {
        super.onCreate(state);
        getWindow().getDecorView().setSystemUiVisibility(
                View.SYSTEM_UI_FLAG_FULLSCREEN | View.SYSTEM_UI_FLAG_HIDE_NAVIGATION |
                View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY);
        final File file;
        try { file = resolveMovie(getIntent().getStringExtra(EXTRA_MOVIE)); }
        catch (IOException e) { setResult(RESULT_CANCELED); finish(); return; }

        FrameLayout frame = new FrameLayout(this);
        frame.setBackgroundColor(0xff000000);
        video = new VideoView(this);
        FrameLayout.LayoutParams layout = new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT, Gravity.CENTER);
        frame.addView(video, layout);
        setContentView(frame);
        video.setOnCompletionListener(mp -> complete(RESULT_OK));
        video.setOnErrorListener((mp, what, extra) -> { complete(RESULT_CANCELED); return true; });
        frame.setOnClickListener(v -> complete(RESULT_CANCELED));
        video.setVideoURI(Uri.fromFile(file));
        video.setOnPreparedListener(mp -> {
            mp.setLooping(getIntent().getBooleanExtra("loop", false));
            video.start();
        });
    }

    private void complete(int result) {
        if (finished) return;
        finished = true;
        if (video != null) video.stopPlayback();
        setResult(result);
        finish();
    }

    @Override public void onBackPressed() { complete(RESULT_CANCELED); }
    @Override protected void onPause() {
        super.onPause();
        if (video != null && video.isPlaying()) video.pause();
    }
    @Override protected void onResume() {
        super.onResume();
        if (video != null && !finished) video.start();
    }
    @Override protected void onDestroy() {
        if (video != null) video.stopPlayback();
        super.onDestroy();
    }
}
