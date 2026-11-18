package de.heyclaude.app;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;

/**
 * Nach einem Update oder Handy-Neustart darf Android den Mikrofon-Dienst nicht von selbst starten.
 * Darum erscheint ein Hinweis: ein Tipp darauf oeffnet die App, und die startet automatisch.
 */
public class RestartReceiver extends BroadcastReceiver {

    @Override
    public void onReceive(Context ctx, Intent intent) {
        String action = intent.getAction();
        if (!Prefs.enabled(ctx) || VoiceService.running) {
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
}
