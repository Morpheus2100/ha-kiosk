package be.mo.hakiosk;

import android.app.Activity;
import android.content.Intent;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.provider.Settings;
import android.widget.TextView;

/** Klein venster: zoekt naar een update en installeert die. Ook bereikbaar via een eigen icoon. */
public class UpdateActivity extends Activity {

    private TextView status;
    private boolean askedPermission = false;
    private boolean started = false;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setTitle("HA Kiosk bijwerken");
        status = new TextView(this);
        int pad = (int) (24 * getResources().getDisplayMetrics().density);
        status.setPadding(pad, pad, pad, pad);
        status.setTextSize(18);
        status.setMinWidth((int) (320 * getResources().getDisplayMetrics().density));
        setContentView(status);
    }

    @Override
    protected void onResume() {
        super.onResume();
        if (started) return;

        if (Build.VERSION.SDK_INT >= 26 && !getPackageManager().canRequestPackageInstalls()) {
            if (!askedPermission) {
                askedPermission = true;
                status.setText("Sta op het volgende scherm 'Toestaan uit deze bron' toe en keer dan terug.");
                startActivity(new Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES,
                        Uri.parse("package:" + getPackageName())));
            } else {
                status.setText("Zonder die toestemming kan HA Kiosk zichzelf niet bijwerken.");
            }
            return;
        }

        started = true;
        new Thread(() -> Updater.run(this, msg -> runOnUiThread(() -> status.setText(msg)))).start();
    }
}
