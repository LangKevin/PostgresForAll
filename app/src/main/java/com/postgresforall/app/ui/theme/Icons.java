package com.postgresforall.app.ui.theme;

import com.formdev.flatlaf.extras.FlatSVGIcon;

import java.awt.Color;
import java.util.function.Supplier;

/**
 * Lucide outline icons (ISC licence, {@code /icons}). They're drawn in IntelliJ's
 * standard icon grey, which FlatLaf's default colour filter re-maps for dark
 * themes; {@link #tinted} paints one in a theme colour instead.
 */
public final class Icons {
    private Icons() { }

    public static final int SMALL = 14;
    public static final int NORMAL = 16;
    public static final int LARGE = 20;

    public static FlatSVGIcon get(String name) {
        return get(name, NORMAL);
    }

    public static FlatSVGIcon get(String name, int size) {
        return new FlatSVGIcon("icons/" + name + ".svg", size, size);
    }

    /** The icon in one colour, re-read on each paint so it follows a theme switch. */
    public static FlatSVGIcon tinted(String name, int size, Supplier<Color> color) {
        var icon = get(name, size);
        icon.setColorFilter(new FlatSVGIcon.ColorFilter(c -> color.get()));
        return icon;
    }
}
