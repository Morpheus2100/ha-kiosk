package be.mo.hakiosk;

import android.Manifest;
import android.annotation.SuppressLint;
import android.app.Activity;
import android.app.ActivityManager;
import android.content.ComponentName;
import android.app.AlertDialog;
import android.app.admin.DevicePolicyManager;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.graphics.Color;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.provider.Settings;
import android.text.InputType;
import android.view.Gravity;
import android.view.KeyEvent;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewGroup;
import android.view.WindowManager;
import android.webkit.CookieManager;
import android.webkit.RenderProcessGoneDetail;
import android.webkit.WebChromeClient;
import android.webkit.WebResourceError;
import android.webkit.WebResourceRequest;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.widget.Button;
import android.widget.CheckBox;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

/**
 * Toont het Home Assistant-dashboard schermvullend.
 * Na X minuten zonder aanraking of beweging gaat het scherm op zwart (minimale helderheid);
 * beweging voor de camera of een aanraking maakt het weer wakker.
 * Instellingen openen: tik 5x snel in de linkerbovenhoek.
 */
public class MainActivity extends Activity {

    static final String PREFS = "kiosk";
    static final String KEY_URL = "url";
    static final String KEY_ASKED_OVERLAY = "asked_overlay";
    static final String KEY_MOTION = "motion_enabled";
    static final String KEY_DIM_MINUTES = "dim_minutes"; // oude instelling, wordt omgezet
    static final String KEY_DIM_SECONDS = "dim_seconds";
    static final String KEY_SENSITIVITY = "sensitivity";
    static final String KEY_BLOCK_VOLUME = "block_volume";
    static final String KEY_PIN = "pin_app";
    static final String KEY_LAUNCHER = "launcher";
    static final String KEY_RELOAD_ON_WAKE = "reload_on_wake";
    static final String KEY_SCREEN_OFF = "screen_off";
    static final String EXTRA_WOKE = "woke";
    static final String KEY_RELOAD_MINUTES = "reload_minutes";

    private static final int REQ_CAMERA = 1;
    private static final long RETRY_MS = 10_000;
    private static final long IDLE_CHECK_MS = 1_000;
    private static final int CORNER_TAPS_NEEDED = 5;
    private static final long CORNER_TAP_WINDOW_MS = 3_000;

    private final Handler handler = new Handler(Looper.getMainLooper());

    private SharedPreferences prefs;
    private FrameLayout root;
    private WebView web;
    private TextView errorView;
    private View sleepOverlay;
    private AlertDialog settingsDialog;

    private boolean pendingError = false;
    private boolean asleep = false;
    private long lastActivity = System.currentTimeMillis();
    private boolean sleptViaLock = false;
    private long lastLoad = System.currentTimeMillis();
    private int cornerTaps = 0;
    private long firstCornerTap = 0;

    private final Runnable retry = () -> {
        pendingError = false;
        if (this.web != null) this.web.reload();
    };

    private final Runnable idleCheck = new Runnable() {
        @Override
        public void run() {
            int seconds = dimSeconds();
            boolean dialogOpen = settingsDialog != null && settingsDialog.isShowing();
            if (seconds > 0 && !asleep && !dialogOpen
                    && System.currentTimeMillis() - lastActivity > seconds * 1_000L) {
                sleep();
            }
            int reloadMin = prefs.getInt(KEY_RELOAD_MINUTES, 0);
            if (reloadMin > 0 && !dialogOpen && !asleep
                    && System.currentTimeMillis() - lastLoad > reloadMin * 60_000L) {
                reloadPage();
            }
            handler.postDelayed(this, IDLE_CHECK_MS);
        }
    };

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        prefs = getSharedPreferences(PREFS, MODE_PRIVATE);

        // Scherm aan houden (de camera moet blijven kijken) en tonen boven het vergrendelscherm
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON
                | WindowManager.LayoutParams.FLAG_SHOW_WHEN_LOCKED
                | WindowManager.LayoutParams.FLAG_TURN_SCREEN_ON
                | WindowManager.LayoutParams.FLAG_DISMISS_KEYGUARD);
        if (Build.VERSION.SDK_INT >= 27) {
            setShowWhenLocked(true);
            setTurnScreenOn(true);
        }

        root = new FrameLayout(this);
        root.setBackgroundColor(Color.BLACK);
        setContentView(root);

        createWebView();
        createErrorView();
        createSleepOverlay();

        String url = prefs.getString(KEY_URL, null);
        if (url == null || url.isEmpty()) {
            showSettings();
        } else {
            web.loadUrl(url);
            askPermissions();
        }
    }

    // ---------- WebView ----------

    @SuppressLint("SetJavaScriptEnabled")
    private void createWebView() {
        web = new WebView(this);
        web.setBackgroundColor(Color.BLACK);

        WebSettings s = web.getSettings();
        s.setJavaScriptEnabled(true);
        s.setDomStorageEnabled(true); // HA bewaart de login-token in localStorage
        s.setDatabaseEnabled(true);
        s.setMediaPlaybackRequiresUserGesture(false);
        s.setLoadWithOverviewMode(true);
        s.setUseWideViewPort(true);
        s.setMixedContentMode(WebSettings.MIXED_CONTENT_ALWAYS_ALLOW);

        CookieManager cookies = CookieManager.getInstance();
        cookies.setAcceptCookie(true);
        cookies.setAcceptThirdPartyCookies(web, true);

        web.setWebViewClient(new WebViewClient() {
            @Override
            public boolean shouldOverrideUrlLoading(WebView view, WebResourceRequest request) {
                return false; // alles binnen de app houden
            }

            @Override
            public void onPageFinished(WebView view, String url) {
                lastLoad = System.currentTimeMillis();
                CookieManager.getInstance().flush();
            }

            @Override
            public void onReceivedError(WebView view, WebResourceRequest request, WebResourceError error) {
                if (Build.VERSION.SDK_INT >= 23 && request.isForMainFrame()) {
                    showError(String.valueOf(error.getDescription()));
                }
            }

            @SuppressWarnings("deprecation")
            @Override
            public void onReceivedError(WebView view, int errorCode, String description, String failingUrl) {
                if (Build.VERSION.SDK_INT < 23) showError(description);
            }

            @Override
            public boolean onRenderProcessGone(WebView view, RenderProcessGoneDetail detail) {
                recreateWebView(); // WebView gecrasht: opnieuw opbouwen i.p.v. app-crash
                return true;
            }
        });

        web.setWebChromeClient(new WebChromeClient() {
            @Override
            public void onProgressChanged(WebView view, int newProgress) {
                if (newProgress == 100 && errorView != null && !pendingError) {
                    errorView.setVisibility(View.GONE);
                }
            }
        });

        root.addView(web, 0, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
    }

    private void recreateWebView() {
        if (web != null) {
            root.removeView(web);
            web.destroy();
        }
        createWebView();
        String url = prefs.getString(KEY_URL, null);
        if (url != null) web.loadUrl(url);
    }

    private void createErrorView() {
        errorView = new TextView(this);
        errorView.setTextColor(Color.WHITE);
        errorView.setTextSize(20);
        errorView.setGravity(Gravity.CENTER);
        errorView.setBackgroundColor(Color.BLACK);
        errorView.setPadding(48, 48, 48, 48);
        errorView.setVisibility(View.GONE);
        root.addView(errorView, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
    }

    private void showError(String description) {
        pendingError = true;
        errorView.setText("Geen verbinding met Home Assistant\n\n" + description
                + "\n\nNieuwe poging over 10 seconden…");
        errorView.setVisibility(View.VISIBLE);
        handler.removeCallbacks(retry);
        handler.postDelayed(retry, RETRY_MS);
    }

    // ---------- Slaapstand en beweging ----------

    private void createSleepOverlay() {
        sleepOverlay = new View(this);
        sleepOverlay.setBackgroundColor(Color.BLACK);
        sleepOverlay.setVisibility(View.GONE);
        root.addView(sleepOverlay, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
    }

    private void sleep() {
        if (prefs.getBoolean(KEY_SCREEN_OFF, false) && adminActive()) {
            // Scherm echt uit; de achtergronddienst blijft met de camera meekijken
            MotionService.ignoreUntil = System.currentTimeMillis() + 3_000;
            sleptViaLock = true;
            try {
                devicePolicy().lockNow();
                return;
            } catch (Exception e) {
                sleptViaLock = false; // terugvallen op zwart scherm
            }
        }
        MotionService.ignoreUntil = System.currentTimeMillis() + 2_000;
        asleep = true;
        sleepOverlay.setVisibility(View.VISIBLE);
        setBrightness(0.01f);
        // Dashboard pauzeren: camerastreams stoppen (spaart ook de batterij van de Nest Cam)
        if (web != null) web.onPause();
    }

    private void wake() {
        lastActivity = System.currentTimeMillis();
        if (!asleep) return;
        asleep = false;
        sleepOverlay.setVisibility(View.GONE);
        setBrightness(WindowManager.LayoutParams.BRIGHTNESS_OVERRIDE_NONE);
        if (web != null) {
            web.onResume();
            if (prefs.getBoolean(KEY_RELOAD_ON_WAKE, true)) reloadPage(); // verse camerabeelden
        }
    }

    private void reloadPage() {
        lastLoad = System.currentTimeMillis();
        if (web != null) web.reload();
    }

    private void setBrightness(float value) {
        WindowManager.LayoutParams lp = getWindow().getAttributes();
        lp.screenBrightness = value;
        getWindow().setAttributes(lp);
    }

    private void onMotion() {
        wake();
    }

    private DevicePolicyManager devicePolicy() {
        return (DevicePolicyManager) getSystemService(DEVICE_POLICY_SERVICE);
    }

    private ComponentName adminComponent() {
        return new ComponentName(this, AdminReceiver.class);
    }

    private boolean adminActive() {
        DevicePolicyManager dpm = devicePolicy();
        return dpm != null && dpm.isAdminActive(adminComponent());
    }

    private void requestAdmin() {
        unpin();
        Intent i = new Intent(DevicePolicyManager.ACTION_ADD_DEVICE_ADMIN);
        i.putExtra(DevicePolicyManager.EXTRA_DEVICE_ADMIN, adminComponent());
        i.putExtra(DevicePolicyManager.EXTRA_ADD_EXPLANATION,
                "HA Kiosk gebruikt dit enkel om het scherm uit te zetten na een tijd zonder beweging.");
        try {
            startActivity(i);
        } catch (Exception ignored) {
        }
    }

    /** Tijd tot slaapstand in seconden; zet een oude minuten-instelling om. */
    private int dimSeconds() {
        if (prefs.contains(KEY_DIM_SECONDS)) return prefs.getInt(KEY_DIM_SECONDS, 300);
        return prefs.getInt(KEY_DIM_MINUTES, 5) * 60;
    }

    private boolean hasCamera() {
        return Build.VERSION.SDK_INT < 23
                || checkSelfPermission(Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED;
    }

    /** Start de achtergronddienst (of herstart hem, bv. na een nieuwe gevoeligheid). */
    private void startMotion(boolean restart) {
        // De dienst draait altijd: hij stuurt ook de batterij naar Home Assistant.
        // Bewegingsdetectie zelf zit er enkel in als die aan staat en de camera mag.
        if (restart && MotionService.running) MotionService.stop(this);
        MotionService.start(this);
    }

    private void stopMotion() {
        MotionService.stop(this);
    }

    private void askPermissions() {
        if (Build.VERSION.SDK_INT >= 23 && prefs.getBoolean(KEY_MOTION, true) && !hasCamera()) {
            requestPermissions(new String[]{Manifest.permission.CAMERA}, REQ_CAMERA);
        } else {
            maybeAskOverlayPermission();
        }
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, String[] permissions, int[] results) {
        super.onRequestPermissionsResult(requestCode, permissions, results);
        if (requestCode == REQ_CAMERA) {
            startMotion(false);
            maybeAskOverlayPermission();
        }
    }

    /** Toestemming "over andere apps weergeven" is nodig om na een herstart vanzelf te openen. */
    private void maybeAskOverlayPermission() {
        if (Build.VERSION.SDK_INT < 23) return;
        if (Settings.canDrawOverlays(this)) return;
        if (prefs.getBoolean(KEY_ASKED_OVERLAY, false)) return;
        prefs.edit().putBoolean(KEY_ASKED_OVERLAY, true).apply();

        new AlertDialog.Builder(this)
                .setTitle("Automatisch starten")
                .setMessage("Om na een herstart van de tablet vanzelf te openen, heeft HA Kiosk de "
                        + "toestemming 'Weergeven boven andere apps' nodig. Zet die aan op het volgende scherm.")
                .setPositiveButton("Openen", (d, w) -> {
                    unpin();
                    try {
                        startActivity(new Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                                Uri.parse("package:" + getPackageName())));
                    } catch (Exception ignored) {
                    }
                })
                .setNegativeButton("Later", null)
                .show();
    }

    // ---------- Instellingen ----------

    private void showSettings() {
        if (settingsDialog != null && settingsDialog.isShowing()) return;
        wake();
        unpin(); // anders kan het toetsenbord of een Android-scherm geblokkeerd zijn

        final String current = prefs.getString(KEY_URL, "");
        float dp = getResources().getDisplayMetrics().density;
        int pad = (int) (20 * dp);

        LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);
        box.setPadding(pad, pad / 2, pad, 0);

        box.addView(label("Adres van je Home Assistant-dashboard, bv. http://192.168.1.10:8123/lovelace/0"));
        final EditText urlInput = new EditText(this);
        urlInput.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_URI);
        urlInput.setText(current.isEmpty() ? "http://" : current);
        urlInput.setSelection(urlInput.getText().length());
        box.addView(urlInput);

        final CheckBox motionBox = new CheckBox(this);
        motionBox.setText("Scherm wekken bij beweging (frontcamera)");
        motionBox.setChecked(prefs.getBoolean(KEY_MOTION, true));
        box.addView(motionBox);

        box.addView(label("Scherm zwart na zoveel seconden zonder beweging (0 = nooit, 300 = 5 minuten)"));
        final EditText dimInput = numberField(dimSeconds());
        box.addView(dimInput);

        box.addView(label("Gevoeligheid beweging, 1 (laag) tot 10 (hoog)"));
        final EditText sensInput = numberField(prefs.getInt(KEY_SENSITIVITY, 5));
        box.addView(sensInput);

        final CheckBox reloadWakeBox = new CheckBox(this);
        reloadWakeBox.setText("Dashboard herladen bij wakker worden (verse camerabeelden)");
        reloadWakeBox.setChecked(prefs.getBoolean(KEY_RELOAD_ON_WAKE, true));
        box.addView(reloadWakeBox);

        box.addView(label("Dashboard automatisch herladen elke zoveel minuten (0 = nooit)"));
        final EditText reloadInput = numberField(prefs.getInt(KEY_RELOAD_MINUTES, 0));
        box.addView(reloadInput);

        final CheckBox volumeBox = new CheckBox(this);
        volumeBox.setText("Volumeknoppen blokkeren");
        volumeBox.setChecked(prefs.getBoolean(KEY_BLOCK_VOLUME, false));
        box.addView(volumeBox);

        final CheckBox pinBox = new CheckBox(this);
        pinBox.setText("App vastzetten (home- en recente-apps-knop werken niet)");
        pinBox.setChecked(prefs.getBoolean(KEY_PIN, false));
        box.addView(pinBox);

        final CheckBox launcherBox = new CheckBox(this);
        launcherBox.setText("Als startscherm gebruiken (homeknop brengt je terug naar de kiosk)");
        launcherBox.setChecked(prefs.getBoolean(KEY_LAUNCHER, false));
        box.addView(launcherBox);

        final CheckBox screenOffBox = new CheckBox(this);
        screenOffBox.setText("Scherm echt uitzetten in slaapstand (vraagt toestemming apparaatbeheerder)");
        screenOffBox.setChecked(prefs.getBoolean(KEY_SCREEN_OFF, false) && adminActive());
        box.addView(screenOffBox);

        Button updateButton = new Button(this);
        updateButton.setText("Zoeken naar update");
        updateButton.setOnClickListener(v -> {
            if (settingsDialog != null) settingsDialog.dismiss();
            unpin();
            startActivity(new Intent(this, UpdateActivity.class));
        });
        box.addView(updateButton);

        long lastMotion = MotionService.lastMotion;
        String motionInfo;
        if (!hasCamera()) motionInfo = "Geen toestemming voor de camera.";
        else if (lastMotion == 0) motionInfo = "Nog geen beweging gezien sinds de start.";
        else motionInfo = "Laatste beweging: " + (System.currentTimeMillis() - lastMotion) / 1000 + " s geleden.";
        String batteryInfo = MotionService.lastBatterySend == 0
                ? "Batterij naar Home Assistant: " + MotionService.lastBatteryResult
                : "Batterij naar Home Assistant: " + MotionService.lastBatteryResult + " ("
                + (System.currentTimeMillis() - MotionService.lastBatterySend) / 1000 + " s geleden)";
        box.addView(label("\n" + batteryInfo + "\n" + motionInfo
                + "\nInstellingen openen: tik 5x snel in de linkerbovenhoek."
                + "\nVersie " + versionName()));

        AlertDialog.Builder b = new AlertDialog.Builder(this)
                .setTitle("HA Kiosk – instellingen")
                .setView(scrollable(box))
                .setCancelable(!current.isEmpty())
                .setPositiveButton("Opslaan", (d, w) -> {
                    String url = normalizeUrl(urlInput.getText().toString());
                    prefs.edit()
                            .putString(KEY_URL, url)
                            .putBoolean(KEY_MOTION, motionBox.isChecked())
                            .putInt(KEY_DIM_SECONDS, parseInt(dimInput, 300, 0, 86400))
                            .putInt(KEY_SENSITIVITY, parseInt(sensInput, 5, 1, 10))
                            .putBoolean(KEY_RELOAD_ON_WAKE, reloadWakeBox.isChecked())
                            .putInt(KEY_RELOAD_MINUTES, parseInt(reloadInput, 0, 0, 1440))
                            .putBoolean(KEY_BLOCK_VOLUME, volumeBox.isChecked())
                            .putBoolean(KEY_PIN, pinBox.isChecked())
                            .putBoolean(KEY_SCREEN_OFF, screenOffBox.isChecked())
                            .apply();
                    if (!url.equals(current)) web.loadUrl(url);
                    startMotion(true);
                    if (!screenOffBox.isChecked() && adminActive()) {
                        devicePolicy().removeActiveAdmin(adminComponent());
                    }
                    boolean launcherWas = prefs.getBoolean(KEY_LAUNCHER, false);
                    setLauncher(launcherBox.isChecked());
                    if (screenOffBox.isChecked() && !adminActive()) {
                        requestAdmin();
                    } else if (launcherBox.isChecked() && !launcherWas) {
                        askDefaultLauncher();
                    } else {
                        askPermissions();
                    }
                })
                .setNeutralButton("App sluiten", (d, w) -> {
                    unpin();
                    stopMotion();
                    finish();
                });
        if (!current.isEmpty()) {
            b.setNegativeButton("Herladen", (d, w) -> web.reload());
        }
        settingsDialog = b.create();
        settingsDialog.setOnDismissListener(d -> {
            lastActivity = System.currentTimeMillis();
            hideSystemUi();
            // even wachten: misschien opent er nog een toestemmingsscherm
            handler.postDelayed(this::applyPinning, 1_500);
        });
        settingsDialog.show();
    }

    private View scrollable(View content) {
        ScrollView scroll = new ScrollView(this);
        scroll.addView(content);
        return scroll;
    }

    private TextView label(String text) {
        TextView t = new TextView(this);
        t.setText(text);
        t.setPadding(0, (int) (8 * getResources().getDisplayMetrics().density), 0, 0);
        return t;
    }

    private EditText numberField(int value) {
        EditText e = new EditText(this);
        e.setInputType(InputType.TYPE_CLASS_NUMBER);
        e.setText(String.valueOf(value));
        return e;
    }

    private static int parseInt(EditText e, int fallback, int min, int max) {
        try {
            int v = Integer.parseInt(e.getText().toString().trim());
            return Math.max(min, Math.min(max, v));
        } catch (NumberFormatException ex) {
            return fallback;
        }
    }

    private static String normalizeUrl(String raw) {
        String url = raw.trim();
        if (!url.startsWith("http://") && !url.startsWith("https://")) url = "http://" + url;
        return url;
    }

    private String versionName() {
        try {
            return getPackageManager().getPackageInfo(getPackageName(), 0).versionName;
        } catch (Exception e) {
            return "?";
        }
    }

    // ---------- Knoppen blokkeren ----------

    private static boolean isVolumeKey(int keyCode) {
        return keyCode == KeyEvent.KEYCODE_VOLUME_UP
                || keyCode == KeyEvent.KEYCODE_VOLUME_DOWN
                || keyCode == KeyEvent.KEYCODE_VOLUME_MUTE;
    }

    @Override
    public boolean onKeyDown(int keyCode, KeyEvent event) {
        wake();
        if (isVolumeKey(keyCode) && prefs.getBoolean(KEY_BLOCK_VOLUME, false)) return true;
        return super.onKeyDown(keyCode, event);
    }

    @Override
    public boolean onKeyUp(int keyCode, KeyEvent event) {
        if (isVolumeKey(keyCode) && prefs.getBoolean(KEY_BLOCK_VOLUME, false)) return true;
        return super.onKeyUp(keyCode, event);
    }

    private boolean inLockTask() {
        ActivityManager am = (ActivityManager) getSystemService(ACTIVITY_SERVICE);
        if (am == null) return false;
        if (Build.VERSION.SDK_INT >= 23) {
            return am.getLockTaskModeState() != ActivityManager.LOCK_TASK_MODE_NONE;
        }
        //noinspection deprecation
        return am.isInLockTaskMode();
    }

    /** Zet de app vast (Android-schermvastzetting) als dat in de instellingen aan staat. */
    private void applyPinning() {
        boolean dialogOpen = settingsDialog != null && settingsDialog.isShowing();
        if (dialogOpen || !hasWindowFocus()) return;
        if (prefs.getBoolean(KEY_PIN, false)) {
            if (!inLockTask()) {
                try {
                    startLockTask();
                } catch (Exception ignored) {
                }
            }
        } else {
            unpin();
        }
    }

    private void unpin() {
        if (!inLockTask()) return;
        try {
            stopLockTask();
        } catch (Exception ignored) {
        }
    }

    /** Zet de "startscherm"-variant van de app aan of uit. */
    private void setLauncher(boolean enabled) {
        prefs.edit().putBoolean(KEY_LAUNCHER, enabled).apply();
        getPackageManager().setComponentEnabledSetting(
                new ComponentName(this, getPackageName() + ".HomeAlias"),
                enabled ? PackageManager.COMPONENT_ENABLED_STATE_ENABLED
                        : PackageManager.COMPONENT_ENABLED_STATE_DISABLED,
                PackageManager.DONT_KILL_APP);
    }

    private void askDefaultLauncher() {
        new AlertDialog.Builder(this)
                .setTitle("Startscherm kiezen")
                .setMessage("Kies op het volgende scherm 'HA Kiosk' als standaard startscherm. "
                        + "Wil je later terug naar het gewone startscherm, zet deze optie dan uit "
                        + "en kies daar je oude startscherm.")
                .setPositiveButton("Openen", (d, w) -> {
                    unpin();
                    try {
                        startActivity(new Intent(Settings.ACTION_HOME_SETTINGS));
                    } catch (Exception e) {
                        startActivity(new Intent(Settings.ACTION_SETTINGS));
                    }
                })
                .setNegativeButton("Later", (d, w) -> askPermissions())
                .show();
    }

    // ---------- Aanraking ----------

    @Override
    public boolean dispatchTouchEvent(MotionEvent ev) {
        if (asleep) {
            // eerste aanraking maakt enkel wakker, zonder op het dashboard te klikken
            if (ev.getActionMasked() == MotionEvent.ACTION_DOWN) wake();
            return true;
        }
        lastActivity = System.currentTimeMillis();

        if (ev.getActionMasked() == MotionEvent.ACTION_DOWN) {
            float corner = 80 * getResources().getDisplayMetrics().density;
            if (ev.getX() < corner && ev.getY() < corner) {
                long now = System.currentTimeMillis();
                if (now - firstCornerTap > CORNER_TAP_WINDOW_MS) {
                    firstCornerTap = now;
                    cornerTaps = 0;
                }
                cornerTaps++;
                if (cornerTaps >= CORNER_TAPS_NEEDED) {
                    cornerTaps = 0;
                    showSettings();
                    return true;
                }
            }
        }
        return super.dispatchTouchEvent(ev);
    }

    // ---------- Volledig scherm en levenscyclus ----------

    @Override
    public void onWindowFocusChanged(boolean hasFocus) {
        super.onWindowFocusChanged(hasFocus);
        if (hasFocus) hideSystemUi();
    }

    @SuppressWarnings("deprecation")
    private void hideSystemUi() {
        getWindow().getDecorView().setSystemUiVisibility(
                View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY
                        | View.SYSTEM_UI_FLAG_LAYOUT_STABLE
                        | View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION
                        | View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN
                        | View.SYSTEM_UI_FLAG_HIDE_NAVIGATION
                        | View.SYSTEM_UI_FLAG_FULLSCREEN);
    }

    @SuppressWarnings("deprecation")
    @Override
    public void onBackPressed() {
        if (web != null && web.canGoBack()) web.goBack();
        // anders niets: de terugknop sluit de kiosk niet
    }

    @Override
    protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        setIntent(intent);
    }

    @Override
    protected void onResume() {
        super.onResume();
        if (web != null) web.onResume();
        hideSystemUi();
        MotionService.listener = this::onMotion;
        if (sleptViaLock) {
            // wakker geworden na "scherm echt uit"
            sleptViaLock = false;
            if (prefs.getBoolean(KEY_RELOAD_ON_WAKE, true)) reloadPage();
        }
        wake();
        startMotion(false);
        handler.postDelayed(this::applyPinning, 1_500);
        handler.removeCallbacks(idleCheck);
        handler.postDelayed(idleCheck, IDLE_CHECK_MS);
    }

    @Override
    protected void onPause() {
        handler.removeCallbacks(idleCheck);
        MotionService.listener = null; // dienst blijft draaien en wekt de kiosk bij beweging
        if (web != null) web.onPause();
        CookieManager.getInstance().flush();
        super.onPause();
    }

    @Override
    protected void onDestroy() {
        handler.removeCallbacksAndMessages(null);
        if (MotionService.listener != null) MotionService.listener = null;
        if (web != null) web.destroy();
        super.onDestroy();
    }
}
