package tv.spelunkr.gecko;

import android.app.Activity;
import android.content.Intent;
import android.net.Uri;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.provider.Settings;
import android.view.KeyEvent;
import android.view.View;
import android.view.WindowManager;

import org.mozilla.geckoview.GeckoResult;
import org.mozilla.geckoview.GeckoRuntime;
import org.mozilla.geckoview.GeckoRuntimeSettings;
import org.mozilla.geckoview.GeckoSession;
import org.mozilla.geckoview.GeckoSessionSettings;
import org.mozilla.geckoview.GeckoView;
import org.mozilla.geckoview.WebRequestError;

import java.io.File;
import java.io.FileOutputStream;

/**
 * Spelunkr in a GeckoView: the site runs on Firefox's engine bundled inside the APK,
 * so it no longer depends on the TV box's (often years-old) system WebView.
 */
public class MainActivity extends Activity {

    // Kiosk build: "https://spelunkr.tv/?skin=modern" (the site's lighter look)
    private static final String START_URL = BuildConfig.START_URL;
    private static final long LONG_PRESS_MS = 1500;
    private static final int SITE_COLOR = 0xFF1C0A57; // the site's own theme color
    private static final long RETRY_MS = 5000;

    // One engine per process; it survives activity re-creation
    private static GeckoRuntime sRuntime;

    private final Handler handler = new Handler(Looper.getMainLooper());
    private GeckoView geckoView;
    private GeckoSession session;
    private boolean canGoBack;
    private boolean fullScreen;
    private String retryUri;
    private boolean backLongPressed;

    private final Runnable retry = () -> {
        if (session != null) session.loadUri(retryUri != null ? retryUri : START_URL);
    };

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);

        geckoView = new GeckoView(this);
        geckoView.coverUntilFirstPaint(SITE_COLOR);
        geckoView.setFocusable(true);
        geckoView.setFocusableInTouchMode(true);
        setContentView(geckoView);

        if (sRuntime == null) {
            GeckoRuntimeSettings.Builder builder = new GeckoRuntimeSettings.Builder()
                    .consoleOutput(false); // logging every page message costs CPU on weak boxes
            String config = writeLiteConfig();
            if (config != null) builder.configFilePath(config);
            sRuntime = GeckoRuntime.create(getApplicationContext(), builder.build());
        }

        openSession();
    }

    /**
     * Engine preferences that lower CPU/GPU load (and heat) on weak TV boxes. Kiosk build only.
     *  - 30 fps rendering instead of 60: old TV video is ~30 fps anyway, half the drawing work
     *  - reduced motion: the page is told to skip decorative animations
     *  - no spare content processes kept warm in the background (saves RAM)
     */
    private String writeLiteConfig() {
        if (!BuildConfig.KIOSK) return null;
        String yaml = "prefs:\n"
                + "  layout.frame_rate: 30\n"
                + "  ui.prefersReducedMotion: 1\n"
                + "  fission.autostart: false\n"
                + "  dom.ipc.processPrelaunch.enabled: false\n";
        try {
            File f = new File(getFilesDir(), "gecko-config.yaml");
            try (FileOutputStream out = new FileOutputStream(f)) {
                out.write(yaml.getBytes("UTF-8"));
            }
            return f.getAbsolutePath();
        } catch (Exception e) {
            return null; // run with engine defaults
        }
    }

    private void openSession() {
        GeckoSessionSettings settings = new GeckoSessionSettings.Builder()
                .userAgentMode(GeckoSessionSettings.USER_AGENT_MODE_MOBILE)
                .viewportMode(GeckoSessionSettings.VIEWPORT_MODE_MOBILE)
                .build();

        session = new GeckoSession(settings);
        session.setNavigationDelegate(navigationDelegate);
        session.setContentDelegate(contentDelegate);
        session.setPermissionDelegate(permissionDelegate);
        session.open(sRuntime);

        geckoView.setSession(session);
        geckoView.requestFocus();
        session.loadUri(START_URL);
    }

    private void restartSession() {
        handler.removeCallbacks(retry);
        if (session != null) {
            geckoView.releaseSession();
            if (session.isOpen()) session.close();
            session = null;
        }
        canGoBack = false;
        fullScreen = false;
        openSession();
    }

    // ---- Navigation: back history and a "NO SIGNAL" screen that retries by itself ----

    private final GeckoSession.NavigationDelegate navigationDelegate = new GeckoSession.NavigationDelegate() {
        @Override
        public void onCanGoBack(GeckoSession s, boolean value) {
            canGoBack = value;
        }

        @Override
        public GeckoResult<String> onLoadError(GeckoSession s, String uri, WebRequestError error) {
            retryUri = (uri != null && uri.startsWith("http")) ? uri : START_URL;
            handler.removeCallbacks(retry);
            handler.postDelayed(retry, RETRY_MS);
            return GeckoResult.fromValue(noSignalPage());
        }
    };

    private static String noSignalPage() {
        String html = "<html><body style=\"margin:0;background:#000;color:#fff;font-family:monospace;"
                + "display:flex;align-items:center;justify-content:center;height:100vh;text-align:center\">"
                + "<div><div style=\"font-size:48px;letter-spacing:4px\">NO SIGNAL</div>"
                + "<div style=\"font-size:20px;margin-top:16px;opacity:.7\">Check the connection. Trying again&hellip;</div>"
                + "</div></body></html>";
        return "data:text/html;charset=utf-8," + Uri.encode(html);
    }

    // ---- Content: video fullscreen, and recovery if the engine's content process dies ----

    private final GeckoSession.ContentDelegate contentDelegate = new GeckoSession.ContentDelegate() {
        @Override
        public void onFullScreen(GeckoSession s, boolean full) {
            fullScreen = full;
            hideSystemBars();
        }

        @Override
        public void onCrash(GeckoSession s) {
            handler.post(MainActivity.this::restartSession);
        }

        @Override
        public void onKill(GeckoSession s) {
            handler.post(MainActivity.this::restartSession);
        }
    };

    // ---- Permissions: channels start playing with sound without a click ----

    private final GeckoSession.PermissionDelegate permissionDelegate = new GeckoSession.PermissionDelegate() {
        @Override
        public GeckoResult<Integer> onContentPermissionRequest(GeckoSession s, ContentPermission perm) {
            if (perm.permission == PERMISSION_AUTOPLAY_AUDIBLE
                    || perm.permission == PERMISSION_AUTOPLAY_INAUDIBLE) {
                return GeckoResult.fromValue(ContentPermission.VALUE_ALLOW);
            }
            return null; // everything else: engine default
        }
    };

    // ---- Remote: BACK leaves fullscreen, then goes back, then exits (kiosk: stays put).
    //      Kiosk: holding BACK for 1.5 s opens the box's Settings, the way out of kiosk mode.
    //      D-pad, OK, media and channel keys go straight to the page. ----

    @Override
    public boolean dispatchKeyEvent(KeyEvent e) {
        if (e.getKeyCode() != KeyEvent.KEYCODE_BACK) return super.dispatchKeyEvent(e);

        if (e.getAction() == KeyEvent.ACTION_DOWN) {
            if (e.getRepeatCount() == 0) {
                backLongPressed = false;
            } else if (BuildConfig.KIOSK && !backLongPressed
                    && e.getEventTime() - e.getDownTime() >= LONG_PRESS_MS) {
                backLongPressed = true;
                openSystemSettings();
            }
        } else if (e.getAction() == KeyEvent.ACTION_UP) {
            if (!backLongPressed && !e.isCanceled()) handleBack();
        }
        return true;
    }

    @Override
    @SuppressWarnings("deprecation")
    public void onBackPressed() {
        handleBack(); // touch / gesture back
    }

    private void handleBack() {
        if (fullScreen && session != null) {
            session.exitFullScreen();
        } else if (canGoBack && session != null) {
            session.goBack();
        } else if (!BuildConfig.KIOSK) {
            finish();
        }
        // Kiosk at the first page: nothing to leave to, stay on Spelunkr
    }

    private void openSystemSettings() {
        try {
            startActivity(new Intent(Settings.ACTION_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
        } catch (Exception ignored) {
            // no Settings app reachable on this box
        }
    }

    // ---- Lifecycle ----

    @Override
    protected void onStart() {
        super.onStart();
        if (session != null) session.setActive(true);
    }

    @Override
    protected void onStop() {
        // Stop playback when the user leaves the app
        if (session != null) session.setActive(false);
        super.onStop();
    }

    @Override
    public void onWindowFocusChanged(boolean hasFocus) {
        super.onWindowFocusChanged(hasFocus);
        if (hasFocus) hideSystemBars();
    }

    @SuppressWarnings("deprecation")
    private void hideSystemBars() {
        getWindow().getDecorView().setSystemUiVisibility(
                View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY
                        | View.SYSTEM_UI_FLAG_FULLSCREEN
                        | View.SYSTEM_UI_FLAG_HIDE_NAVIGATION
                        | View.SYSTEM_UI_FLAG_LAYOUT_STABLE
                        | View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN
                        | View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION);
    }

    @Override
    protected void onDestroy() {
        handler.removeCallbacksAndMessages(null);
        if (isFinishing() && session != null) {
            geckoView.releaseSession();
            session.close();
            session = null;
        }
        super.onDestroy();
    }
}
