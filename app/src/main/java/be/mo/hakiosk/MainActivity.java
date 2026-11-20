package be.mo.hakiosk;

import android.annotation.SuppressLint;
import android.app.Activity;
import android.app.AlertDialog;
import android.content.Intent;
import android.content.SharedPreferences;
import android.graphics.Color;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.provider.Settings;
import android.text.InputType;
import android.view.Gravity;
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
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.TextView;

/**
 * Toont het Home Assistant-dashboard schermvullend.
 * Instellingen openen: tik 5x snel in de linkerbovenhoek.
 */
public class MainActivity extends Activity {

    static final String PREFS = "kiosk";
    static final String KEY_URL = "url";
    static final String KEY_ASKED_OVERLAY = "asked_overlay";

    private static final long RETRY_MS = 10_000;
    private static final int CORNER_TAPS_NEEDED = 5;
    private static final long CORNER_TAP_WINDOW_MS = 3_000;

    private final Handler handler = new Handler(Looper.getMainLooper());
    private final Runnable retry = () -> {
        if (this.web != null) this.web.reload();
    };

    private SharedPreferences prefs;
    private FrameLayout root;
    private WebView web;
    private TextView errorView;
    private AlertDialog settingsDialog;

    private int cornerTaps = 0;
    private long firstCornerTap = 0;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        prefs = getSharedPreferences(PREFS, MODE_PRIVATE);

        // Scherm aan houden en tonen boven het vergrendelscherm
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

        String url = prefs.getString(KEY_URL, null);
        if (url == null || url.isEmpty()) {
            showSettings();
        } else {
            web.loadUrl(url);
            maybeAskOverlayPermission();
        }
    }

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
        if (Build.VERSION.SDK_INT >= 21) {
            s.setMixedContentMode(WebSettings.MIXED_CONTENT_ALWAYS_ALLOW);
        }

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
                // WebView gecrasht: opnieuw opbouwen in plaats van de hele app te laten crashen
                recreateWebView();
                return true;
            }
        });

        // Verberg de foutmelding zodra een pagina succesvol begint te tekenen
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

    private boolean pendingError = false;

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
        handler.postDelayed(() -> {
            pendingError = false;
            retry.run();
        }, RETRY_MS);
    }

    // ---------- Instellingen ----------

    private void showSettings() {
        if (settingsDialog != null && settingsDialog.isShowing()) return;

        final String current = prefs.getString(KEY_URL, "");
        int pad = (int) (20 * getResources().getDisplayMetrics().density);

        LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);
        box.setPadding(pad, pad / 2, pad, 0);

        TextView hint = new TextView(this);
        hint.setText("Adres van je Home Assistant-dashboard, bv. http://192.168.1.10:8123/lovelace/0"
                + "\n\nInstellingen later terug openen: tik 5x snel in de linkerbovenhoek."
                + "\n\nVersie " + versionName());
        box.addView(hint);

        final EditText input = new EditText(this);
        input.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_URI);
        input.setText(current.isEmpty() ? "http://" : current);
        input.setSelection(input.getText().length());
        box.addView(input);

        AlertDialog.Builder b = new AlertDialog.Builder(this)
                .setTitle("HA Kiosk – instellingen")
                .setView(box)
                .setCancelable(!current.isEmpty())
                .setPositiveButton("Opslaan", (d, w) -> {
                    String url = normalizeUrl(input.getText().toString());
                    prefs.edit().putString(KEY_URL, url).apply();
                    web.loadUrl(url);
                    maybeAskOverlayPermission();
                })
                .setNeutralButton("App sluiten", (d, w) -> finish());
        if (!current.isEmpty()) {
            b.setNegativeButton("Herladen", (d, w) -> web.reload());
        }
        settingsDialog = b.create();
        settingsDialog.setOnDismissListener(d -> hideSystemUi());
        settingsDialog.show();
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
                    try {
                        startActivity(new Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                                Uri.parse("package:" + getPackageName())));
                    } catch (Exception ignored) {
                    }
                })
                .setNegativeButton("Later", null)
                .show();
    }

    // ---------- Geheime tik in de hoek ----------

    @Override
    public boolean dispatchTouchEvent(MotionEvent ev) {
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

    // ---------- Volledig scherm ----------

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
        // anders niets doen: terugknop sluit de kiosk niet
    }

    @Override
    protected void onResume() {
        super.onResume();
        if (web != null) web.onResume();
        hideSystemUi();
    }

    @Override
    protected void onPause() {
        if (web != null) web.onPause();
        CookieManager.getInstance().flush();
        super.onPause();
    }

    @Override
    protected void onDestroy() {
        handler.removeCallbacksAndMessages(null);
        if (web != null) web.destroy();
        super.onDestroy();
    }
}
