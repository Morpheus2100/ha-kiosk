package be.mo.hakiosk;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;

/** Start de kiosk na een herstart van de tablet en na een update van de app. */
public class BootReceiver extends BroadcastReceiver {
    @Override
    public void onReceive(Context context, Intent intent) {
        String action = intent.getAction();
        if (Intent.ACTION_BOOT_COMPLETED.equals(action)
                || "android.intent.action.QUICKBOOT_POWERON".equals(action)
                || Intent.ACTION_MY_PACKAGE_REPLACED.equals(action)) {
            Intent start = new Intent(context, MainActivity.class);
            start.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            try {
                context.startActivity(start);
            } catch (Exception ignored) {
                // zonder overlay-toestemming kan Android 10+ dit weigeren
            }
        }
    }
}
