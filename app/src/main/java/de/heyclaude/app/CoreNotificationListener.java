package de.heyclaude.app;

import android.service.notification.NotificationListenerService;
import android.service.notification.StatusBarNotification;
import android.util.Log;

/**
 * Optionaler Zugriff auf Benachrichtigungen (z.B. Nachrichten vorlesen oder beantworten).
 * Nur aktiv, wenn der Nutzer ihn erlaubt. Logik im Programmcode ("Notifications").
 */
public class CoreNotificationListener extends NotificationListenerService {

    private Component impl;
    private int generation = -1;

    @Override
    public void onListenerConnected() {
        super.onListenerConnected();
        Core.setNotificationListener(this);
        call("onListenerConnected");
    }

    @Override
    public void onListenerDisconnected() {
        Core.setNotificationListener(null);
        call("onListenerDisconnected");
        super.onListenerDisconnected();
    }

    @Override
    public void onNotificationPosted(StatusBarNotification sbn) {
        call("onNotificationPosted", sbn);
    }

    @Override
    public void onNotificationRemoved(StatusBarNotification sbn) {
        call("onNotificationRemoved", sbn);
    }

    private void call(String event, Object... args) {
        int g = Core.generation();
        if (impl == null || g != generation) {
            generation = g;
            impl = Core.createOptional(this, "Notifications");
        }
        if (impl == null) {
            return;
        }
        try {
            impl.handle(this, event, args);
        } catch (Throwable t) {
            Log.w("HeyClaude", "Benachrichtigungen " + event, t);
        }
    }
}
