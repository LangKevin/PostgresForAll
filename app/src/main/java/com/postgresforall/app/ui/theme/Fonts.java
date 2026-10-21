package com.postgresforall.app.ui.theme;

import javax.swing.UIManager;
import java.awt.Font;

/** Fonts from the theme: Inter for the UI, JetBrains Mono for code, grids and logs. */
public final class Fonts {
    private Fonts() { }

    /** Monospaced font, {@code delta} points relative to the UI font (scaled with it). */
    public static Font mono(int delta) {
        var base = UIManager.getFont("defaultFont");
        var size = (base != null ? base.getSize2D() : 13f) + delta;
        var mono = UIManager.getFont("monospaced.font");
        if (mono == null) mono = new Font(Font.MONOSPACED, Font.PLAIN, 13);
        return mono.deriveFont(Font.PLAIN, size);
    }

    /** UI font {@code delta} points larger, optionally bold. */
    public static Font ui(int delta, boolean bold) {
        var base = UIManager.getFont("defaultFont");
        if (base == null) base = new Font(Font.SANS_SERIF, Font.PLAIN, 13);
        return base.deriveFont(bold ? Font.BOLD : Font.PLAIN, base.getSize2D() + delta);
    }
}
