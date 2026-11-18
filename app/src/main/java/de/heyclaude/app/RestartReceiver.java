package de.heyclaude.app;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;

/** Nach App-Update oder Handy-Neustart. Logik im Programmcode. */
public class RestartReceiver extends BroadcastReceiver {

    @Override
    public void onReceive(Context ctx, Intent intent) {
        CoreReceiver.dispatch(ctx, "restart", intent);
    }
}
