package de.heyclaude.app;

import android.app.PendingIntent;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageInfo;
import android.content.pm.PackageInstaller;
import android.net.ConnectivityManager;
import android.os.Build;
import android.util.Log;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;

/**
 * Holt neue Versionen automatisch von GitHub (Releases) und installiert sie.
 * Ab Android 12 klappt das im Hintergrund ohne Rueckfrage, weil die App sich selbst aktualisiert.
 */
final class Updater {

    private static final String TAG = "HeyClaude";
    private static final String LATEST =
            "https://api.github.com/repos/tjark0867-cmyk/Hey-Claude-/releases/latest";
    static final String ACTION_INSTALL_STATUS = "de.heyclaude.app.INSTALL_STATUS";

    static volatile String lastStatus = "";

    private Updater() {
    }

    static long currentVersion(Context ctx) {
        try {
            PackageInfo pi = ctx.getPackageManager().getPackageInfo(ctx.getPackageName(), 0);
            return Build.VERSION.SDK_INT >= 28 ? pi.getLongVersionCode() : pi.versionCode;
        } catch (Exception e) {
            return 0;
        }
    }

    static boolean isMetered(Context ctx) {
        try {
            ConnectivityManager cm = ctx.getSystemService(ConnectivityManager.class);
            return cm == null || cm.isActiveNetworkMetered();
        } catch (Exception e) {
            return true;
        }
    }

    /**
     * Sucht nach einer neuen Version und laedt sie herunter (blockiert, nicht im Haupt-Thread).
     * Liefert die APK-Datei oder null, wenn nichts zu tun ist. Ergebnis steht in lastStatus.
     */
    static File download(Context ctx, boolean userInitiated) {
        try {
            if (!userInitiated && isMetered(ctx)) {
                lastStatus = "Update-Suche wartet auf WLAN";
                return null;
            }
            Prefs.get(ctx).edit().putLong(Prefs.LAST_UPDATE_CHECK, System.currentTimeMillis()).apply();
            File[] old = ctx.getCacheDir().listFiles();
            if (old != null) {
                for (File f : old) {
                    if (f.getName().startsWith("update-") && f.lastModified() < System.currentTimeMillis() - 3600_000L) {
                        f.delete();
                    }
                }
            }
            JSONObject rel = new JSONObject(get(LATEST));
            String tag = rel.optString("tag_name", "");
            long remote = parseBuild(tag);
            long local = currentVersion(ctx);
            if (remote <= local) {
                lastStatus = "Aktuell (Version " + local + ")";
                return null;
            }
            String url = null;
            long size = -1;
            JSONArray assets = rel.optJSONArray("assets");
            if (assets != null) {
                for (int i = 0; i < assets.length(); i++) {
                    JSONObject a = assets.optJSONObject(i);
                    if (a != null && a.optString("name", "").endsWith(".apk")) {
                        url = a.optString("browser_download_url", null);
                        size = a.optLong("size", -1);
                        break;
                    }
                }
            }
            if (url == null) {
                lastStatus = "Neue Version " + remote + " hat keine APK";
                return null;
            }
            lastStatus = "Lade Version " + remote + " …";
            File out = new File(ctx.getCacheDir(), "update-" + remote + ".apk");
            if (!(out.exists() && out.length() == size)) {
                downloadTo(url, out);
            }
            if (size > 0 && out.length() != size) {
                out.delete();
                lastStatus = "Download unvollständig – versuche es später nochmal";
                return null;
            }
            lastStatus = "Version " + remote + " bereit";
            return out;
        } catch (Exception e) {
            Log.w(TAG, "Update-Suche", e);
            lastStatus = "Update-Suche fehlgeschlagen (" + e.getClass().getSimpleName() + ")";
            return null;
        }
    }

    /** Uebergibt die APK an Android. Danach wird die App neu gestartet (Prozess endet). */
    static void install(Context ctx, File apk) {
        try {
            PackageInstaller installer = ctx.getPackageManager().getPackageInstaller();
            PackageInstaller.SessionParams params =
                    new PackageInstaller.SessionParams(PackageInstaller.SessionParams.MODE_FULL_INSTALL);
            params.setAppPackageName(ctx.getPackageName());
            params.setSize(apk.length());
            if (Build.VERSION.SDK_INT >= 31) {
                params.setRequireUserAction(PackageInstaller.SessionParams.USER_ACTION_NOT_REQUIRED);
            }
            int id = installer.createSession(params);
            PackageInstaller.Session session = installer.openSession(id);
            try {
                OutputStream out = session.openWrite("base.apk", 0, apk.length());
                InputStream in = new FileInputStream(apk);
                try {
                    byte[] buf = new byte[65536];
                    int n;
                    while ((n = in.read(buf)) != -1) {
                        out.write(buf, 0, n);
                    }
                    session.fsync(out);
                } finally {
                    in.close();
                    out.close();
                }
                Intent status = new Intent(ctx, InstallStatusReceiver.class).setAction(ACTION_INSTALL_STATUS);
                int flags = PendingIntent.FLAG_UPDATE_CURRENT
                        | (Build.VERSION.SDK_INT >= 31 ? PendingIntent.FLAG_MUTABLE : 0);
                PendingIntent pi = PendingIntent.getBroadcast(ctx, 3, status, flags);
                lastStatus = "Installiere Update …";
                session.commit(pi.getIntentSender());
            } finally {
                session.close();
            }
        } catch (Exception e) {
            Log.e(TAG, "Update-Installation", e);
            lastStatus = "Installation fehlgeschlagen: " + e.getMessage();
        }
    }

    static long parseBuild(String tag) {
        String digits = tag.replaceAll("[^0-9]", "");
        if (digits.isEmpty()) {
            return -1;
        }
        try {
            return Long.parseLong(digits);
        } catch (NumberFormatException e) {
            return -1;
        }
    }

    private static String get(String urlStr) throws IOException {
        HttpURLConnection con = (HttpURLConnection) new URL(urlStr).openConnection();
        try {
            con.setConnectTimeout(15000);
            con.setReadTimeout(30000);
            con.setRequestProperty("User-Agent", "HeyClaude-App");
            con.setRequestProperty("Accept", "application/vnd.github+json");
            int code = con.getResponseCode();
            if (code != 200) {
                throw new IOException("GitHub antwortet " + code);
            }
            InputStream is = con.getInputStream();
            try {
                ByteArrayOutputStream bos = new ByteArrayOutputStream();
                byte[] buf = new byte[8192];
                int n;
                while ((n = is.read(buf)) != -1) {
                    bos.write(buf, 0, n);
                }
                return new String(bos.toByteArray(), StandardCharsets.UTF_8);
            } finally {
                is.close();
            }
        } finally {
            con.disconnect();
        }
    }

    private static void downloadTo(String urlStr, File out) throws IOException {
        HttpURLConnection con = (HttpURLConnection) new URL(urlStr).openConnection();
        try {
            con.setConnectTimeout(15000);
            con.setReadTimeout(60000);
            con.setInstanceFollowRedirects(true);
            con.setRequestProperty("User-Agent", "HeyClaude-App");
            int code = con.getResponseCode();
            if (code != 200) {
                throw new IOException("Download-Fehler " + code);
            }
            InputStream in = con.getInputStream();
            try {
                OutputStream os = new FileOutputStream(out);
                try {
                    byte[] buf = new byte[65536];
                    int n;
                    while ((n = in.read(buf)) != -1) {
                        os.write(buf, 0, n);
                    }
                } finally {
                    os.close();
                }
            } finally {
                in.close();
            }
        } finally {
            con.disconnect();
        }
    }
}
