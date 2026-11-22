package be.mo.hakiosk;

import android.app.PendingIntent;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageInstaller;
import android.os.Build;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;

/** Zoekt de nieuwste build op GitHub, downloadt hem en start de installatie. */
public class Updater {

    public interface Progress {
        void update(String message);
    }

    static final String REPO = "Morpheus2100/ha-kiosk";
    private static final String API = "https://api.github.com/repos/" + REPO + "/releases/latest";

    /** Draait op een achtergrondthread. */
    static void run(Context ctx, Progress p) {
        try {
            p.update("Zoeken naar een nieuwe versie…");
            JSONObject rel = new JSONObject(readString(API));
            int latest = Integer.parseInt(rel.getString("tag_name").replace("build-", ""));
            int current = currentBuild(ctx);
            if (latest <= current) {
                p.update("Je hebt al de nieuwste versie (build " + current + ").");
                return;
            }

            String apkUrl = null;
            JSONArray assets = rel.getJSONArray("assets");
            for (int i = 0; i < assets.length(); i++) {
                JSONObject a = assets.getJSONObject(i);
                if (a.getString("name").endsWith(".apk")) {
                    apkUrl = a.getString("browser_download_url");
                    break;
                }
            }
            if (apkUrl == null) {
                p.update("Build " + latest + " gevonden, maar zonder APK-bestand.");
                return;
            }

            p.update("Build " + latest + " downloaden…");
            PackageInstaller installer = ctx.getPackageManager().getPackageInstaller();
            PackageInstaller.SessionParams params =
                    new PackageInstaller.SessionParams(PackageInstaller.SessionParams.MODE_FULL_INSTALL);
            int sessionId = installer.createSession(params);
            PackageInstaller.Session session = installer.openSession(sessionId);

            HttpURLConnection con = open(apkUrl);
            int total = con.getContentLength();
            try (InputStream in = con.getInputStream();
                 OutputStream out = session.openWrite("HAKiosk.apk", 0, total > 0 ? total : -1)) {
                byte[] buf = new byte[16 * 1024];
                int n, done = 0, lastPct = -1;
                while ((n = in.read(buf)) > 0) {
                    out.write(buf, 0, n);
                    done += n;
                    if (total > 0) {
                        int pct = done * 100 / total;
                        if (pct != lastPct && pct % 10 == 0) {
                            lastPct = pct;
                            p.update("Build " + latest + " downloaden… " + pct + "%");
                        }
                    }
                }
                session.fsync(out);
            } finally {
                con.disconnect();
            }

            int flags = PendingIntent.FLAG_UPDATE_CURRENT;
            if (Build.VERSION.SDK_INT >= 31) flags |= PendingIntent.FLAG_MUTABLE;
            PendingIntent done = PendingIntent.getBroadcast(ctx, sessionId,
                    new Intent(ctx, InstallReceiver.class), flags);
            session.commit(done.getIntentSender());
            session.close();
            p.update("Build " + latest + " is gedownload. Tik op 'Installeren'.");
        } catch (Exception e) {
            p.update("Bijwerken mislukt: " + e.getMessage());
        }
    }

    @SuppressWarnings("deprecation")
    static int currentBuild(Context ctx) {
        try {
            return ctx.getPackageManager().getPackageInfo(ctx.getPackageName(), 0).versionCode;
        } catch (Exception e) {
            return 0;
        }
    }

    private static HttpURLConnection open(String url) throws Exception {
        HttpURLConnection con = (HttpURLConnection) new URL(url).openConnection();
        con.setRequestProperty("User-Agent", "HAKiosk");
        con.setConnectTimeout(15_000);
        con.setReadTimeout(30_000);
        con.setInstanceFollowRedirects(true);
        return con;
    }

    private static String readString(String url) throws Exception {
        HttpURLConnection con = open(url);
        con.setRequestProperty("Accept", "application/vnd.github+json");
        try (InputStream in = con.getInputStream()) {
            ByteArrayOutputStream bos = new ByteArrayOutputStream();
            byte[] buf = new byte[8192];
            int n;
            while ((n = in.read(buf)) > 0) bos.write(buf, 0, n);
            return bos.toString("UTF-8");
        } finally {
            con.disconnect();
        }
    }
}
