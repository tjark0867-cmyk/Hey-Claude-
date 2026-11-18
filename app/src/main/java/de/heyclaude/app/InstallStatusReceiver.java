package de.heyclaude.app;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.util.Log;

/** Rueckmeldung zur App-Installation (nur fuer seltene Huellen-Updates). Logik im Programmcode. */
public class InstallStatusReceiver extends BroadcastReceiver {

    @Override
    public void onReceive(Context ctx, Intent intent) {
        CoreReceiver.dispatch(ctx, "install_status", intent);
    }
}
