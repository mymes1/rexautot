package com.rexauto.port;

import android.content.Intent;
import android.net.Uri;
import android.os.Bundle;
import android.os.ParcelFileDescriptor;

import com.rexauto.port.gamepad.PadSettings;
import com.rexauto.port.gamepad.VirtualPadView;

import org.libsdl.app.SDLActivity;

/** SDL3 activity hosting the recompiled game (SDL is linked statically into libmain.so). */
public class MainActivity extends SDLActivity {
    private VirtualPadView mGamepad;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        if (!GameFiles.hasValidGameRoot(this)) {
            startActivity(new Intent(this, SetupActivity.class));
            finish();
            return;
        }
        applyOrientation();
        super.onCreate(savedInstanceState);
        // Ask the OS for the sustained (thermally stable) clock profile instead of
        // the burst-then-throttle default, and never dim while playing.
        android.view.Window w = getWindow();
        w.addFlags(android.view.WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        if (android.os.Build.VERSION.SDK_INT >= 24) w.setSustainedPerformanceMode(true);
        if (android.os.Build.VERSION.SDK_INT >= 31) {
            android.view.WindowManager.LayoutParams lp = w.getAttributes();
            lp.preferredRefreshRate = 0;
            w.setAttributes(lp);
        }
        if (PadSettings.get(this).enabled()) {
            installGamepad();
            // Some Samsung builds inflate SDL's view hierarchy a frame later
            // than the activity; retry once the decor view has laid out so the
            // touch controls can never silently go missing.
            getWindow().getDecorView().post(this::installGamepad);
        }
        startStartupBanner();
    }

    // ------------------------------------------------------------------
    // Startup banner: shows the last line of logs/startup.txt on screen.
    // A black screen during the first minute is then self-explanatory
    // ("OnInitialize starts now", "entering the message loop", ...) instead
    // of a dead tablet, and it disappears by itself once the game is up.
    // ------------------------------------------------------------------
    private android.widget.TextView mBanner;
    private Thread mBannerThread;

    private void startStartupBanner() {
        android.view.ViewGroup layout;
        try {
            layout = (android.view.ViewGroup) org.libsdl.app.SDLActivity.getContentView();
        } catch (Throwable t) {
            return;
        }
        if (layout == null || mBanner != null) return;
        final android.widget.TextView tv = new android.widget.TextView(this);
        tv.setText("Starting the game...");
        tv.setTextColor(0xFFE8E8E8);
        tv.setTextSize(11);
        tv.setTypeface(android.graphics.Typeface.MONOSPACE);
        tv.setPadding(24, 10, 24, 10);
        tv.setBackgroundColor(0xAA000000);
        tv.setClickable(false);
        tv.setFocusable(false);
        try {
            android.widget.FrameLayout.LayoutParams lp = new android.widget.FrameLayout.LayoutParams(
                    android.view.ViewGroup.LayoutParams.MATCH_PARENT,
                    android.view.ViewGroup.LayoutParams.WRAP_CONTENT);
            lp.gravity = android.view.Gravity.BOTTOM;
            layout.addView(tv, lp);
        } catch (Throwable t) {
            layout.addView(tv, new android.view.ViewGroup.LayoutParams(
                    android.view.ViewGroup.LayoutParams.MATCH_PARENT,
                    android.view.ViewGroup.LayoutParams.WRAP_CONTENT));
        }
        mBanner = tv;

        final java.io.File file = new java.io.File(new java.io.File(getExternalFilesDir(null), "logs"),
                "startup.txt");
        mBannerThread = new Thread(() -> {
            long started = System.currentTimeMillis();
            boolean done = false;
            while (!Thread.currentThread().isInterrupted()) {
                try {
                    Thread.sleep(1200);
                } catch (InterruptedException e) {
                    return;
                }
                final String line = lastLine(file);
                final String text = line.isEmpty()
                        ? "(waiting for the native startup log)"
                        : "Starting: " + line;
                runOnUiThread(() -> {
                    if (mBanner != null) mBanner.setText(text);
                });
                if (line.contains("message loop")) {
                    // The runtime loop is running; give the first frames a
                    // moment and get out of the way.
                    done = true;
                    try { Thread.sleep(4000); } catch (InterruptedException e) { return; }
                    break;
                }
                if (System.currentTimeMillis() - started > 300_000) {
                    done = true;
                    break;
                }
            }
            final boolean gaveUp = !done || System.currentTimeMillis() - started > 300_000;
            runOnUiThread(() -> {
                if (mBanner != null) {
                    ((android.view.ViewGroup) mBanner.getParent()).removeView(mBanner);
                    mBanner = null;
                }
                if (gaveUp) {
                    android.widget.Toast.makeText(this,
                            "Still starting after 5 minutes - open the app again and press Logs, then Share.",
                            android.widget.Toast.LENGTH_LONG).show();
                }
            });
        }, "rex-startup-banner");
        mBannerThread.setDaemon(true);
        mBannerThread.start();
    }

    private static String lastLine(java.io.File f) {
        try (java.io.RandomAccessFile raf = new java.io.RandomAccessFile(f, "r")) {
            long len = raf.length();
            if (len <= 0) return "";
            long from = Math.max(0, len - 2048);
            raf.seek(from);
            byte[] buf = new byte[(int) (len - from)];
            raf.readFully(buf);
            String[] lines = new String(buf, java.nio.charset.StandardCharsets.UTF_8).split("\n");
            for (int i = lines.length - 1; i >= 0; i--) {
                String l = lines[i].trim();
                if (!l.isEmpty()) return l;
            }
        } catch (Throwable ignored) {
        }
        return "";
    }

    @Override
    protected void onDestroy() {
        if (mBannerThread != null) mBannerThread.interrupt();
        super.onDestroy();
    }

    /**
     * Idempotent: creates the overlay once, and afterwards only re-attaches the
     * same instance if SDL rebuilt its layout. A detached overlay still draws
     * nothing but also receives nothing, so this has to run after every resume
     * and after surface re-creation, not just once.
     */
    private void installGamepad() {
        mGamepad = VirtualPadView.install(this, mGamepad);
    }

    @Override
    protected void onResume() {
        super.onResume();
        if (PadSettings.get(this).enabled()) {
            installGamepad();
            getWindow().getDecorView().post(this::installGamepad);
        }
    }

    @Override
    public void onConfigurationChanged(android.content.res.Configuration newConfig) {
        super.onConfigurationChanged(newConfig);
        // Samsung rebuilds the window on a fold/rotate/DeX switch; re-stack.
        getWindow().getDecorView().post(this::installGamepad);
    }

    /** Orientation chosen in the launcher (Graphics dialog); landscape by default. */
    private void applyOrientation() {
        String o = new GraphicsSettings(this).orientation();
        int req;
        switch (o) {
            case "portrait": req = android.content.pm.ActivityInfo.SCREEN_ORIENTATION_PORTRAIT; break;
            case "auto": req = android.content.pm.ActivityInfo.SCREEN_ORIENTATION_FULL_USER; break;
            default: req = android.content.pm.ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE;
        }
        setRequestedOrientation(req);
    }

    @Override
    protected void onPause() {
        if (mGamepad != null) mGamepad.onHostPause();
        super.onPause();
    }

    @Override
    protected String[] getLibraries() {
        return new String[]{"main"};
    }

    public static ParcelFileDescriptor openContentFd(String uri, String mode) {
        SDLActivity self = mSingleton;
        if (self == null || uri == null) return null;
        try {
            return self.getContentResolver().openFileDescriptor(Uri.parse(uri), mode == null ? "r" : mode);
        } catch (Exception e) {
            return null;
        }
    }
}
