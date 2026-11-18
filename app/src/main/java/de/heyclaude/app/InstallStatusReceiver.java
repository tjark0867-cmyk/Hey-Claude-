package de.heyclaude.app;

import android.app.PendingIntent;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageInstaller;
import android.util.Log;

/** Rueckmeldung von Android zur Update-Installation. */
public class InstallStatusReceiver extends BroadcastReceiver {

    @Override
    @SuppressWarnings("deprecation")
    public void onReceive(Context ctx, Intent intent) {
        if (!Updater.ACTION_INSTALL_STATUS.equals(intent.getAction())) {
            return;
        }
        int status = intent.getIntExtra(PackageInstaller.EXTRA_STATUS, PackageInstaller.STATUS_FAILURE);
        if (status == PackageInstaller.STATUS_PENDING_USER_ACTION) {
            // Android moechte eine Bestaetigung (z.B. beim allerersten Update)
            Intent confirm = intent.getParcelableExtra(Intent.EXTRA_INTENT);
            if (confirm == null) {
                return;
            }
            confirm.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            Updater.lastStatus = "Update wartet auf deine Bestätigung";
            if (MainActivity.visible) {
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
            Log.w("HeyClaude", "Update-Status " + status + ": " + msg);
        }
    }
}
