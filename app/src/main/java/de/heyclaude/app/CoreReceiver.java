package de.heyclaude.app;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.util.Log;

/** Allgemeiner Empfaenger (z.B. fuer Wecker/Timer in spaeteren Versionen). Logik im Programmcode. */
public class CoreReceiver extends BroadcastReceiver {

    @Override
    public void onReceive(Context ctx, Intent intent) {
        dispatch(ctx, "core", intent);
    }

    static void dispatch(Context ctx, String source, Intent intent) {
        try {
            Component c = Core.create(ctx, "Receivers");
            if (c != null) {
                c.handle(ctx, source, intent);
            }
        } catch (Throwable t) {
            Log.e("HeyClaude", "Empfänger " + source, t);
        }
    }
}
