package com.halo.decomp;

import android.app.AlertDialog;
import android.content.Context;
import android.content.Intent;
import android.net.Uri;
import android.widget.Toast;
import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import android.net.wifi.WifiManager;
import android.os.Bundle;
import android.view.Display;
import android.view.WindowManager;
import android.view.ViewGroup;

import org.libsdl.app.SDLActivity;

/**
 * The game: SDL3's activity, running libmain.so (port/android/host), which
 * loads the game image from the APK's assets.
 */
public class HaloActivity extends SDLActivity {
    /** lets system link's broadcasts in over Wi-Fi while the game runs */
    private WifiManager.MulticastLock multicastLock;
    private TouchControls touchControls;
    private static final int EXPORT_LAYOUT = 401, IMPORT_LAYOUT = 402;
    private volatile java.util.concurrent.CountDownLatch movieWait;
    private volatile boolean movieStarted;
    private volatile boolean movieSkipped;
    private volatile boolean moviePlaybackFailed;
    private android.widget.FrameLayout movieLayer;
    private android.widget.VideoView movieVideo;
    private String pendingLayoutExport;

    @Override
    protected String[] getLibraries() {
        return new String[] { "SDL3", "main" };
    }

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        if (savedInstanceState != null)
            pendingLayoutExport = savedInstanceState.getString("pending-layout-export");
        if (mLayout != null) {
            touchControls = new TouchControls(this);
            mLayout.addView(touchControls, new ViewGroup.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        }
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        preferHighestRefreshRate();
        acquireMulticastLock();
        // a new version looked for while the game starts
        Updater.start(this);
    }

    /** SAF lets the player choose a folder and filename without storage permissions. */
    public void chooseLayoutFile(boolean export, String configuration) {
        new AlertDialog.Builder(this).setTitle(export ? "Export layout" : "Import layout")
            .setMessage(export ? "Choose the folder and filename for your touch layout."
                : "Choose an exported Halo touch layout. It will replace your current buttons, sensitivity and General settings.")
            .setNegativeButton("Cancel", null)
            .setPositiveButton(export ? "Choose location" : "Choose file", (dialog, which) -> {
                Intent intent = new Intent(export ? Intent.ACTION_CREATE_DOCUMENT : Intent.ACTION_OPEN_DOCUMENT);
                intent.addCategory(Intent.CATEGORY_OPENABLE);
                intent.setType(export ? "text/plain" : "*/*");
                if (export) {
                    pendingLayoutExport = configuration;
                    intent.putExtra(Intent.EXTRA_TITLE, "halo-touch-layout.halolayout");
                }
                try { startActivityForResult(intent, export ? EXPORT_LAYOUT : IMPORT_LAYOUT); }
                catch (android.content.ActivityNotFoundException e) {
                    pendingLayoutExport = null;
                    Toast.makeText(this, "No document picker is available.", Toast.LENGTH_LONG).show();
                }
            }).show();
    }

    @Override protected void onSaveInstanceState(Bundle state) {
        super.onSaveInstanceState(state);
        state.putString("pending-layout-export", pendingLayoutExport);
    }

    /**
     * Keep video inside the existing SDL Activity. HaloActivity is
     * singleInstance, which forces startActivityForResult into another task
     * and may return RESULT_CANCELED immediately. An in-place overlay also
     * avoids pausing SDL's surface and losing its GL state.
     *
     * This is only called from the guest thread, never from the UI thread.
     */
    public boolean playMovieBlocking(String requestedPath) {
        if (android.os.Looper.myLooper() == android.os.Looper.getMainLooper()
                || requestedPath == null || isFinishing() || isDestroyed())
            return false;
        final java.io.File converted;
        try {
            String p = requestedPath.replace('\\', '/');
            String name = p.substring(p.lastIndexOf('/') + 1);
            converted = MovieActivity.resolveMovie(name);
        } catch (java.io.IOException e) { return false; }
        final java.util.concurrent.CountDownLatch wait = new java.util.concurrent.CountDownLatch(1);
        synchronized (this) {
            if (movieWait != null) return false;
            movieWait = wait;
            movieStarted = false;
            movieSkipped = false;
            moviePlaybackFailed = false;
        }
        runOnUiThread(() -> openMovieOverlay(converted, wait));
        boolean finished = false;
        try { finished = wait.await(20, java.util.concurrent.TimeUnit.MINUTES); }
        catch (InterruptedException e) { Thread.currentThread().interrupt(); }
        finally {
            if (!finished) {
                // Keep this request reserved until its queued UI cleanup runs.
                // Otherwise a timed-out callback can stop a newer movie.
                runOnUiThread(() -> {
                    if (movieWait != wait) return;
                    moviePlaybackFailed = true;
                    try { finishMovieRequest(); }
                    finally {
                        synchronized (this) { if (movieWait == wait) movieWait = null; }
                    }
                });
            } else {
                synchronized (this) { if (movieWait == wait) movieWait = null; }
            }
        }
        // A playable MP4 was shown; completion and user skipping both count
        // as a handled movie. Codec errors fall back to the Bink null path.
        return finished && (movieStarted || movieSkipped) && !moviePlaybackFailed;
    }

    private void openMovieOverlay(java.io.File file, java.util.concurrent.CountDownLatch request) {
        // A lifecycle cancellation can finish before this queued UI task runs.
        // Never open a stale movie over a later request.
        if (movieWait != request) return;
        if (moviePlaybackFailed || isFinishing() || isDestroyed() || mLayout == null) {
            finishMovieRequest();
            return;
        }
        try {
            android.widget.FrameLayout layer = new android.widget.FrameLayout(this);
            layer.setBackgroundColor(android.graphics.Color.BLACK);
            layer.setClickable(true);
            android.widget.VideoView video = new android.widget.VideoView(this);
            video.setZOrderMediaOverlay(true);
            android.widget.FrameLayout.LayoutParams fit = new android.widget.FrameLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT,
                    android.view.Gravity.CENTER);
            layer.addView(video, fit);
            android.view.View.OnTouchListener skip = (view, event) -> {
                if (event.getAction() == android.view.MotionEvent.ACTION_UP) {
                    movieSkipped = true;
                    finishMovieRequest();
                }
                return true;
            };
            layer.setOnTouchListener(skip);
            video.setOnTouchListener(skip);
            movieLayer = layer;
            movieVideo = video;
            mLayout.addView(layer, new ViewGroup.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
            if (touchControls != null) touchControls.stopDeviceInput();
            video.setOnCompletionListener(mp -> finishMovieRequest());
            video.setOnErrorListener((mp, what, extra) -> {
                android.util.Log.w("halo", "MP4 playback failed: " + what + "/" + extra);
                moviePlaybackFailed = true;
                finishMovieRequest();
                return true;
            });
            video.setOnPreparedListener(mp -> {
                if (movieVideo == video && movieLayer != null) {
                    try {
                        video.start();
                        movieStarted = true;
                    } catch (RuntimeException e) {
                        android.util.Log.e("halo", "MP4 playback could not start", e);
                        moviePlaybackFailed = true;
                        finishMovieRequest();
                    }
                }
            });
            video.setVideoURI(Uri.fromFile(file));
        } catch (RuntimeException e) {
            android.util.Log.e("halo", "Could not play converted Bink movie", e);
            moviePlaybackFailed = true;
            finishMovieRequest();
        }
    }

    /** Called on the UI thread for completion, skip, error and destruction. */
    private void finishMovieRequest() {
        if (movieVideo != null) {
            android.widget.VideoView video = movieVideo;
            movieVideo = null;
            video.setOnCompletionListener(null);
            video.setOnErrorListener(null);
            video.setOnPreparedListener(null);
            video.stopPlayback();
        }
        if (movieLayer != null) {
            android.widget.FrameLayout layer = movieLayer;
            movieLayer = null;
            if (layer.getParent() instanceof ViewGroup)
                ((ViewGroup) layer.getParent()).removeView(layer);
        }
        if (touchControls != null && getWindow().getDecorView().hasWindowFocus())
            touchControls.startDeviceInput();
        synchronized (this) {
            if (movieWait != null) movieWait.countDown();
        }
    }

    @Override protected void onActivityResult(int request, int result, Intent data) {
        if (request != EXPORT_LAYOUT && request != IMPORT_LAYOUT) {
            super.onActivityResult(request, result, data); return;
        }
        String exported = pendingLayoutExport; pendingLayoutExport = null;
        if (result != RESULT_OK || data == null || data.getData() == null) return;
        Uri document = data.getData();
        new Thread(() -> {
            try {
                if (request == EXPORT_LAYOUT) {
                    if (exported == null) throw new java.io.IOException("Layout snapshot is unavailable");
                    try (OutputStream output = getContentResolver().openOutputStream(document, "wt")) {
                        if (output == null) throw new java.io.IOException("Cannot open destination");
                        output.write(exported.getBytes(StandardCharsets.UTF_8));
                    }
                    runOnUiThread(() -> Toast.makeText(this, "Layout exported.", Toast.LENGTH_SHORT).show());
                } else {
                    ByteArrayOutputStream contents = new ByteArrayOutputStream();
                    try (InputStream input = getContentResolver().openInputStream(document)) {
                        if (input == null) throw new java.io.IOException("Cannot open layout file");
                        byte[] buffer = new byte[4096]; int size;
                        while ((size = input.read(buffer)) != -1) {
                            if (contents.size()+size > 65536) throw new java.io.IOException("Layout file is too large");
                            contents.write(buffer, 0, size);
                        }
                    }
                    String configuration = new String(contents.toByteArray(), StandardCharsets.UTF_8);
                    // Validate away from the UI thread; apply atomically to the active view.
                    TouchLayout.importConfiguration(configuration);
                    runOnUiThread(() -> {
                        if (isFinishing() || isDestroyed() || touchControls == null) return;
                        try {
                            touchControls.importLayout(configuration);
                            Toast.makeText(this, "Layout imported and saved.", Toast.LENGTH_SHORT).show();
                        } catch (IllegalArgumentException e) { layoutFileError(e); }
                    });
                }
            } catch (Exception e) { runOnUiThread(() -> layoutFileError(e)); }
        }, "halo-touch-layout-file").start();
    }

    private void layoutFileError(Exception error) {
        if (isFinishing() || isDestroyed()) return;
        new AlertDialog.Builder(this).setTitle("Layout file")
            .setMessage("Could not complete the operation: "+error.getMessage())
            .setPositiveButton("OK", null).show();
    }

    @Override public void onBackPressed() {
        // A movie can be pending before its overlay is attached to mLayout.
        // Treat Back as a skip in that window instead of sending it to the game.
        if (movieWait != null) {
            movieSkipped = true;
            finishMovieRequest();
            return;
        }
        if (touchControls != null && touchControls.menuBack()) return;
        super.onBackPressed();
    }

    @Override protected void onResume() {
        super.onResume();
        // Focus/resume must not restart gameplay input over the movie overlay.
        if (touchControls != null && movieWait == null
                && getWindow().getDecorView().hasWindowFocus())
            touchControls.startDeviceInput();
    }

    @Override
    protected void onPause() {
        // Backgrounding is not successful movie completion or an intentional skip.
        // Cancel even if the overlay has not yet been created on the UI thread.
        if (movieWait != null) {
            moviePlaybackFailed = true;
            finishMovieRequest();
        }
        if (touchControls != null) touchControls.stopDeviceInput();
        super.onPause();
    }

    @Override
    public void onWindowFocusChanged(boolean hasFocus) {
        if (touchControls != null) {
            if (hasFocus && movieWait == null) touchControls.startDeviceInput();
            else touchControls.stopDeviceInput();
        }
        super.onWindowFocusChanged(hasFocus);
    }

    @Override
    protected void onDestroy() {
        if (movieWait != null) moviePlaybackFailed = true;
        finishMovieRequest();
        if (touchControls != null) touchControls.stopDeviceInput();
        if (multicastLock != null && multicastLock.isHeld())
            multicastLock.release();
        multicastLock = null;
        super.onDestroy();
    }

    /**
     * Many phones drop the Wi-Fi's broadcast and multicast datagrams to
     * save power unless an app holds this: without it they would not see
     * system link games on the local network, nor be seen hosting one.
     */
    private void acquireMulticastLock() {
        try {
            WifiManager wifi = (WifiManager) getApplicationContext().getSystemService(Context.WIFI_SERVICE);
            if (wifi == null)
                return;
            multicastLock = wifi.createMulticastLock("halo-system-link");
            multicastLock.setReferenceCounted(false);
            multicastLock.acquire();
        } catch (RuntimeException e) {
            // (no Wi-Fi, or not allowed: the local network may miss games)
            multicastLock = null;
        }
    }

    /**
     * The game draws a frame at every display refresh, between its 30 Hz
     * ticks (port/shared/game/render_interpolation.c); Android otherwise
     * often keeps an app at 60 Hz on a faster display.
     */
    private void preferHighestRefreshRate() {
        Display display = getWindowManager().getDefaultDisplay();
        Display.Mode current = display.getMode();
        Display.Mode best = current;

        for (Display.Mode mode : display.getSupportedModes()) {
            if (mode.getPhysicalWidth() == current.getPhysicalWidth() &&
                mode.getPhysicalHeight() == current.getPhysicalHeight() &&
                mode.getRefreshRate() > best.getRefreshRate()) {
                best = mode;
            }
        }
        WindowManager.LayoutParams attributes = getWindow().getAttributes();
        attributes.preferredDisplayModeId = best.getModeId();
        getWindow().setAttributes(attributes);
    }
}
