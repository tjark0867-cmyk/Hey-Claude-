package de.heyclaude.core;

/** Build-Nummer dieses Programmcodes. Wird beim Bauen auf GitHub automatisch eingetragen. */
public final class BuildInfo {

    public static final int BUILD = 0;

    /** Dieser Programmcode laeuft ab dieser Huellen-Version (neuere Funktionen pruefen die Version selbst). */
    public static final int MIN_SHELL = 1;

    private BuildInfo() {
    }
}
