package de.heyclaude.core;

import android.app.PendingIntent;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageInstaller;
import android.util.Log;

import de.heyclaude.app.Component;

/** Logik fuer alle Empfaenger der Huelle (Update-Status, Neustart, spaeter Wecker usw.). */
public class Receivers implements Component {

    @Override
    public Object handle(Object host, String event, Object... args) {
        Context ctx = (Context) host;
        Intent intent = args.length > 0 ? (Intent) args[0] : null;
        if (intent == null) {
            return null;
        }
        switch (event) {
            case "install_status":
                installStatus(ctx, intent);
                break;
            case "restart":
                Timers.rescheduleAll(ctx); // Wecker ueberleben Neustart und Update
                restart(ctx, intent);
                break;
            case "core":
                Timers.handle(ctx, intent);
                break;
            default:
                break;
        }
        return null;
    }

    private static void restart(Context ctx, Intent intent) {
        String action = intent.getAction();
        if (!Prefs.enabled(ctx) || Engine.running) {
            return;
        }
        if (Intent.ACTION_MY_PACKAGE_REPLACED.equals(action)) {
            Hints.showRestart(ctx, "Hey Claude wurde aktualisiert ✨",
                    "Tippe hier, damit ich wieder auf „Hey Claude“ höre.");
        } else if (Intent.ACTION_BOOT_COMPLETED.equals(action)) {
            Hints.showRestart(ctx, "Hey Claude",
                    "Tippe hier, damit ich wieder auf „Hey Claude“ höre.");
        }
    }

    @SuppressWarnings("deprecation")
    private static void installStatus(Context ctx, Intent intent) {
        if (!Updater.ACTION_INSTALL_STATUS.equals(intent.getAction())) {
            return;
        }
        int status = intent.getIntExtra(PackageInstaller.EXTRA_STATUS, PackageInstaller.STATUS_FAILURE);
        if (status == PackageInstaller.STATUS_PENDING_USER_ACTION) {
            Intent confirm = intent.getParcelableExtra(Intent.EXTRA_INTENT);
            if (confirm == null) {
                return;
            }
            confirm.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            Updater.lastStatus = "Update wartet auf deine Bestätigung";
            if (Screen.visible) {
                try {
                    ctx.startActivity(confirm);
                    return;
                } catch (Exception e) {
                    Log.w("HeyClaude", "Bestätigung", e);
                }
            }
            PendingIntent pi = PendingIntent.getActivity(ctx, 11, confirm,
                    PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT);
            Hints.show(ctx, Hints.ID_UPDATE, "Hey Claude: Update bereit",
                    "Tippe, um die neue Version zu installieren.", pi);
        } else if (status == PackageInstaller.STATUS_SUCCESS) {
            Updater.lastStatus = "Update installiert";
        } else {
            String msg = intent.getStringExtra(PackageInstaller.EXTRA_STATUS_MESSAGE);
            Updater.lastStatus = "Update fehlgeschlagen" + (msg != null ? ": " + msg : "");
        }
    }
}
