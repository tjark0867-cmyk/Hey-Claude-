package de.heyclaude.core;

import de.heyclaude.app.Component;

/** Ereignisse der Bedienungshilfe (Huelle 2): stoesst die laufende Automatik an. */
public class Accessibility implements Component {

    @Override
    public Object handle(Object host, String event, Object... args) {
        if ("onAccessibilityEvent".equals(event)) {
            Automation.poke();
        }
        return null;
    }
}
