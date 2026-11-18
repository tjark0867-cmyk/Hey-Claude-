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
