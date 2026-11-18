package de.heyclaude.core;

import android.app.Activity;
import android.content.Intent;
import android.util.Log;

import de.heyclaude.app.Component;

/**
 * Unsichtbarer Zwischenschritt (laeuft in der durchsichtigen CoreActivity der Huelle):
 * Wenn er startet, ist Hey Claude kurz "im Vordergrund" und darf die eigentliche App (YouTube …) oeffnen.
 * Ausserdem meldet er dem Launcher, dass der Start geklappt hat.
 */
public class Trampoline implements Component {

    @Override
    @SuppressWarnings("deprecation")
    public Object handle(Object host, String event, Object... args) {
        if (!"onCreate".equals(event)) {
            return null;
        }
        Activity a = (Activity) host;
        Intent in = a.getIntent();
        int token = in != null ? in.getIntExtra("token", 0) : 0;
        Intent target = in != null ? in.getParcelableExtra("target") : null;
        if (Launcher.claim(token) && target != null) {
            try {
                target.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                a.startActivity(target);
            } catch (Exception e) {
                Log.w("HeyClaude", "Trampolin-Ziel", e);
                Launcher.log("Ziel ließ sich nicht starten: " + e.getMessage());
            }
        }
        a.finish();
        a.overridePendingTransition(0, 0);
        return null;
    }
}
