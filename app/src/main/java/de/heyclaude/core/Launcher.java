package de.heyclaude.core;

import android.app.PendingIntent;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.content.pm.ResolveInfo;
import android.graphics.PixelFormat;
import android.hardware.display.DisplayManager;
import android.net.Uri;
import android.os.Build;
import android.os.Handler;
import android.provider.Settings;
import android.util.Log;
import android.view.Display;
import android.view.View;
import android.view.WindowManager;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Startet andere Apps (YouTube, Spotify, WhatsApp …) – auch aus dem Hintergrund.
 * Android erlaubt das nur mit der Erlaubnis "Über anderen Apps einblenden": dann wird kurz ein
 * unsichtbares 1-Pixel-Fenster eingeblendet und die App gestartet. Ohne Erlaubnis erscheint
 * ein Hinweis zum Antippen.
 */
final class Launcher {

    private static final String TAG = "HeyClaude";
    static final String YOUTUBE = "com.google.android.youtube";
    static final String YT_MUSIC = "com.google.android.apps.youtube.music";
    static final String SPOTIFY = "com.spotify.music";

    private static final Pattern VIDEO_RENDERER = Pattern.compile("\"videoRenderer\":\\{\"videoId\":\"([A-Za-z0-9_-]{11})\"");
    private static final Pattern ANY_VIDEO = Pattern.compile("\"videoId\":\"([A-Za-z0-9_-]{11})\"");

    private Launcher() {
    }

    /** Darf die App aus dem Hintergrund Apps oeffnen? (Overlay-Erlaubnis oder eingeschaltete Bedienungshilfe) */
    static boolean canLaunchFromBackground(Context ctx) {
        return Settings.canDrawOverlays(ctx) || Shell.accessibility() != null;
    }

    /** Xiaomi/Redmi/POCO brauchen zusaetzlich "Pop-up-Fenster im Hintergrund anzeigen". */
    static boolean isXiaomi() {
        String m = (Build.MANUFACTURER + " " + Build.BRAND).toLowerCase(Locale.ROOT);
        return m.contains("xiaomi") || m.contains("redmi") || m.contains("poco");
    }

    static Intent xiaomiPermissionEditor(Context ctx) {
        Intent i = new Intent("miui.intent.action.APP_PERM_EDITOR");
        i.setClassName("com.miui.securitycenter", "com.miui.permcenter.permissions.PermissionsEditorActivity");
        i.putExtra("extra_pkgname", ctx.getPackageName());
        i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        return i;
    }

    static boolean isInstalled(Context ctx, String pkg) {
        try {
            ctx.getPackageManager().getPackageInfo(pkg, 0);
            return true;
        } catch (Exception e) {
            return false;
        }
    }

    // ------------------------------------------------------------------ Starten (mit mehreren Wegen)

    private static final String[] WAY_NAMES = {"Bedienungshilfe", "direkt (Overlay-Erlaubnis)",
            "sichtbares Overlay-Fenster", "System-Startfreigabe"};
    private static final java.util.LinkedList<String> DIAG = new java.util.LinkedList<>();
    private static volatile int claimedToken;
    private static int tokenCounter = (int) (System.currentTimeMillis() & 0xFFFF) * 16;

    /** Fuer die Diagnose in der App. */
    static synchronized void log(String line) {
        String t = new java.text.SimpleDateFormat("HH:mm:ss", Locale.ROOT).format(new java.util.Date());
        DIAG.addFirst(t + " " + line);
        while (DIAG.size() > 15) {
            DIAG.removeLast();
        }
        Log.i(TAG, "Start: " + line);
    }

    static synchronized String diagnostics(Context ctx) {
        StringBuilder sb = new StringBuilder();
        sb.append("Android ").append(Build.VERSION.SDK_INT).append(" · ").append(Build.MANUFACTURER).append(' ')
                .append(Build.MODEL).append(" · Hülle ").append(Shell.version()).append(" · Code ").append(BuildInfo.BUILD)
                .append("\nOverlay-Erlaubnis: ").append(Settings.canDrawOverlays(ctx) ? "an" : "AUS")
                .append(" · Bedienungshilfe: ").append(Shell.accessibility() != null ? "an" : "aus")
                .append(" · Bevorzugter Weg: ").append(wayName(Prefs.get(ctx).getInt("launch_way", -1)));
        for (String l : DIAG) {
            sb.append("\n").append(l);
        }
        return sb.toString();
    }

    private static String wayName(int w) {
        return w >= 0 && w < WAY_NAMES.length ? WAY_NAMES[w] : "noch keiner";
    }

    /** Vom Trampolin aufgerufen: nur der erste Start eines Auftrags oeffnet das Ziel. */
    static synchronized boolean claim(int token) {
        if (token == 0 || token == claimedToken) {
            return false;
        }
        claimedToken = token;
        return true;
    }

    /**
     * Oeffnet eine App – auch wenn Hey Claude nur im Hintergrund laeuft. Probiert nacheinander mehrere
     * Wege und prueft jeweils, ob der unsichtbare Zwischenschritt wirklich gestartet ist. Der Weg, der
     * klappt, wird gemerkt. Klappt keiner, kommt eine Benachrichtigung zum Antippen.
     * target == null: nur testen.
     */
    static boolean launch(final Context ctx, final Handler main, final Intent target, final String label) {
        if (target != null) {
            target.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        }
        main.post(() -> new Attempt(ctx.getApplicationContext(), main, target, label).start());
        return canLaunchFromBackground(ctx);
    }

    private static final class Attempt {
        final Context ctx;
        final Handler main;
        final Intent target;
        final String label;
        final int token;
        final int preferred;
        int way = -1;
        boolean triedPreferred;
        View overlay;
        WindowManager overlayWm;

        Attempt(Context ctx, Handler main, Intent target, String label) {
            this.ctx = ctx;
            this.main = main;
            this.target = target;
            this.label = label == null ? "App" : label;
            synchronized (Launcher.class) {
                tokenCounter++;
                this.token = tokenCounter;
            }
            this.preferred = Prefs.get(ctx).getInt("launch_way", -1);
        }

        Intent trampoline() {
            Intent t = new Intent(ctx, de.heyclaude.app.CoreActivity.class);
            t.putExtra("screen", "Trampoline");
            t.putExtra("token", token);
            if (target != null) {
                t.putExtra("target", target);
            }
            t.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_NO_ANIMATION
                    | Intent.FLAG_ACTIVITY_EXCLUDE_FROM_RECENTS);
            return t;
        }

        void start() {
            // Zuerst den Weg, der zuletzt geklappt hat
            if (preferred >= 0 && preferred < WAY_NAMES.length) {
                triedPreferred = true;
                tryWay(preferred);
            } else {
                next();
            }
        }

        void next() {
            way++;
            if (triedPreferred && way == preferred) {
                way++;
            }
            if (way >= WAY_NAMES.length) {
                fail();
                return;
            }
            tryWay(way);
        }

        void tryWay(int w) {
            boolean started;
            try {
                started = startWith(w);
            } catch (Exception e) {
                log(label + ": Weg „" + WAY_NAMES[w] + "“ Fehler: " + e.getClass().getSimpleName() + " " + e.getMessage());
                started = false;
            }
            if (!started) {
                main.post(this::next);
                return;
            }
            final int tried = w;
            main.postDelayed(() -> check(tried), w == 2 ? 1500 : 1100);
        }

        /** Startet das Trampolin auf Weg w. false = Weg hier nicht moeglich. */
        boolean startWith(int w) throws Exception {
            switch (w) {
                case 0: {
                    android.accessibilityservice.AccessibilityService a11y = Shell.accessibility();
                    if (a11y == null) {
                        return false;
                    }
                    a11y.startActivity(trampoline());
                    return true;
                }
                case 1:
                    if (!Settings.canDrawOverlays(ctx)) {
                        return false;
                    }
                    ctx.startActivity(trampoline());
                    return true;
                case 2:
                    if (!Settings.canDrawOverlays(ctx)) {
                        return false;
                    }
                    showOverlayThenStart();
                    return true;
                case 3: {
                    Intent t = trampoline();
                    android.os.Bundle creatorOpts = null;
                    if (Build.VERSION.SDK_INT >= 34) {
                        creatorOpts = android.app.ActivityOptions.makeBasic()
                                .setPendingIntentCreatorBackgroundActivityStartMode(
                                        android.app.ActivityOptions.MODE_BACKGROUND_ACTIVITY_START_ALLOWED)
                                .toBundle();
                    }
                    PendingIntent pi = PendingIntent.getActivity(ctx, 40 + (token & 0xF), t,
                            PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT, creatorOpts);
                    if (Build.VERSION.SDK_INT >= 34) {
                        android.app.ActivityOptions o = android.app.ActivityOptions.makeBasic()
                                .setPendingIntentBackgroundActivityStartMode(
                                        android.app.ActivityOptions.MODE_BACKGROUND_ACTIVITY_START_ALLOWED);
                        pi.send(ctx, 0, null, null, null, null, o.toBundle());
                    } else {
                        pi.send();
                    }
                    return true;
                }
                default:
                    return false;
            }
        }

        void showOverlayThenStart() throws Exception {
            Context wctx = ctx;
            if (Build.VERSION.SDK_INT >= 30) {
                Display d = ctx.getSystemService(DisplayManager.class).getDisplay(Display.DEFAULT_DISPLAY);
                wctx = ctx.createWindowContext(d, WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY, null);
            }
            overlayWm = wctx.getSystemService(WindowManager.class);
            overlay = new View(wctx);
            overlay.setBackgroundColor(0x08000000); // kaum sichtbar, zaehlt aber als sichtbares Fenster
            int px = Math.max(2, Math.round(4 * ctx.getResources().getDisplayMetrics().density));
            WindowManager.LayoutParams lp = new WindowManager.LayoutParams(px, px,
                    WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
                    WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE | WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE,
                    PixelFormat.TRANSLUCENT);
            overlayWm.addView(overlay, lp);
            final View ov = overlay;
            ov.post(() -> ov.postDelayed(() -> {
                try {
                    ov.getContext().startActivity(trampoline());
                } catch (Exception e) {
                    log(label + ": Overlay-Start Fehler " + e.getMessage());
                }
            }, 300));
        }

        void removeOverlay() {
            if (overlay != null && overlayWm != null) {
                final View ov = overlay;
                final WindowManager wm = overlayWm;
                overlay = null;
                main.postDelayed(() -> {
                    try {
                        wm.removeView(ov);
                    } catch (Exception ignored) {
                        // egal
                    }
                }, 1500);
            }
        }

        void check(int w) {
            if (claimedToken == token) {
                removeOverlay();
                log(label + ": geöffnet über „" + WAY_NAMES[w] + "“");
                Prefs.get(ctx).edit().putInt("launch_way", w).apply();
                if (target == null) {
                    Hints.show(ctx, Hints.ID_LAUNCH, "Test erfolgreich ✅",
                            "Hey Claude kann Apps im Hintergrund öffnen (Weg: " + WAY_NAMES[w] + ").", null);
                }
                return;
            }
            removeOverlay();
            log(label + ": Weg „" + WAY_NAMES[w] + "“ blockiert");
            next();
        }

        void fail() {
            log(label + ": alle Wege blockiert → Benachrichtigung");
            Prefs.get(ctx).edit().remove("launch_way").apply();
            if (target == null) {
                Hints.showWithAction(ctx, Hints.ID_LAUNCH, "Test: blockiert ❌",
                        "Android lässt Hey Claude im Hintergrund noch keine Apps öffnen. "
                                + "Bitte die Bedienungshilfe einschalten (neue App-Hülle) und die Diagnose schicken.",
                        null, "Overlay-Erlaubnis", Hints.overlaySettings(ctx));
                return;
            }
            PendingIntent pi = PendingIntent.getActivity(ctx, 20, target,
                    PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT);
            Hints.showWithAction(ctx, Hints.ID_LAUNCH, label, "Tippe hier zum Öffnen.", pi,
                    "Immer direkt öffnen", Hints.overlaySettings(ctx));
        }
    }

    // ------------------------------------------------------------------ Musik

    /** Sucht das erste YouTube-Video zur Suche (ohne API-Schluessel). Null, wenn nichts gefunden. */
    static String findYouTubeVideo(String query) {
        try {
            URL url = new URL("https://www.youtube.com/results?search_query="
                    + URLEncoder.encode(query, "UTF-8") + "&hl=de&gl=DE");
            HttpURLConnection con = (HttpURLConnection) url.openConnection();
            try {
                con.setConnectTimeout(10000);
                con.setReadTimeout(15000);
                con.setRequestProperty("User-Agent",
                        "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/130.0 Safari/537.36");
                con.setRequestProperty("Accept-Language", "de-DE,de;q=0.9,en;q=0.8");
                con.setRequestProperty("Cookie", "SOCS=CAI; CONSENT=YES+cb");
                if (con.getResponseCode() != 200) {
                    return null;
                }
                InputStream in = con.getInputStream();
                ByteArrayOutputStream bos = new ByteArrayOutputStream();
                try {
                    byte[] buf = new byte[32768];
                    int n;
                    while ((n = in.read(buf)) != -1 && bos.size() < 3_000_000) {
                        bos.write(buf, 0, n);
                    }
                } finally {
                    in.close();
                }
                String html = new String(bos.toByteArray(), StandardCharsets.UTF_8);
                Matcher m = VIDEO_RENDERER.matcher(html);
                if (m.find()) {
                    return m.group(1);
                }
                m = ANY_VIDEO.matcher(html);
                return m.find() ? m.group(1) : null;
            } finally {
                con.disconnect();
            }
        } catch (Exception e) {
            Log.w(TAG, "YouTube-Suche", e);
            return null;
        }
    }

    /** Baut den Start-Befehl fuer Musik. app: youtube, youtube_music, spotify. */
    static Intent musicIntent(Context ctx, String app, String query, String[] label) {
        String q = query == null ? "" : query.trim();
        if ("spotify".equals(app) && isInstalled(ctx, SPOTIFY)) {
            label[0] = "Spotify: " + q;
            Intent i = new Intent(android.provider.MediaStore.INTENT_ACTION_MEDIA_PLAY_FROM_SEARCH);
            i.setPackage(SPOTIFY);
            i.putExtra(android.app.SearchManager.QUERY, q);
            i.putExtra(android.provider.MediaStore.EXTRA_MEDIA_FOCUS, "vnd.android.cursor.item/*");
            return i;
        }
        String id = q.isEmpty() ? null : findYouTubeVideo(q);
        boolean music = "youtube_music".equals(app) && isInstalled(ctx, YT_MUSIC);
        String enc;
        try {
            enc = URLEncoder.encode(q, "UTF-8");
        } catch (Exception e) {
            enc = q;
        }
        Intent i;
        if (music) {
            label[0] = "YouTube Music: " + q;
            i = new Intent(Intent.ACTION_VIEW, Uri.parse(id != null
                    ? "https://music.youtube.com/watch?v=" + id
                    : "https://music.youtube.com/search?q=" + enc));
            i.setPackage(YT_MUSIC);
        } else {
            label[0] = "YouTube: " + q;
            i = new Intent(Intent.ACTION_VIEW, Uri.parse(id != null
                    ? "https://www.youtube.com/watch?v=" + id
                    : "https://www.youtube.com/results?search_query=" + enc));
            if (isInstalled(ctx, YOUTUBE)) {
                i.setPackage(YOUTUBE);
            }
        }
        return i;
    }

    // ------------------------------------------------------------------ Apps

    /** Findet eine App per Name (z.B. "WhatsApp"). Liefert {Paket, Name} oder null. */
    static String[] findApp(Context ctx, String name) {
        String wanted = normalize(name);
        if (wanted.isEmpty()) {
            return null;
        }
        PackageManager pm = ctx.getPackageManager();
        Intent main = new Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER);
        List<ResolveInfo> apps = pm.queryIntentActivities(main, 0);
        String[] starts = null;
        String[] contains = null;
        for (ResolveInfo ri : apps) {
            String label = String.valueOf(ri.loadLabel(pm));
            String n = normalize(label);
            String pkg = ri.activityInfo.packageName;
            if (n.equals(wanted)) {
                return new String[]{pkg, label};
            }
            if (starts == null && n.startsWith(wanted)) {
                starts = new String[]{pkg, label};
            } else if (contains == null && (n.contains(wanted) || wanted.contains(n) && n.length() >= 4)) {
                contains = new String[]{pkg, label};
            }
        }
        return starts != null ? starts : contains;
    }

    private static String normalize(String s) {
        return s == null ? "" : s.toLowerCase(Locale.ROOT).replaceAll("[^\\p{L}\\p{N}]", "");
    }
}
