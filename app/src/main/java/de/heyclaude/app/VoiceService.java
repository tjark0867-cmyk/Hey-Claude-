package de.heyclaude.app;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.Service;
import android.content.Intent;
import android.os.IBinder;
import android.util.Log;

/** Huelle fuer den Hintergrund-Dienst. Die eigentliche Arbeit macht der nachladbare Code (Engine). */
public class VoiceService extends Service {

    /** Wird nach einem Code-Wechsel an die neue Engine geschickt (Dienst laeuft bereits im Vordergrund). */
    public static final String ACTION_RESUME = "de.heyclaude.app.RESUME";

    private Component impl;
    private boolean emergency;

    @Override
    public void onCreate() {
        super.onCreate();
        Core.setService(this);
        impl = Core.create(this, "Engine");
        call("onCreate");
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        if (impl == null) {
            emergencyStop();
            return START_NOT_STICKY;
        }
        Object r = call("onStartCommand", intent, flags, startId);
        if (impl == null) {
            emergencyStop();
            return START_NOT_STICKY;
        }
        return r instanceof Integer ? (Integer) r : START_STICKY;
    }

    @Override
    public void onDestroy() {
        call("onDestroy");
        Core.setService(null);
        super.onDestroy();
    }

    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }

    /** Neuer Code ist da: alte Engine sauber beenden, neue starten – der Dienst selbst laeuft weiter. */
    void swap() {
        call("onDestroy");
        impl = Core.create(this, "Engine");
        if (impl == null) {
            emergencyStop();
            return;
        }
        call("onCreate");
        call("onStartCommand", new Intent(ACTION_RESUME), 0, 0);
    }

    private Object call(String event, Object... args) {
        Component c = impl;
        if (c == null) {
            return null;
        }
        try {
            return c.handle(this, event, args);
        } catch (Throwable t) {
            Log.e("HeyClaude", "Engine-Fehler bei " + event, t);
            if (Core.fallBackToBundled()) {
                // mit dem mitgelieferten Code neu versuchen
                impl = Core.create(this, "Engine");
                if (impl != null && !"onDestroy".equals(event)) {
                    try {
                        if (!"onCreate".equals(event)) {
                            impl.handle(this, "onCreate");
                        }
                        return impl.handle(this, event, args);
                    } catch (Throwable t2) {
                        Log.e("HeyClaude", "Auch mitgelieferter Code fehlgeschlagen", t2);
                    }
                }
            }
            impl = null;
            return null;
        }
    }

    /** Notfall: Android verlangt startForeground, bevor der Dienst beendet werden darf. */
    private void emergencyStop() {
        if (emergency) {
            return;
        }
        emergency = true;
        try {
            NotificationManager nm = getSystemService(NotificationManager.class);
            nm.createNotificationChannel(new NotificationChannel("heyclaude_error", "Hey Claude Fehler",
                    NotificationManager.IMPORTANCE_LOW));
            Notification n = new Notification.Builder(this, "heyclaude_error")
                    .setSmallIcon(android.R.drawable.stat_notify_error)
                    .setContentTitle("Hey Claude")
                    .setContentText("Konnte nicht starten – bitte App öffnen")
                    .build();
            startForeground(43, n);
        } catch (Throwable t) {
            Log.e("HeyClaude", "Notfall-Start", t);
        }
        stopSelf();
    }
}
