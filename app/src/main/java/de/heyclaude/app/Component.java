package de.heyclaude.app;

/**
 * Schnittstelle zwischen der installierten Huelle und dem nachladbaren Programmcode.
 * NIE aendern – sonst passt neuer Code nicht mehr zu alten Huellen.
 */
public interface Component {
    /**
     * @param host  die Android-Komponente (Activity, Service, Context)
     * @param event z.B. "onCreate", "onResume", "onStartCommand"
     * @param args  ereignisabhaengige Argumente
     * @return optionales Ergebnis (z.B. Rueckgabewert von onStartCommand)
     */
    Object handle(Object host, String event, Object... args);
}
