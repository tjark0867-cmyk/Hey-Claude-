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

    /** Startet die Activity. Liefert true, wenn sie direkt starten konnte (sonst kommt ein Hinweis). */
    static boolean launch(final Context ctx, final Handler main, final Intent intent, final String label) {
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        final boolean direct = canLaunchFromBackground(ctx);
        final android.accessibilityservice.AccessibilityService a11y = Shell.accessibility();
        main.post(() -> {
            View overlay = null;
            WindowManager wm = null;
            if (Settings.canDrawOverlays(ctx)) {
                try {
                    Context wctx = ctx;
                    if (Build.VERSION.SDK_INT >= 30) {
                        Display d = ctx.getSystemService(DisplayManager.class).getDisplay(Display.DEFAULT_DISPLAY);
                        wctx = ctx.createWindowContext(d, WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY, null);
                    }
                    wm = wctx.getSystemService(WindowManager.class);
                    overlay = new View(wctx);
                    overlay.setBackgroundColor(0x01000000); // fast unsichtbar, zaehlt aber als sichtbares Fenster
                    WindowManager.LayoutParams lp = new WindowManager.LayoutParams(1, 1,
                            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
                            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
                                    | WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE,
                            PixelFormat.TRANSLUCENT);
                    wm.addView(overlay, lp);
                } catch (Exception e) {
                    Log.w(TAG, "Overlay", e);
                    overlay = null;
                }
            }
            final View ov = overlay;
            final WindowManager fwm = wm;
            main.postDelayed(() -> {
                try {
                    // Bedienungshilfe (falls an) darf immer starten, sonst ueber die Overlay-Erlaubnis
                    if (a11y != null) {
                        a11y.startActivity(intent);
                    } else {
                        ctx.startActivity(intent);
                    }
                } catch (Exception e) {
                    Log.w(TAG, "Start " + label, e);
                }
                if (!direct) {
                    PendingIntent pi = PendingIntent.getActivity(ctx, 20, intent,
                            PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT);
                    Hints.showWithAction(ctx, Hints.ID_LAUNCH, label, "Tippe hier zum Öffnen. "
                                    + "Damit das künftig von selbst geht: „Immer direkt öffnen“.", pi,
                            "Immer direkt öffnen", Hints.overlaySettings(ctx));
                }
                if (ov != null && fwm != null) {
                    main.postDelayed(() -> {
                        try {
                            fwm.removeView(ov);
                        } catch (Exception ignored) {
                            // egal
                        }
                    }, 3000);
                }
            }, ov != null ? 400 : 0);
        });
        return direct;
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
