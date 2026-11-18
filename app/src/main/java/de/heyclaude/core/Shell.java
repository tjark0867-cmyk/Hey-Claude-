package de.heyclaude.core;

import android.accessibilityservice.AccessibilityService;
import android.content.Context;
import android.provider.Settings;

import java.lang.reflect.Method;

import de.heyclaude.app.Core;

/**
 * Zugriff auf Funktionen neuerer Huellen. Der Programmcode muss auch auf aelteren Huellen laufen,
 * darum hier nur per Reflection und mit Versions-Pruefung.
 */
final class Shell {

    private Shell() {
    }

    static int version() {
        return Core.shellVersion();
    }

    /** Die eingeschaltete Bedienungshilfe (ab Huelle 2) oder null. */
    static AccessibilityService accessibility() {
        if (version() < 2) {
            return null;
        }
        try {
            Method m = Core.class.getMethod("accessibilityService");
            return (AccessibilityService) m.invoke(null);
        } catch (Throwable t) {
            return null;
        }
    }

    /** Ist die Bedienungshilfe von Hey Claude in den Einstellungen eingeschaltet? */
    static boolean accessibilityEnabled(Context ctx) {
        if (accessibility() != null) {
            return true;
        }
        try {
            String s = Settings.Secure.getString(ctx.getContentResolver(),
                    Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES);
            return s != null && s.contains(ctx.getPackageName() + "/");
        } catch (Exception e) {
            return false;
        }
    }
}
