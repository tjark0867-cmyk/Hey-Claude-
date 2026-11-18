package de.heyclaude.core;

import android.content.Context;

/**
 * Bilder aus der installierten Huelle per Name finden. (Ressourcen-Nummern koennen sich zwischen
 * Versionen aendern, Namen nicht – darum nie R.drawable im Programmcode benutzen.)
 */
final class Res {

    private Res() {
    }

    static int drawable(Context ctx, String name) {
        int id = ctx.getResources().getIdentifier(name, "drawable", ctx.getPackageName());
        return id != 0 ? id : android.R.drawable.ic_btn_speak_now;
    }
}
