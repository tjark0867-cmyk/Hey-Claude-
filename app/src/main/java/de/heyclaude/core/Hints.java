package de.heyclaude.core;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.content.Context;
import android.content.Intent;

import de.heyclaude.app.MainActivity;

/** Einfache Hinweis-Benachrichtigungen (Update, Neustart). */
final class Hints {

    private static final String CHANNEL = "heyclaude_hints";
    static final int ID_RESTART = 50;
    static final int ID_UPDATE = 51;
    static final int ID_LAUNCH = 52;
    static final int ID_OVERLAY = 53;

    private Hints() {
    }

    static void show(Context ctx, int id, String title, String text, PendingIntent tap) {
        try {
            NotificationManager nm = ctx.getSystemService(NotificationManager.class);
            NotificationChannel ch = new NotificationChannel(CHANNEL, "Hey Claude Hinweise",
                    NotificationManager.IMPORTANCE_DEFAULT);
            nm.createNotificationChannel(ch);
            Notification n = new Notification.Builder(ctx, CHANNEL)
                    .setSmallIcon(Res.drawable(ctx, "ic_mic"))
                    .setContentTitle(title)
                    .setContentText(text)
                    .setStyle(new Notification.BigTextStyle().bigText(text))
                    .setAutoCancel(true)
                    .setContentIntent(tap)
                    .build();
            nm.notify(id, n);
        } catch (Exception ignored) {
            // ohne Benachrichtigungs-Erlaubnis geht es eben nicht
        }
    }

    /** Hinweis mit zusaetzlichem Knopf. */
    static void showWithAction(Context ctx, int id, String title, String text, PendingIntent tap,
                               String actionLabel, PendingIntent action) {
        try {
            NotificationManager nm = ctx.getSystemService(NotificationManager.class);
            nm.createNotificationChannel(new NotificationChannel(CHANNEL, "Hey Claude Hinweise",
                    NotificationManager.IMPORTANCE_DEFAULT));
            Notification.Builder b = new Notification.Builder(ctx, CHANNEL)
                    .setSmallIcon(Res.drawable(ctx, "ic_mic"))
                    .setContentTitle(title)
                    .setContentText(text)
                    .setStyle(new Notification.BigTextStyle().bigText(text))
                    .setAutoCancel(true)
                    .setContentIntent(tap);
            if (action != null) {
                b.addAction(new Notification.Action.Builder(
                        android.graphics.drawable.Icon.createWithResource(ctx, Res.drawable(ctx, "ic_mic")),
                        actionLabel, action).build());
            }
            nm.notify(id, b.build());
        } catch (Exception ignored) {
            // egal
        }
    }

    /** Fuehrt direkt zur Einstellung "Ueber anderen Apps einblenden". */
    static PendingIntent overlaySettings(Context ctx) {
        Intent i = new Intent(android.provider.Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                android.net.Uri.parse("package:" + ctx.getPackageName()))
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        return PendingIntent.getActivity(ctx, 30, i, PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT);
    }

    /** Einmal pro Start: Hinweis, dass Apps im Hintergrund noch nicht direkt aufgehen. */
    static void showOverlayHint(Context ctx) {
        PendingIntent pi = overlaySettings(ctx);
        showWithAction(ctx, ID_OVERLAY, "Apps im Hintergrund öffnen",
                "Damit ich YouTube, Instagram & Co. auch öffnen kann, wenn Hey Claude nur im Hintergrund läuft: "
                        + "hier tippen und „Über anderen Apps einblenden“ einschalten.",
                pi, "Jetzt erlauben", pi);
    }

    static void cancel(Context ctx, int id) {
        try {
            ctx.getSystemService(NotificationManager.class).cancel(id);
        } catch (Exception ignored) {
            // egal
        }
    }

    /** Hinweis: "Tippe, um wieder zuzuhoeren" – oeffnet die App, die dann automatisch startet. */
    static void showRestart(Context ctx, String title, String text) {
        Intent open = new Intent(ctx, MainActivity.class)
                .setFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_SINGLE_TOP);
        PendingIntent pi = PendingIntent.getActivity(ctx, 10, open,
                PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT);
        show(ctx, ID_RESTART, title, text, pi);
    }
}
