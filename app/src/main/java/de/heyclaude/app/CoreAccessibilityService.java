package de.heyclaude.app;

import android.accessibilityservice.AccessibilityService;
import android.content.Intent;
import android.util.Log;
import android.view.accessibility.AccessibilityEvent;

/**
 * Bedienungshilfe: erlaubt Hey Claude, in anderen Apps zu tippen (z.B. eine Nachricht absenden).
 * Nur aktiv, wenn der Nutzer sie in den Einstellungen einschaltet. Logik im Programmcode ("Accessibility").
 */
public class CoreAccessibilityService extends AccessibilityService {

    private Component impl;
    private int generation = -1;

    @Override
    protected void onServiceConnected() {
        super.onServiceConnected();
        Core.setAccessibility(this);
        call("onServiceConnected");
    }

    @Override
    public void onAccessibilityEvent(AccessibilityEvent event) {
        call("onAccessibilityEvent", event);
    }

    @Override
    public void onInterrupt() {
        call("onInterrupt");
    }

    @Override
    public boolean onUnbind(Intent intent) {
        Core.setAccessibility(null);
        call("onUnbind");
        return super.onUnbind(intent);
    }

    @Override
    public void onDestroy() {
        Core.setAccessibility(null);
        super.onDestroy();
    }

    private void call(String event, Object... args) {
        int g = Core.generation();
        if (impl == null || g != generation) {
            generation = g;
            impl = Core.createOptional(this, "Accessibility"); // nach Code-Update neu holen
        }
        if (impl == null) {
            return;
        }
        try {
            impl.handle(this, event, args);
        } catch (Throwable t) {
            Log.w("HeyClaude", "Bedienungshilfe " + event, t);
        }
    }
}
