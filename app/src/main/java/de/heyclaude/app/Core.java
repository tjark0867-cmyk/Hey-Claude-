package de.heyclaude.app;

import android.app.Activity;
import android.content.Context;
import android.content.SharedPreferences;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;

/**
 * Laedt den Programmcode der App. Neue Versionen kommen als kleine Code-Datei von GitHub
 * und werden ohne Installation aktiv (wie Web-Apps). Startet eine neue Version nicht sauber,
 * faellt die App automatisch auf den mitgelieferten Code zurueck.
 */
public final class Core {

    /** Version der Huelle. Nur erhoehen, wenn sich Manifest, Berechtigungen oder Klassen in diesem Paket aendern. */
    public static final int SHELL = 1;

    private static final String TAG = "HeyClaude";
    private static final String CORE_PKG = "de.heyclaude.core.";
    private static final String PREFS = "heyclaude_shell";
    private static final long HEALTHY_AFTER_MS = 60_000L;

    private static final Handler MAIN = new Handler(Looper.getMainLooper());

    private static ClassLoader loader;
    private static int loadedBuild;
    private static boolean downloaded;
    private static boolean healthy = true;
    private static int generation;
    private static boolean guardInstalled;
    private static Context appCtx;
    private static VoiceService service;
    private static Activity visibleActivity;

    private Core() {
    }

    // ------------------------------------------------------------------ Infos fuer den Programmcode

    /** Version der installierten Huelle (zur Laufzeit, nicht beim Kompilieren eingesetzt). */
    public static int shellVersion() {
        return SHELL;
    }

    /** Build-Nummer des in der APK mitgelieferten Codes. */
    public static int bundledBuild() {
        return de.heyclaude.core.BuildInfo.BUILD;
    }

    /** Build-Nummer des gerade laufenden Codes. */
    public static synchronized int loadedBuild() {
        return loadedBuild;
    }

    public static synchronized boolean isDownloadedCode() {
        return downloaded;
    }

    public static synchronized int generation() {
        return generation;
    }

    // ------------------------------------------------------------------ Laden

    static synchronized ClassLoader loader(Context ctx) {
        if (loader == null) {
            choose(ctx.getApplicationContext());
        }
        return loader;
    }

    private static void choose(Context app) {
        appCtx = app;
        installCrashGuard();
        int bundled = bundledBuild();
        SharedPreferences sp = prefs(app);
        File best = null;
        int bestBuild = bundled;
        File[] files = codeDir(app).listFiles();
        if (files != null) {
            for (File f : files) {
                int b = parseBuild(f.getName());
                if (b <= 0) {
                    continue;
                }
                if (b <= bundled) {
                    deleteQuietly(f); // aelter als der mitgelieferte Code
                    continue;
                }
                if (sp.getBoolean("bad_" + b, false)) {
                    continue;
                }
                if (b > bestBuild) {
                    bestBuild = b;
                    best = f;
                }
            }
        }
        if (best != null) {
            try {
                best.setReadOnly();
                ClassLoader cl = new CoreClassLoader(best.getAbsolutePath(),
                        app.getApplicationInfo().nativeLibraryDir, Core.class.getClassLoader());
                cl.loadClass(CORE_PKG + "BuildInfo");
                loader = cl;
                loadedBuild = bestBuild;
                downloaded = true;
                healthy = false;
                final int b = bestBuild;
                MAIN.postDelayed(() -> markHealthy(b), HEALTHY_AFTER_MS);
                Log.i(TAG, "Nachgeladener Code aktiv: Build " + bestBuild);
                return;
            } catch (Throwable t) {
                Log.e(TAG, "Code-Build " + bestBuild + " unbrauchbar", t);
                markBad(app, bestBuild);
            }
        }
        loader = Core.class.getClassLoader();
        loadedBuild = bundled;
        downloaded = false;
        healthy = true;
    }

    /** Erzeugt eine Klasse aus dem Programmcode (z.B. "Engine"). Faellt bei Fehlern auf den mitgelieferten Code zurueck. */
    public static Component create(Context ctx, String simpleName) {
        for (int attempt = 0; attempt < 2; attempt++) {
            ClassLoader cl = loader(ctx);
            try {
                Class<?> c = cl.loadClass(CORE_PKG + simpleName);
                return (Component) c.getDeclaredConstructor().newInstance();
            } catch (Throwable t) {
                Log.e(TAG, "Kann " + simpleName + " nicht laden", t);
                if (!fallBackToBundled()) {
                    break;
                }
            }
        }
        return null;
    }

    /** Wie create, aber fuer Klassen, die es (noch) nicht geben muss. */
    public static Component createOptional(Context ctx, String simpleName) {
        try {
            Class<?> c = loader(ctx).loadClass(CORE_PKG + simpleName);
            return (Component) c.getDeclaredConstructor().newInstance();
        } catch (ClassNotFoundException e) {
            return null;
        } catch (Throwable t) {
            Log.w(TAG, "Optionale Klasse " + simpleName, t);
            return null;
        }
    }

    /** Meldet einen Fehler im nachgeladenen Code: Build wird gesperrt, mitgelieferter Code wird aktiv. */
    public static synchronized boolean fallBackToBundled() {
        if (!downloaded) {
            return false;
        }
        if (appCtx != null) {
            markBad(appCtx, loadedBuild);
        }
        loader = Core.class.getClassLoader();
        loadedBuild = bundledBuild();
        downloaded = false;
        healthy = true;
        generation++;
        return true;
    }

    // ------------------------------------------------------------------ Neuer Code

    /** Speichert eine heruntergeladene Code-Datei (wird mit hotSwap oder beim naechsten Start aktiv). */
    public static synchronized void installCode(Context ctx, File source, int build) throws IOException {
        File dir = codeDir(ctx);
        File target = new File(dir, "core-" + build + ".jar");
        if (target.exists()) {
            target.setWritable(true);
            deleteQuietly(target);
        }
        InputStream in = new FileInputStream(source);
        try {
            OutputStream out = new FileOutputStream(target);
            try {
                target.setReadOnly(); // Android 14: nachgeladener Code muss schreibgeschuetzt sein
                byte[] buf = new byte[65536];
                int n;
                while ((n = in.read(buf)) != -1) {
                    out.write(buf, 0, n);
                }
            } finally {
                out.close();
            }
        } finally {
            in.close();
        }
        deleteQuietly(source);
    }

    /** Ob ein Build nach Abstuerzen gesperrt ist. */
    public static boolean isBad(Context ctx, int build) {
        return prefs(ctx).getBoolean("bad_" + build, false);
    }

    /** Wechselt im laufenden Betrieb auf den neuesten Code – ohne Neustart, Mikrofon bleibt an. */
    public static void hotSwap(final Context ctx) {
        MAIN.post(() -> {
            synchronized (Core.class) {
                loader = null;
                choose(ctx.getApplicationContext());
                generation++;
            }
            VoiceService s = service;
            if (s != null) {
                s.swap();
            }
            Activity a = visibleActivity;
            if (a != null && !a.isFinishing()) {
                a.recreate();
            }
        });
    }

    // ------------------------------------------------------------------ Verwaltung

    static void setService(VoiceService s) {
        service = s;
    }

    static void setVisibleActivity(Activity a) {
        visibleActivity = a;
    }

    private static synchronized void markHealthy(int build) {
        if (downloaded && loadedBuild == build) {
            healthy = true;
        }
    }

    private static void markBad(Context ctx, int build) {
        try {
            prefs(ctx).edit().putBoolean("bad_" + build, true).commit();
        } catch (Throwable ignored) {
            // egal
        }
    }

    private static void installCrashGuard() {
        if (guardInstalled) {
            return;
        }
        guardInstalled = true;
        final Thread.UncaughtExceptionHandler prev = Thread.getDefaultUncaughtExceptionHandler();
        Thread.setDefaultUncaughtExceptionHandler((thread, error) -> {
            try {
                synchronized (Core.class) {
                    if (downloaded && appCtx != null) {
                        SharedPreferences sp = prefs(appCtx);
                        int crashes = sp.getInt("crash_" + loadedBuild, 0) + 1;
                        SharedPreferences.Editor e = sp.edit().putInt("crash_" + loadedBuild, crashes);
                        if (!healthy || crashes >= 3) {
                            e.putBoolean("bad_" + loadedBuild, true);
                        }
                        e.commit();
                    }
                }
            } catch (Throwable ignored) {
                // egal
            }
            if (prev != null) {
                prev.uncaughtException(thread, error);
            }
        });
    }

    private static SharedPreferences prefs(Context ctx) {
        return ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
    }

    private static File codeDir(Context ctx) {
        File d = new File(ctx.getFilesDir(), "code");
        if (!d.isDirectory()) {
            d.mkdirs();
        }
        return d;
    }

    private static int parseBuild(String name) {
        if (!name.startsWith("core-") || !name.endsWith(".jar")) {
            return -1;
        }
        try {
            return Integer.parseInt(name.substring(5, name.length() - 4));
        } catch (NumberFormatException e) {
            return -1;
        }
    }

    private static void deleteQuietly(File f) {
        try {
            f.setWritable(true);
            f.delete();
        } catch (Throwable ignored) {
            // egal
        }
    }
}
