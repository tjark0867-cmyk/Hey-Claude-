package de.heyclaude.app;

import android.service.notification.NotificationListenerService;
import android.service.notification.StatusBarNotification;
import android.util.Log;

/**
 * Optionaler Zugriff auf Benachrichtigungen (z.B. "Lies meine Nachrichten vor" in spaeteren Versionen).
 * Nur aktiv, wenn der Nutzer ihn in den Einstellungen erlaubt. Logik im Programmcode ("Notifications").
 */
public class CoreNotificationListener extends NotificationListenerService {

    private Component impl;

    @Override
    public void onListenerConnected() {
        super.onListenerConnected();
        impl = Core.createOptional(this, "Notifications");
        call("onListenerConnected");
    }

    @Override
    public void onListenerDisconnected() {
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
