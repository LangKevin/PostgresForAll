package com.postgresforall.app.ui;

import java.awt.Color;

/** Named colours the original UI used (WPF brush names). */
public final class Palette {
    private Palette() { }

    public static final Color FIREBRICK = new Color(178, 34, 34);
    public static final Color FOREST_GREEN = new Color(34, 139, 34);
    public static final Color SEA_GREEN = new Color(46, 139, 87);
    public static final Color DARK_ORANGE = new Color(255, 140, 0);
    public static final Color GOLDENROD = new Color(218, 165, 32);
    public static final Color MEDIUM_PURPLE = new Color(147, 112, 219);
    public static final Color DIM_GRAY = new Color(105, 105, 105);
    public static final Color GRAY = new Color(128, 128, 128);
    public static final Color LIGHT_GRAY = new Color(190, 190, 190);
    public static final Color DARK_SLATE_GRAY = new Color(47, 79, 79);

    public static final Color BANNER_YELLOW = new Color(255, 244, 200);
    public static final Color BANNER_RED = new Color(255, 220, 220);
    public static final Color BANNER_ORANGE = new Color(255, 230, 200);
    public static final Color CAP_BANNER = new Color(255, 244, 214);
    public static final Color CAP_BANNER_BORDER = new Color(238, 197, 106);
    public static final Color BOOT_BANNER = new Color(233, 247, 236);
    public static final Color BOOT_BANNER_BORDER = new Color(154, 201, 168);
    public static final Color BOOT_BANNER_TEXT = new Color(31, 90, 46);
    public static final Color HEADER_STRIP = new Color(246, 246, 248);

    public static String hex(Color c) {
        return String.format("#%02x%02x%02x", c.getRed(), c.getGreen(), c.getBlue());
    }
}
