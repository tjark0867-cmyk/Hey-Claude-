package de.heyclaude.core;

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

import de.heyclaude.app.Core;
import de.heyclaude.app.InstallStatusReceiver;

/**
 * Holt neue Versionen automatisch von GitHub (Releases).
 * Normalerweise ist das nur neuer Programmcode, der ohne Installation sofort aktiv wird.
 * Nur wenn sich die Huelle aendert (selten), wird eine APK installiert.
 */
final class Updater {

    private static final String TAG = "HeyClaude";
    private static final String LATEST =
            "https://api.github.com/repos/tjark0867-cmyk/Hey-Claude-/releases/latest";
    static final String ACTION_INSTALL_STATUS = "de.heyclaude.app.INSTALL_STATUS";

    static volatile String lastStatus = "";
    /** Neueste verfuegbare Huellen-Version (0 = keine neuere bekannt). */
    static volatile int shellAvailable;

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

    /** Ein gefundenes Update: entweder neuer Programmcode (ohne Installation) oder eine neue Huelle (APK). */
    static final class Update {
        final boolean shell;
        final int build;
        final File file;

        Update(boolean shell, int build, File file) {
            this.shell = shell;
            this.build = build;
            this.file = file;
        }
    }

    /**
     * Sucht nach einer neuen Version und laedt sie herunter (blockiert, nicht im Haupt-Thread).
     * Neuer Programmcode ist klein und kommt immer; eine neue Huelle (APK, selten) nur im WLAN,
     * ausser der Nutzer sucht selbst. Ergebnis steht in lastStatus.
     */
    static Update check(Context ctx, boolean userInitiated) {
        return check(ctx, userInitiated, false);
    }

    /** allowShell: auch eine neue App-Huelle (APK) laden – nur wenn der Nutzer das ausdruecklich will. */
    static Update check(Context ctx, boolean userInitiated, boolean allowShell) {
        try {
            Prefs.get(ctx).edit().putLong(Prefs.LAST_UPDATE_CHECK, System.currentTimeMillis()).apply();
            cleanup(ctx);
            JSONObject rel = new JSONObject(get(LATEST));
            JSONArray assets = rel.optJSONArray("assets");
            String apkUrl = null;
            long apkSize = -1;
            String coreUrl = null;
            long coreSize = -1;
            String metaUrl = null;
            if (assets != null) {
                for (int i = 0; i < assets.length(); i++) {
                    JSONObject a = assets.optJSONObject(i);
                    if (a == null) {
                        continue;
                    }
                    String name = a.optString("name", "");
                    String url = a.optString("browser_download_url", null);
                    long size = a.optLong("size", -1);
                    if (name.endsWith(".apk")) {
                        apkUrl = url;
                        apkSize = size;
                    } else if (name.equals("core.jar")) {
                        coreUrl = url;
                        coreSize = size;
                    } else if (name.equals("update.json")) {
                        metaUrl = url;
                    }
                }
            }
            int running = BuildInfo.BUILD;
            if (metaUrl == null) {
                lastStatus = "Aktuell (Build " + running + ")";
                return null;
            }
            JSONObject meta = new JSONObject(get(metaUrl));
            int build = meta.optInt("build", -1);
            int minShell = meta.optInt("shell", 1);           // Programmcode braucht mindestens diese Huelle
            int apkShell = meta.optInt("apk_shell", minShell); // Huelle in der APK dieses Releases
            String sha = meta.optString("sha256", "");
            int installedShell = Core.shellVersion();

            // 1) Neue Huelle (selten, z.B. fuer Bedienungshilfe): nur auf Knopfdruck installieren
            if (apkShell > installedShell) {
                shellAvailable = apkShell;
                if (allowShell && apkUrl != null) {
                    lastStatus = "Lade neue App-Hülle " + apkShell + " …";
                    File apk = new File(ctx.getCacheDir(), "update-" + build + ".apk");
                    if (!(apk.exists() && apk.length() == apkSize)) {
                        downloadTo(apkUrl, apk);
                    }
                    if (apkSize > 0 && apk.length() != apkSize) {
                        apk.delete();
                        lastStatus = "Download unvollständig – später nochmal";
                        return null;
                    }
                    return new Update(true, build, apk);
                }
            }
            if (minShell > installedShell) {
                lastStatus = "Neue Version braucht die neue App-Hülle – „Neue App-Hülle installieren“ tippen";
                return null;
            }

            if (build <= running) {
                lastStatus = "Aktuell (Build " + running + ")"
                        + (shellAvailable > installedShell ? " · neue App-Hülle " + shellAvailable + " verfügbar" : "");
                return null;
            }
            if (Core.isBad(ctx, build)) {
                lastStatus = "Build " + build + " übersprungen (lief nicht stabil)";
                return null;
            }
            if (coreUrl == null) {
                lastStatus = "Neue Version ohne Code-Datei";
                return null;
            }
            lastStatus = "Lade neue Version " + build + " …";
            File code = new File(ctx.getCacheDir(), "core-dl-" + build + ".jar");
            downloadTo(coreUrl, code);
            if ((coreSize > 0 && code.length() != coreSize)
                    || (!sha.isEmpty() && !sha.equalsIgnoreCase(sha256(code)))) {
                code.delete();
                lastStatus = "Download fehlerhaft – später nochmal";
                return null;
            }
            return new Update(false, build, code);
        } catch (Exception e) {
            Log.w(TAG, "Update-Suche", e);
            lastStatus = "Update-Suche fehlgeschlagen (" + e.getClass().getSimpleName() + ")";
            return null;
        }
    }

    /** Wendet ein Update an. Programmcode wird sofort aktiv, ohne Installation und ohne Neustart. */
    static void apply(Context ctx, Update u) {
        if (u.shell) {
            install(ctx, u.file);
            return;
        }
        try {
            Core.installCode(ctx, u.file, u.build);
            lastStatus = "Neue Version " + u.build + " aktiv";
            Core.hotSwap(ctx);
        } catch (Exception e) {
            Log.e(TAG, "Code-Update", e);
            lastStatus = "Update fehlgeschlagen: " + e.getMessage();
        }
    }

    private static String sha256(File f) {
        try {
            java.security.MessageDigest md = java.security.MessageDigest.getInstance("SHA-256");
            InputStream in = new FileInputStream(f);
            try {
                byte[] buf = new byte[65536];
                int n;
                while ((n = in.read(buf)) != -1) {
                    md.update(buf, 0, n);
                }
            } finally {
                in.close();
            }
            StringBuilder sb = new StringBuilder();
            for (byte b : md.digest()) {
                sb.append(String.format("%02x", b));
            }
            return sb.toString();
        } catch (Exception e) {
            return "";
        }
    }

    private static void cleanup(Context ctx) {
        File[] old = ctx.getCacheDir().listFiles();
        if (old == null) {
            return;
        }
        for (File f : old) {
            String n = f.getName();
            if ((n.startsWith("update-") || n.startsWith("core-dl-"))
                    && f.lastModified() < System.currentTimeMillis() - 3600_000L) {
                f.delete();
            }
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
